package com.torchat.core.wire

import com.torchat.core.card.Card
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Identity
import com.torchat.core.net.LoopbackListener
import com.torchat.core.net.SocketStream
import com.torchat.core.port.Stream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire layer over a real loopback socket pair.
 *
 * No Tor anywhere — that is the point of §6's abstraction. If these pass, every
 * protocol failure later on is a Tor problem rather than a handshake one, which
 * is the whole reason the layers are separated.
 */
class LinkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val onion = "a".repeat(56) + ".onion"
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun contextFor(name: String): Pair<Identity, LinkContext> {
        val identity = Identity.create(name)
        val card = Card.create(identity, listOf(Endpoint.onion(onion)))
        return identity to LinkContext(
            identity = identity.profile,
            signPrivate = identity.sign.private,
            boxPrivate = identity.box.private,
            cardCode = card,
        )
    }

    /** A connected pair of streams, as a dialler and a responder would hold them. */
    private fun streamPair(): Pair<Stream, Stream> {
        val listener = LoopbackListener.bind()
        closeables += listener

        val accepted = AtomicReference<Stream>()
        val ready = CountDownLatch(1)
        Thread {
            accepted.set(listener.accept())
            ready.countDown()
        }.apply { isDaemon = true }.start()

        val dialled = SocketStream(Socket("127.0.0.1", listener.port))
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return dialled to accepted.get()
    }

    /**
     * Bring up both ends the way the real thing does: the dialler greets, the
     * responder stays silent until spoken to.
     */
    private fun connectedPair(
        dialExpectId: String? = null,
        // Runs before either link starts. Callbacks attached after start() race
        // the handshake — on a loaded machine the failure can land first and be
        // delivered to nobody, which is exactly what Link's docs warn against.
        beforeStart: (dialler: Link) -> Unit = {},
    ): Triple<Link, Link, CountDownLatch> {
        val (_, aliceCtx) = contextFor("alice")
        val (_, bobCtx) = contextFor("bob")
        val (dialledStream, acceptedStream) = streamPair()

        val bothReady = CountDownLatch(2)

        val responder = Link(acceptedStream, bobCtx, greetFirst = false)
        responder.onReady = { bothReady.countDown() }

        val dialler = Link(dialledStream, aliceCtx, expectId = dialExpectId, greetFirst = true)
        dialler.onReady = { bothReady.countDown() }
        beforeStart(dialler)

        responder.start(scope)
        dialler.start(scope)

        return Triple(dialler, responder, bothReady)
    }

    // --- the handshake (§7.2) -----------------------------------------------

    @Test
    fun `two links complete a mutual handshake`() {
        val (dialler, responder, bothReady) = connectedPair()

        assertTrue("both sides should reach ready", bothReady.await(10, TimeUnit.SECONDS))
        assertTrue(dialler.isReady)
        assertTrue(responder.isReady)
    }

    @Test
    fun `each side learns who the other is`() {
        val (dialler, responder, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        assertEquals("bob", dialler.peer?.name)
        assertEquals("alice", responder.peer?.name)
    }

    @Test
    fun `a link is not ready until both sides have proved possession`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        // Nobody is listening on the far end, so no proof ever comes back.
        val dialler = Link(dialled, aliceCtx, handshakeTimeoutMs = 60_000)
        dialler.start(scope)
        Thread.sleep(300)

        assertFalse(dialler.isReady)
        accepted.close()
    }

    // --- messages (§7.6) ----------------------------------------------------

    @Test
    fun `application frames cross the sealed channel`() {
        val (dialler, responder, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        val received = AtomicReference<JsonObject>()
        val arrived = CountDownLatch(1)
        responder.onMessage = { received.set(it); arrived.countDown() }

        assertTrue(dialler.send(buildJsonObject {
            put("t", "msg")
            put("id", "deadbeefdeadbeef")
            put("ts", 1753900000000L)
            put("body", "hello over the wire")
        }))

        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertEquals("msg", received.get()["t"]?.toString()?.trim('"'))
        assertEquals("hello over the wire", received.get()["body"]?.toString()?.trim('"'))
    }

    @Test
    fun `messages flow in both directions`() {
        val (dialler, responder, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        val backwards = CountDownLatch(1)
        dialler.onMessage = { backwards.countDown() }

        assertTrue(responder.send(buildJsonObject { put("t", "msg"); put("body", "hi") }))
        assertTrue(backwards.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `ping and pong stay inside the transport`() {
        val (dialler, responder, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        val surfaced = AtomicReference<JsonObject>()
        responder.onMessage = { surfaced.set(it) }
        dialler.onMessage = { surfaced.set(it) }

        dialler.send(buildJsonObject { put("t", "ping") })
        Thread.sleep(500)

        // The responder answers with a pong and neither is handed upwards.
        assertNull(surfaced.get())
    }

    @Test
    fun `an unknown frame type is ignored rather than fatal`() {
        val (dialler, responder, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        val seen = CountDownLatch(1)
        responder.onMessage = { seen.countDown() }

        // Forward compatibility: a future version's frame reaches the
        // application, which ignores it, and the link survives.
        dialler.send(buildJsonObject { put("t", "something-from-2027") })

        assertTrue(seen.await(5, TimeUnit.SECONDS))
        assertFalse(responder.isClosed)
    }

    @Test
    fun `sending before ready is refused`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        dialler.start(scope)

        assertFalse(dialler.send(buildJsonObject { put("t", "msg") }))
        accepted.close()
    }

    // --- rejection paths (§7.4) ---------------------------------------------

    @Test
    fun `dialling a specific peer rejects reaching a different one`() {
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        connectedPair(dialExpectId = "TOR-0000-0000-0000") { dialler ->
            dialler.onFailed = { reason.set(it); failed.countDown() }
        }

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertTrue(reason.get(), reason.get().startsWith("expected TOR-0000-0000-0000 but reached"))
    }

    @Test
    fun `connecting to yourself is rejected`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        // Both ends are the same identity, which is what dialling your own onion
        // address looks like from the inside.
        val responder = Link(accepted, aliceCtx, greetFirst = false)
        val dialler = Link(dialled, aliceCtx)

        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        responder.onFailed = { reason.set(it); failed.countDown() }

        responder.start(scope)
        dialler.start(scope)

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("that is your own address", reason.get())
    }

    @Test
    fun `a frame larger than the ceiling closes the link`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        dialler.onFailed = { reason.set(it); failed.countDown() }
        dialler.start(scope)

        // A 2 MiB length prefix, over the 1 MiB ceiling. Nothing follows it,
        // proving the length alone is rejected rather than the body being read.
        accepted.output.write(byteArrayOf(0x00, 0x20, 0x00, 0x00, 1))
        accepted.output.flush()

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("peer sent a malformed frame", reason.get())
        assertTrue(dialler.isClosed)
    }

    @Test
    fun `a zero-length frame closes the link`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val failed = CountDownLatch(1)
        dialler.onFailed = { failed.countDown() }
        dialler.start(scope)

        accepted.output.write(byteArrayOf(0, 0, 0, 0))
        accepted.output.flush()

        assertTrue(failed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `a sealed frame before the handshake closes the link`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        dialler.onFailed = { reason.set(it); failed.countDown() }
        dialler.start(scope)

        // Tag 1 with a plausible body, before any key could exist.
        val body = ByteArray(40)
        accepted.output.write(byteArrayOf(0, 0, 0, (body.size + 1).toByte(), 1))
        accepted.output.write(body)
        accepted.output.flush()

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("sealed frame before handshake", reason.get())
    }

    @Test
    fun `an unknown frame tag closes the link`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        dialler.onFailed = { reason.set(it); failed.countDown() }
        dialler.start(scope)

        accepted.output.write(byteArrayOf(0, 0, 0, 1, 7))
        accepted.output.flush()

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("unknown frame tag 7", reason.get())
    }

    @Test
    fun `a proof arriving before hello is rejected`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        dialler.onFailed = { reason.set(it); failed.countDown() }
        dialler.start(scope)

        writePlain(accepted, """{"t":"proof","sig":"abc"}""")

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("proof arrived before hello", reason.get())
    }

    @Test
    fun `a hello with a short nonce is rejected`() {
        val (_, aliceCtx) = contextFor("alice")
        val (bobIdentity, _) = contextFor("bob")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        dialler.onFailed = { reason.set(it); failed.countDown() }
        dialler.start(scope)

        val bobCard = Card.create(bobIdentity, listOf(Endpoint.onion(onion)))
        writePlain(accepted, """{"t":"hello","card":"$bobCard","nonce":"tooshort"}""")

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("peer sent no usable challenge", reason.get())
    }

    @Test
    fun `a hello with no nonce at all is rejected`() {
        val (_, aliceCtx) = contextFor("alice")
        val (bobIdentity, _) = contextFor("bob")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val failed = CountDownLatch(1)
        dialler.onFailed = { failed.countDown() }
        dialler.start(scope)

        val bobCard = Card.create(bobIdentity, listOf(Endpoint.onion(onion)))
        writePlain(accepted, """{"t":"hello","card":"$bobCard"}""")

        assertTrue(failed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `a second hello on the same link is rejected`() {
        val (aliceIdentity, _) = contextFor("alice")
        val (_, bobCtx) = contextFor("bob")
        val (dialled, accepted) = streamPair()

        // The responder is a real Link; the dialler side is driven by hand so a
        // second hello can be written, which a conformant Link would refuse to
        // send.
        val responder = Link(accepted, bobCtx, greetFirst = false)
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        responder.onFailed = { reason.set(it); failed.countDown() }
        responder.start(scope)

        val card = Card.create(aliceIdentity, listOf(Endpoint.onion(onion)))
        val hello = """{"t":"hello","card":"$card","nonce":"${"n".repeat(32)}"}"""
        writePlain(dialled, hello)
        writePlain(dialled, hello)

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("peer said hello twice", reason.get())
    }

    @Test
    fun `a hello carrying an unparseable card is rejected with the card's own reason`() {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        val dialler = Link(dialled, aliceCtx)
        val reason = AtomicReference<String>()
        val failed = CountDownLatch(1)
        dialler.onFailed = { reason.set(it); failed.countDown() }
        dialler.start(scope)

        writePlain(accepted, """{"t":"hello","card":"nonsense","nonce":"${"n".repeat(32)}"}""")

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("that does not look like a TorChat code", reason.get())
    }

    // --- teardown -----------------------------------------------------------

    @Test
    fun `closing one end closes the other`() {
        val (dialler, responder, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        val closed = CountDownLatch(1)
        responder.onClose = { closed.countDown() }

        dialler.close("test")

        assertTrue(closed.await(10, TimeUnit.SECONDS))
        assertFalse(responder.isReady)
    }

    @Test
    fun `a close handler attached after the link died still hears about it`() {
        val (dialler, _, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        dialler.close("died first")

        // The adopter checks isClosed and then registers, on a different
        // dispatcher from the read loop. If the link dies in that gap and the
        // event is lost, the engine keeps a dead link in its live-links map
        // forever and only a restart recovers (§10.2).
        val heard = AtomicReference<String>()
        dialler.onClose = { heard.set(it ?: "closed") }

        assertEquals("died first", heard.get())
    }

    @Test
    fun `close is idempotent and reports once`() {
        val (dialler, _, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        var closes = 0
        dialler.onClose = { closes++ }

        dialler.close("first")
        dialler.close("second")

        assertEquals(1, closes)
    }

    @Test
    fun `a closed link refuses to send`() {
        val (dialler, _, bothReady) = connectedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        dialler.close()

        assertFalse(dialler.send(buildJsonObject { put("t", "msg") }))
    }

    // --- handshakeOverStream ------------------------------------------------

    @Test
    fun `handshakeOverStream resolves with a ready link`() = runBlocking {
        val (_, aliceCtx) = contextFor("alice")
        val (_, bobCtx) = contextFor("bob")
        val (dialled, accepted) = streamPair()

        val responder = Link(accepted, bobCtx, greetFirst = false)
        responder.start(scope)

        val link = handshakeOverStream(dialled, aliceCtx, scope, timeoutMs = 15_000)

        assertTrue(link.isReady)
        assertEquals("bob", link.peer?.name)
        assertNotNull(responder.peer)
    }

    @Test
    fun `handshakeOverStream times out rather than hanging`() = runBlocking {
        val (_, aliceCtx) = contextFor("alice")
        val (dialled, accepted) = streamPair()

        // Nothing ever answers, which is what a peer whose app is closed but
        // whose onion still resolves looks like.
        val failure = runCatching {
            handshakeOverStream(dialled, aliceCtx, scope, timeoutMs = 500)
        }.exceptionOrNull()

        assertTrue(failure is LinkException)
        assertEquals(DialFailure.TIMEOUT, (failure as LinkException).failure)
        accepted.close()
    }

    // --- probing a link that may have died ----------------------------------

    /**
     * A stream whose writes can be silently swallowed — what a circuit that died
     * without a FIN looks like from the other end: the socket is open, and
     * nothing ever comes out of it again.
     */
    private class MutableStream(private val inner: Stream) : Stream {
        @Volatile
        var muted = false

        override val input = inner.input
        override val output = object : java.io.OutputStream() {
            override fun write(b: Int) {
                if (!muted) inner.output.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (!muted) inner.output.write(b, off, len)
            }

            override fun flush() {
                if (!muted) inner.output.flush()
            }
        }

        override fun close() = inner.close()
    }

    private fun probedPair(): Triple<Link, MutableStream, CountDownLatch> {
        val (_, aliceCtx) = contextFor("alice")
        val (_, bobCtx) = contextFor("bob")
        val (dialledStream, acceptedStream) = streamPair()
        val bobSide = MutableStream(acceptedStream)

        val bothReady = CountDownLatch(2)
        val responder = Link(bobSide, bobCtx, greetFirst = false)
        responder.onReady = { bothReady.countDown() }
        val dialler = Link(dialledStream, aliceCtx, greetFirst = true)
        dialler.onReady = { bothReady.countDown() }

        responder.start(scope)
        dialler.start(scope)
        return Triple(dialler, bobSide, bothReady)
    }

    @Test
    fun `a probe answered by the peer keeps the link`() {
        val (dialler, _, bothReady) = probedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        dialler.probe(timeoutMs = 1_000)
        Thread.sleep(1_500)

        // A false positive here would tear down every healthy link each time
        // the phone changed networks.
        assertTrue(dialler.isReady)
        assertFalse(dialler.isClosed)
    }

    @Test
    fun `a probe the peer never answers fails the link`() {
        val (dialler, bobSide, bothReady) = probedPair()
        assertTrue(bothReady.await(10, TimeUnit.SECONDS))

        val reason = AtomicReference<String?>()
        val closed = CountDownLatch(1)
        dialler.onClose = { why ->
            reason.set(why)
            closed.countDown()
        }

        // Bob's end goes quiet without hanging up — the half-open husk a
        // network change leaves behind.
        bobSide.muted = true
        dialler.probe(timeoutMs = 500)

        assertTrue("the probe should fail the link", closed.await(5, TimeUnit.SECONDS))
        assertEquals("peer went silent", reason.get())
    }

    private fun writePlain(stream: Stream, json: String) {
        val body = json.toByteArray()
        val length = body.size + 1
        stream.output.write(
            byteArrayOf(
                (length ushr 24).toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte(),
                0,
            )
        )
        stream.output.write(body)
        stream.output.flush()
    }
}
