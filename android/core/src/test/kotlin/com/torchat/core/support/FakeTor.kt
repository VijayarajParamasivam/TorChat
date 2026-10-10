package com.torchat.core.support

import com.torchat.core.crypto.Base64Url
import com.torchat.core.crypto.Crypto
import com.torchat.core.net.SocketStream
import com.torchat.core.port.OnionService
import com.torchat.core.port.Stream
import com.torchat.core.port.TorEvent
import com.torchat.core.port.TorProvider
import com.torchat.core.wire.DialFailure
import com.torchat.core.wire.LinkException
import java.net.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A stand-in Tor network for engine tests.
 *
 * Onion addresses are registered against the loopback port their owner is
 * listening on, and a "dial" is a plain TCP connection to that port. Every layer
 * above the provider — handshake, sealed channel, delivery contract, dial policy
 * — runs exactly as it does in production; only the six relays are missing.
 *
 * This is the fake dialler §15 calls for: the whole engine, tested with no
 * network.
 */
class FakeTorNetwork {

    private val services = mutableMapOf<String, Int>()

    /** Refuse every dial, as a Tor that has fallen over would. */
    @Volatile
    var reachable: Boolean = true

    fun provider(): FakeTorProvider = FakeTorProvider(this)

    @Synchronized
    fun register(address: String, localPort: Int) {
        services[address] = localPort
    }

    @Synchronized
    fun portFor(address: String): Int? = services[address]
}

class FakeTorProvider(private val network: FakeTorNetwork) : TorProvider {

    // Replays, because publish() emits before the engine's collector is
    // guaranteed to be attached and a dropped event would make the test flaky
    // rather than failing honestly.
    private val _events = MutableSharedFlow<TorEvent>(replay = 8, extraBufferCapacity = 32)
    override val events: SharedFlow<TorEvent> = _events.asSharedFlow()

    override var isReady: Boolean = false
        private set

    var address: String? = null
        private set

    /** Return the bare service ID, as kmp-tor's `OnionAddress.value` does. */
    var malformedAddress: Boolean = false

    /** How many times the engine has asked Tor to start. */
    @Volatile
    var starts = 0
        private set

    override suspend fun start() {
        starts++
        isReady = true
        _events.tryEmit(TorEvent.Bootstrap(100))
    }

    override suspend fun publish(
        localPort: Int,
        virtualPort: Int,
        existingKey: String?,
    ): OnionService {
        // A v3 onion address is 56 base32 characters; any stable string of the
        // right shape will do here, derived from the key so a restart with the
        // same key keeps the same address.
        val key = existingKey ?: "ED25519-V3:" + Base64Url.encode(Crypto.randomBytes(32))
        val digest = Crypto.hkdf(key.toByteArray(), ByteArray(0), ByteArray(0), 35)
        val serviceId = base32(digest).take(56)
        val host = if (malformedAddress) serviceId else "$serviceId.onion"

        address = host
        network.register(host, localPort)

        // The real provider returns as soon as the address exists and reports
        // reachability later, as an event. Mirrored here so the engine is tested
        // against the same ordering it will meet in production.
        _events.tryEmit(TorEvent.DescriptorPublished)

        return OnionService(address = host, key = if (existingKey == null) key else null)
    }

    override suspend fun dial(host: String, port: Int, timeoutMs: Long): Stream {
        if (!isReady || !network.reachable) {
            throw LinkException("tor is not running", DialFailure.NO_TOR)
        }

        val target = network.portFor(host)
            ?: throw LinkException("host unreachable — the onion service is probably offline", DialFailure.UNREACHABLE)

        return SocketStream(Socket("127.0.0.1", target))
    }

    override fun stop() {
        isReady = false
    }

    /**
     * What `DisableNetwork` does to a real Tor: the SOCKS listener closes, so
     * nothing can be dialled — but the daemon is alive and says nothing.
     */
    fun pauseNetwork() {
        isReady = false
    }

    fun resumeNetwork() {
        isReady = true
    }

    fun goDown() {
        isReady = false
        _events.tryEmit(TorEvent.Down)
    }

    private companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

        fun base32(bytes: ByteArray): String = buildString {
            var buffer = 0L
            var bits = 0
            for (byte in bytes) {
                buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    append(ALPHABET[((buffer shr bits) and 0x1F).toInt()])
                }
            }
        }
    }
}
