package com.torchat.core.model

import com.torchat.core.Constants
import com.torchat.core.card.PeerCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    @Test
    fun `a name is trimmed then truncated`() {
        assertEquals("alice", Profile.name("  alice  "))
        assertEquals("x".repeat(24), Profile.name("x".repeat(100)))
        assertEquals("x".repeat(24), Profile.name("  " + "x".repeat(100)))
    }

    @Test
    fun `an empty name falls back rather than displaying blank`() {
        assertEquals("anon", Profile.name(null))
        assertEquals("anon", Profile.name(""))
        assertEquals("anon", Profile.name("    "))
    }

    @Test
    fun `a name falls back to the caller's choice when given one`() {
        // A peer renaming itself to nothing keeps the name we already show.
        assertEquals("alice", Profile.name("", fallback = "alice"))
        assertEquals("alice", Profile.name("   ", fallback = "alice"))
    }

    @Test
    fun `a sigil is capped at two characters`() {
        assertEquals("ab", Profile.sigil("abcdef"))
        assertEquals("*", Profile.sigil(null))
        assertEquals("*", Profile.sigil(""))
    }
}

class EndpointTest {

    private val onion = "a".repeat(56) + ".onion"
    private val other = "b".repeat(56) + ".onion"

    @Test
    fun `only v3 onion addresses are recognised`() {
        assertTrue(Endpoint.isOnion(onion))
        assertTrue(Endpoint.isOnion(onion.uppercase()))

        assertFalse(Endpoint.isOnion("192.168.1.5"))
        assertFalse(Endpoint.isOnion("example.com"))
        assertFalse(Endpoint.isOnion("short.onion"))
        assertFalse(Endpoint.isOnion("a".repeat(16) + ".onion")) // a v2 address
        assertFalse(Endpoint.isOnion("1".repeat(56) + ".onion")) // 0, 1, 8, 9 are not base32
        assertFalse(Endpoint.isOnion(null))
    }

    @Test
    fun `the merge drops everything that is not an onion`() {
        val merged = Endpoint.mergeOnions(
            incoming = listOf(
                Endpoint("wan", "203.0.113.7", 47777),
                Endpoint("lan", "192.168.0.4", 47777),
                Endpoint.onion(onion),
            ),
        )

        // It drops rather than deprioritises: there is no ordering in which a
        // rejected address gets used.
        assertEquals(listOf(onion), merged.map { it.host })
    }

    @Test
    fun `incoming addresses go ahead of existing ones`() {
        val merged = Endpoint.mergeOnions(
            incoming = listOf(Endpoint.onion(other)),
            existing = listOf(Endpoint.onion(onion)),
        )

        assertEquals(listOf(other, onion), merged.map { it.host })
    }

    @Test
    fun `duplicates collapse on host and port`() {
        val merged = Endpoint.mergeOnions(
            incoming = listOf(Endpoint.onion(onion)),
            existing = listOf(Endpoint.onion(onion), Endpoint.onion(onion, 9999)),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf(Constants.ONION_PORT, 9999), merged.map { it.port })
    }

    @Test
    fun `the list is capped`() {
        val many = (0 until 10).map { Endpoint.onion("${('a' + it)}".repeat(56) + ".onion") }

        assertEquals(Constants.MAX_ENDPOINTS, Endpoint.mergeOnions(many).size)
    }

    @Test
    fun `a missing or zero port becomes the standard one`() {
        assertEquals(Constants.ONION_PORT, Endpoint.onion(onion, 0).port)
        assertEquals(Constants.ONION_PORT, Endpoint.onion(onion, -1).port)
        assertEquals(1234, Endpoint.onion(onion, 1234).port)
    }

    @Test
    fun `our own address is never dialable`() {
        assertTrue(Endpoint.isDialable(Endpoint.onion(onion), ownOnion = other))
        assertFalse(Endpoint.isDialable(Endpoint.onion(onion), ownOnion = onion))
        assertFalse(Endpoint.isDialable(Endpoint("wan", "203.0.113.7", 47777)))
        assertFalse(Endpoint.isDialable(null))
    }
}

class FriendTest {

    private val live = "a".repeat(56) + ".onion"
    private val dead = "b".repeat(56) + ".onion"

    private fun card(ts: Long, host: String, name: String = "bob") = PeerCard(
        id = "TOR-1111-1111-1111",
        sign = "sign-key",
        box = "box-key",
        name = name,
        sigil = "!",
        endpoints = listOf(Endpoint.onion(host)),
        ts = ts,
    )

    @Test
    fun `a fresh card applies everything`() {
        val friend = Friend(id = "TOR-1111-1111-1111", addedAt = 0)
            .applyCard(card(ts = 1000, host = live))

        assertEquals("bob", friend.name)
        assertEquals("!", friend.sigil)
        assertEquals("sign-key", friend.sign)
        assertEquals(1000L, friend.cardTs)
        assertEquals(listOf(live), friend.endpoints.map { it.host })
    }

    @Test
    fun `a newer card is not stale`() {
        val friend = Friend(id = "x", cardTs = 1000, addedAt = 0)

        assertFalse(friend.isStale(card(ts = 2000, host = live), isNew = false))
    }

    @Test
    fun `an older card is stale`() {
        val friend = Friend(id = "x", cardTs = 2000, addedAt = 0)

        assertTrue(friend.isStale(card(ts = 1000, host = live), isNew = false))
    }

    @Test
    fun `nothing is stale for somebody new`() {
        // There is nothing to be older than.
        val friend = Friend(id = "x", cardTs = 5000, addedAt = 0)

        assertFalse(friend.isStale(card(ts = 1, host = live), isNew = true))
    }

    @Test
    fun `a stale card keeps its address only as a fallback`() {
        val friend = Friend(id = "x", name = "bob", cardTs = 2000, addedAt = 0)
            .applyCard(card(ts = 2000, host = live, name = "bob"))

        val afterStale = friend.keepAsFallback(card(ts = 1000, host = dead, name = "mallory"))

        // The live address stays in front, and the old card does not get to
        // rename them.
        assertEquals(listOf(live, dead), afterStale.endpoints.map { it.host })
        assertEquals("bob", afterStale.name)
        assertEquals(2000L, afterStale.cardTs)
    }

    @Test
    fun `a rename touches labels and nothing else`() {
        val friend = Friend(id = "x", addedAt = 0).applyCard(card(ts = 1000, host = live))

        val renamed = friend.renamed("robert", "#")

        assertEquals("robert", renamed.name)
        assertEquals("#", renamed.sigil)
        assertEquals(friend.sign, renamed.sign)
        assertEquals(friend.endpoints, renamed.endpoints)
    }

    @Test
    fun `a rename to nothing keeps the name we already show`() {
        val friend = Friend(id = "x", name = "bob", addedAt = 0)

        assertEquals("bob", friend.renamed("", null).name)
    }
}

class MessageTest {

    @Test
    fun `a composed message starts queued and is ours`() {
        val message = Message.compose("hello")

        assertTrue(message.mine)
        assertEquals(MessageState.QUEUED, message.state)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(message.id))
    }

    @Test
    fun `a body is clamped`() {
        assertEquals(Constants.MAX_BODY, Message.compose("x".repeat(9999)).body.length)
    }

    @Test
    fun `an incoming id is clamped`() {
        assertEquals(Constants.MAX_ID, Message.idFrom("x".repeat(500)).length)
    }

    @Test
    fun `an incoming message with no id gets one`() {
        assertTrue(Regex("^[0-9a-f]{16}$").matches(Message.idFrom(null)))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(Message.idFrom("")))
    }

    @Test
    fun `an incoming message with no timestamp is stamped on arrival`() {
        val received = Message.received("abc", ts = null, body = "hi", now = 1234)

        assertEquals(1234L, received.ts)
        assertFalse(received.mine)
        assertEquals(MessageState.RECEIVED, received.state)
    }

    @Test
    fun `everything of ours that is not delivered still awaits an ack`() {
        assertTrue(Message.compose("x").awaitingAck)
        assertTrue(Message.compose("x").copy(state = MessageState.SENT).awaitingAck)
        assertFalse(Message.compose("x").copy(state = MessageState.DELIVERED).awaitingAck)

        // Never one of theirs: an ack names an ID they got from us.
        assertFalse(Message.received("a", 1, "x").awaitingAck)
    }
}
