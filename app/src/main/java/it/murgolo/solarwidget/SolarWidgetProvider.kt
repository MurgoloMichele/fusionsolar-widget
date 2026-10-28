package it.murgolo.solarwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class SolarWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) = FetchWorker.schedulePeriodic(context)

    override fun onDisabled(context: Context) {
        FetchWorker.cancel(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        KeepAliveReceiver.schedule(context)
        FetchWorker.schedulePeriodic(context)
        FetchWorker.refreshNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SolarWidgetProvider::class.java))
            for (id in ids) {
                manager.partiallyUpdateAppWidget(id, RemoteViews(context.packageName, R.layout.widget).apply {
                    setTextViewText(R.id.updated, "aggiornamento…")
                })
            }
            FetchWorker.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "it.murgolo.solarwidget.REFRESH"

        private const val ORIGINAL_APP = "com.huawei.smartpvms"

        private val GREEN = Color.parseColor("#4ADE80")
        private val YELLOW = Color.parseColor("#FACC15")
        private val ORANGE = Color.parseColor("#FB923C")
        private val BLUE = Color.parseColor("#7DD3FC")
        private val WHITE = Color.WHITE
        private val DIM = Color.parseColor("#B3FFFFFF")
        private val OFF = Color.parseColor("#80FFFFFF")

        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            render(ctx, manager, manager.getAppWidgetIds(ComponentName(ctx, SolarWidgetProvider::class.java)))
        }

        private fun kw(v: Double?) = if (v == null) "--" else String.format(Locale.ITALY, "%.2f", abs(v))
        private fun kwh(v: Double?) = if (v == null) "--" else String.format(Locale.ITALY, "%.2f kWh", v)

        private fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val d = SolarData.load(ctx)
            val err = SolarData.error(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)

            fun tint(id: Int, color: Int) = v.setInt(id, "setColorFilter", color)

            val pv = d?.pv
            val producing = pv != null && pv > 0.01
            v.setTextViewText(R.id.pv, kw(pv))
            v.setTextColor(R.id.pv, if (producing) YELLOW else WHITE)
            v.setTextViewText(R.id.pv_sub, if (producing) "kW · produzione" else "kW · FV")
            tint(R.id.pv_icon, if (producing) YELLOW else OFF)

            v.setTextViewText(R.id.load, kw(d?.load))
            tint(R.id.load_icon, BLUE)

            val g = d?.grid
            val exporting = g != null && g < -0.01
            val importing = g != null && g > 0.01
            v.setTextViewText(R.id.grid, kw(g))
            v.setTextViewText(R.id.grid_sub, when {
                exporting -> "kW · immissione"
                importing -> "kW · prelievo"
                else -> "kW · Rete"
            })
            v.setTextColor(R.id.grid, if (exporting) GREEN else if (importing) ORANGE else WHITE)
            tint(R.id.grid_icon, if (exporting) GREEN else if (importing) ORANGE else OFF)

            val soc = d?.soc
            val b = d?.battery
            v.setTextViewText(R.id.soc, soc?.let { "${it.toInt()}%" } ?: "--")
            v.setTextViewText(R.id.batt_sub, when {
                b == null -> "Batteria"
                b > 0.01 -> "▲ ${kw(b)} kW"
                b < -0.01 -> "▼ ${kw(b)} kW"
                else -> "in attesa"
            })
            v.setTextColor(R.id.batt_sub, if (b != null && b > 0.01) GREEN else if (b != null && b < -0.01) ORANGE else DIM)
            v.setProgressBar(R.id.soc_bar, 100, (soc ?: 0.0).toInt(), false)
            tint(R.id.batt_icon, when {
                soc == null -> OFF
                soc < 20 -> ORANGE
                else -> GREEN
            })

            // One-line summary of what the system is doing right now.
            val headline = when {
                d == null -> "FusionSolar"
                producing -> "Stai producendo ${kw(pv)} kW"
                b != null && b < -0.01 -> "In uso la batteria"
                importing -> "Prelievo dalla rete"
                else -> "Impianto a riposo"
            }
            v.setTextViewText(R.id.headline, headline)
            v.setImageViewResource(R.id.status_icon, if (producing) R.drawable.ic_sun else if (b != null && b < -0.01) R.drawable.ic_battery else R.drawable.ic_grid)
            tint(R.id.status_icon, if (producing) YELLOW else if (b != null && b < -0.01) GREEN else if (importing) ORANGE else OFF)

            v.setTextViewText(R.id.day, "Oggi  ☀ ${kwh(d?.dayYield)} prodotti   ·   ⌂ ${kwh(d?.dayUse)} consumati")

            val time = d?.let { SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(it.time)) } ?: "--:--"
            v.setTextViewText(R.id.updated, if (err != null) "⚠ $err" else time)
            v.setTextColor(R.id.updated, if (err != null) ORANGE else DIM)
            tint(R.id.refresh, DIM)

            val refresh = Intent(ctx, SolarWidgetProvider::class.java).setAction(ACTION_REFRESH)
            v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(
                ctx, 0, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            // Tapping the widget opens the official FusionSolar app (ours if it is not installed).
            val open = ctx.packageManager.getLaunchIntentForPackage(ORIGINAL_APP)
                ?: Intent(ctx, MainActivity::class.java)
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(
                ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            manager.updateAppWidget(ids, v)
        }
    }
}
