package com.torchat.core.wire

import com.torchat.core.Constants
import com.torchat.core.card.Card
import com.torchat.core.card.PeerCard
import com.torchat.core.crypto.Crypto
import com.torchat.core.json.CanonicalJson
import com.torchat.core.model.Profile
import com.torchat.core.port.Stream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Everything a link needs to know about us in order to introduce us. */
data class LinkContext(
    val identity: Profile,
    val signPrivate: String,
    val boxPrivate: String,
    /** Rebuilt whenever the profile or address changes, so it is passed per link. */
    val cardCode: String,
)

/**
 * The wire: a mutual-proof handshake and an encrypted channel (§7).
 *
 * Frame layout is `[4-byte big-endian length][1-byte tag][body]`, where the tag
 * says whether the body is plaintext handshake JSON (0) or an AES-GCM sealed
 * payload (1). Tagging each frame rather than tracking a plaintext/encrypted
 * mode avoids a race where one side starts encrypting before the other has
 * finished its handshake.
 *
 * The handshake proves **possession**, not knowledge: each side sends a random
 * nonce and must return a signature over the other's. Copying somebody's public
 * contact card therefore gets you nowhere — you would need their private key.
 *
 * Nothing here knows that the bytes are travelling through Tor. That is the
 * point: the encryption does not depend on the transport keeping any promises,
 * and does not trust it to.
 *
 * Callbacks are plain properties rather than a Flow because they must all be in
 * place before [start] runs. Nothing is buffered and nothing replays; a `ready`
 * delivered to nobody would strand the link.
 */
class Link(
    private val stream: Stream,
    private val ctx: LinkContext,
    private val expectId: String? = null,
    private val greetFirst: Boolean = true,
    private val handshakeTimeoutMs: Long = Constants.HANDSHAKE_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    var onReady: ((PeerCard) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    /**
     * Fires immediately if the link has already closed.
     *
     * The adopter checks [isClosed] before registering, but it runs on a
     * different dispatcher from the read loop, so the link can die in the gap
     * between the check and the assignment. Without this, that close event is
     * lost: the peer stays in the engine's live-links map forever, the redial
     * sweep skips them for having a "live" link, and only a restart brings them
     * back (§10.2).
     */
    var onClose: ((String?) -> Unit)? = null
        set(value) {
            val fireNow = synchronized(pending) {
                field = value
                value != null && closed.get() && closeReported
            }
            if (fireNow) value?.invoke(closeReason)
        }

    /**
     * Application frames arriving before this is set are held, not dropped.
     *
     * The engine cannot attach its handler until it has adopted the link, and
     * adoption is a hop onto another dispatcher — so there is a real window,
     * right after `ready`, in which the peer's first message would otherwise
     * arrive with nowhere to go. The delivery contract would eventually retry it,
     * but only after a visible delay.
     */
    var onMessage: ((JsonObject) -> Unit)? = null
        set(value) {
            val drained = synchronized(pending) {
                field = value
                if (value == null) {
                    emptyList()
                } else {
                    pending.toList().also { pending.clear() }
                }
            }
            drained.forEach { value?.invoke(it) }
        }

    /** Guards the pending queue and the close bookkeeping alike. */
    private val pending = ArrayDeque<JsonObject>()

    private var closeReason: String? = null
    private var closeReported = false

    @Volatile
    var peer: PeerCard? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    val isClosed: Boolean get() = closed.get()

    private val closed = AtomicBoolean(false)
    private val greeted = AtomicBoolean(false)

    private val myNonce = Crypto.nonce()

    @Volatile
    private var channelKey: ByteArray? = null
    private var theyProved = false
    private var weProved = false

    @Volatile
    private var lastSeen = now()

    /** When anything at all last arrived from the peer. */
    val lastHeardAt: Long get() = lastSeen

    /**
     * Frames read so far. What [probe] watches: a clock reading cannot tell an
     * answer from a frame that landed in the same millisecond the question left,
     * and on a fast link both happen.
     */
    private val framesRead = java.util.concurrent.atomic.AtomicLong()

    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)
    private lateinit var scope: CoroutineScope

    /**
     * Begin reading and writing. Set every callback first.
     *
     * The dialler greets immediately; the responder waits — see [sendHello].
     */
    fun start(parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)

        stream.setKeepAlive(Constants.TCP_KEEPALIVE_MS)

        scope.launch { writeLoop() }
        scope.launch { readLoop() }
        scope.launch {
            // Passed down from the caller, never fixed here. A hardcoded 45 s
            // always fired before a 90 s dial budget, so a handshake that was
            // merely slow across six relays got reported as failed (§7.8).
            delay(handshakeTimeoutMs)
            if (!isReady) fail("handshake timed out")
        }

        if (greetFirst) sendHello()
    }

    // --- framing (§7.1) -----------------------------------------------------

    /**
     * Queue a frame. Returns whether it was accepted for writing, which is all
     * `sent` has ever meant — see the delivery contract in §8.1.
     */
    private fun write(tag: Int, body: ByteArray): Boolean {
        if (closed.get()) return false

        val length = body.size + 1
        val frame = ByteArray(FRAME_HEADER + body.size)
        frame[0] = (length ushr 24).toByte()
        frame[1] = (length ushr 16).toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = tag.toByte()
        body.copyInto(frame, FRAME_HEADER)

        return outbound.trySend(frame).isSuccess
    }

    private fun sendPlain(value: JsonObject): Boolean =
        write(TAG_PLAIN, value.toString().toByteArray(Charsets.UTF_8))

    /** Send an application message. Only valid once the channel is up. */
    fun send(value: JsonObject): Boolean {
        val key = channelKey
        if (!isReady || key == null) return false
        return write(TAG_SEALED, Crypto.seal(key, value.toString().toByteArray(Charsets.UTF_8)))
    }

    private suspend fun writeLoop() {
        try {
            for (frame in outbound) {
                stream.output.write(frame)
                stream.output.flush()
            }
        } catch (_: IOException) {
            if (!closed.get()) close("connection lost while writing")
        }
    }

    private suspend fun readLoop() {
        val input = DataInputStream(stream.input)

        try {
            while (scope.isActive && !closed.get()) {
                val length = input.readInt()
                lastSeen = now()

                // A length outside these bounds leaves the stream desynchronised.
                // There is no way to resynchronise except by guessing, so the
                // link closes instead.
                if (length < 1 || length > Constants.MAX_FRAME) {
                    fail("peer sent a malformed frame")
                    return
                }

                val tag = input.readUnsignedByte()
                val body = ByteArray(length - 1)
                input.readFully(body)
                lastSeen = now()
                framesRead.incrementAndGet()

                dispatch(tag, body)
                if (closed.get()) return
            }
        } catch (_: EOFException) {
            close("peer disconnected")
        } catch (_: IOException) {
            if (!closed.get()) close("connection lost")
        }
    }

    private fun dispatch(tag: Int, body: ByteArray) {
        when (tag) {
            TAG_PLAIN -> onHandshakeFrame(body)

            TAG_SEALED -> {
                // A sealed frame before the channel key exists closes the link.
                val key = channelKey ?: return fail("sealed frame before handshake")

                val opened = try {
                    Crypto.open(key, body)
                } catch (_: Exception) {
                    // Never skipped: a frame that fails to open is a protocol
                    // failure, not a dropped packet (§3.2).
                    return fail("could not open a sealed frame")
                }

                val frame = parseJson(opened) ?: return fail("malformed sealed payload")
                onApplicationFrame(frame)
            }

            else -> fail("unknown frame tag $tag")
        }
    }

    private fun parseJson(bytes: ByteArray): JsonObject? = try {
        CanonicalJson.parser.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    } catch (_: Exception) {
        null
    }

    // --- handshake (§7.2, §7.4) ---------------------------------------------

    /**
     * Send our opening frame, exactly once.
     *
     * The side that **accepted** the connection must not send this until the
     * dialler has been heard from, and that is not a style preference — it is a
     * hard property of the transport. Tor discards anything an onion service
     * writes into a rendezvous stream before the `CONNECTED` cell reaches the
     * other end, silently and with no error on the writing side. A measured
     * 120-byte marker written on accept never arrived, while the same marker
     * written four seconds later arrived in 380 ms.
     *
     * The handshake stays symmetric in content; only the responder's timing
     * differs, and only because Tor forces it.
     */
    private fun sendHello() {
        if (!greeted.compareAndSet(false, true)) return
        sendPlain(buildJsonObject {
            put("t", "hello")
            put("card", ctx.cardCode)
            put("nonce", myNonce)
        })
    }

    private fun onHandshakeFrame(body: ByteArray) {
        val frame = parseJson(body) ?: return fail("malformed handshake frame")

        when (val type = frame.text("t")) {
            "hello" -> onHello(frame)
            "proof" -> onProof(frame)
            else -> fail("unexpected handshake frame $type")
        }
    }

    private fun onHello(frame: JsonObject) {
        if (peer != null) return fail("peer said hello twice")

        // A nonce that was never validated got signed as the literal string
        // "undefined" — the same challenge for every peer that omits it, which
        // is the one input to a proof that must not be attacker-chosen or
        // constant.
        val nonce = frame.text("nonce")
        if (nonce == null || nonce.length < Constants.MIN_NONCE_LENGTH) {
            return fail("peer sent no usable challenge")
        }

        val parsed = try {
            Card.parse(frame.text("card"))
        } catch (failure: Exception) {
            return fail(failure.message ?: "peer sent an unusable card")
        }

        if (expectId != null && parsed.id != expectId) {
            return fail("expected $expectId but reached ${parsed.id}")
        }
        if (parsed.id == ctx.identity.id) return fail("that is your own address")

        val key = try {
            Crypto.deriveChannelKey(ctx.boxPrivate, parsed.box)
        } catch (_: Exception) {
            return fail("peer sent an unusable key")
        }

        peer = parsed
        channelKey = key

        // Now it is safe to speak: hearing from them proves the stream is joined
        // end to end, so this will actually be delivered. Hello before proof —
        // they cannot check a proof from someone who has not identified
        // themselves yet.
        sendHello()

        sendPlain(buildJsonObject {
            put("t", "proof")
            put("sig", Crypto.sign(ctx.signPrivate, proofPayload(nonce, ctx.identity.id, parsed.id)))
        })
        weProved = true
        maybeReady()
    }

    private fun onProof(frame: JsonObject) {
        val known = peer ?: return fail("proof arrived before hello")

        // Verified against **the nonce we sent**, their ID as signer and ours as
        // recipient. Signing the bare nonce would make the signature a
        // free-floating assertion, valid in any context that happened to present
        // the same bytes.
        val expected = proofPayload(myNonce, known.id, ctx.identity.id)
        if (!Crypto.verify(known.sign, expected, frame.text("sig"))) {
            return fail("peer could not prove it owns that ID")
        }

        theyProved = true
        maybeReady()
    }

    /** The link is ready only when **both** sides have proved possession. */
    private fun maybeReady() {
        if (isReady || !theyProved || !weProved) return

        isReady = true
        scope.launch { pingLoop() }
        onReady?.invoke(peer!!)
    }

    // --- application frames (§7.6) ------------------------------------------

    private fun onApplicationFrame(frame: JsonObject) {
        if (!isReady) return

        when (frame.text("t")) {
            // Handled inside the transport and never surfaced to the application.
            "ping" -> send(buildJsonObject { put("t", "pong") })
            "pong" -> Unit
            // Unknown types are ignored rather than errors — the
            // forward-compatibility hinge.
            else -> deliver(frame)
        }
    }

    private fun deliver(frame: JsonObject) {
        val handler = synchronized(pending) {
            onMessage.also { if (it == null) pending += frame }
        }
        handler?.invoke(frame)
    }

    /** §7.7. Any received byte refreshes last-seen, not only pongs. */
    private suspend fun pingLoop() {
        while (scope.isActive && !closed.get()) {
            delay(Constants.PING_INTERVAL_MS)
            if (closed.get()) return

            if (now() - lastSeen > Constants.PING_TIMEOUT_MS) {
                return fail("peer went silent")
            }
            send(buildJsonObject { put("t", "ping") })
        }
    }

    /**
     * Ask the peer to prove the link is alive, and fail it if nothing comes back
     * within [timeoutMs].
     *
     * The regular ping loop needs a full [Constants.PING_TIMEOUT_MS] of silence
     * before it gives up, which is right for a link nobody is worried about. It
     * is far too slow for one that probably just died: after the phone hops from
     * wifi to mobile data, or wakes from deep sleep, every circuit it had is gone
     * but nothing has said so, and a friend would sit "online" for a minute
     * while each send vanished. A live link answers a ping within one round
     * trip; a dead one never will.
     */
    fun probe(timeoutMs: Long) {
        if (!isReady || closed.get()) return

        val before = framesRead.get()
        send(buildJsonObject { put("t", "ping") })
        scope.launch {
            delay(timeoutMs)
            if (!closed.get() && framesRead.get() == before) fail("peer went silent")
        }
    }

    // --- teardown -----------------------------------------------------------

    private fun fail(reason: String) {
        if (closed.get()) return
        onFailed?.invoke(reason)
        close(reason)
    }

    fun close(reason: String? = null) {
        if (!closed.compareAndSet(false, true)) return
        isReady = false

        outbound.close()
        stream.close()

        // Recorded under the same lock the setter uses, so a listener attaching
        // concurrently either sees the flag and fires itself, or is picked up
        // here — never neither, and never both.
        val handler = synchronized(pending) {
            closeReason = reason
            closeReported = true
            onClose
        }

        // Fired before the scope is torn down, so a listener always hears it.
        handler?.invoke(reason)

        if (::scope.isInitialized) scope.cancel()
    }

    companion object {
        const val TAG_PLAIN = 0
        const val TAG_SEALED = 1

        private const val FRAME_HEADER = 5

        /**
         * What a proof signs (§7.3).
         *
         * Naming the protocol, the signer and the intended recipient makes the
         * signature answer exactly one question: *did this identity prove itself
         * to that identity, in this handshake?*
         */
        fun proofPayload(nonce: String, signerId: String, recipientId: String): String =
            "${Constants.PROOF_CONTEXT}|$nonce|$signerId|$recipientId"

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
