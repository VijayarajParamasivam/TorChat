package com.torchat.core.model

import com.torchat.core.Constants
import com.torchat.core.crypto.Crypto

/**
 * The delivery contract (§8.1). This is the most important behaviour in the
 * system and the one that costs a bug to get right.
 *
 * Nothing but an `ack` may set [DELIVERED]. A message written into a circuit
 * that dies a moment later is still [SENT], and [SENT] is still owed a retry.
 * That distinction is the whole reason messages do not vanish silently.
 */
enum class MessageState(val wire: String) {
    /** Composed locally, nothing on the wire yet. */
    QUEUED("queued"),

    /** The socket accepted the bytes — **which is not delivery**. */
    SENT("sent"),

    /** The peer sent an `ack`. Only then. */
    DELIVERED("delivered"),

    /** It came from them. */
    RECEIVED("received");

    companion object {
        fun fromWire(value: String?): MessageState =
            entries.firstOrNull { it.wire == value } ?: QUEUED
    }
}

data class Message(
    val id: String,
    val ts: Long,
    val body: String,
    /** Did we compose it? Every lookup is scoped by this — see §8.6. */
    val mine: Boolean,
    val state: MessageState,
) {

    /** Is this one of ours that the peer has not confirmed? */
    val awaitingAck: Boolean get() = mine && state != MessageState.DELIVERED

    companion object {

        fun clampBody(value: String?): String = (value ?: "").take(Constants.MAX_BODY)

        /**
         * A peer's message ID, made safe to use as a key.
         *
         * Derived separately from the message itself because the receiving path
         * needs it before deciding whether this is a duplicate worth
         * re-acknowledging (§8.4).
         */
        fun idFrom(value: String?): String =
            if (value.isNullOrEmpty()) Crypto.messageId() else value.take(Constants.MAX_ID)

        /** Something we are sending. */
        fun compose(body: String?, now: Long = System.currentTimeMillis()) = Message(
            id = Crypto.messageId(),
            ts = now,
            body = clampBody(body),
            mine = true,
            state = MessageState.QUEUED,
        )

        /** Something that arrived, with its ID already derived by the caller. */
        fun received(
            id: String,
            ts: Long?,
            body: String?,
            now: Long = System.currentTimeMillis(),
        ) = Message(
            id = id,
            ts = if (ts == null || ts <= 0L) now else ts,
            body = clampBody(body),
            mine = false,
            state = MessageState.RECEIVED,
        )
    }
}
