package it.murgolo.solarwidget

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Short history of session events (pings, logins, captchas) shown in the debug screen. */
object Events {
    private const val MAX = 80

    @Synchronized
    fun add(ctx: Context, msg: String) {
        val p = ctx.getSharedPreferences("events", Context.MODE_PRIVATE)
        val old = JSONArray(p.getString("list", "[]"))
        val list = JSONArray()
        val from = maxOf(0, old.length() - MAX + 1)
        for (i in from until old.length()) list.put(old.getString(i))
        list.put(SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date()) + "  " + msg)
        p.edit().putString("list", list.toString()).apply()
    }

    fun all(ctx: Context): String {
        val a = JSONArray(ctx.getSharedPreferences("events", Context.MODE_PRIVATE).getString("list", "[]"))
        return (a.length() - 1 downTo 0).joinToString("\n") { a.getString(it) }
    }
}
