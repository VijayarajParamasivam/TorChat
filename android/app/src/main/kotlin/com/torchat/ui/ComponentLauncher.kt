package com.torchat.ui

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.result.ActivityResultLauncher
import com.torchat.R
import com.torchat.data.TorChatContract
import com.torchat.receivers.KeepAliveReceiver
import com.torchat.service.KeepAlive
import com.torchat.service.TorChatService
import com.torchat.ui.common.QrCode
import com.torchat.ui.common.Repository
import com.torchat.ui.importcard.ImportCardActivity
import com.torchat.widget.NodeWidgetProvider

/**
 * One method per way of activating an Android component.
 *
 * Android has four kinds of component and each is woken by its own mechanism:
 * an activity by an intent passed to `startActivity`, a service by
 * `startForegroundService` or `bindService`, a broadcast receiver by
 * `sendBroadcast`, and a content provider by a query through a
 * `ContentResolver`. The app's own screens call these — the add-friend screen
 * opens the scanner through [openScanner], the code screen shares through
 * [shareCode] — and the diagnostics screen calls the same methods so each
 * mechanism can be seen working on its own.
 */
object ComponentLauncher {

    // --- activities ---------------------------------------------------------

    /**
     * An **explicit** intent: this exact activity in this app, started for a
     * result (the scanned code).
     */
    fun openScanner(launcher: ActivityResultLauncher<Unit>) = launcher.launch(Unit)

    /**
     * An **implicit** intent: "send this text", with the person choosing which
     * app handles it. TorChat does not know or care which messenger carries the
     * code — any of them will do, because the code is not a secret.
     */
    fun shareCode(context: Context, card: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_code_subject))
            .putExtra(Intent.EXTRA_TEXT, card)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
    }

    /** An explicit intent carrying data: the import screen, with a code to show. */
    fun openImport(context: Context, card: String) {
        context.startActivity(
            Intent(context, ImportCardActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, card)
        )
    }

    // --- services -----------------------------------------------------------

    /** Started: runs until stopped, independent of any screen. */
    fun startNode(context: Context): Boolean = TorChatService.start(context)

    /** Bound: lives at least as long as the binding, and hands back a binder. */
    fun bindNode(context: Context, connection: ServiceConnection): Boolean =
        context.bindService(Intent(context, TorChatService::class.java), connection, Context.BIND_AUTO_CREATE)

    fun unbindNode(context: Context, connection: ServiceConnection) =
        runCatching { context.unbindService(connection) }.isSuccess

    fun stopNode(context: Context) = TorChatService.stop(context)

    // --- broadcast receivers ------------------------------------------------

    /** The same broadcast the keep-alive alarm fires, sent now. */
    fun sendKeepAlive(context: Context) {
        context.sendBroadcast(
            Intent(context, KeepAliveReceiver::class.java).setAction(KeepAlive.ACTION)
        )
    }

    /** Ask the home-screen widget to redraw. */
    fun refreshWidget(context: Context) = NodeWidgetProvider.requestRefresh(context)

    // --- content providers --------------------------------------------------

    /** A query through the ContentResolver, as any client of a provider makes one. */
    fun queryProvider(resolver: ContentResolver): String {
        val friends = resolver.query(TorChatContract.Friends.CONTENT_URI, null, null, null, null)
            ?.use { it.count } ?: 0
        val conversations = resolver.query(TorChatContract.Conversations.CONTENT_URI, null, null, null, null)
            ?.use { it.count } ?: 0
        val stats = Repository.readStats(resolver)
        val type = resolver.getType(TorChatContract.Conversations.CONTENT_URI)
        return "friends=$friends conversations=$conversations unread=${stats.unread} " +
            "outbox=${stats.outbox}\ntype=$type"
    }

    /** A FileProvider grant: another app reads one image, and nothing else. */
    fun shareQrImage(context: Context, card: String): Boolean {
        val intent = QrCode.shareIntent(context, card, context.getString(R.string.share_qr)) ?: return false
        context.startActivity(intent)
        return true
    }

    /** A ServiceConnection that reports what it hears. */
    fun connection(report: (String) -> Unit): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val local = binder as? TorChatService.LocalBinder
            val engine = local?.engine?.value
            report("bound to ${name.shortClassName} — engine ${if (engine != null) "running" else "not running yet"}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            report("service ${name.shortClassName} disconnected")
        }
    }
}
