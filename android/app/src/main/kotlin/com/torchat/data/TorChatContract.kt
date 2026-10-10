package com.torchat.data

import android.content.ContentResolver
import android.net.Uri

/**
 * The public shape of [com.torchat.provider.TorChatProvider]: its authority, its
 * URIs and the columns each one returns.
 *
 * Everything that reads conversations — the chat list, a chat, the home-screen
 * widget, the diagnostics screen — goes through these URIs rather than through
 * the database directly, so a write anywhere is a change notification
 * everywhere. The engine is the only writer, through [SqlStore].
 */
object TorChatContract {

    const val AUTHORITY = "com.torchat.provider"

    val BASE: Uri = Uri.parse("content://$AUTHORITY")

    object Friends {
        const val PATH = "friends"
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE = "${ContentResolver.CURSOR_DIR_BASE_TYPE}/vnd.$AUTHORITY.friend"
        const val CONTENT_ITEM_TYPE = "${ContentResolver.CURSOR_ITEM_BASE_TYPE}/vnd.$AUTHORITY.friend"

        fun uri(peerId: String): Uri = CONTENT_URI.buildUpon().appendPath(peerId).build()

        const val ID = "id"
        const val NAME = "name"
        const val SIGIL = "sigil"
        const val SIGN = "sign"
        const val BOX = "box"
        const val ENDPOINTS = "endpoints"
        const val CARD_TS = "card_ts"
        const val ADDED_AT = "added_at"
        const val STATE = "state"
        /** When we last had a live link to them. App bookkeeping, not protocol. */
        const val LAST_SEEN = "last_seen"
        /** Notifications silenced for this chat. */
        const val MUTED = "muted"
    }

    object Messages {
        const val PATH = "messages"
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE = "${ContentResolver.CURSOR_DIR_BASE_TYPE}/vnd.$AUTHORITY.message"

        fun uri(peerId: String): Uri = CONTENT_URI.buildUpon().appendPath(peerId).build()

        const val ROW_ID = "_id"
        const val PEER_ID = "peer_id"
        const val MESSAGE_ID = "msg_id"
        const val MINE = "mine"
        const val TS = "ts"
        const val BODY = "body"
        const val STATE = "state"
        /** Received and not yet seen on screen. Drives badges and notifications. */
        const val UNREAD = "unread"
    }

    /** One row per accepted friend: who, their last message, and how much is unread. */
    object Conversations {
        const val PATH = "conversations"
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE = "${ContentResolver.CURSOR_DIR_BASE_TYPE}/vnd.$AUTHORITY.conversation"

        const val PEER_ID = "peer_id"
        const val NAME = "name"
        const val SIGIL = "sigil"
        const val LAST_SEEN = "last_seen"
        const val MUTED = "muted"
        const val ADDED_AT = "added_at"
        const val LAST_BODY = "last_body"
        const val LAST_TS = "last_ts"
        const val LAST_MINE = "last_mine"
        const val LAST_STATE = "last_state"
        const val UNREAD = "unread"
    }

    /** A single row of totals: unread messages and undelivered ones. */
    object Stats {
        const val PATH = "stats"
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE = "${ContentResolver.CURSOR_ITEM_BASE_TYPE}/vnd.$AUTHORITY.stats"

        const val UNREAD = "unread"
        const val OUTBOX = "outbox"
        const val FRIENDS = "friends"
    }
}
