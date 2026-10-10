package com.torchat.core.engine

import com.torchat.core.Constants
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Message
import com.torchat.core.model.MessageState
import com.torchat.core.support.InMemoryStore
import com.torchat.core.wire.DialFailure
import com.torchat.core.wire.LinkException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivery contract, exercised directly against the store.
 *
 * The engine test proves two peers talk; this proves the rules that only show up
 * under collision and loss, which are the ones that cost a bug.
 */
class MessengerTest {

    private val peerId = "TOR-1111-1111-1111"
    private val store = InMemoryStore()
    private val events = mutableListOf<EngineEvent>()

    /** Records what would have gone on the wire, without a link. */
    private val written = mutableListOf<JsonObject>()

    private val messenger = Messenger(
        store = store,
        // No live link: composing leaves messages queued, which is the state the
        // interesting rules operate on.
        linkFor = { null },
        emit = { events += it },
    )

    private fun incoming(id: String, body: String, ts: Long = 1_000) = buildJsonObject {
        put("t", "msg")
        put("id", id)
        put("ts", ts)
        put("body", body)
    }

    // --- the mine boundary (§8.6) -------------------------------------------

    @Test
    fun `an ack never matches one of their messages`() {
        // Their message, filed under an ID they chose.
        messenger.receive(peerId, "bob", incoming("collide", "theirs"))

        // Now they ack that same ID. Without the `mine` scoping this would mark
        // their own inbound message delivered.
        messenger.acknowledge(peerId, buildJsonObject { put("id", "collide") })

        val theirs = store.recentMessages(peerId, 10).single()
        assertEquals(MessageState.RECEIVED, theirs.state)
        assertFalse(theirs.mine)
    }

    @Test
    fun `an ack for an ID we never sent changes nothing`() {
        messenger.acknowledge(peerId, buildJsonObject { put("id", "never-sent") })

        assertTrue(store.recentMessages(peerId, 10).isEmpty())
        assertTrue(events.none { it is EngineEvent.Delivered })
    }

    @Test
    fun `the duplicate check never matches one of ours`() {
        val ours = Message.compose("ours").copy(id = "collide")
        store.appendMessage(peerId, ours)

        // A peer picking the same ID must not have their message swallowed as a
        // duplicate of ours.
        messenger.receive(peerId, "bob", incoming("collide", "theirs"))

        val log = store.recentMessages(peerId, 10)
        assertEquals(2, log.size)
        assertEquals(listOf("ours", "theirs"), log.map { it.body })
    }

    // --- duplicates (§8.4) --------------------------------------------------

    @Test
    fun `a duplicate is re-acked but never filed or displayed twice`() {
        messenger.receive(peerId, "bob", incoming("abc", "hello"))
        events.clear()

        messenger.receive(peerId, "bob", incoming("abc", "hello"))

        assertEquals(1, store.recentMessages(peerId, 10).size)
        assertTrue(events.none { it is EngineEvent.MessageReceived })
    }

    @Test
    fun `a genuinely new message is filed and emitted`() {
        messenger.receive(peerId, "bob", incoming("abc", "hello"))

        val emitted = events.filterIsInstance<EngineEvent.MessageReceived>().single()
        assertEquals("hello", emitted.message.body)
        assertEquals("bob", emitted.friendName)
    }

    // --- the outbox (§8.3, §8.5) --------------------------------------------

    @Test
    fun `a message with no link stays queued and stays in the outbox`() {
        val message = messenger.compose(peerId, "hello")

        assertEquals(MessageState.QUEUED, message.state)
        assertEquals(listOf(message.id), store.undelivered(peerId).map { it.id })
    }

    @Test
    fun `the outbox includes messages already marked sent`() {
        store.appendMessage(peerId, Message.compose("a").copy(state = MessageState.SENT))
        store.appendMessage(peerId, Message.compose("b").copy(state = MessageState.QUEUED))
        store.appendMessage(peerId, Message.compose("c").copy(state = MessageState.DELIVERED))

        // `sent` only ever meant the socket accepted the bytes; a circuit that
        // died in transit took them with it, so it is still owed a retry.
        assertEquals(listOf("a", "b"), store.undelivered(peerId).map { it.body })
    }

    @Test
    fun `the outbox never includes their messages`() {
        messenger.receive(peerId, "bob", incoming("abc", "theirs"))

        assertTrue(store.undelivered(peerId).isEmpty())
    }

    @Test
    fun `an ack is the only thing that marks delivered`() {
        val message = messenger.compose(peerId, "hello")

        messenger.acknowledge(peerId, buildJsonObject { put("id", message.id) })

        assertEquals(
            MessageState.DELIVERED,
            store.recentMessages(peerId, 10).single().state,
        )
        assertTrue(store.undelivered(peerId).isEmpty())
        assertEquals(1, events.filterIsInstance<EngineEvent.Delivered>().size)
    }

    @Test
    fun `history is capped`() {
        repeat(Constants.HISTORY_CAP + 50) {
            store.appendMessage(peerId, Message.compose("m$it"))
        }

        assertEquals(Constants.HISTORY_CAP, store.recentMessages(peerId, 99_999).size)
    }
}

class RetryPolicyTest {

    private var clock = 0L
    private val retry = RetryPolicy { clock }

    @Test
    fun `backoff doubles from 15 seconds to a five minute ceiling`() {
        val delays = mutableListOf<Long>()
        repeat(8) {
            retry.penalise("peer")
            delays += retry.nextTryAt("peer") - clock
        }

        assertEquals(
            listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L),
            delays,
        )
    }

    @Test
    fun `somebody never tried is due immediately`() {
        assertTrue(retry.isDue("peer"))
    }

    @Test
    fun `a penalised peer is not due until their time comes`() {
        retry.penalise("peer")

        assertFalse(retry.isDue("peer"))
        clock += 15_000
        assertTrue(retry.isDue("peer"))
    }

    @Test
    fun `a successful connection resets the backoff`() {
        repeat(5) { retry.penalise("peer") }
        retry.reset("peer")

        assertTrue(retry.isDue("peer"))
        retry.penalise("peer")
        assertEquals(15_000L, retry.nextTryAt("peer") - clock)
    }

    @Test
    fun `a failure is reported only when the reason changes`() {
        assertTrue(retry.shouldReport("peer", "offline"))
        assertFalse(retry.shouldReport("peer", "offline"))
        assertTrue(retry.shouldReport("peer", "handshake failed"))
        assertFalse(retry.shouldReport("peer", "handshake failed"))
    }
}

class DialPolicyTest {

    private val onion = "a".repeat(56) + ".onion"
    private val mine = "b".repeat(56) + ".onion"

    @Test
    fun `dialable endpoints exclude non-onions and our own address`() {
        val ordered = DialPolicy.orderEndpoints(
            listOf(
                Endpoint("wan", "203.0.113.7", 47777),
                Endpoint.onion(mine),
                Endpoint.onion(onion),
            ),
            ownOnion = mine,
        )

        assertEquals(listOf(onion), ordered.map { it.host })
    }

    @Test
    fun `failures are explained in terms a person can act on`() {
        val endpoint = Endpoint.onion(onion)

        assertTrue(
            DialPolicy.explainDialFailure(endpoint, LinkException("x", DialFailure.NO_TOR))
                .contains("tor is not running here")
        )
        assertTrue(
            DialPolicy.explainDialFailure(endpoint, LinkException("x", DialFailure.TIMEOUT))
                .contains("they are probably offline")
        )
        assertTrue(
            DialPolicy.explainDialFailure(endpoint, LinkException("bad proof", DialFailure.HANDSHAKE))
                .contains("reached them, but the handshake failed: bad proof")
        )
        assertTrue(
            DialPolicy.explainDialFailure(endpoint, RuntimeException("host unreachable"))
                .contains("their torchat is not running")
        )
    }

    @Test
    fun `every explanation names the address it is about`() {
        val explained = DialPolicy.explainDialFailure(
            Endpoint.onion(onion),
            RuntimeException("something odd"),
        )

        assertTrue(explained.startsWith("$onion:${Constants.ONION_PORT}"))
    }
}
