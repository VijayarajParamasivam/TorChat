package com.torchat.ui.common

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.torchat.core.model.Endpoint
import com.torchat.core.model.FriendState
import com.torchat.core.model.Message
import com.torchat.core.model.MessageState
import com.torchat.data.SqlStore.Companion.endpointsFromJson
import com.torchat.data.SqlStore.Companion.int
import com.torchat.data.SqlStore.Companion.long
import com.torchat.data.SqlStore.Companion.string
import com.torchat.data.SqlStore.Companion.toMessage
import com.torchat.data.TorChatContract
import com.torchat.data.TorChatContract.Conversations as C
import com.torchat.data.TorChatContract.Friends as F
import com.torchat.data.TorChatContract.Stats as S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** One accepted friend with their latest message — a row in the chat list. */
data class ConversationRow(
    val peerId: String,
    val name: String,
    val sigil: String,
    val lastSeen: Long,
    val muted: Boolean,
    val addedAt: Long,
    val lastBody: String?,
    val lastTs: Long,
    val lastMine: Boolean,
    val lastState: MessageState?,
    val unread: Int,
    val online: Boolean = false,
)

/** Anyone in the friend book, whatever their state. */
data class PeerRow(
    val id: String,
    val name: String,
    val sigil: String,
    val state: FriendState,
    val endpoints: List<Endpoint>,
    val addedAt: Long,
    val lastSeen: Long,
    val muted: Boolean,
    val online: Boolean = false,
)

data class Stats(val unread: Int = 0, val outbox: Int = 0, val friends: Int = 0)

/**
 * Screens' view of the data: queries against [com.torchat.provider.TorChatProvider],
 * re-run whenever the provider says the URI changed.
 *
 * Nothing here caches or mirrors anything. The provider is the one source of
 * truth and its change notifications are the only signal, so a screen cannot
 * drift out of step with what is actually stored.
 */
object Repository {

    fun conversations(context: Context): Flow<List<ConversationRow>> =
        context.observe(C.CONTENT_URI) { resolver ->
            resolver.query(C.CONTENT_URI, null, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ConversationRow(
                                peerId = cursor.string(C.PEER_ID)!!,
                                name = cursor.string(C.NAME) ?: "anon",
                                sigil = cursor.string(C.SIGIL) ?: "*",
                                lastSeen = cursor.long(C.LAST_SEEN),
                                muted = cursor.int(C.MUTED) == 1,
                                addedAt = cursor.long(C.ADDED_AT),
                                lastBody = cursor.string(C.LAST_BODY),
                                lastTs = cursor.long(C.LAST_TS),
                                lastMine = cursor.int(C.LAST_MINE) == 1,
                                lastState = cursor.string(C.LAST_STATE)?.let(MessageState::fromWire),
                                unread = cursor.int(C.UNREAD),
                            )
                        )
                    }
                }
            }.orEmpty()
        }

    fun peers(context: Context): Flow<List<PeerRow>> =
        context.observe(F.CONTENT_URI) { resolver ->
            resolver.query(F.CONTENT_URI, null, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            PeerRow(
                                id = cursor.string(F.ID)!!,
                                name = cursor.string(F.NAME) ?: "anon",
                                sigil = cursor.string(F.SIGIL) ?: "*",
                                state = FriendState.fromWire(cursor.string(F.STATE)),
                                endpoints = endpointsFromJson(cursor.string(F.ENDPOINTS)),
                                addedAt = cursor.long(F.ADDED_AT),
                                lastSeen = cursor.long(F.LAST_SEEN),
                                muted = cursor.int(F.MUTED) == 1,
                            )
                        )
                    }
                }
            }.orEmpty()
        }

    fun messages(context: Context, peerId: String): Flow<List<Message>> {
        val uri = TorChatContract.Messages.uri(peerId)
        return context.observe(uri) { resolver ->
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.toMessage()) }
            }.orEmpty()
        }
    }

    fun stats(context: Context): Flow<Stats> =
        context.observe(S.CONTENT_URI) { resolver -> readStats(resolver) }

    fun readStats(resolver: ContentResolver): Stats =
        resolver.query(S.CONTENT_URI, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                Stats()
            } else {
                Stats(cursor.int(S.UNREAD), cursor.int(S.OUTBOX), cursor.int(S.FRIENDS))
            }
        } ?: Stats()

    /** Mark a conversation read through the provider, which notifies everyone watching. */
    fun markRead(context: Context, peerId: String) {
        context.contentResolver.update(
            TorChatContract.Messages.uri(peerId),
            android.content.ContentValues().apply { put(TorChatContract.Messages.UNREAD, 0) },
            null, null,
        )
    }

    fun clearConversation(context: Context, peerId: String) {
        context.contentResolver.delete(TorChatContract.Messages.uri(peerId), null, null)
    }

    /**
     * Re-run [read] now and every time [uri] changes, off the main thread.
     * Conflated: a burst of writes costs one query, not one per write.
     */
    private fun <T> Context.observe(uri: Uri, read: (ContentResolver) -> T): Flow<T> {
        val resolver = applicationContext.contentResolver
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            resolver.registerContentObserver(uri, true, observer)
            trySend(Unit)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }
            .conflate()
            .map { read(resolver) }
            .flowOn(Dispatchers.IO)
    }
}
