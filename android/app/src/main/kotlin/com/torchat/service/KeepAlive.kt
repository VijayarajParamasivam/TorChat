package com.torchat.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.PendingIntentCompat
import com.torchat.receivers.KeepAliveReceiver

/**
 * A periodic wake-up while the phone sleeps.
 *
 * A foreground service keeps the process alive, but not the processor: in Doze
 * the CPU sleeps, every timer in the engine stops, and a link that died while
 * the screen was off is not noticed until something wakes the phone. This alarm
 * is that something. Each time it fires, [KeepAliveReceiver] holds a short wake
 * lock and asks the engine to check its links and dial anyone due
 * ([com.torchat.core.engine.Engine.nudge]).
 *
 * Inexact and allowed-while-idle: Android batches it with other wake-ups, needs
 * no exact-alarm permission, and still fires in Doze (at most every nine
 * minutes or so, which is why the interval is above that).
 */
object KeepAlive {

    const val ACTION = "com.torchat.action.KEEP_ALIVE"
    private const val INTERVAL_MS = 15 * 60 * 1000L

    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        AlarmManagerCompat.setAndAllowWhileIdle(
            alarms,
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            intent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
    }

    private fun intent(context: Context): PendingIntent =
        PendingIntentCompat.getBroadcast(
            context,
            0,
            Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )!!
}
