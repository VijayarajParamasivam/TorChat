package com.torchat.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.navigation.NavDeepLinkBuilder
import com.torchat.MainActivity
import com.torchat.R
import com.torchat.receivers.NotificationActionReceiver
import com.torchat.service.Session
import com.torchat.ui.common.Identicon

/**
 * Message and request notifications.
 *
 * Built from the database, not from the event that triggered them. A
 * notification shows every unread message from that friend, read fresh from
 * [com.torchat.data.SqlStore.unread] — so an event that was somehow missed
 * costs nothing, because the next one shows everything, and marking a chat read
 * anywhere (on screen, from the notification, from another device's ack)
 * clears it everywhere.
 *
 * Each chat is a MessagingStyle conversation with its own shortcut, so Android
 * 11+ lists it under Conversations and lets the person prioritise it, and it
 * can be answered without opening the app.
 */
class MessageNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val store get() = Session.store(context)

    /** Can we post at all? Android 13+ needs the person's permission. */
    val canPost: Boolean
        get() = manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    // --- messages -----------------------------------------------------------

    /** Show, refresh or clear the notification for one chat to match what is unread. */
    fun showConversation(peerId: String, alert: Boolean = true) {
        val friend = store.readFriends().firstOrNull { it.id == peerId }
        val unread = store.unread(peerId)
        if (friend == null || unread.isEmpty()) {
            cancelConversation(peerId)
            return
        }
        if (!canPost) return

        val muted = store.isMuted(peerId)
        val previews = Session.prefs(context).previews
        val person = person(peerId, friend.name)
        pushShortcut(peerId, friend.name, person)

        val builder = NotificationCompat.Builder(context, Channels.forMessage(context, peerId))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.tc_primary))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (muted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setShortcutId(peerId)
            .setLocusId(LocusIdCompat(peerId))
            .setGroup(GROUP_MESSAGES)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(muted)
            .setNumber(unread.size)
            .setWhen(unread.last().ts)
            .setContentIntent(openChat(peerId))
            // The lock screen gets "1 new message" and nothing else. A
            // notification is the most public place a message is ever shown.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, Channels.forMessage(context, peerId))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(hiddenSummary(unread.size))
                    .build()
            )
            .addAction(replyAction(peerId))
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_done_all,
                    context.getString(R.string.mark_read),
                    actionIntent(NotificationActionReceiver.ACTION_MARK_READ, peerId),
                )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build()
            )

        if (previews) {
            val style = NotificationCompat.MessagingStyle(me())
            for (message in unread) style.addMessage(message.body, message.ts, person)
            builder.setStyle(style)
        } else {
            builder.setContentTitle(friend.name).setContentText(hiddenSummary(unread.size))
        }

        post(TAG_MESSAGE + peerId, ID_MESSAGE, builder)
        refreshSummary()
    }

    fun cancelConversation(peerId: String) {
        manager.cancel(TAG_MESSAGE + peerId, ID_MESSAGE)
        refreshSummary()
    }

    /** Re-render every chat with something unread — after the preview setting changes, say. */
    fun refreshAll() {
        for (peer in store.peersWithUnread()) showConversation(peer, alert = false)
    }

    /**
     * A summary that holds the per-chat notifications together once there is
     * more than one, so five busy chats are one expandable entry rather than
     * five separate interruptions.
     */
    private fun refreshSummary() {
        val peers = store.peersWithUnread()
        if (peers.size < 2 || !canPost) {
            manager.cancel(ID_SUMMARY)
            return
        }

        val total = store.unreadCount()
        val builder = NotificationCompat.Builder(context, Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.tc_primary))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(hiddenSummary(total))
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        post(null, ID_SUMMARY, builder)
    }

    // --- requests -----------------------------------------------------------

    fun showRequest(peerId: String, name: String) {
        if (!canPost) return
        val builder = NotificationCompat.Builder(context, Channels.REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.tc_primary))
            .setLargeIcon(Identicon.bitmap(peerId, LARGE_ICON_PX))
            .setContentTitle(name)
            .setContentText(context.getString(R.string.notif_request_body))
            .setSubText(peerId)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(deepLink(R.id.requestsFragment, null))
            .addAction(
                R.drawable.ic_check,
                context.getString(R.string.accept),
                actionIntent(NotificationActionReceiver.ACTION_ACCEPT, peerId),
            )
            .addAction(
                R.drawable.ic_block,
                context.getString(R.string.reject),
                actionIntent(NotificationActionReceiver.ACTION_REJECT, peerId),
            )
        post(TAG_REQUEST + peerId, ID_REQUEST, builder)
    }

    fun cancelRequest(peerId: String) = manager.cancel(TAG_REQUEST + peerId, ID_REQUEST)

    // --- the node itself ----------------------------------------------------

    /**
     * The node stopped and Android will not let us restart it from the
     * background. Said out loud, because the alternative is a friend whose
     * messages silently stop arriving.
     */
    fun showStopped() {
        if (!canPost) return
        val builder = NotificationCompat.Builder(context, Channels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.tc_warning))
            .setContentTitle(context.getString(R.string.notif_stopped_title))
            .setContentText(context.getString(R.string.notif_stopped_body))
            .setAutoCancel(true)
            .setContentIntent(openApp())
        post(null, ID_STOPPED, builder)
    }

    fun cancelStopped() = manager.cancel(ID_STOPPED)

    // --- shortcuts ----------------------------------------------------------

    /**
     * A long-lived conversation shortcut per chat. It is what makes Android
     * treat the notification as a conversation, and it shows the chat in the
     * launcher's long-press menu.
     */
    fun pushShortcut(peerId: String, name: String, person: Person = person(peerId, name)) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, peerId)
                .setShortLabel(name)
                .setLongLived(true)
                .setPerson(person)
                .setIcon(IconCompat.createWithBitmap(Identicon.bitmap(peerId, SHORTCUT_PX)))
                .setLocusId(LocusIdCompat(peerId))
                .setCategories(setOf(SHORTCUT_CATEGORY_CONVERSATION))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("torchat://chat/$peerId"), context, MainActivity::class.java)
                )
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    fun removeShortcut(peerId: String) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(peerId)) }
    }

    // --- internals ----------------------------------------------------------

    private fun person(peerId: String, name: String): Person = Person.Builder()
        .setName(name)
        .setKey(peerId)
        .setIcon(IconCompat.createWithBitmap(Identicon.bitmap(peerId, LARGE_ICON_PX)))
        .build()

    private fun me(): Person = Person.Builder()
        .setName(context.getString(R.string.you))
        .setKey("me")
        .build()

    private fun hiddenSummary(count: Int): String =
        context.resources.getQuantityString(R.plurals.notif_hidden_messages, count, count)

    private fun replyAction(peerId: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(NotificationActionReceiver.EXTRA_REPLY)
            .setLabel(context.getString(R.string.notif_reply))
            .build()

        // Mutable: RemoteInput writes the typed reply into this intent.
        val intent = PendingIntentCompat.getBroadcast(
            context,
            requestCode(NotificationActionReceiver.ACTION_REPLY, peerId),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_REPLY)
                .putExtra(NotificationActionReceiver.EXTRA_PEER, peerId),
            PendingIntent.FLAG_UPDATE_CURRENT,
            true,
        )!!

        return NotificationCompat.Action.Builder(R.drawable.ic_reply, context.getString(R.string.notif_reply), intent)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun actionIntent(action: String, peerId: String): PendingIntent =
        PendingIntentCompat.getBroadcast(
            context,
            requestCode(action, peerId),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_PEER, peerId),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )!!

    private fun openChat(peerId: String): PendingIntent =
        deepLink(R.id.chatFragment, Bundle().apply { putString(ARG_PEER, peerId) })

    private fun deepLink(destination: Int, args: Bundle?): PendingIntent =
        NavDeepLinkBuilder(context)
            .setComponentName(MainActivity::class.java)
            .setGraph(R.navigation.nav_graph)
            .setDestination(destination)
            .setArguments(args)
            .createPendingIntent()

    private fun openApp(): PendingIntent =
        PendingIntentCompat.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )!!

    @Suppress("MissingPermission") // checked by canPost before every call
    private fun post(tag: String?, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost) return
        runCatching { manager.notify(tag, id, builder.build()) }
    }

    private fun requestCode(action: String, peerId: String) = (action + peerId).hashCode()

    companion object {
        /** The navigation argument a chat is opened with. */
        const val ARG_PEER = "peerId"

        const val ID_STATUS = 1
        private const val ID_STOPPED = 2
        private const val ID_MESSAGE = 10
        private const val ID_REQUEST = 11
        private const val ID_SUMMARY = 12

        private const val TAG_MESSAGE = "msg:"
        private const val TAG_REQUEST = "req:"
        private const val GROUP_MESSAGES = "com.torchat.MESSAGES"

        private const val SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation"
        private const val LARGE_ICON_PX = 128
        private const val SHORTCUT_PX = 192
    }
}
