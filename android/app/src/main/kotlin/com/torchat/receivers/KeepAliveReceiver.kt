package com.torchat.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.torchat.service.KeepAlive
import com.torchat.service.Session
import com.torchat.service.TorChatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fired by [KeepAlive]'s alarm, and by the diagnostics screen on demand.
 *
 * Holds the processor awake just long enough for the engine to probe its links
 * and dial whoever is due, then lets the phone sleep again. If the node is not
 * running when it should be, it tries to bring it back — which Android may
 * refuse from the background, in which case the person is told.
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()

        val wake = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorChat:keepalive")
            ?.apply { acquire(WAKE_MS) }

        scope.launch {
            try {
                val engine = Session.engine
                if (engine != null) {
                    withTimeoutOrNull(NUDGE_BUDGET_MS) { engine.nudge() }
                    Log.i(TAG, "keep-alive: nudged the engine")
                } else if (TorChatService.shouldRun(app)) {
                    Log.i(TAG, "keep-alive: node should be running — starting it")
                    TorChatService.start(app)
                }
            } finally {
                // Next one is scheduled from here, so the chain survives
                // the service being restarted by the system in between.
                if (TorChatService.shouldRun(app)) KeepAlive.schedule(app)
                runCatching { if (wake?.isHeld == true) wake.release() }
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TorChat"
        const val WAKE_MS = 30_000L
        const val NUDGE_BUDGET_MS = 8_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
