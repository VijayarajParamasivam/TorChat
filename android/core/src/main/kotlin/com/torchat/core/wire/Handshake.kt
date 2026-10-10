package com.torchat.core.wire

import com.torchat.core.port.Stream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Why a link never came up. The engine turns these into human causes (§10.5). */
enum class DialFailure { NO_TOR, TIMEOUT, HANDSHAKE, UNREACHABLE, OTHER }

class LinkException(
    message: String,
    val failure: DialFailure = DialFailure.HANDSHAKE,
) : Exception(message)

/**
 * Run the handshake over an already-connected stream and resolve with the link
 * once both sides have proved possession.
 *
 * A Tor circuit arrives already open — there is no connect step to wait for.
 * The link stays symmetric in content: both ends send `hello` and both answer
 * with `proof`, so two peers who dialled each other at once still end up with
 * one working channel rather than a deadlock.
 *
 * [timeoutMs] is the caller's whole budget and is handed to the link as well.
 * Giving the link its own fixed timeout means the tighter of the two always
 * wins, and a handshake that was merely slow across six relays gets reported as
 * failed (§7.8).
 */
suspend fun handshakeOverStream(
    stream: Stream,
    ctx: LinkContext,
    scope: CoroutineScope,
    expectId: String? = null,
    timeoutMs: Long,
    greetFirst: Boolean = true,
): Link {
    val link = Link(
        stream = stream,
        ctx = ctx,
        expectId = expectId,
        greetFirst = greetFirst,
        handshakeTimeoutMs = timeoutMs,
    )

    return try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine<Link> { continuation ->
                link.onReady = {
                    if (continuation.isActive) continuation.resume(link)
                }
                link.onFailed = { reason ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(LinkException(reason))
                    }
                }
                link.onClose = { reason ->
                    // A link that closes without ever failing — the peer hung up
                    // mid-handshake — must still settle the caller, or the dial
                    // hangs until the outer timeout.
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            LinkException(reason ?: "the connection closed during the handshake")
                        )
                    }
                }
                continuation.invokeOnCancellation { link.close("handshake cancelled") }

                // Started only once every callback is attached — the handshake
                // can complete inside this call.
                link.start(scope)
            }
        }
    } catch (_: TimeoutCancellationException) {
        link.close("handshake timed out")
        throw LinkException("handshake stalled after ${timeoutMs}ms", DialFailure.TIMEOUT)
    }.also {
        // The callbacks above belong to the handshake only. The adopter installs
        // its own, and leaving these attached would resume a dead continuation.
        link.onReady = null
        link.onFailed = null
        link.onClose = null
    }
}
