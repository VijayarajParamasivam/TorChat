package com.torchat.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    // --- key encoding (§3.1) ------------------------------------------------

    @Test
    fun `signing keys use SPKI and PKCS8 DER, not raw 32-byte keys`() {
        val pair = Crypto.generateSigningPair()

        // The single most likely interop bug in a port: an Ed25519 SPKI blob is
        // 44 bytes and a PKCS#8 private blob is 48. Raw keys would be 32.
        assertEquals(44, Base64Url.decode(pair.public).size)
        assertEquals(48, Base64Url.decode(pair.private).size)
    }

    @Test
    fun `box keys use SPKI and PKCS8 DER`() {
        val pair = Crypto.generateBoxPair()

        assertEquals(44, Base64Url.decode(pair.public).size)
        assertEquals(48, Base64Url.decode(pair.private).size)
    }

    @Test
    fun `encoded keys are base64url with no padding`() {
        val pair = Crypto.generateSigningPair()

        assertFalse(pair.public.contains('+'))
        assertFalse(pair.public.contains('/'))
        assertFalse(pair.public.contains('='))
        assertFalse(pair.private.contains('='))
    }

    @Test
    fun `keys round-trip through their encoded form`() {
        val pair = Crypto.generateSigningPair()
        val box = Crypto.generateBoxPair()

        // Re-deriving the public half from the private one must reproduce the
        // exact same encoding, or the HKDF salt in §7.5 would not agree.
        assertTrue(Crypto.verify(pair.public, "probe", Crypto.sign(pair.private, "probe")))
        assertTrue(Crypto.parsesAsBoxPrivate(box.private))
    }

    // --- signing ------------------------------------------------------------

    @Test
    fun `a signature verifies against its own key`() {
        val pair = Crypto.generateSigningPair()
        val signature = Crypto.sign(pair.private, "torchat-proof-v1|abc|TOR-1|TOR-2")

        assertTrue(Crypto.verify(pair.public, "torchat-proof-v1|abc|TOR-1|TOR-2", signature))
    }

    @Test
    fun `a signature does not verify against a different key`() {
        val mine = Crypto.generateSigningPair()
        val theirs = Crypto.generateSigningPair()

        assertFalse(Crypto.verify(theirs.public, "hello", Crypto.sign(mine.private, "hello")))
    }

    @Test
    fun `a signature does not verify over different data`() {
        val pair = Crypto.generateSigningPair()

        assertFalse(Crypto.verify(pair.public, "hello", Crypto.sign(pair.private, "goodbye")))
    }

    @Test
    fun `verify returns false rather than throwing on malformed input`() {
        val pair = Crypto.generateSigningPair()

        // This runs on bytes from an untrusted peer, so every one of these is a
        // rejection rather than a crash (§5.4).
        assertFalse(Crypto.verify("not a key", "data", "not a signature"))
        assertFalse(Crypto.verify(pair.public, "data", "!!!"))
        assertFalse(Crypto.verify(pair.public, "data", null))
        assertFalse(Crypto.verify(null, "data", "sig"))
        assertFalse(Crypto.verify("", "data", ""))
    }

    // --- identity (§4.1) ----------------------------------------------------

    @Test
    fun `an ID has the documented shape`() {
        val id = Crypto.deriveId(Crypto.generateSigningPair().public)

        assertTrue(id, Regex("^TOR-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$").matches(id))
    }

    @Test
    fun `an ID never contains the letters Crockford omits`() {
        // I, L, O and U are absent so an ID survives being read aloud.
        repeat(50) {
            val id = Crypto.deriveId(Crypto.generateSigningPair().public).removePrefix("TOR-")
            assertFalse(id, id.any { it == 'I' || it == 'L' || it == 'O' || it == 'U' })
        }
    }

    @Test
    fun `ID derivation is deterministic`() {
        val pair = Crypto.generateSigningPair()

        assertEquals(Crypto.deriveId(pair.public), Crypto.deriveId(pair.public))
    }

    @Test
    fun `different keys give different IDs`() {
        val a = Crypto.deriveId(Crypto.generateSigningPair().public)
        val b = Crypto.deriveId(Crypto.generateSigningPair().public)

        assertNotEquals(a, b)
    }

    @Test
    fun `idMatchesKey accepts a real pairing and rejects everything else`() {
        val mine = Crypto.generateSigningPair()
        val theirs = Crypto.generateSigningPair()

        assertTrue(Crypto.idMatchesKey(Crypto.deriveId(mine.public), mine.public))
        assertFalse(Crypto.idMatchesKey(Crypto.deriveId(theirs.public), mine.public))
        assertFalse(Crypto.idMatchesKey("TOR-0000-0000-0000", mine.public))
        assertFalse(Crypto.idMatchesKey(null, mine.public))
        assertFalse(Crypto.idMatchesKey("TOR-0000-0000-0000", "garbage"))
    }

    // --- channel key (§7.5) -------------------------------------------------

    @Test
    fun `both ends derive the same channel key regardless of who dialled`() {
        val alice = Crypto.generateBoxPair()
        val bob = Crypto.generateBoxPair()

        val fromAlice = Crypto.deriveChannelKey(alice.private, bob.public)
        val fromBob = Crypto.deriveChannelKey(bob.private, alice.public)

        assertArrayEquals(fromAlice, fromBob)
        assertEquals(32, fromAlice.size)
    }

    @Test
    fun `a third party derives a different channel key`() {
        val alice = Crypto.generateBoxPair()
        val bob = Crypto.generateBoxPair()
        val mallory = Crypto.generateBoxPair()

        assertFalse(
            Crypto.deriveChannelKey(alice.private, bob.public)
                .contentEquals(Crypto.deriveChannelKey(mallory.private, bob.public))
        )
    }

    // --- sealing (§3.2) -----------------------------------------------------

    @Test
    fun `sealed frames round-trip`() {
        val key = Crypto.randomBytes(32)
        val plaintext = """{"t":"msg","id":"deadbeefdeadbeef","ts":1753900000000,"body":"hi"}"""

        val opened = Crypto.open(key, Crypto.seal(key, plaintext.toByteArray()))

        assertEquals(plaintext, String(opened))
    }

    @Test
    fun `a sealed frame is laid out as iv, tag, then ciphertext`() {
        val key = Crypto.randomBytes(32)
        val plaintext = "hello".toByteArray()

        val sealed = Crypto.seal(key, plaintext)

        // 12-byte IV + 16-byte tag + the ciphertext, which for GCM is exactly
        // the length of the plaintext. The tag preceding the ciphertext is the
        // part every AEAD library gets backwards.
        assertEquals(12 + 16 + plaintext.size, sealed.size)
    }

    @Test
    fun `every frame gets a fresh IV`() {
        val key = Crypto.randomBytes(32)

        val first = Crypto.seal(key, "same".toByteArray()).copyOfRange(0, 12)
        val second = Crypto.seal(key, "same".toByteArray()).copyOfRange(0, 12)

        assertFalse(first.contentEquals(second))
    }

    @Test(expected = Exception::class)
    fun `a tampered frame fails to open`() {
        val key = Crypto.randomBytes(32)
        val sealed = Crypto.seal(key, "hello".toByteArray())

        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0xFF).toByte()
        Crypto.open(key, sealed)
    }

    @Test(expected = Exception::class)
    fun `a frame does not open under the wrong key`() {
        val sealed = Crypto.seal(Crypto.randomBytes(32), "hello".toByteArray())

        Crypto.open(Crypto.randomBytes(32), sealed)
    }

    @Test(expected = Exception::class)
    fun `a truncated frame is rejected rather than misread`() {
        Crypto.open(Crypto.randomBytes(32), ByteArray(10))
    }

    // --- random values (§3.3) -----------------------------------------------

    @Test
    fun `a nonce is 24 random bytes, so comfortably past the 16-char floor`() {
        val nonce = Crypto.nonce()

        assertEquals(24, Base64Url.decode(nonce).size)
        assertTrue(nonce.length >= 16)
        assertNotEquals(nonce, Crypto.nonce())
    }

    @Test
    fun `a message ID is 16 lowercase hex characters`() {
        val id = Crypto.messageId()

        assertTrue(id, Regex("^[0-9a-f]{16}$").matches(id))
        assertNotEquals(id, Crypto.messageId())
    }
}
