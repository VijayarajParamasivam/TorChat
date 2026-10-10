package com.torchat.core

/**
 * Every tunable in the protocol, in one place (ARCHITECTURE.md §12).
 *
 * The Tor-related timeouts are generous on purpose. A first bootstrap downloads
 * the full network consensus — megabytes — and was measured at over 120 s on a
 * phone hotspot, which is exactly the network this design exists for. A timeout
 * tight enough for TCP abandons connections that were about to succeed.
 */
object Constants {

    /** Contact code marker. */
    const val CARD_PREFIX = "TORCHAT1."

    /** Virtual port on the onion. The same for everyone — it only exists inside Tor. */
    const val ONION_PORT = 47777

    /** Handshake proof domain separator. */
    const val PROOF_CONTEXT = "torchat-proof-v1"

    /** Channel key domain separator. */
    const val HKDF_INFO = "torchat-channel-v1"

    const val MAX_FRAME = 1_048_576
    const val MAX_BODY = 4_000
    const val MAX_ID = 64
    const val MAX_NAME = 24
    const val MAX_SIGIL = 2
    const val MAX_ENDPOINTS = 4
    const val HISTORY_CAP = 2_000

    /** A nonce shorter than this is rejected (§7.4). */
    const val MIN_NONCE_LENGTH = 16

    const val BOOTSTRAP_TIMEOUT_MS = 300_000L
    const val DESCRIPTOR_TIMEOUT_MS = 120_000L
    const val DIAL_TIMEOUT_MS = 90_000L
    const val HANDSHAKE_TIMEOUT_MS = 45_000L
    const val PING_INTERVAL_MS = 20_000L
    const val PING_TIMEOUT_MS = 60_000L
    const val REDIAL_INTERVAL_MS = 30_000L
    const val FIRST_BACKOFF_MS = 15_000L
    const val MAX_BACKOFF_MS = 300_000L
    const val DIAL_STAGGER_MS = 2_000L
    const val SAVE_DEBOUNCE_MS = 300L

    /** TCP keepalive on a link's socket. */
    const val TCP_KEEPALIVE_MS = 15_000

    /** A card dated further ahead than this is rejected (§5.4). */
    const val CARD_FUTURE_SLACK_MS = 86_400_000L
}
