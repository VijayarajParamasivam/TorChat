package com.torchat.core.engine

import com.torchat.core.card.CardException
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Identity
import com.torchat.core.model.MessageState
import com.torchat.core.engine.TorPhase
import com.torchat.core.support.FakeTorNetwork
import com.torchat.core.support.FakeTorProvider
import com.torchat.core.support.InMemorySettings
import com.torchat.core.support.InMemoryStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole engine, two peers, no Tor.
 *
 * Both engines bind real loopback listeners and speak the real protocol to each
 * other through [FakeTorNetwork]; only the circuits are missing. If these pass,
 * what is left untested is Tor itself.
 */
class EngineTest {

    private val network = FakeTorNetwork()

    private lateinit var alice: Peer
    private lateinit var bob: Peer

    /** One side: an identity, its stores, its provider and its engine. */
    private class Peer(name: String, network: FakeTorNetwork) {
        val identity: Identity = Identity.create(name)
        val store = InMemoryStore()
        val settings = InMemorySettings()
        val tor: FakeTorProvider = network.provider()
        val engine = Engine(identity, store, settings, tor)

        suspend fun card(): String = engine.myCard()
    }

    @Before
    fun setUp() = runBlocking {
        alice = Peer("alice", network)
        bob = Peer("bob", network)
        alice.engine.start()
        bob.engine.start()
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { alice.engine.stop() }
        runCatching { bob.engine.stop() }
        Unit
    }

    /**
     * Get the two of them connected: Alice pastes Bob's code, Bob accepts.
     *
     * Two steps now rather than one — a peer dialling in no longer accepts
     * itself, so a test that only pastes leaves Bob sitting on a request.
     */
    private suspend fun becomeFriends() {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }
        bob.engine.acceptRequest(alice.identity.id)
        eventually { alice.engine.friends().singleOrNull()?.online == true }
        eventually { bob.engine.friends().singleOrNull()?.online == true }
    }

    /** Poll until [check] holds, so tests do not depend on fixed sleeps. */
    private suspend fun eventually(timeoutMs: Long = 15_000, check: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!check()) delay(25)
        }
    }

    // --- adding friends (§9.3) ----------------------------------------------

    @Test
    fun `pasting a card adds the friend and connects once accepted`() = runBlocking {
        becomeFriends()

        assertEquals("bob", alice.engine.friends().single().name)
    }

    // --- requests -----------------------------------------------------------

    @Test
    fun `a peer that dials in arrives as a request, not a friend`() = runBlocking {
        // Alice pastes Bob's code, so Alice decided. Bob did not, and gets asked.
        alice.engine.addFriend(bob.card())

        eventually { bob.engine.requests().isNotEmpty() }

        assertEquals("alice", bob.engine.requests().single().name)
        assertTrue(bob.engine.friends().isEmpty())
    }

    @Test
    fun `accepting a request connects both sides`() = runBlocking {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }

        assertTrue(bob.engine.acceptRequest(alice.identity.id))

        eventually { bob.engine.friends().singleOrNull()?.online == true }
        eventually { alice.engine.friends().singleOrNull()?.online == true }
        assertTrue(bob.engine.requests().isEmpty())
    }

    @Test
    fun `messages sent while a request was pending arrive once it is accepted`() = runBlocking {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }

        // Nothing is delivered while Bob has not answered, but the outbox keeps
        // it — the delivery contract does not care why a link is missing.
        alice.engine.sendText(bob.identity.id, "let me in")
        assertTrue(bob.engine.history(alice.identity.id).isEmpty())

        bob.engine.acceptRequest(alice.identity.id)

        eventually { bob.engine.history(alice.identity.id).any { it.body == "let me in" } }
    }

    @Test
    fun `a rejected peer cannot get back in by redialling`() = runBlocking {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }

        assertTrue(bob.engine.rejectRequest(alice.identity.id))

        // Alice keeps trying — she still holds Bob's card and nothing told her
        // otherwise. That must not put her back in the list.
        repeat(3) { alice.engine.tryNow(bob.identity.id) }
        delay(500)

        assertTrue(bob.engine.friends().isEmpty())
        assertTrue(bob.engine.requests().isEmpty())
        assertEquals(1, bob.engine.blocked().size)
    }

    @Test
    fun `a removed friend stays removed`() = runBlocking {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }
        bob.engine.acceptRequest(alice.identity.id)
        eventually { bob.engine.friends().singleOrNull()?.online == true }

        assertTrue(bob.engine.removeFriend(alice.identity.id))

        // The bug this exists for: removal used to last only until the peer's
        // next redial, when they were silently accepted all over again.
        repeat(3) { alice.engine.tryNow(bob.identity.id) }
        delay(500)

        assertTrue(bob.engine.friends().isEmpty())
        assertTrue(bob.engine.requests().isEmpty())
    }

    @Test
    fun `unblocking lets them ask again`() = runBlocking {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }
        bob.engine.rejectRequest(alice.identity.id)

        assertTrue(bob.engine.unblock(alice.identity.id))
        assertTrue(bob.engine.blocked().isEmpty())

        alice.engine.tryNow(bob.identity.id)

        eventually { bob.engine.requests().isNotEmpty() }
    }

    @Test
    fun `pasting a code accepts outright, with no request of our own to answer`() = runBlocking {
        // Pasting is itself the decision. Asking the person who just chose to
        // add someone whether they meant it would be theatre.
        alice.engine.addFriend(bob.card())

        assertEquals("bob", alice.engine.friends().single().name)
        assertTrue(alice.engine.requests().isEmpty())
    }

    @Test
    fun `pasting the code of someone you blocked un-blocks them`() = runBlocking {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }
        bob.engine.rejectRequest(alice.identity.id)

        bob.engine.addFriend(alice.card())

        assertEquals("alice", bob.engine.friends().single().name)
        assertTrue(bob.engine.blocked().isEmpty())
    }

    @Test
    fun `adding your own code is refused`() = runBlocking {
        val failure = runCatching { alice.engine.addFriend(alice.card()) }.exceptionOrNull()

        assertTrue(failure is CardException)
        assertEquals("that is your own code", failure!!.message)
    }

    @Test
    fun `text that is not a card is refused with a message about pasting`() = runBlocking {
        val failure = runCatching { alice.engine.addFriend("hello") }.exceptionOrNull()

        assertTrue(failure is CardException)
        assertTrue(failure!!.message!!.contains("paste their full"))
    }

    @Test
    fun `a card with no onion address is refused`() = runBlocking {
        // A card built before an address existed carries an empty endpoint list.
        val addressless = com.torchat.core.card.Card.create(bob.identity, emptyList())

        val failure = runCatching { alice.engine.addFriend(addressless) }.exceptionOrNull()

        assertTrue(failure is CardException)
        assertTrue(failure!!.message!!.contains("no onion address"))
    }

    @Test
    fun `removing a friend drops them and their link`() = runBlocking {
        becomeFriends()

        assertTrue(alice.engine.removeFriend(bob.identity.id))
        assertTrue(alice.engine.friends().isEmpty())
    }

    // --- the delivery contract (§8) -----------------------------------------

    @Test
    fun `a message reaches the other side and is acknowledged`() = runBlocking {
        becomeFriends()

        val sent = alice.engine.sendText(bob.identity.id, "hello bob")

        eventually { bob.engine.history(alice.identity.id).any { it.body == "hello bob" } }
        eventually {
            alice.engine.history(bob.identity.id)
                .single { it.id == sent.id }.state == MessageState.DELIVERED
        }

        val theirs = bob.engine.history(alice.identity.id).single()
        assertEquals(MessageState.RECEIVED, theirs.state)
        assertFalse(theirs.mine)
    }

    @Test
    fun `messages flow both ways`() = runBlocking {
        becomeFriends()

        alice.engine.sendText(bob.identity.id, "from alice")
        bob.engine.sendText(alice.identity.id, "from bob")

        eventually { bob.engine.history(alice.identity.id).any { it.body == "from alice" } }
        eventually { alice.engine.history(bob.identity.id).any { it.body == "from bob" } }
    }

    @Test
    fun `a message composed with nobody connected stays queued and is not lost`() = runBlocking {
        // Bob is known but unreachable: his onion resolves to nothing.
        val bobCard = bob.card()
        bob.engine.stop()
        alice.engine.addFriend(bobCard)

        val message = alice.engine.sendText(bob.identity.id, "you were away")

        assertEquals(MessageState.QUEUED, message.state)
        assertEquals(
            MessageState.QUEUED,
            alice.engine.history(bob.identity.id).single().state,
        )
    }

    @Test
    fun `only an ack marks a message delivered`() = runBlocking {
        val bobCard = bob.card()
        bob.engine.stop()
        alice.engine.addFriend(bobCard)

        alice.engine.sendText(bob.identity.id, "into the void")
        delay(200)

        // Nothing came back, so nothing may claim delivery.
        assertTrue(alice.engine.history(bob.identity.id).none { it.state == MessageState.DELIVERED })
    }

    @Test
    fun `body is clamped to the ceiling`() = runBlocking {
        alice.engine.addFriend(bob.card())

        val message = alice.engine.sendText(bob.identity.id, "x".repeat(9_000))

        assertEquals(com.torchat.core.Constants.MAX_BODY, message.body.length)
    }

    @Test
    fun `sending to somebody who is not a friend is refused`() = runBlocking {
        val failure = runCatching {
            alice.engine.sendText("TOR-0000-0000-0000", "hello?")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    // --- status and identity ------------------------------------------------

    @Test
    fun `our address exists as soon as start returns, without waiting to be reachable`() =
        runBlocking {
            // The address is usable immediately: ADD_ONION returns in
            // milliseconds. Blocking startup on the descriptor upload instead
            // left the app pretending it had no address for two minutes.
            assertNotNull(alice.engine.status.value.onion)
            assertTrue(alice.engine.status.value.onion!!.endsWith(".onion"))
            assertTrue(alice.engine.status.value.torRunning)
        }

    @Test
    fun `reachability arrives separately, as an event`() = runBlocking {
        eventually { alice.engine.status.value.published }

        assertEquals(TorPhase.REACHABLE, alice.engine.status.value.phase)
    }

    @Test
    fun `the published card always carries an address, or is withheld`() = runBlocking {
        // Read as state rather than rebuilt on an event. The observer used to
        // build it once at attach time — when there was no address yet — and
        // then rely on a notification to correct it. A `replay = 0` SharedFlow
        // drops anything emitted before a collector attaches, so the correction
        // could simply never arrive, and the code people shared had an empty
        // endpoint list: "that code has no onion address".
        val published = alice.engine.card.value
        assertNotNull(published)

        // Dialable, not merely present. Asserting "not empty" is what let a
        // card ship whose host was a bare 56-character service ID with no
        // ".onion" suffix: an endpoint was there, and it was useless — the
        // receiving side dropped it and reported "no onion address".
        val endpoint = com.torchat.core.card.Card.parse(published!!).endpoints.single()
        assertTrue("host was '${endpoint.host}'", Endpoint.isDialable(endpoint))
    }

    @Test
    fun `an address that is not a valid onion is refused at startup`() = runBlocking {
        val peer = Peer("mallory", network)
        peer.tor.malformedAddress = true

        val failure = runCatching { peer.engine.start() }.exceptionOrNull()

        // Loudly, at the source. The alternative is a client that looks healthy
        // and hands out codes nobody can use.
        assertTrue(failure is IllegalStateException)
        runCatching { peer.engine.stop() }
        Unit
    }

    @Test
    fun `a card carries the onion address before the descriptor has published`() = runBlocking {
        // Anything shared during the propagation window has to work. The earlier
        // build handed out cards with an empty endpoint list, which the receiving
        // side rejects with "they need to send a new one".
        val parsed = com.torchat.core.card.Card.parse(alice.card())

        assertTrue(parsed.endpoints.isNotEmpty())
    }

    @Test
    fun `our card carries our onion address and nothing else`() = runBlocking {
        val parsed = com.torchat.core.card.Card.parse(alice.card())

        assertEquals(alice.identity.id, parsed.id)
        assertEquals(listOf(alice.tor.address), parsed.endpoints.map { it.host })
    }

    @Test
    fun `the onion key is persisted so the address survives a restart`() = runBlocking {
        assertNotNull(alice.settings.onionKey)
    }

    // --- resolution (§9.4) --------------------------------------------------

    @Test
    fun `a friend resolves by exact id, by fragment, and by name`() = runBlocking {
        alice.engine.addFriend(bob.card())
        val id = bob.identity.id

        assertEquals(id, alice.engine.resolve(id)?.id)
        assertEquals(id, alice.engine.resolve(id.removePrefix("TOR-").take(4))?.id)
        assertEquals(id, alice.engine.resolve("bob")?.id)
        assertNull(alice.engine.resolve("nobody"))
        assertNull(alice.engine.resolve(""))
    }

    // --- tor going down (§10.6) ---------------------------------------------

    @Test
    fun `tor going down takes every link with it`() = runBlocking {
        becomeFriends()

        alice.tor.goDown()

        eventually { alice.engine.friends().single().online.not() }
        assertEquals(0, alice.engine.status.value.online)
    }

    @Test
    fun `tor coming back on its own reconnects everyone`() = runBlocking {
        becomeFriends()

        alice.tor.goDown()
        eventually { alice.engine.friends().single().online.not() }

        // Nobody relaunches anything. Before the restart supervisor existed,
        // this stayed offline until the app was killed and opened again.
        eventually(timeoutMs = 20_000) { alice.engine.friends().single().online }
        assertTrue(alice.engine.status.value.torRunning)
        assertFalse(alice.engine.status.value.torRestarting)
        // Same key, same address: every friend's saved card still works.
        assertEquals(alice.tor.address, alice.engine.status.value.onion)
    }

    @Test
    fun `while tor is down the status says so instead of claiming reachability`() = runBlocking {
        eventually { alice.engine.status.value.phase == TorPhase.REACHABLE }

        alice.tor.goDown()

        eventually { alice.engine.status.value.phase == TorPhase.BOOTSTRAPPING }
        assertFalse(alice.engine.status.value.published)
    }

    // --- the network coming and going ---------------------------------------

    @Test
    fun `losing the network is reported as its own phase`() = runBlocking {
        alice.engine.onNetworkChanged(available = false)
        assertEquals(TorPhase.OFFLINE, alice.engine.status.value.phase)

        alice.engine.onNetworkChanged(available = true)
        assertTrue(alice.engine.status.value.phase != TorPhase.OFFLINE)
    }

    @Test
    fun `a network change while tor's socks port is closed does not restart a live tor`() = runBlocking {
        // Measured on an emulator: kmp-tor sets DisableNetwork on every network
        // flap, Tor closes its SOCKS listener, and "not ready" was read as
        // "dead". The restart then collided with the onion service the live
        // daemon was still running, and failed again every few seconds.
        val startsBefore = alice.tor.starts
        alice.tor.pauseNetwork()

        alice.engine.onNetworkChanged(available = false)
        alice.engine.onNetworkChanged(available = true, changed = true)
        alice.engine.nudge()
        alice.engine.reconnectAll()
        alice.engine.restartTor()
        delay(4_000) // past the first restart delay, had one been scheduled

        assertEquals(startsBefore, alice.tor.starts)
        assertFalse(alice.engine.status.value.torRestarting)
        alice.tor.resumeNetwork()
    }

    @Test
    fun `a network change keeps links that still answer`() = runBlocking {
        becomeFriends()

        alice.engine.onNetworkChanged(available = true, changed = true)
        delay(500)

        // The probe is answered, so the link survives the switch.
        assertTrue(alice.engine.friends().single().online)
    }

    // --- profile and counts ---------------------------------------------------

    @Test
    fun `renaming yourself reaches a connected friend`() = runBlocking {
        becomeFriends()

        alice.engine.setProfile("alicia", "@")

        eventually { bob.engine.friends().single().name == "alicia" }
        assertEquals("alicia", com.torchat.core.card.Card.parse(alice.card()).name)
        assertEquals("alicia", alice.engine.profile.name)
    }

    @Test
    fun `the friend count leaves out requests, which are counted separately`() = runBlocking {
        alice.engine.addFriend(bob.card())
        eventually { bob.engine.requests().isNotEmpty() }

        eventually { bob.engine.status.value.requests == 1 }
        assertEquals(0, bob.engine.status.value.friends)

        bob.engine.acceptRequest(alice.identity.id)
        eventually { bob.engine.status.value.friends == 1 }
        assertEquals(0, bob.engine.status.value.requests)
    }

    @Test
    fun `who is online is readable as state`() = runBlocking {
        becomeFriends()

        eventually { alice.engine.online.value == setOf(bob.identity.id) }
    }
}
