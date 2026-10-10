package com.torchat.core.port

import kotlinx.coroutines.flow.SharedFlow

/**
 * What [TorProvider.publish] came back with.
 *
 * Note what is *not* here: whether the descriptor has been uploaded. `ADD_ONION`
 * returns as soon as the service exists locally — in milliseconds — but nobody
 * can reach it until the descriptor reaches the hidden-service directories, which
 * takes tens of seconds. Those are two different moments, and folding them into
 * one return value meant startup blocked on the second: the address existed the
 * whole time and the app refused to use it. Reachability arrives later, as
 * [TorEvent.DescriptorPublished].
 */
data class OnionService(
    val address: String,
    /** Non-null on first creation only. Persist it immediately. */
    val key: String?,
)

sealed interface TorEvent {
    data class Log(val line: String) : TorEvent
    data class Bootstrap(val percent: Int) : TorEvent

    /**
     * Our descriptor reached the hidden-service directories: friends can now
     * reach us. Until this arrives we can still dial *out* perfectly well —
     * the descriptor governs inbound reachability only.
     */
    data object DescriptorPublished : TorEvent

    /**
     * Tor died. Fatal to connectivity, not something to route around — there is
     * nothing to fall back to, and falling back would mean connecting over a
     * plain IP (§10.6).
     */
    data object Down : TorEvent
}

/**
 * The two things the core needs from Tor, and nothing else (§6).
 *
 * This is the **only** platform-specific interface in the system. Everything
 * above it is portable logic; porting to a new platform means implementing this
 * and re-expressing the rest.
 */
interface TorProvider {

    /** Bring Tor up. Returns when bootstrapping reaches 100%. */
    suspend fun start()

    /**
     * Publish an onion service forwarding `virtualPort` to `127.0.0.1:localPort`.
     *
     * **Returns as soon as the address exists**, not when it is reachable —
     * watch [events] for [TorEvent.DescriptorPublished]. Pass the saved key to
     * keep the same address across restarts. The service must be created
     * **without the `Detach` flag**, so that it dies with the control connection
     * — quitting should take the onion down, not leave it advertised and
     * unanswered, which looks to a friend exactly like being ignored.
     */
    suspend fun publish(localPort: Int, virtualPort: Int, existingKey: String?): OnionService

    /**
     * Open a stream to a peer's onion address, through Tor.
     *
     * The address goes to the SOCKS proxy **as a domain name**, never as a
     * resolved IP: Tor resolves the `.onion` inside the network, and this device
     * never performs a lookup that could leak who it is trying to reach.
     */
    suspend fun dial(host: String, port: Int, timeoutMs: Long): Stream

    fun stop()

    val isReady: Boolean

    val events: SharedFlow<TorEvent>
}
