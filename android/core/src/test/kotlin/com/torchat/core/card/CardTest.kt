package com.torchat.core.card

import com.torchat.core.Constants
import com.torchat.core.crypto.Base64Url
import com.torchat.core.crypto.Crypto
import com.torchat.core.json.CanonicalJson
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Identity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTest {

    private val onion = "a".repeat(56) + ".onion"
    private val other = "b".repeat(56) + ".onion"

    private fun identity(name: String = "alice") = Identity.create(name, "*")

    private fun cardFor(
        identity: Identity = identity(),
        endpoints: List<Endpoint> = listOf(Endpoint.onion(onion)),
        now: Long = System.currentTimeMillis(),
    ) = Card.create(identity, endpoints, now)

    /** Re-sign an arbitrary payload, the way a hostile-but-competent peer would. */
    private fun forge(identity: Identity, mutate: (MutableMap<String, JsonPrimitive>) -> Unit): String {
        val fields = linkedMapOf(
            "v" to JsonPrimitive(1),
            "id" to JsonPrimitive(identity.id),
            "sign" to JsonPrimitive(identity.sign.public),
            "box" to JsonPrimitive(identity.box.public),
            "name" to JsonPrimitive(identity.name),
            "sigil" to JsonPrimitive(identity.sigil),
            "ts" to JsonPrimitive(System.currentTimeMillis()),
        )
        mutate(fields)

        val payload = JsonObject(fields)
        val signature = Crypto.sign(identity.sign.private, CanonicalJson.encode(payload))
        val signed = JsonObject(payload + ("sig" to JsonPrimitive(signature)))
        return Card.PREFIX + Base64Url.encode(signed.toString().toByteArray())
    }

    // --- the happy path -----------------------------------------------------

    @Test
    fun `a card round-trips through create and parse`() {
        val alice = identity("alice")

        val parsed = Card.parse(cardFor(alice))

        assertEquals(alice.id, parsed.id)
        assertEquals(alice.sign.public, parsed.sign)
        assertEquals(alice.box.public, parsed.box)
        assertEquals("alice", parsed.name)
        assertEquals("*", parsed.sigil)
        assertEquals(listOf(Endpoint("onion", onion, Constants.ONION_PORT)), parsed.endpoints)
    }

    @Test
    fun `a card carries the documented prefix`() {
        assertTrue(cardFor().startsWith("TORCHAT1."))
    }

    @Test
    fun `whitespace anywhere in a pasted code is tolerated`() {
        val card = cardFor()

        // Email clients wrap long lines; the code is still the one that was sent.
        val mangled = card.chunked(40).joinToString("\n  ") + "\n"

        assertEquals(Card.parse(card).id, Card.parse(mangled).id)
    }

    @Test
    fun `a card with no endpoints is still a valid card`() {
        val parsed = Card.parse(cardFor(endpoints = emptyList()))

        assertTrue(parsed.endpoints.isEmpty())
    }

    // --- rejection paths (§5.4) ---------------------------------------------

    @Test
    fun `text without the prefix is rejected`() {
        val failure = assertThrows(CardException::class.java) { Card.parse("hello there") }

        assertEquals("that does not look like a TorChat code", failure.message)
    }

    @Test
    fun `a truncated code is rejected with a message about copying`() {
        val card = cardFor()
        val truncated = card.dropLast(30)

        val failure = assertThrows(CardException::class.java) { Card.parse(truncated) }

        assertTrue(failure.message!!, failure.message!!.contains("truncated when copied"))
    }

    @Test
    fun `a code that is not base64 at all is rejected`() {
        assertThrows(CardException::class.java) { Card.parse("TORCHAT1.!!!!!!") }
    }

    @Test
    fun `an empty or null code is rejected`() {
        assertThrows(CardException::class.java) { Card.parse(null) }
        assertThrows(CardException::class.java) { Card.parse("") }
    }

    @Test
    fun `an unsupported version is rejected`() {
        val alice = identity()
        val card = forge(alice) { it["v"] = JsonPrimitive(2) }

        val failure = assertThrows(CardException::class.java) { Card.parse(card) }

        assertTrue(failure.message!!, failure.message!!.startsWith("unsupported card version"))
    }

    @Test
    fun `a version smuggled in as a string is rejected`() {
        val alice = identity()
        val card = forge(alice) { it["v"] = JsonPrimitive("1") }

        assertThrows(CardException::class.java) { Card.parse(card) }
    }

    @Test
    fun `a card missing its keys is rejected`() {
        val alice = identity()
        val card = forge(alice) { it.remove("box") }

        val failure = assertThrows(CardException::class.java) { Card.parse(card) }

        assertEquals("code is missing fields", failure.message)
    }

    @Test
    fun `an ID that does not match its key is rejected as forged`() {
        val alice = identity()
        val card = forge(alice) { it["id"] = JsonPrimitive("TOR-0000-0000-0000") }

        val failure = assertThrows(CardException::class.java) { Card.parse(card) }

        assertEquals("code is forged — the ID does not match its key", failure.message)
    }

    @Test
    fun `wearing someone else's key with your own ID is rejected`() {
        val alice = identity("alice")
        val bob = identity("bob")

        // Bob's key, Alice's ID: the ID no longer hashes from the key present.
        val card = forge(alice) { it["sign"] = JsonPrimitive(bob.sign.public) }

        assertThrows(CardException::class.java) { Card.parse(card) }
    }

    @Test
    fun `a card altered after signing is rejected`() {
        val alice = identity("alice")
        val card = cardFor(alice)

        // Re-encode with a changed name but the original signature.
        val body = CanonicalJson.parser
            .parseToJsonElement(String(Base64Url.decode(card.removePrefix(Card.PREFIX))))
            .jsonObject
        val tampered = JsonObject(body + ("name" to JsonPrimitive("mallory")))
        val rebuilt = Card.PREFIX + Base64Url.encode(tampered.toString().toByteArray())

        val failure = assertThrows(CardException::class.java) { Card.parse(rebuilt) }

        assertEquals("signature check failed — the code was altered", failure.message)
    }

    @Test
    fun `a card with no signature at all is rejected`() {
        val alice = identity()
        val payload = linkedMapOf<String, JsonPrimitive>(
            "v" to JsonPrimitive(1),
            "id" to JsonPrimitive(alice.id),
            "sign" to JsonPrimitive(alice.sign.public),
            "box" to JsonPrimitive(alice.box.public),
        )
        val unsigned = Card.PREFIX + Base64Url.encode(JsonObject(payload).toString().toByteArray())

        assertThrows(CardException::class.java) { Card.parse(unsigned) }
    }

    @Test
    fun `a card dated more than a day in the future is rejected`() {
        val now = System.currentTimeMillis()
        val card = cardFor(now = now + Constants.CARD_FUTURE_SLACK_MS + 60_000)

        val failure = assertThrows(CardException::class.java) { Card.parse(card, now) }

        assertTrue(failure.message!!, failure.message!!.contains("dated in the future"))
    }

    @Test
    fun `a card slightly in the future is accepted, because clocks are wrong`() {
        val now = System.currentTimeMillis()
        val card = cardFor(now = now + 3_600_000)

        Card.parse(card, now)
    }

    // --- forward compatibility (§5.2) ---------------------------------------

    @Test
    fun `a card carrying an unknown field still verifies`() {
        val alice = identity()

        // The sender signed the extra field too, so canonicalising "everything
        // except sig" reproduces their bytes. Canonicalising a fixed schema
        // instead would reject every card a future version ever sends.
        val card = forge(alice) { it["futureThing"] = JsonPrimitive("hello") }

        assertEquals(alice.id, Card.parse(card).id)
    }

    // --- clamping (§4.2) ----------------------------------------------------

    @Test
    fun `a name from a card is clamped`() {
        val alice = identity()
        val card = forge(alice) { it["name"] = JsonPrimitive("   " + "x".repeat(80) + "   ") }

        assertEquals("x".repeat(24), Card.parse(card).name)
    }

    @Test
    fun `an empty name falls back rather than displaying blank`() {
        val alice = identity()
        val card = forge(alice) { it["name"] = JsonPrimitive("     ") }

        assertEquals("anon", Card.parse(card).name)
    }

    @Test
    fun `a sigil is clamped to two characters`() {
        val alice = identity()
        val card = forge(alice) { it["sigil"] = JsonPrimitive("abcdef") }

        assertEquals("ab", Card.parse(card).sigil)
    }

    // --- endpoints ----------------------------------------------------------

    @Test
    fun `endpoints survive parsing unfiltered, for the merge to judge`() {
        val alice = identity()
        val card = cardFor(alice, listOf(Endpoint.onion(onion), Endpoint.onion(other)))

        assertEquals(listOf(onion, other), Card.parse(card).endpoints.map { it.host })
    }

    @Test
    fun `a card is signed over the port it declares`() {
        val alice = identity()
        val card = cardFor(alice, listOf(Endpoint("onion", onion, 1234)))

        assertEquals(1234, Card.parse(card).endpoints.single().port)
    }
}
