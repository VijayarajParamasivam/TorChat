package com.torchat.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory

/**
 * All cryptography for TorChat. Four primitives and no more (§3):
 * Ed25519 for identity, X25519 for agreement, HKDF-SHA256 for derivation,
 * AES-256-GCM for the channel.
 *
 * Two details here are the difference between a client that interoperates and
 * one that only ever talks to itself, and both are places where the obvious
 * library call does the wrong thing:
 *
 *  - Keys cross every boundary as **DER**, SPKI for public and PKCS#8 for
 *    private, base64url encoded (§3.1). BouncyCastle's low-level API hands you
 *    raw 32-byte keys; those are wrapped explicitly below. An Ed25519 SPKI blob
 *    is 44 bytes, not 32.
 *  - A sealed frame puts the **GCM tag before the ciphertext** (§3.2). Every
 *    mainstream AEAD API, BouncyCastle's included, appends it. `seal` and `open`
 *    reorder in both directions.
 *
 * The lightweight (`org.bouncycastle.crypto.*`) API is used throughout rather
 * than the JCE provider: it behaves identically on every Android API level,
 * needs no `Security.addProvider` call at startup, and cannot be shadowed by the
 * platform's own bundled BouncyCastle.
 */
object Crypto {

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    // RFC 8410. Fixed by the standard, so they are written out here rather than
    // imported — BouncyCastle has moved the constants that hold them between
    // packages more than once.
    private val ID_ED25519 = ASN1ObjectIdentifier("1.3.101.112")
    private val ID_X25519 = ASN1ObjectIdentifier("1.3.101.110")

    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8

    private const val CHANNEL_KEY_BYTES = 32
    private const val NONCE_BYTES = 24
    private const val MESSAGE_ID_BYTES = 8

    private val random = SecureRandom()

    // --- key generation -----------------------------------------------------

    /** An Ed25519 pair, encoded per §3.1. The signing key *is* the identity. */
    fun generateSigningPair(): KeyPair {
        val private = Ed25519PrivateKeyParameters(random)
        return KeyPair(
            public = encodePublic(private.generatePublicKey()),
            private = encodePrivate(ID_ED25519, private.encoded),
        )
    }

    /** An X25519 pair. Ed25519 cannot do ECDH, hence a second pair. */
    fun generateBoxPair(): KeyPair {
        val private = X25519PrivateKeyParameters(random)
        return KeyPair(
            public = encodePublic(private.generatePublicKey()),
            private = encodePrivate(ID_X25519, private.encoded),
        )
    }

    // --- key serialisation --------------------------------------------------

    /** Raw key parameters -> SPKI DER -> base64url. 44 bytes for both curves. */
    private fun encodePublic(key: org.bouncycastle.crypto.params.AsymmetricKeyParameter): String =
        Base64Url.encode(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key).encoded)

    /**
     * Raw private key -> PKCS#8 DER -> base64url. 48 bytes for both curves.
     *
     * Built by hand rather than through `PrivateKeyInfoFactory`, which emits the
     * RFC 8410 version-1 form with the public key attached as an optional
     * attribute — 83 bytes. §3.1 specifies the minimal version-0 form, and a
     * client that stored the longer one would hand out backups and derive HKDF
     * salts that no other implementation reproduces.
     */
    private fun encodePrivate(algorithm: ASN1ObjectIdentifier, rawKey: ByteArray): String =
        Base64Url.encode(
            PrivateKeyInfo(AlgorithmIdentifier(algorithm), DEROctetString(rawKey)).encoded
        )

    fun signPublicKey(encoded: String): Ed25519PublicKeyParameters =
        PublicKeyFactory.createKey(SubjectPublicKeyInfo.getInstance(Base64Url.decode(encoded)))
                as Ed25519PublicKeyParameters

    fun signPrivateKey(encoded: String): Ed25519PrivateKeyParameters =
        PrivateKeyFactory.createKey(PrivateKeyInfo.getInstance(Base64Url.decode(encoded)))
                as Ed25519PrivateKeyParameters

    fun boxPublicKey(encoded: String): X25519PublicKeyParameters =
        PublicKeyFactory.createKey(SubjectPublicKeyInfo.getInstance(Base64Url.decode(encoded)))
                as X25519PublicKeyParameters

    fun boxPrivateKey(encoded: String): X25519PrivateKeyParameters =
        PrivateKeyFactory.createKey(PrivateKeyInfo.getInstance(Base64Url.decode(encoded)))
                as X25519PrivateKeyParameters

    /** Does this parse as the private key it claims to be? Used on backup import. */
    fun parsesAsBoxPrivate(encoded: String): Boolean = try {
        boxPrivateKey(encoded)
        true
    } catch (_: Exception) {
        false
    }

    // --- signing ------------------------------------------------------------

    fun sign(signPrivateEncoded: String, data: String): String =
        sign(signPrivateKey(signPrivateEncoded), data.toByteArray(Charsets.UTF_8))

    fun sign(key: Ed25519PrivateKeyParameters, data: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(data, 0, data.size)
        return Base64Url.encode(signer.generateSignature())
    }

    /**
     * Verify a signature against an encoded Ed25519 public key.
     *
     * Returns false rather than throwing on malformed input of any kind, because
     * every caller runs this on bytes that arrived from an untrusted peer (§5.4).
     */
    fun verify(signPublicEncoded: String?, data: String, signature: String?): Boolean = try {
        if (signPublicEncoded == null || signature == null) {
            false
        } else {
            val bytes = data.toByteArray(Charsets.UTF_8)
            val signer = Ed25519Signer()
            signer.init(false, signPublicKey(signPublicEncoded))
            signer.update(bytes, 0, bytes.size)
            signer.verifySignature(Base64Url.decode(signature))
        }
    } catch (_: Exception) {
        false
    }

    // --- identity -----------------------------------------------------------

    /**
     * The human-facing TorChat ID: SHA-256 of the *encoded SPKI blob*, first 60
     * bits rendered in Crockford base32 and grouped — `TOR-4K7P-9XQ2-M3TV`.
     *
     * Crockford's alphabet omits I, L, O and U, so an ID survives being read
     * aloud or copied by hand. Because the ID is a hash of the key, nobody can
     * claim yours without your private key — that is what replaces a server-side
     * account registry.
     */
    fun deriveId(signPublicEncoded: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64Url.decode(signPublicEncoded))

        // First 8 bytes as a 64-bit big-endian bit string, of which 60 bits are used.
        val bits = StringBuilder(64)
        for (i in 0 until 8) {
            val byte = digest[i].toInt() and 0xFF
            for (bit in 7 downTo 0) bits.append((byte shr bit) and 1)
        }

        val out = StringBuilder(12)
        for (group in 0 until 12) {
            val value = bits.substring(group * 5, group * 5 + 5).toInt(2)
            out.append(CROCKFORD[value])
        }

        return "TOR-${out.substring(0, 4)}-${out.substring(4, 8)}-${out.substring(8, 12)}"
    }

    /**
     * Check that an ID really is the hash of the key claiming it.
     *
     * Anywhere a peer presents an ID alongside a signing key, this runs and a
     * mismatch is rejected. It is what makes ID spoofing impossible rather than
     * merely difficult (§4.1).
     */
    fun idMatchesKey(peerId: String?, signPublicEncoded: String?): Boolean = try {
        peerId != null && signPublicEncoded != null && deriveId(signPublicEncoded) == peerId
    } catch (_: Exception) {
        false
    }

    // --- the encrypted channel ----------------------------------------------

    /**
     * The symmetric channel key, from our X25519 private key and the peer's
     * public one (§7.5).
     *
     * Both sides compute an identical secret without it ever crossing the wire.
     * The salt is the two public keys sorted, so both ends agree regardless of
     * who dialled whom.
     *
     * `theirBoxPublicEncoded` is used **exactly as it appeared in their card** —
     * same encoding, no re-normalisation — or the two sides derive different
     * salts and every frame fails to open.
     */
    fun deriveChannelKey(myBoxPrivateEncoded: String, theirBoxPublicEncoded: String): ByteArray {
        val myPrivate = boxPrivateKey(myBoxPrivateEncoded)
        val theirPublic = boxPublicKey(theirBoxPublicEncoded)

        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        myPrivate.generateSecret(theirPublic, shared, 0)

        val mine = encodePublic(myPrivate.generatePublicKey())
        val salt = listOf(mine, theirBoxPublicEncoded).sorted().joinToString("|")

        return hkdf(
            ikm = shared,
            salt = salt.toByteArray(Charsets.UTF_8),
            info = HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = CHANNEL_KEY_BYTES,
        )
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    /**
     * Encrypt a payload. Layout is `iv(12) | tag(16) | ciphertext` — note the tag
     * comes *first*, which is not what any AEAD API produces natively (§3.2).
     */
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = randomBytes(GCM_IV_BYTES)

        val cipher = GCMBlockCipher(AESEngine())
        cipher.init(true, AEADParameters(KeyParameter(key), GCM_TAG_BITS, iv))

        // BouncyCastle emits ciphertext followed by tag; the wire format wants
        // the reverse, so the two halves are split apart and reassembled.
        val combined = ByteArray(cipher.getOutputSize(plaintext.size))
        var written = cipher.processBytes(plaintext, 0, plaintext.size, combined, 0)
        written += cipher.doFinal(combined, written)

        val bodyLength = written - GCM_TAG_BYTES
        val out = ByteArray(GCM_IV_BYTES + written)
        iv.copyInto(out, 0)
        combined.copyInto(out, GCM_IV_BYTES, bodyLength, written)              // tag
        combined.copyInto(out, GCM_IV_BYTES + GCM_TAG_BYTES, 0, bodyLength)    // ciphertext
        return out
    }

    /**
     * Reverse [seal]. Throws on tag mismatch or a truncated frame.
     *
     * A frame that fails to open is a protocol failure and closes the link — it
     * is never skipped (§3.2).
     */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= GCM_IV_BYTES + GCM_TAG_BYTES) { "sealed frame is truncated" }

        val iv = sealed.copyOfRange(0, GCM_IV_BYTES)
        val tag = sealed.copyOfRange(GCM_IV_BYTES, GCM_IV_BYTES + GCM_TAG_BYTES)
        val body = sealed.copyOfRange(GCM_IV_BYTES + GCM_TAG_BYTES, sealed.size)

        // Put the tag back where BouncyCastle expects to find it.
        val combined = ByteArray(body.size + GCM_TAG_BYTES)
        body.copyInto(combined, 0)
        tag.copyInto(combined, body.size)

        val cipher = GCMBlockCipher(AESEngine())
        cipher.init(false, AEADParameters(KeyParameter(key), GCM_TAG_BITS, iv))

        val out = ByteArray(cipher.getOutputSize(combined.size))
        var written = cipher.processBytes(combined, 0, combined.size, out, 0)
        written += cipher.doFinal(out, written)
        return out.copyOf(written)
    }

    // --- random values (§3.3) -----------------------------------------------

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    /** A handshake challenge: base64url of 24 random bytes, so 32 characters. */
    fun nonce(): String = Base64Url.encode(randomBytes(NONCE_BYTES))

    /** A message ID: lowercase hex of 8 random bytes, so 16 characters. */
    fun messageId(): String = randomBytes(MESSAGE_ID_BYTES)
        .joinToString("") { "%02x".format(it) }

    const val HKDF_INFO = "torchat-channel-v1"
}

/** A generated keypair, both halves encoded per §3.1. */
data class KeyPair(val public: String, val private: String)
