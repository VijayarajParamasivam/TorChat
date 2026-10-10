package com.torchat.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.torchat.service.TorChatService

/**
 * Brings the node back after a reboot or an app update.
 *
 * Without this, a phone that restarted overnight is unreachable until its owner
 * happens to open the app — and with no server to hold messages, that silence
 * is total. Both broadcasts are among the few from which Android still allows
 * a foreground service to start.
 *
 * Respects the person's choices: nothing starts if there is no identity yet,
 * if they turned "start on boot" off, or if they stopped the node themselves.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit

            else -> return
        }

        val app = context.applicationContext
        if (!TorChatService.shouldRun(app, forBoot = true)) {
            Log.i(TAG, "${intent.action}: not starting the node")
            return
        }

        Log.i(TAG, "${intent.action}: starting the node")
        TorChatService.start(app)
    }

    private companion object {
        const val TAG = "TorChat"
    }
}
