package com.torchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact DER structures §3.1 requires, pinned byte for byte.
 *
 * These prefixes are the algorithm identifiers and length headers; only the
 * trailing 32 bytes of key material differ between runs. If a BouncyCastle
 * upgrade ever changes what the encoders emit — the version-1 PKCS#8 form with
 * an attached public key is the realistic way this goes wrong, and is what the
 * obvious `PrivateKeyInfoFactory` call produces — these fail immediately, rather
 * than surfacing much later as a peer whose signatures will not verify.
 */
class KeyEncodingTest {

    private fun hexPrefix(encoded: String, bytes: Int) =
        Base64Url.decode(encoded).copyOf(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `Ed25519 public keys are SPKI with the id-Ed25519 OID`() {
        // SEQUENCE(42) SEQUENCE(5) OID 1.3.101.112 BIT STRING(33) unused-bits=0
        assertEquals("302a300506032b65700321", hexPrefix(Crypto.generateSigningPair().public, 11))
        assertEquals(44, Base64Url.decode(Crypto.generateSigningPair().public).size)
    }

    @Test
    fun `Ed25519 private keys are the minimal version-0 PKCS8 form`() {
        // SEQUENCE(46) INTEGER 0 SEQUENCE(5) OID 1.3.101.112 OCTET STRING(34) OCTET STRING(32)
        // Version 0 with no trailing public-key attribute: 48 bytes, not 83.
        val encoded = Crypto.generateSigningPair().private
        assertEquals("302e020100300506032b657004220420", hexPrefix(encoded, 16))
        assertEquals(48, Base64Url.decode(encoded).size)
    }

    @Test
    fun `X25519 public keys are SPKI with the id-X25519 OID`() {
        // Same shape, OID 1.3.101.110.
        assertEquals("302a300506032b656e0321", hexPrefix(Crypto.generateBoxPair().public, 11))
        assertEquals(44, Base64Url.decode(Crypto.generateBoxPair().public).size)
    }

    @Test
    fun `X25519 private keys are the minimal version-0 PKCS8 form`() {
        val encoded = Crypto.generateBoxPair().private
        assertEquals("302e020100300506032b656e04220420", hexPrefix(encoded, 16))
        assertEquals(48, Base64Url.decode(encoded).size)
    }
}
