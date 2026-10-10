package com.torchat.core.crypto

import java.util.Base64

/**
 * RFC 4648 §5 base64url — the `-`/`_` alphabet, padding stripped.
 *
 * Every key, signature and nonce that crosses a boundary is encoded this way
 * (ARCHITECTURE.md §3.1). The decoder accepts padded input too, because a card
 * that survived a round trip through some messenger that "helpfully" re-padded
 * it is still a card the user expects to work.
 */
object Base64Url {

    fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

    /** @throws IllegalArgumentException on input that is not base64url. */
    fun decode(text: String): ByteArray = DECODER.decode(text.trimEnd('='))

    /** Decode without throwing, for the many paths that run on untrusted bytes. */
    fun decodeOrNull(text: String?): ByteArray? = try {
        if (text == null) null else decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
}
