package it.murgolo.solarwidget

import android.content.Context
import org.json.JSONObject

/** Power values in kW. grid > 0 = prelievo, < 0 = immissione. battery > 0 = in carica, < 0 = in scarica. */
data class SolarData(
    val pv: Double?,
    val load: Double?,
    val grid: Double?,
    val battery: Double?,
    val soc: Double?,
    val dayYield: Double?,
    val dayUse: Double?,
    val time: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("pv", pv); put("load", load); put("grid", grid); put("battery", battery)
        put("soc", soc); put("dayYield", dayYield); put("dayUse", dayUse); put("time", time)
    }.toString()

    companion object {
        private fun JSONObject.d(k: String) = if (isNull(k)) null else optDouble(k)

        fun fromJson(s: String) = JSONObject(s).run {
            SolarData(d("pv"), d("load"), d("grid"), d("battery"), d("soc"), d("dayYield"), d("dayUse"), optLong("time"))
        }

        fun save(ctx: Context, data: SolarData) = store(ctx).edit()
            .putString("last", data.toJson()).remove("error").apply()

        fun saveError(ctx: Context, msg: String) = store(ctx).edit()
            .putString("error", msg).putLong("errorTime", System.currentTimeMillis()).apply()

        fun load(ctx: Context): SolarData? = store(ctx).getString("last", null)?.let { fromJson(it) }
        fun error(ctx: Context): String? = store(ctx).getString("error", null)

        private fun store(ctx: Context) = ctx.getSharedPreferences("data", Context.MODE_PRIVATE)
    }
}
