package it.murgolo.solarwidget

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var captchaBox: LinearLayout
    private lateinit var captchaImg: ImageView
    private lateinit var captchaCode: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); setPadding(0, pad / 2, 0, pad / 4)
        }

        col.addView(label("FusionSolar Widget", 22f))
        col.addView(label("Credenziali FusionSolar (eu5). Restano solo su questo telefono, la password è cifrata."))

        val user = EditText(this).apply {
            hint = "Email / nome utente"
            setText(Credentials.user(this@MainActivity) ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val pass = EditText(this).apply {
            hint = if (Credentials.password(this@MainActivity) != null) "Password (salvata)" else "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        col.addView(user, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(pass, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(Button(this).apply {
            text = "Salva e prova"
            setOnClickListener {
                val p = pass.text.toString().ifEmpty { Credentials.password(this@MainActivity) ?: "" }
                if (user.text.isBlank() || p.isEmpty()) { status.text = "Inserisci utente e password"; return@setOnClickListener }
                Credentials.save(this@MainActivity, user.text.toString().trim(), p)
                FusionApi(this@MainActivity).resetSession()
                test()
            }
        })
        col.addView(Button(this).apply { text = "Aggiorna ora"; setOnClickListener { test() } })
        col.addView(Button(this).apply {
            text = "Mostra dati grezzi (debug)"
            setOnClickListener {
                val p = getSharedPreferences("fusion", MODE_PRIVATE)
                status.text = "LOG:\n" + p.getString("log", "-") + "\nENERGY-FLOW:\n" + p.getString("rawFlow", "Nessun dato")
            }
        })

        // Shown only when the portal asks for a captcha.
        captchaBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        captchaBox.addView(label("Il portale FusionSolar chiede un captcha. Scrivi il codice dell'immagine (toccala per cambiarla):", 13f))
        captchaImg = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
            setOnClickListener { loadCaptcha() }
        }
        captchaBox.addView(captchaImg, LinearLayout.LayoutParams((240 * resources.displayMetrics.density).toInt(), WRAP_CONTENT))
        captchaCode = EditText(this).apply {
            hint = "Codice captcha"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        captchaBox.addView(captchaCode, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        captchaBox.addView(Button(this).apply { text = "Conferma captcha"; setOnClickListener { solveCaptcha() } })
        col.addView(captchaBox)

        status = label("", 13f).apply { setTextIsSelectable(true) }
        col.addView(status)
        col.addView(label("Aggiungi il widget dalla schermata Home: tieni premuto → Widget → Solar Widget.", 13f))

        setContentView(ScrollView(this).apply { addView(col) })
        FetchWorker.schedulePeriodic(this)
        showLast()
        if (FusionApi(this).needsCaptcha) loadCaptcha()
    }

    private fun loadCaptcha() {
        captchaBox.visibility = View.VISIBLE
        captchaCode.setText("")
        thread {
            val img = try { FusionApi(this).captchaImage() } catch (e: Exception) { null }
            runOnUiThread {
                if (img != null) captchaImg.setImageBitmap(BitmapFactory.decodeByteArray(img, 0, img.size))
                else status.text = "Impossibile caricare il captcha, tocca l'immagine per riprovare"
            }
        }
    }

    private fun solveCaptcha() {
        val code = captchaCode.text.toString()
        if (code.isBlank()) { status.text = "Scrivi il codice del captcha"; return }
        status.text = "Accesso in corso…"
        thread {
            val msg = try {
                SolarData.save(this, FusionApi(this).solveCaptcha(code))
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            SolarWidgetProvider.updateAll(this)
            runOnUiThread {
                if (msg == null) { captchaBox.visibility = View.GONE; showLast() }
                else { status.text = "Errore: $msg"; loadCaptcha() }
            }
        }
    }

    private fun test() {
        status.text = "Connessione in corso…"
        thread {
            val msg = try {
                val d = FusionApi(this).fetch()
                SolarData.save(this, d)
                null
            } catch (e: Exception) {
                SolarData.saveError(this, e.message ?: e.javaClass.simpleName)
                "Errore: ${e.message ?: e.javaClass.simpleName}"
            }
            SolarWidgetProvider.updateAll(this)
            runOnUiThread {
                if (msg != null) status.text = msg else showLast()
                if (FusionApi(this).needsCaptcha) loadCaptcha()
            }
        }
    }

    private fun showLast() {
        val d = SolarData.load(this) ?: return
        fun f(v: Double?) = v?.let { String.format(Locale.ITALY, "%.2f", it) } ?: "--"
        status.text = "OK ✓\nFV: ${f(d.pv)} kW\nCasa: ${f(d.load)} kW\nRete: ${f(d.grid)} kW (+prelievo / −immissione)\n" +
            "Batteria: ${d.soc?.toInt() ?: "--"}%  ${f(d.battery)} kW (+carica / −scarica)\n" +
            "Resa oggi: ${f(d.dayYield)} kWh\nConsumo oggi: ${f(d.dayUse)} kWh"
    }
}
