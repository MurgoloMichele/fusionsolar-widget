package it.murgolo.solarwidget

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView

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

        status = label("", 13f).apply { setTextIsSelectable(true) }
        col.addView(status)
        col.addView(label("Aggiungi il widget dalla schermata Home: tieni premuto → Widget → Solar Widget.", 13f))

        setContentView(ScrollView(this).apply { addView(col) })
        FetchWorker.schedulePeriodic(this)
        showLast()
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
            runOnUiThread { if (msg != null) status.text = msg else showLast() }
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
