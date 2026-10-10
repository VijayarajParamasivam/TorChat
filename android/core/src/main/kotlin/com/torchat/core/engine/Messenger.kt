package com.torchat.core.engine

import com.torchat.core.model.Message
import com.torchat.core.model.MessageState
import com.torchat.core.port.Store
import com.torchat.core.wire.Link
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Conversations: composing, delivering, acknowledging and retrying (§8).
 *
 * The delivery contract is the whole point of this file. A message is only
 * `delivered` when the peer says so. Anything else stays in the outbox and goes
 * again on the next link, because `sent` never meant more than "the socket
 * accepted the bytes" — and a circuit that dies a moment later takes those bytes
 * with it, silently.
 *
 * Retrying is safe precisely because of the duplicate rule in [receive]: the
 * receiver recognises a message it already has and simply re-acknowledges it.
 * The only cost of a needless retry is one extra ack.
 */
class Messenger(
    private val store: Store,
    private val linkFor: (String) -> Link?,
    private val emit: (EngineEvent) -> Unit,
) {

    /** Write a message, hand it over if we can, and keep it until acknowledged. */
    fun compose(peerId: String, body: String?): Message {
        val message = Message.compose(body)
        store.appendMessage(peerId, message)

        // Left in the outbox regardless of what happens next. Only an ack
        // removes it.
        return if (deliver(peerId, message)) message.copy(state = MessageState.SENT) else message
    }

    /**
     * Put one message on the wire and mark it sent if the socket took it.
     *
     * "Sent" means the bytes were accepted for writing, nothing more.
     */
    private fun deliver(peerId: String, message: Message): Boolean {
        val link = linkFor(peerId) ?: return false
        if (!link.send(toFrame(message))) return false

        store.updateMessageState(peerId, message.id, mine = true, state = MessageState.SENT)
        return true
    }

    private fun ack(peerId: String, id: String) {
        linkFor(peerId)?.send(buildJsonObject {
            put("t", "ack")
            put("id", id)
        })
    }

    /** A message arrived from a friend. */
    fun receive(peerId: String, friendName: String, frame: JsonObject) {
        val id = Message.idFrom(frame.text("id"))

        // The sender retries anything it has no ack for, so the same message
        // legitimately arrives twice whenever an *ack* was the thing that got
        // lost. Re-acknowledge it — that is what they are waiting for — but do
        // not file it, display it, or emit an event (§8.4).
        if (store.hasMessage(peerId, id, mine = false)) {
            ack(peerId, id)
            return
        }

        val message = Message.received(id, frame.number("ts"), frame.text("body"))
        store.appendMessage(peerId, message)
        ack(peerId, message.id)
        emit(EngineEvent.MessageReceived(peerId, friendName, message))
    }

    /** A friend confirmed one of ours. */
    fun acknowledge(peerId: String, frame: JsonObject) {
        val id = frame.text("id") ?: return

        // Only ever our own outgoing messages: an ack names an ID the peer got
        // from us, so matching one of theirs would be a collision, not a receipt.
        val updated = store.updateMessageState(
            peerId,
            id,
            mine = true,
            state = MessageState.DELIVERED,
        )
        if (updated != null) emit(EngineEvent.Delivered(peerId, updated.id))
    }

    /**
     * Hand over everything the peer has not acknowledged (§8.5).
     *
     * This runs on **every** newly established link, and deliberately includes
     * messages already marked `sent` — those went into a circuit that may since
     * have died.
     */
    fun flushOutbox(peerId: String) {
        if (linkFor(peerId) == null) return

        val pending = store.undelivered(peerId)
        if (pending.isEmpty()) return

        val sent = pending.count { deliver(peerId, it) }
        if (sent == 0) return

        emit(EngineEvent.Log("resent $sent unacknowledged message(s)"))
        emit(EngineEvent.HistoryChanged(peerId))
    }

    fun history(peerId: String, limit: Int): List<Message> = store.recentMessages(peerId, limit)

    private companion object {

        /** The wire form: only what a peer needs, never our local bookkeeping. */
        fun toFrame(message: Message): JsonObject = buildJsonObject {
            put("t", "msg")
            put("id", message.id)
            put("ts", message.ts)
            put("body", message.body)
        }

        fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonObject.number(key: String): Long? =
            (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
    }
}
