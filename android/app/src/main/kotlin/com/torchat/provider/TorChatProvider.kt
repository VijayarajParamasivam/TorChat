package com.torchat.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.torchat.data.TorChatContract
import com.torchat.data.TorChatContract.Conversations as C
import com.torchat.data.TorChatContract.Friends as F
import com.torchat.data.TorChatContract.Messages as M
import com.torchat.data.TorChatContract.Stats as S
import com.torchat.data.TorChatDatabase
import com.torchat.data.TorChatDatabase.Companion.TABLE_FRIENDS
import com.torchat.data.TorChatDatabase.Companion.TABLE_MESSAGES

/**
 * Conversations and friends, as a content provider.
 *
 * Every screen reads through here — the chat list, an open chat, the home-screen
 * widget — and registers for change notifications on the same URIs. The engine
 * writes through [com.torchat.data.SqlStore], which notifies these URIs after
 * every write, so a message arriving while a chat is open shows up without the
 * screen having to catch an event at the right moment.
 *
 * **Not exported.** Messages are private, and nothing outside this app has any
 * business reading them; the manifest sets `exported="false"`. The widget runs
 * in this app's process and needs no permission.
 *
 * Writes are deliberately narrow. Sending a message is the engine's job — it
 * owns the delivery contract — so `insert` is refused rather than letting a
 * row appear that nothing will ever deliver. What *can* be changed here is
 * local bookkeeping: marking a conversation read and clearing one.
 */
class TorChatProvider : ContentProvider() {

    private lateinit var database: TorChatDatabase

    override fun onCreate(): Boolean {
        database = TorChatDatabase.get(context!!)
        return true
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        FRIENDS -> F.CONTENT_TYPE
        FRIEND -> F.CONTENT_ITEM_TYPE
        MESSAGES -> M.CONTENT_TYPE
        CONVERSATIONS -> C.CONTENT_TYPE
        STATS -> S.CONTENT_TYPE
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val db = database.readableDatabase
        val cursor = when (MATCHER.match(uri)) {
            FRIENDS -> db.query(
                TABLE_FRIENDS, projection, selection, selectionArgs, null, null,
                sortOrder ?: "${F.NAME} COLLATE NOCASE ASC",
            )

            FRIEND -> db.query(
                TABLE_FRIENDS, projection, "${F.ID} = ?", arrayOf(uri.lastPathSegment), null, null, null,
            )

            MESSAGES -> {
                val peer = uri.lastPathSegment
                val where = buildString {
                    append("${M.PEER_ID} = ?")
                    if (!selection.isNullOrBlank()) append(" AND ($selection)")
                }
                db.query(
                    TABLE_MESSAGES, projection, where, arrayOf(peer) + (selectionArgs ?: emptyArray()),
                    null, null, sortOrder ?: "${M.ROW_ID} ASC",
                    uri.getQueryParameter(PARAM_LIMIT),
                )
            }

            CONVERSATIONS -> db.rawQuery(CONVERSATIONS_SQL, null)

            STATS -> db.rawQuery(STATS_SQL, null)

            else -> throw IllegalArgumentException("unknown uri $uri")
        }

        // Whoever holds the cursor hears about the next write to this URI.
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException(
            "messages are sent through the engine, which owns delivery — not inserted"
        )

    /**
     * Only the `unread` flag of a conversation can be changed from outside the
     * engine: `update(messages/<peer>, {unread: 0})` marks it read.
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (MATCHER.match(uri) != MESSAGES) throw IllegalArgumentException("read-only: $uri")
        val allowed = ContentValues().apply {
            values?.getAsInteger(M.UNREAD)?.let { put(M.UNREAD, it) }
        }
        if (allowed.size() == 0) return 0

        val changed = database.writableDatabase.update(
            TABLE_MESSAGES, allowed, "${M.PEER_ID} = ? AND ${M.UNREAD} != ?",
            arrayOf(uri.lastPathSegment, allowed.getAsInteger(M.UNREAD).toString()),
        )
        if (changed > 0) notifyConversation(uri)
        return changed
    }

    /** `delete(messages/<peer>)` clears that conversation on this phone only. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (MATCHER.match(uri) != MESSAGES) throw IllegalArgumentException("cannot delete $uri")
        val changed = database.writableDatabase.delete(
            TABLE_MESSAGES, "${M.PEER_ID} = ?", arrayOf(uri.lastPathSegment),
        )
        notifyConversation(uri)
        return changed
    }

    private fun notifyConversation(uri: Uri) {
        val resolver = context!!.contentResolver
        resolver.notifyChange(uri, null)
        resolver.notifyChange(C.CONTENT_URI, null)
        resolver.notifyChange(S.CONTENT_URI, null)
    }

    companion object {
        /** `messages/<peer>?limit=N` caps a long history. */
        const val PARAM_LIMIT = "limit"

        private const val FRIENDS = 1
        private const val FRIEND = 2
        private const val MESSAGES = 3
        private const val CONVERSATIONS = 4
        private const val STATS = 5

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(TorChatContract.AUTHORITY, F.PATH, FRIENDS)
            addURI(TorChatContract.AUTHORITY, "${F.PATH}/*", FRIEND)
            addURI(TorChatContract.AUTHORITY, "${M.PATH}/*", MESSAGES)
            addURI(TorChatContract.AUTHORITY, C.PATH, CONVERSATIONS)
            addURI(TorChatContract.AUTHORITY, S.PATH, STATS)
        }

        private fun lastOf(column: String) =
            "(SELECT m.$column FROM $TABLE_MESSAGES m WHERE m.${M.PEER_ID} = f.${F.ID} " +
                "ORDER BY m.${M.ROW_ID} DESC LIMIT 1)"

        /** Accepted friends, most recently active first. */
        private val CONVERSATIONS_SQL = """
            SELECT f.rowid AS _id,
                   f.${F.ID} AS ${C.PEER_ID},
                   f.${F.NAME} AS ${C.NAME},
                   f.${F.SIGIL} AS ${C.SIGIL},
                   f.${F.LAST_SEEN} AS ${C.LAST_SEEN},
                   f.${F.MUTED} AS ${C.MUTED},
                   f.${F.ADDED_AT} AS ${C.ADDED_AT},
                   ${lastOf(M.BODY)} AS ${C.LAST_BODY},
                   ${lastOf(M.TS)} AS ${C.LAST_TS},
                   ${lastOf(M.MINE)} AS ${C.LAST_MINE},
                   ${lastOf(M.STATE)} AS ${C.LAST_STATE},
                   (SELECT COUNT(*) FROM $TABLE_MESSAGES m
                     WHERE m.${M.PEER_ID} = f.${F.ID} AND m.${M.UNREAD} = 1) AS ${C.UNREAD}
            FROM $TABLE_FRIENDS f
            WHERE f.${F.STATE} = 'accepted'
            ORDER BY COALESCE(${C.LAST_TS}, f.${F.ADDED_AT}) DESC
        """.trimIndent()

        private val STATS_SQL = """
            SELECT
              (SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE ${M.UNREAD} = 1) AS ${S.UNREAD},
              (SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE ${M.MINE} = 1 AND ${M.STATE} != 'delivered') AS ${S.OUTBOX},
              (SELECT COUNT(*) FROM $TABLE_FRIENDS WHERE ${F.STATE} = 'accepted') AS ${S.FRIENDS}
        """.trimIndent()
    }
}
