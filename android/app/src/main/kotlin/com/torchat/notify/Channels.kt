package com.torchat.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.torchat.R
import com.torchat.core.model.Friend

/**
 * Every notification channel the app owns: creating them, changing them, and
 * deleting them.
 *
 * Four are fixed — the node's own status, messages, connection requests, and
 * alerts — and the rest come and go with friends. Adding a friend **creates** a
 * channel for that chat, so a person can give one friend a ringtone and silence
 * another from system settings. A friend renaming themselves **modifies** it,
 * since a channel still called by someone's old name is a puzzle in settings.
 * Removing a friend **deletes** it.
 *
 * Android lets an app change a channel's name and description but never raise
 * its importance once created — that belongs to the person. So muting and
 * unmuting replace the channel: the old one is deleted and a new one is made
 * under a different ID at the new importance. Re-creating the *same* ID would
 * quietly restore the deleted channel's settings, which is the opposite of what
 * was asked for.
 */
object Channels {

    const val GROUP_NODE = "group_node"
    const val GROUP_CHATS = "group_chats"

    const val NODE = "node"
    const val MESSAGES = "messages"
    const val REQUESTS = "requests"
    const val ALERTS = "alerts"

    private const val CONVERSATION_PREFIX = "conv_"
    private const val MUTED_SUFFIX = "_muted"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** What the channels screen lists. */
    data class Info(
        val id: String,
        val name: String,
        val description: String?,
        val importance: Int,
        val group: String?,
        /** The friend this channel belongs to, for per-chat channels. */
        val peerId: String?,
        val muted: Boolean,
    )

    // --- creation -----------------------------------------------------------

    /**
     * Make sure the fixed channels exist. Idempotent: creating a channel that
     * already exists only refreshes its name and description, never the
     * person's own choices about it.
     */
    fun createBase(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = manager(context)

        manager.createNotificationChannelGroups(
            listOf(
                NotificationChannelGroup(GROUP_NODE, context.getString(R.string.channel_group_node)),
                NotificationChannelGroup(GROUP_CHATS, context.getString(R.string.channel_group_chats)),
            )
        )

        manager.createNotificationChannels(
            listOf(
                channel(
                    NODE, context.getString(R.string.channel_node), NotificationManager.IMPORTANCE_LOW,
                    context.getString(R.string.channel_node_desc), GROUP_NODE,
                ).apply { setShowBadge(false) },
                channel(
                    ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT,
                    context.getString(R.string.channel_alerts_desc), GROUP_NODE,
                ),
                channel(
                    MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH,
                    context.getString(R.string.channel_messages_desc), GROUP_CHATS,
                ),
                channel(
                    REQUESTS, context.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH,
                    context.getString(R.string.channel_requests_desc), GROUP_CHATS,
                ),
            )
        )
    }

    /**
     * Create the channel for one chat, or rename it if it exists under an old
     * name. Returns its ID.
     */
    fun ensureConversation(context: Context, peerId: String, name: String, muted: Boolean = false): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager = manager(context)

        val existing = existingConversationId(manager, peerId)
        val wanted = conversationId(peerId, muted)

        // Muted state flipped since the channel was made: replace it.
        if (existing != null && existing != wanted) manager.deleteNotificationChannel(existing)

        manager.createNotificationChannel(conversationChannel(context, peerId, name, muted))
        return wanted
    }

    // --- modification -------------------------------------------------------

    /**
     * Rename a chat's channel. Name and description are the parts of a channel
     * an app is allowed to change after creating it; everything else is the
     * person's.
     */
    fun rename(context: Context, channelId: String, newName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = manager(context)
        val channel = manager.getNotificationChannel(channelId) ?: return false

        channel.name = newName
        peerOf(channelId)?.let {
            channel.description = context.getString(R.string.channel_conversation_desc, newName)
        }
        manager.createNotificationChannel(channel)
        return true
    }

    /**
     * Mute or unmute one chat by replacing its channel at a different
     * importance. See the class note for why it cannot be done in place.
     */
    fun setMuted(context: Context, peerId: String, name: String, muted: Boolean): String? =
        ensureConversation(context, peerId, name, muted)

    // --- deletion -----------------------------------------------------------

    /** Delete a chat's channel, muted or not. */
    fun deleteConversation(context: Context, peerId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = manager(context)
        manager.deleteNotificationChannel(conversationId(peerId, muted = false))
        manager.deleteNotificationChannel(conversationId(peerId, muted = true))
    }

    fun delete(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).deleteNotificationChannel(channelId)
    }

    // --- keeping them in step with the friend list --------------------------

    /**
     * Make the per-chat channels match the friend list: one for each accepted
     * friend, named after them, and none for anybody else. Called whenever the
     * list changes, so adding, renaming and removing a friend each do the right
     * thing to their channel with no separate bookkeeping.
     */
    fun sync(context: Context, friends: List<Friend>, mutedIds: Set<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = manager(context)
        val accepted = friends.filter { it.isAccepted }.associateBy { it.id }

        for (channel in manager.notificationChannels) {
            val peer = peerOf(channel.id) ?: continue
            if (peer !in accepted) manager.deleteNotificationChannel(channel.id)
        }

        for (friend in accepted.values) {
            val muted = friend.id in mutedIds
            val id = existingConversationId(manager, friend.id)
            val current = id?.let { manager.getNotificationChannel(it) }

            when {
                current == null || id != conversationId(friend.id, muted) ->
                    ensureConversation(context, friend.id, friend.name, muted)

                current.name?.toString() != friend.name -> rename(context, id, friend.name)
            }
        }
    }

    /** The channel a message from [peerId] should be posted on. */
    fun forMessage(context: Context, peerId: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return MESSAGES
        return existingConversationId(manager(context), peerId) ?: MESSAGES
    }

    fun list(context: Context): List<Info> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        return manager(context).notificationChannels
            .sortedWith(compareBy({ peerOf(it.id) != null }, { it.name?.toString()?.lowercase() }))
            .map { channel ->
                Info(
                    id = channel.id,
                    name = channel.name?.toString() ?: channel.id,
                    description = channel.description,
                    importance = channel.importance,
                    group = channel.group,
                    peerId = peerOf(channel.id),
                    muted = channel.id.endsWith(MUTED_SUFFIX),
                )
            }
    }

    fun peerOf(channelId: String): String? =
        if (channelId.startsWith(CONVERSATION_PREFIX)) {
            channelId.removePrefix(CONVERSATION_PREFIX).removeSuffix(MUTED_SUFFIX)
        } else {
            null
        }

    // --- internals ----------------------------------------------------------

    private fun conversationId(peerId: String, muted: Boolean) =
        CONVERSATION_PREFIX + peerId + if (muted) MUTED_SUFFIX else ""

    @RequiresApi(Build.VERSION_CODES.O)
    private fun existingConversationId(manager: NotificationManager, peerId: String): String? =
        listOf(conversationId(peerId, false), conversationId(peerId, true))
            .firstOrNull { manager.getNotificationChannel(it) != null }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun conversationChannel(context: Context, peerId: String, name: String, muted: Boolean) =
        channel(
            id = conversationId(peerId, muted),
            name = name,
            importance = if (muted) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH,
            description = context.getString(R.string.channel_conversation_desc, name),
            group = GROUP_CHATS,
        ).apply {
            // Android 11 treats a channel tied to a conversation shortcut as
            // that conversation's own settings page, and lists it among
            // "Conversations" rather than as an anonymous category.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setConversationId(MESSAGES, peerId)
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun channel(id: String, name: String, importance: Int, description: String, group: String) =
        NotificationChannel(id, name, importance).apply {
            this.description = description
            this.group = group
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
