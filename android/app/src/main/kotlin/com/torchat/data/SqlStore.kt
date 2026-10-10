package com.torchat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.torchat.core.Constants
import com.torchat.core.json.CanonicalJson
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Friend
import com.torchat.core.model.FriendState
import com.torchat.core.model.Message
import com.torchat.core.model.MessageState
import com.torchat.core.port.Store
import com.torchat.data.TorChatContract.Friends as F
import com.torchat.data.TorChatContract.Messages as M
import com.torchat.data.TorChatDatabase.Companion.TABLE_FRIENDS
import com.torchat.data.TorChatDatabase.Companion.TABLE_MESSAGES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The engine's [Store], on SQLite.
 *
 * Every write lands immediately, in a transaction, and is followed by a change
 * notification on the provider URIs it affects — that is how the chat list, an
 * open chat and the home-screen widget find out, with no event of their own to
 * miss. There is nothing to debounce and nothing to flush: a crash loses at
 * most the write that was in progress, and SQLite rolls that back.
 *
 * The `mine` scoping of §8.6 is in every query that looks a message up.
 */
class SqlStore(
    private val database: TorChatDatabase,
    private val notify: (Uri) -> Unit,
) : Store {

    constructor(context: Context) : this(
        TorChatDatabase.get(context),
        { uri -> context.applicationContext.contentResolver.notifyChange(uri, null) },
    )

    private val db get() = database.writableDatabase

    // --- friends ------------------------------------------------------------

    override fun readFriends(): List<Friend> =
        db.query(TABLE_FRIENDS, null, null, null, null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toFriend()) }
        }

    /**
     * Make the table match [friends] exactly.
     *
     * Update-then-insert rather than an UPSERT, which SQLite only learned in
     * 3.24 — newer than the one on the oldest phones this runs on. Columns the
     * app owns ([F.LAST_SEEN], [F.MUTED]) are left alone; the engine knows
     * nothing about them.
     */
    override fun writeFriends(friends: List<Friend>) {
        val db = db
        db.beginTransaction()
        try {
            val keep = friends.map { it.id }.toSet()
            val existing = db.query(TABLE_FRIENDS, arrayOf(F.ID), null, null, null, null, null)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

            for (id in existing) {
                if (id !in keep) db.delete(TABLE_FRIENDS, "${F.ID} = ?", arrayOf(id))
            }

            for (friend in friends) {
                val values = friend.toValues()
                val updated = db.update(TABLE_FRIENDS, values, "${F.ID} = ?", arrayOf(friend.id))
                if (updated == 0) db.insertOrThrow(TABLE_FRIENDS, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        notify(F.CONTENT_URI)
        notify(TorChatContract.Conversations.CONTENT_URI)
        notify(TorChatContract.Stats.CONTENT_URI)
    }

    // --- conversations ------------------------------------------------------

    override fun appendMessage(peerId: String, message: Message) {
        val db = db
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                TABLE_MESSAGES,
                null,
                ContentValues().apply {
                    put(M.PEER_ID, peerId)
                    put(M.MESSAGE_ID, message.id)
                    put(M.MINE, if (message.mine) 1 else 0)
                    put(M.TS, message.ts)
                    put(M.BODY, message.body)
                    put(M.STATE, message.state.wire)
                    put(M.UNREAD, if (message.mine) 0 else 1)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )

            // The same ceiling the reference client keeps (§11): the oldest go.
            db.execSQL(
                """
                DELETE FROM $TABLE_MESSAGES WHERE ${M.PEER_ID} = ? AND ${M.ROW_ID} NOT IN (
                    SELECT ${M.ROW_ID} FROM $TABLE_MESSAGES WHERE ${M.PEER_ID} = ?
                    ORDER BY ${M.ROW_ID} DESC LIMIT ${Constants.HISTORY_CAP}
                )
                """.trimIndent(),
                arrayOf(peerId, peerId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        messagesChanged(peerId)
    }

    override fun updateMessageState(
        peerId: String,
        id: String,
        mine: Boolean,
        state: MessageState,
    ): Message? {
        val where = "${M.PEER_ID} = ? AND ${M.MESSAGE_ID} = ? AND ${M.MINE} = ?"
        val args = arrayOf(peerId, id, if (mine) "1" else "0")

        val changed = db.update(TABLE_MESSAGES, ContentValues().apply { put(M.STATE, state.wire) }, where, args)
        if (changed == 0) return null

        messagesChanged(peerId)
        return db.query(TABLE_MESSAGES, null, where, args, null, null, null, "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.toMessage() else null
        }
    }

    override fun hasMessage(peerId: String, id: String, mine: Boolean): Boolean =
        db.query(
            TABLE_MESSAGES,
            arrayOf(M.ROW_ID),
            "${M.PEER_ID} = ? AND ${M.MESSAGE_ID} = ? AND ${M.MINE} = ?",
            arrayOf(peerId, id, if (mine) "1" else "0"),
            null, null, null, "1",
        ).use { it.moveToFirst() }

    override fun undelivered(peerId: String): List<Message> =
        db.query(
            TABLE_MESSAGES,
            null,
            "${M.PEER_ID} = ? AND ${M.MINE} = 1 AND ${M.STATE} != ?",
            arrayOf(peerId, MessageState.DELIVERED.wire),
            null, null, "${M.ROW_ID} ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }

    override fun recentMessages(peerId: String, limit: Int): List<Message> =
        db.query(
            TABLE_MESSAGES,
            null,
            "${M.PEER_ID} = ?",
            arrayOf(peerId),
            null, null, "${M.ROW_ID} DESC", limit.toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }
            .asReversed()

    /** Nothing is held back: every write already went straight to SQLite. */
    override fun flush() = Unit

    // --- app bookkeeping (not part of the protocol) -------------------------

    /** The person looked at this conversation. Clears badges and notifications. */
    fun markRead(peerId: String): Int {
        val changed = db.update(
            TABLE_MESSAGES,
            ContentValues().apply { put(M.UNREAD, 0) },
            "${M.PEER_ID} = ? AND ${M.UNREAD} = 1",
            arrayOf(peerId),
        )
        if (changed > 0) messagesChanged(peerId)
        return changed
    }

    /** Received messages not yet seen, oldest first. What a notification shows. */
    fun unread(peerId: String, limit: Int = 8): List<Message> =
        db.query(
            TABLE_MESSAGES,
            null,
            "${M.PEER_ID} = ? AND ${M.UNREAD} = 1",
            arrayOf(peerId),
            null, null, "${M.ROW_ID} DESC", limit.toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }
            .asReversed()

    fun unreadCount(peerId: String? = null): Int {
        val (where, args) = if (peerId == null) {
            "${M.UNREAD} = 1" to emptyArray()
        } else {
            "${M.PEER_ID} = ? AND ${M.UNREAD} = 1" to arrayOf(peerId)
        }
        return db.rawQuery("SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE $where", args)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Which peers have something unread right now. */
    fun peersWithUnread(): List<String> =
        db.rawQuery(
            "SELECT DISTINCT ${M.PEER_ID} FROM $TABLE_MESSAGES WHERE ${M.UNREAD} = 1",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun clearConversation(peerId: String) {
        db.delete(TABLE_MESSAGES, "${M.PEER_ID} = ?", arrayOf(peerId))
        messagesChanged(peerId)
    }

    fun setLastSeen(peerId: String, at: Long) {
        db.update(
            TABLE_FRIENDS,
            ContentValues().apply { put(F.LAST_SEEN, at) },
            "${F.ID} = ?",
            arrayOf(peerId),
        )
        notify(F.CONTENT_URI)
        notify(TorChatContract.Conversations.CONTENT_URI)
    }

    fun setMuted(peerId: String, muted: Boolean) {
        db.update(
            TABLE_FRIENDS,
            ContentValues().apply { put(F.MUTED, if (muted) 1 else 0) },
            "${F.ID} = ?",
            arrayOf(peerId),
        )
        notify(F.CONTENT_URI)
        notify(TorChatContract.Conversations.CONTENT_URI)
    }

    fun isMuted(peerId: String): Boolean =
        db.query(TABLE_FRIENDS, arrayOf(F.MUTED), "${F.ID} = ?", arrayOf(peerId), null, null, null)
            .use { it.moveToFirst() && it.getInt(0) == 1 }

    private fun messagesChanged(peerId: String) {
        notify(M.uri(peerId))
        notify(TorChatContract.Conversations.CONTENT_URI)
        notify(TorChatContract.Stats.CONTENT_URI)
    }

    companion object {

        fun Friend.toValues() = ContentValues().apply {
            put(F.ID, id)
            put(F.NAME, name)
            put(F.SIGIL, sigil)
            put(F.SIGN, sign)
            put(F.BOX, box)
            put(F.ENDPOINTS, endpointsToJson(endpoints))
            put(F.CARD_TS, cardTs)
            put(F.ADDED_AT, addedAt)
            put(F.STATE, state.wire)
        }

        fun Cursor.toFriend() = Friend(
            id = string(F.ID)!!,
            name = string(F.NAME) ?: "anon",
            sigil = string(F.SIGIL) ?: "*",
            sign = string(F.SIGN),
            box = string(F.BOX),
            endpoints = endpointsFromJson(string(F.ENDPOINTS)),
            cardTs = long(F.CARD_TS),
            addedAt = long(F.ADDED_AT),
            state = FriendState.fromWire(string(F.STATE)),
        )

        fun Cursor.toMessage() = Message(
            id = string(M.MESSAGE_ID)!!,
            ts = long(M.TS),
            body = string(M.BODY) ?: "",
            mine = long(M.MINE) == 1L,
            state = MessageState.fromWire(string(M.STATE)),
        )

        fun endpointsToJson(endpoints: List<Endpoint>): String = buildJsonArray {
            for (endpoint in endpoints) {
                add(buildJsonObject {
                    put("type", endpoint.type)
                    put("host", endpoint.host)
                    put("port", endpoint.port)
                })
            }
        }.toString()

        /**
         * Read back through the same choke point as every other address path
         * (§9.1): a database edited by hand to hold an IP still cannot get one
         * dialled. The engine merges on load as well; this is belt and braces.
         */
        fun endpointsFromJson(text: String?): List<Endpoint> = runCatching {
            val array = CanonicalJson.parser.parseToJsonElement(text ?: "[]") as JsonArray
            Endpoint.mergeOnions(
                array.mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    val host = (entry["host"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    Endpoint(
                        type = (entry["type"] as? JsonPrimitive)?.content ?: "",
                        host = host,
                        port = (entry["port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                    )
                }
            )
        }.getOrElse { emptyList() }

        fun Cursor.string(column: String): String? {
            val index = getColumnIndex(column)
            return if (index < 0 || isNull(index)) null else getString(index)
        }

        fun Cursor.long(column: String): Long {
            val index = getColumnIndex(column)
            return if (index < 0 || isNull(index)) 0L else getLong(index)
        }

        fun Cursor.int(column: String): Int = long(column).toInt()
    }
}
