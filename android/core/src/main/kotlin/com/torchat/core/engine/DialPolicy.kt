package com.torchat.core.engine

import com.torchat.core.Constants
import com.torchat.core.model.Endpoint
import com.torchat.core.wire.DialFailure
import com.torchat.core.wire.Link
import com.torchat.core.wire.LinkException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Deciding what to dial, in what order, and how long to sulk after a failure.
 *
 * Everything here is either a pure function or a small bag of retry state, so
 * the policy can be reasoned about — and tested — without a circuit, a socket, or
 * a running Tor anywhere in sight. The actual dialling is injected.
 */
object DialPolicy {

    /**
     * Onion addresses only, and never our own.
     *
     * The same filter that guards the friend list, applied again at the moment of
     * use. An address that somehow got stored still cannot be dialled (§9.1).
     */
    fun orderEndpoints(endpoints: List<Endpoint>, ownOnion: String? = null): List<Endpoint> =
        endpoints.filter { Endpoint.isDialable(it, ownOnion) }

    /** Turn a failed dial into something a person can act on (§10.5). */
    fun explainDialFailure(endpoint: Endpoint, error: Throwable): String {
        val where = "${endpoint.host}:${endpoint.port}"
        val failure = (error as? LinkException)?.failure
        val message = error.message ?: "the connection failed"

        return when {
            failure == DialFailure.NO_TOR ->
                "$where — tor is not running here, so nothing can be dialled."

            failure == DialFailure.TIMEOUT ->
                "$where — no answer. they are probably offline, or their tor has not published yet."

            failure == DialFailure.HANDSHAKE ->
                "$where — reached them, but the handshake failed: $message"

            // Tor reports "host unreachable" for a service that is not running,
            // which is the ordinary case of a friend having the app closed.
            Regex("offline|unreachable", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
                "$where — their torchat is not running."

            else -> "$where — $message"
        }
    }

    /**
     * Try each known address, staggered, and resolve with the first link that
     * comes up.
     *
     * Friends almost always have exactly one address; this matters only just
     * after someone's has changed and both the old and the new are on record.
     */
    suspend fun raceEndpoints(
        endpoints: List<Endpoint>,
        dial: suspend (Endpoint) -> Link,
        onFailure: (Endpoint, Throwable) -> Unit = { _, _ -> },
    ): Link? = coroutineScope {
        val winner = CompletableDeferred<Link?>()

        val attempts = endpoints.mapIndexed { index, endpoint ->
            async {
                delay(index * Constants.DIAL_STAGGER_MS)
                if (winner.isCompleted) return@async

                try {
                    val link = dial(endpoint)
                    // A straggler that won a race already decided. Nothing will
                    // adopt it, so it is closed here rather than leaked.
                    if (!winner.complete(link)) link.close("a faster address won")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    onFailure(endpoint, failure)
                }
            }
        }

        launch {
            attempts.awaitAll()
            winner.complete(null)
        }

        winner.await().also { attempts.forEach { attempt -> attempt.cancel() } }
    }
}

/**
 * How long to leave a friend alone after a failed attempt, and whether their
 * latest failure is worth mentioning again.
 *
 * 15 s, 30 s, 60 s, 120 s, 240 s, then capped at 5 minutes. Reset on any
 * successful connection (§10.4).
 */
class RetryPolicy(private val now: () -> Long = System::currentTimeMillis) {

    private val attempts = mutableMapOf<String, Int>()
    private val nextTry = mutableMapOf<String, Long>()
    private val lastFailure = mutableMapOf<String, String>()

    fun penalise(id: String) {
        val count = (attempts[id] ?: 0) + 1
        attempts[id] = count

        val delay = minOf(
            Constants.MAX_BACKOFF_MS,
            Constants.FIRST_BACKOFF_MS shl (count - 1).coerceAtMost(20),
        )
        nextTry[id] = now() + delay
    }

    fun isDue(id: String, at: Long = now()): Boolean = (nextTry[id] ?: 0L) <= at

    fun nextTryAt(id: String): Long = nextTry[id] ?: 0L

    fun reset(id: String) {
        attempts.remove(id)
        nextTry.remove(id)
        lastFailure.remove(id)
    }

    /**
     * Report a failure only when the reason **changes** — otherwise a permanently
     * offline friend spams the log forever.
     */
    fun shouldReport(id: String, signature: String): Boolean {
        if (lastFailure[id] == signature) return false
        lastFailure[id] = signature
        return true
    }
}
