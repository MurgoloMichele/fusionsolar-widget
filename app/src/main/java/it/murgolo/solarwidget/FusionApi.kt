package it.murgolo.solarwidget

import android.content.Context
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.math.abs

class FusionException(msg: String) : Exception(msg)

/** Client for the (unofficial) FusionSolar web API used by the official app. */
class FusionApi(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("fusion", Context.MODE_PRIVATE)
    private val jar = PrefsCookieJar(ctx)
    private val client = OkHttpClient.Builder()
        .cookieJar(jar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private val loginHost = "eu5.fusionsolar.huawei.com"
    private var dataHost: String
        get() = prefs.getString("dataHost", loginHost)!!
        set(v) = prefs.edit().putString("dataHost", v).apply()
    private var csrf: String? = null

    private val log = StringBuilder()
    private fun log(s: String) { log.append(s).append('\n') }

    fun fetch(): SolarData {
        log.setLength(0)
        try {
            return doFetch()
        } finally {
            prefs.edit().putString("log", log.toString()).apply()
        }
    }

    /** True once the portal asked for a captcha: background refreshes stop logging in until it is solved. */
    val needsCaptcha get() = prefs.getBoolean("needsCaptcha", false)

    /** Captcha image for [solveCaptcha]; it is bound to the current cookies, so they are kept. */
    fun captchaImage(): ByteArray {
        val req = Request.Builder().url("https://$loginHost/unisso/verifycode?timestamp=${now()}").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw FusionException("Captcha non disponibile (HTTP ${r.code})")
            return r.body!!.bytes()
        }
    }

    fun solveCaptcha(code: String): SolarData {
        log.setLength(0)
        try {
            login(code.trim())
            prefs.edit().putBoolean("needsCaptcha", false).apply()
            return doFetch()
        } finally {
            prefs.edit().putString("log", log.toString()).apply()
        }
    }

    private fun doFetch(): SolarData {
        if (!keepAlive()) {
            if (needsCaptcha) throw FusionException("Apri Solar Widget e inserisci il captcha")
            // Logging in too often makes the portal require a captcha: at most once every 10 minutes.
            val last = prefs.getLong("lastLogin", 0)
            if (System.currentTimeMillis() - last < 10 * 60_000) throw FusionException("Sessione scaduta, riprovo tra poco")
            login()
        }
        if (csrf == null) {
            val candidates = linkedSetOf(dataHost, "region01eu5.fusionsolar.huawei.com", "uni001eu5.fusionsolar.huawei.com", loginHost)
            val ok = candidates.firstOrNull { h -> dataHost = h; keepAlive() }
            if (ok == null) throw FusionException("Sessione non valida dopo il login (vedi log)")
        }
        val dn = stationDn()
        // The app uses v3/v2 on newer hosts; keep the first version that yields a PV value.
        var data: SolarData? = null
        val raw = StringBuilder()
        for (v in listOf("v1", "v2", "v3")) {
            try {
                val flow = getJson("/rest/pvms/web/station/$v/overview/energy-flow?stationDn=${enc(dn)}&_=${now()}")
                raw.append("== $v ==\n").append(flow.toString(1)).append('\n')
                val parsed = parseFlow(flow)
                log("flow $v: pv=${parsed.pv} load=${parsed.load} grid=${parsed.grid} batt=${parsed.battery} soc=${parsed.soc}")
                if (data == null || (data.pv == null && parsed.pv != null)) data = parsed
                if (data.pv != null && data.grid != null) break
            } catch (e: Exception) {
                log("flow $v: ${e.message}")
            }
        }
        prefs.edit().putString("rawFlow", raw.toString()).apply()
        if (data == null) throw FusionException("Nessun dato energy-flow (vedi log)")
        return try {
            val (yieldKwh, useKwh) = dailyEnergy(dn)
            data.copy(dayYield = yieldKwh, dayUse = useKwh)
        } catch (e: Exception) {
            data
        }
    }

    fun resetSession() {
        jar.clear()
        prefs.edit().remove("stationDn").remove("dataHost").remove("lastLogin").apply()
    }

    // ---------- login ----------

    private fun login(captcha: String? = null) {
        val user = Credentials.user(ctx) ?: throw FusionException("Inserisci le credenziali nell'app")
        val pass = Credentials.password(ctx) ?: throw FusionException("Inserisci le credenziali nell'app")
        if (captcha == null) jar.clear()
        csrf = null
        prefs.edit().putLong("lastLogin", System.currentTimeMillis()).apply()

        val pk = JSONObject(get("https://$loginHost/unisso/pubkey"))
        val resp: JSONObject
        if (pk.optBoolean("enableEncrypt")) {
            val pwd = encryptPassword(pass, pk.getString("pubKey")) + pk.getString("version")
            val body = JSONObject().put("organizationName", "").put("username", user).put("password", pwd)
            captcha?.let { body.put("verifycode", it) }
            resp = JSONObject(post("https://$loginHost/unisso/v3/validateUser.action?timeStamp=${now()}&nonce=${nonce()}", body))
        } else {
            val body = JSONObject().put("organizationName", "").put("username", user).put("password", pass)
            val service = enc("https://$loginHost/unisso/login.action")
            resp = JSONObject(post("https://$loginHost/unisso/v2/validateUser.action?decision=1&service=$service", body))
        }

        log("login: " + resp.toString().replace(Regex("ticket=[^\"&]+"), "ticket=…"))
        if (resp.optBoolean("verifyCodeCreate") && resp.optString("redirectURL").let { it.isBlank() || it == "null" } && resp.optJSONArray("respMultiRegionName") == null) {
            prefs.edit().putBoolean("needsCaptcha", true).apply()
            throw FusionException(if (captcha == null) "Il portale richiede un captcha: inseriscilo nell'app" else "Captcha errato, riprova")
        }
        val tfa = resp.opt("twoFactorStatus")
        if (tfa != null && tfa != JSONObject.NULL && tfa.toString().isNotBlank()) log("twoFactorStatus=$tfa")
        val err = resp.optString("errorMsg").takeIf { it.isNotBlank() && it != "null" }
            ?: resp.optString("errorCode").takeIf { it.isNotBlank() && it != "null" && it != "0" }
        var redirect: String? = null
        val multi = resp.optJSONArray("respMultiRegionName")
        if (multi != null && multi.length() > 1) redirect = multi.optString(1)
        if (redirect.isNullOrBlank()) redirect = resp.optString("redirectURL").takeIf { it.isNotBlank() && it != "null" }
        if (redirect == null && err != null) throw FusionException("Login rifiutato: $err")

        if (redirect != null) {
            val url = if (redirect.startsWith("http")) redirect else "https://$loginHost$redirect"
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val chain = generateSequence(r) { it.priorResponse }.toList().reversed()
                chain.forEach { log("redirect ${it.code} ${it.request.url.host}${it.request.url.encodedPath}") }
                log("final body: " + r.body?.string().orEmpty().take(200).replace("\n", " "))
                val host = r.request.url.host
                if (host.endsWith("fusionsolar.huawei.com")) dataHost = host
            }
            log("cookies: " + jar.describe())
        }
    }

    private fun encryptPassword(password: String, pem: String): String {
        val der = Base64.decode(
            pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace("\\s".toRegex(), ""),
            Base64.DEFAULT
        )
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val spec = OAEPParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, PSource.PSpecified.DEFAULT)
        val encoded = URLEncoder.encode(password, "UTF-8").replace("+", "%20")
        val out = StringBuilder()
        encoded.chunked(270).forEach { chunk ->
            val c = Cipher.getInstance("RSA/ECB/OAEPPadding")
            c.init(Cipher.ENCRYPT_MODE, key, spec)
            if (out.isNotEmpty()) out.append("00000001")
            out.append(Base64.encodeToString(c.doFinal(chunk.toByteArray()), Base64.NO_WRAP))
        }
        return out.toString()
    }

    // Newer "uni" hosts use dpcloud, older "region" hosts use neteco.
    private fun keepAlive(): Boolean =
        listOf("/rest/dpcloud/auth/v1/keep-alive", "/rest/neteco/auth/v1/keep-alive").any { path ->
            try {
                val s = get("https://$dataHost$path")
                log("keep-alive $dataHost$path: ${s.take(150).replace("\n", " ")}")
                val token = JSONObject(s).optString("payload")
                if (token.isNotBlank() && token != "null") { csrf = token; true } else false
            } catch (e: Exception) {
                log("keep-alive $dataHost$path: ${e.message}")
                false
            }
        }

    // ---------- data ----------

    private fun stationDn(): String {
        prefs.getString("stationDn", null)?.let { return it }
        val body = JSONObject()
            .put("curPage", 1).put("pageSize", 10).put("gridConnectedTime", "")
            .put("queryTime", dayStart().timeInMillis).put("timeZone", tzHours())
            .put("sortId", "createTime").put("sortDir", "DESC").put("locale", "it_IT")
        val o = JSONObject(post("https://$dataHost/rest/pvms/web/station/v1/station/station-list", body))
        val list = o.optJSONObject("data")?.optJSONArray("list")
        if (list == null || list.length() == 0) throw FusionException("Nessun impianto trovato")
        val dn = list.getJSONObject(0).getString("dn")
        prefs.edit().putString("stationDn", dn).apply()
        return dn
    }

    private fun dailyEnergy(dn: String): Pair<Double?, Double?> {
        val start = dayStart()
        val dateStr = SimpleDateFormat("yyyy-MM-dd 00:00:00", Locale.US).format(start.time)
        val o = getJson(
            "/rest/pvms/web/station/v2/overview/energy-balance?stationDn=${enc(dn)}&timeDim=2" +
                "&queryTime=${start.timeInMillis}&timeZone=${tzHours()}&timeZoneStr=${enc(TimeZone.getDefault().id)}" +
                "&dateStr=${enc(dateStr)}&_=${now()}"
        )
        val d = o.optJSONObject("data") ?: return null to null
        return num(d.opt("totalProductPower")) to num(d.opt("totalUsePower"))
    }

    private fun parseFlow(o: JSONObject): SolarData {
        val flow = o.optJSONObject("data")?.optJSONObject("flow") ?: throw FusionException("Risposta energy-flow inattesa")
        val nodes = flow.optJSONArray("nodes") ?: JSONArray()
        var pv: Double? = null; var load: Double? = null; var grid: Double? = null
        var batt: Double? = null; var soc: Double? = null; var inverter: Double? = null
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            // Names look like "neteco.pvms.devTypeLangKey.string": match only the last segment.
            val name = n.optString("name").lowercase().substringAfterLast('.')
            val desc = n.optJSONObject("description")
            val tips = n.optJSONObject("deviceTips")
            val value = num(desc?.opt("value")) ?: num(n.opt("value"))
            log("node ${n.optString("name")} value=${desc?.opt("value") ?: n.opt("value")} tips=${tips?.toString()?.take(200)}")
            when {
                "energy_store" in name || "battery" in name || name == "ess" -> {
                    soc = num(tips?.opt("SOC")) ?: soc
                    batt = num(tips?.opt("BATTERY_POWER")) ?: value ?: batt
                }
                "electricalload" in name || "load" in name || "house" in name -> load = value ?: load
                "string" in name || "pv" in name || "solar" in name -> pv = value ?: pv
                "inverter" in name -> inverter = value ?: num(tips?.opt("ACTIVE_POWER")) ?: inverter
                "grid" in name || "meter" in name || "power_grid" in name -> grid = value ?: num(tips?.opt("ACTIVE_POWER")) ?: grid
            }
        }
        val links = flow.optJSONArray("links") ?: JSONArray()
        for (i in 0 until links.length()) {
            val d = links.getJSONObject(i).optJSONObject("description") ?: continue
            val label = d.optString("label").lowercase()
            log("link ${d.optString("label")} value=${d.opt("value")}")
            val v = num(d.opt("value")) ?: continue
            if (grid == null && ("buy" in label || "sell" in label || "grid" in label || "feed" in label)) grid = v
        }
        // Without optimizers the PV power is only reported on the inverter node.
        if (pv == null) pv = inverter
        if (grid == null && pv != null && load != null) {
            // Huawei reports BATTERY_POWER > 0 when charging.
            return SolarData(pv, load, load + (batt ?: 0.0) - pv, batt, soc, null, null, System.currentTimeMillis())
        }
        // Signs are not reliable in the API: pick the combination that balances PV + import = load + charge.
        val p = pv ?: 0.0; val l = load ?: 0.0
        val g = abs(grid ?: 0.0); val b = abs(batt ?: 0.0)
        val preferredBattSign = if ((batt ?: 0.0) < 0) -1 else 1
        var best = Triple(1, preferredBattSign, Double.MAX_VALUE)
        for (gs in intArrayOf(1, -1)) for (bs in intArrayOf(preferredBattSign, -preferredBattSign)) {
            val residual = abs(p + gs * g - l - bs * b)
            if (residual < best.third - 0.02) best = Triple(gs, bs, residual)
        }
        return SolarData(
            pv = pv, load = load,
            grid = grid?.let { best.first * g },
            battery = batt?.let { best.second * b },
            soc = soc, dayYield = null, dayUse = null,
            time = System.currentTimeMillis()
        )
    }

    // ---------- http helpers ----------

    private fun getJson(path: String) = JSONObject(get("https://$dataHost$path"))

    private fun get(url: String): String = exec(Request.Builder().url(url).get())

    private fun post(url: String, body: JSONObject): String =
        exec(Request.Builder().url(url).post(body.toString().toRequestBody(json)))

    private fun exec(b: Request.Builder): String {
        b.header("Accept", "application/json")
        csrf?.let { b.header("roarand", it) }
        client.newCall(b.build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw FusionException("HTTP ${r.code} su ${r.request.url.encodedPath}")
            return s
        }
    }

    private fun num(v: Any?): Double? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toDouble()
        else -> {
            val s = v.toString().replace(",", ".")
            val m = Regex("-?\\d+(\\.\\d+)?").find(s)
            m?.value?.toDoubleOrNull()?.let { x ->
                if (Regex("\\d\\s*W\\b").containsMatchIn(s) && !s.contains("kW", true)) x / 1000 else x
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun now() = System.currentTimeMillis()
    private fun nonce() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun tzHours() = TimeZone.getDefault().getOffset(now()) / 3_600_000.0
    private fun dayStart() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}

/** Cookie jar persisted in SharedPreferences so the session survives between widget refreshes. */
class PrefsCookieJar(ctx: Context) : CookieJar {
    private val prefs = ctx.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val cookies = mutableMapOf<String, Cookie>()

    init {
        // The key carries the cookie's domain, needed to parse host-only cookies back.
        prefs.all.forEach { (k, v) ->
            val domain = k.substringAfter('|').substringBefore('|')
            Cookie.parse("https://$domain/".toHttpUrl(), v.toString())?.let { put(it) }
        }
    }

    private fun key(c: Cookie) = "${c.name}|${c.domain}|${c.path}"
    private fun put(c: Cookie) { cookies[key(c)] = c }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val e = prefs.edit()
        cookies.forEach { c ->
            put(c)
            e.putString(key(c), c.toString())
        }
        e.apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { it.value.expiresAt < now }
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun describe() = cookies.values.joinToString { "${it.name}@${it.domain}${if (it.hostOnly) "" else "*"}" }

    @Synchronized
    fun clear() {
        cookies.clear()
        prefs.edit().clear().apply()
    }
}
