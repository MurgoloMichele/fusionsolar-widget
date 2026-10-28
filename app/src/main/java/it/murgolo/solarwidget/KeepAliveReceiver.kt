package it.murgolo.solarwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Pings the portal every ~10 minutes, also while the phone is dozing, so the web session never
 * expires and no new login (and captcha) is needed. WorkManager alone is deferred for hours in Doze.
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        schedule(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        thread {
            try {
                FusionApi(ctx).ping()
            } catch (e: Exception) {
                Events.add(ctx, "ping: errore ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val INTERVAL = 10 * 60_000L

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, KeepAliveReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL, pi)
        }
    }
}
