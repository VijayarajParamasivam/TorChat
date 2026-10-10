package com.torchat.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.torchat.core.engine.Engine
import com.torchat.notify.MessageNotifier
import com.torchat.service.Session
import com.torchat.service.TorChatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The buttons on a notification: reply, mark as read, accept, reject.
 *
 * A reply goes through the engine like any other message, so it is queued,
 * delivered and retried under the same contract (§8) — answering from the
 * shade is not a lesser path. If the node happens to be down, tapping a
 * notification button is one of the moments Android allows it to be started.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val peerId = intent.getStringExtra(EXTRA_PEER) ?: return
        val notifier = MessageNotifier(app)
        val pending = goAsync()

        scope.launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(EXTRA_REPLY)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) engine(app)?.sendText(peerId, text)
                        Session.store(app).markRead(peerId)
                        notifier.cancelConversation(peerId)
                    }

                    ACTION_MARK_READ -> {
                        Session.store(app).markRead(peerId)
                        notifier.cancelConversation(peerId)
                    }

                    ACTION_ACCEPT -> {
                        engine(app)?.acceptRequest(peerId)
                        notifier.cancelRequest(peerId)
                    }

                    ACTION_REJECT -> {
                        engine(app)?.rejectRequest(peerId)
                        notifier.cancelRequest(peerId)
                    }
                }
            } catch (failure: Exception) {
                Log.w(TAG, "notification action ${intent.action} failed", failure)
            } finally {
                pending.finish()
            }
        }
    }

    /** The running engine, starting the node first if it is down. */
    private suspend fun engine(context: Context): Engine? {
        Session.engine?.let { return it }
        TorChatService.start(context)
        return withTimeoutOrNull(START_WAIT_MS) { Session.engineFlow.filterNotNull().first() }
    }

    companion object {
        const val ACTION_REPLY = "com.torchat.action.REPLY"
        const val ACTION_MARK_READ = "com.torchat.action.MARK_READ"
        const val ACTION_ACCEPT = "com.torchat.action.ACCEPT"
        const val ACTION_REJECT = "com.torchat.action.REJECT"

        const val EXTRA_PEER = "peerId"
        const val EXTRA_REPLY = "reply"

        private const val TAG = "TorChat"
        private const val START_WAIT_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
