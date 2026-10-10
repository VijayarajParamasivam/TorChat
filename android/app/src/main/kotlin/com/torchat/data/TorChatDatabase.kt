package com.torchat.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.torchat.data.TorChatContract.Friends as F
import com.torchat.data.TorChatContract.Messages as M

/**
 * Friends and conversations on disk (§11).
 *
 * SQLite replaced one JSON file per conversation. Those files were rewritten
 * whole on a debounce from a background thread while the engine kept mutating
 * the same lists, and a write that collided with a mutation threw, was
 * swallowed, and cleared the dirty flag — so the message that arrived in that
 * instant was never saved. A database does row-level writes in transactions
 * and makes that whole class of bug impossible.
 *
 * The identity is not here and never will be: it lives behind the Keystore in
 * [SecureStore]. Conversations can live in an ordinary database; the private
 * keys cannot.
 *
 * One instance per process. SQLiteDatabase is internally synchronised, and WAL
 * lets the provider read while the engine writes.
 */
class TorChatDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ${TABLE_FRIENDS} (
                ${F.ID} TEXT PRIMARY KEY NOT NULL,
                ${F.NAME} TEXT NOT NULL,
                ${F.SIGIL} TEXT NOT NULL,
                ${F.SIGN} TEXT,
                ${F.BOX} TEXT,
                ${F.ENDPOINTS} TEXT NOT NULL DEFAULT '[]',
                ${F.CARD_TS} INTEGER NOT NULL DEFAULT 0,
                ${F.ADDED_AT} INTEGER NOT NULL DEFAULT 0,
                ${F.STATE} TEXT NOT NULL DEFAULT 'accepted',
                ${F.LAST_SEEN} INTEGER NOT NULL DEFAULT 0,
                ${F.MUTED} INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // UNIQUE on (peer, id, mine): the two directions share an ID space that
        // neither side controls both halves of (§8.6), so the direction is part
        // of the key — and a duplicate delivery cannot be filed twice even if
        // the check before the insert were ever skipped.
        db.execSQL(
            """
            CREATE TABLE ${TABLE_MESSAGES} (
                ${M.ROW_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${M.PEER_ID} TEXT NOT NULL,
                ${M.MESSAGE_ID} TEXT NOT NULL,
                ${M.MINE} INTEGER NOT NULL,
                ${M.TS} INTEGER NOT NULL,
                ${M.BODY} TEXT NOT NULL,
                ${M.STATE} TEXT NOT NULL,
                ${M.UNREAD} INTEGER NOT NULL DEFAULT 0,
                UNIQUE (${M.PEER_ID}, ${M.MESSAGE_ID}, ${M.MINE})
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX messages_by_peer ON $TABLE_MESSAGES (${M.PEER_ID}, ${M.ROW_ID})")
        db.execSQL("CREATE INDEX messages_unread ON $TABLE_MESSAGES (${M.UNREAD}) WHERE ${M.UNREAD} = 1")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Anything added later is migrated here,
        // in place — dropping tables would delete conversations nobody else has
        // a copy of.
    }

    companion object {
        const val NAME = "torchat.db"
        const val VERSION = 1

        const val TABLE_FRIENDS = "friends"
        const val TABLE_MESSAGES = "messages"

        @Volatile
        private var instance: TorChatDatabase? = null

        fun get(context: Context): TorChatDatabase =
            instance ?: synchronized(this) {
                instance ?: TorChatDatabase(context).also { instance = it }
            }
    }
}
