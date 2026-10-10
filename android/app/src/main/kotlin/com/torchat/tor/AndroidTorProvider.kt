package com.torchat.tor

import android.content.Context
import android.util.Log
import com.torchat.core.Constants
import com.torchat.core.port.OnionService
import com.torchat.core.port.Stream
import com.torchat.core.port.TorEvent as CoreTorEvent
import com.torchat.core.port.TorProvider
import com.torchat.core.wire.DialFailure
import com.torchat.core.wire.LinkException
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.NetworkObserver
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnFailure
import io.matthewnelson.kmp.tor.runtime.core.OnSuccess
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.HiddenServiceEntry
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3.PrivateKey.Companion.toED25519_V3PrivateKeyOrNull
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tor on Android, via kmp-tor (§6, §13).
 *
 * The binary ships inside the APK's JNI lib directory as `libtor.so`, because
 * Android 10+ blocks executing anything from the app data directory — the
 * desktop client's "download tor at install time" approach does not port.
 *
 * This is the **only** platform-specific class in the system. Everything above
 * it is the portable protocol.
 */
class AndroidTorProvider(
    context: Context,
    /**
     * Turns the phone's connectivity into Tor's `DisableNetwork`. Without it Tor
     * learns that wifi has gone only when its sockets time out, minutes later.
     */
    private val networkObserver: NetworkObserver? = null,
) : TorProvider {

    private val appContext = context.applicationContext

    /**
     * No replay. One provider outlives many engines — it is process-wide,
     * because kmp-tor allows one runtime per working directory — and a replayed
     * event belongs to whichever engine was listening when it happened. A fresh
     * engine handed the last one's `Down` would tear itself down on arrival, and
     * one handed a stale `DescriptorPublished` would report itself reachable
     * before it had published anything. The engine subscribes before it starts
     * Tor, so nothing it needs is emitted before it is listening.
     */
    private val _events = MutableSharedFlow<CoreTorEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CoreTorEvent> = _events.asSharedFlow()

    @Volatile
    private var socksPort: Int? = null

    @Volatile
    private var started = false

    /**
     * Where to dial through, re-read rather than remembered once.
     *
     * The LISTENERS observer and `startDaemonAsync` returning are not ordered
     * with respect to each other: measured here, the daemon reported ready with
     * the SOCKS listener already open on 127.0.0.1:43383, and `listeners()` still
     * came back empty for the snapshot taken a microsecond later. Caching that
     * one reading left the port null forever, and every dial then failed with
     * "tor is not running" — reported to the user as the *friend* being offline.
     */
    private val currentSocksPort: Int?
        get() = socksPort
            ?: runtime.listeners().socks.firstOrNull()?.port?.value?.also { socksPort = it }

    /** Ready means bootstrapped *and* we know where to dial through. */
    override val isReady: Boolean get() = started && currentSocksPort != null

    /** The onion service we published, once ADD_ONION has told us its address. */
    @Volatile
    private var serviceId: String? = null

    private val environment: TorRuntime.Environment by lazy {
        TorRuntime.Environment.Builder(
            workDirectory = File(appContext.filesDir, "tor"),
            // NOT cacheDir. Tor's consensus and microdescriptors live here —
            // several megabytes that take the whole slow half of a bootstrap to
            // download. Android evicts cacheDir under storage pressure, and
            // losing it turns every subsequent start back into a cold one.
            // Measured: cold start minutes, warm start about four seconds.
            cacheDirectory = File(appContext.noBackupFilesDir, "tor-cache"),
            loader = ResourceLoaderTorExec::getOrCreate,
        )
    }

    private val runtime: TorRuntime by lazy {
        TorRuntime.Builder(environment) {
            // Registering an observer is not enough: tor only emits an event
            // class once it has been asked to via SETEVENTS, and this is what
            // puts HS_DESC in that list. Without it the observer below is
            // attached to a stream that never carries anything, and the app
            // waits forever for a descriptor confirmation that tor was never
            // told to send. NOTICE is always enabled by kmp-tor, which is why
            // bootstrap logging worked while this did not.
            required(TorEvent.HS_DESC)

            networkObserver?.let { this.networkObserver = it }

            config {
                // Nothing else on the device should be able to use us as a proxy.
                TorOption.SocksPort.configure {
                    auto()
                }

                // Phone flash is slow and this saves a good deal of churn during
                // the consensus download.
                TorOption.AvoidDiskWrites.configure(true)
            }

            // Bootstrap progress, so a long first start looks like progress
            // rather than a hang. On a phone hotspot this was measured at over
            // two minutes.
            observerStatic(RuntimeEvent.STATE) { state ->
                val percent = state.daemon.bootstrap.toInt() and 0xFF
                _events.tryEmit(CoreTorEvent.Bootstrap(percent))

                // Tor dying later is not hypothetical: an OOM reaper or an OEM
                // battery manager will do it. Without noticing, every dial would
                // go to a dead SOCKS port and the failure would be reported as
                // the *friend* being offline.
                if (started && state.daemon.isOff) {
                    started = false
                    socksPort = null
                    _events.tryEmit(CoreTorEvent.Down)
                }
            }

            observerStatic(RuntimeEvent.LISTENERS) { listeners ->
                socksPort = listeners.socks.firstOrNull()?.port?.value
            }

            observerStatic(RuntimeEvent.LOG.WARN) { line ->
                _events.tryEmit(CoreTorEvent.Log("tor: $line"))
            }

            // Tor's own notices, which carry the bootstrap detail. Without them
            // a stalled start is a spinner and nothing else — and a first
            // bootstrap on a slow link genuinely takes minutes, so "slow" and
            // "wedged" have to be tellable apart.
            observerStatic(TorEvent.NOTICE) { line ->
                Log.i(TAG, "notice: $line")
            }

            // Subscribed for the lifetime of the runtime, not for the duration
            // of one publish call. A descriptor that lands late — after any
            // wait would have given up — still has to be noticed, or the app
            // reports itself unreachable forever while it is in fact reachable.
            observerStatic(TorEvent.HS_DESC) { line ->
                Log.i(TAG, "hs_desc: $line")
                onHsDesc(line)
            }
        }
    }

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            _events.tryEmit(CoreTorEvent.Log("tor: starting (a first run can take a few minutes)"))
            Log.i(TAG, "startDaemonAsync: waiting for the daemon")
            runtime.startDaemonAsync()
            started = true
            Log.i(TAG, "startDaemonAsync: returned")

            // The daemon is up, but the listener event that tells us the SOCKS
            // port may land a moment later — so this is a read, not a snapshot.
            Log.i(TAG, "socks port = $currentSocksPort")
            _events.tryEmit(CoreTorEvent.Log("tor: bootstrapped"))
        }
    }

    /**
     * Publish the onion service pointing at our local listener.
     *
     * Created **without the `Detach` flag**, so the service dies with the control
     * connection: quitting should take the onion down rather than leave it
     * advertised and unanswered, which looks to a friend exactly like being
     * ignored (§6.1).
     */
    override suspend fun publish(
        localPort: Int,
        virtualPort: Int,
        existingKey: String?,
    ): OnionService = withContext(Dispatchers.IO) {
        // Tolerates Tor's "ED25519-V3:" spelling, which is how the desktop client
        // stores the key and so how a restored desktop backup arrives.
        val saved = existingKey?.removePrefix("ED25519-V3:")?.toED25519_V3PrivateKeyOrNull()
        if (existingKey != null && saved == null) {
            // A saved key that will not parse must not be treated as "no key":
            // the service is recreated, but the freshly generated key was then
            // discarded rather than stored, so the address changed again on
            // every launch and no friend's card ever stayed valid.
            Log.w(TAG, "the saved onion key could not be read — creating a new address")
        }

        val command = if (saved != null) {
            TorCmd.Onion.Add.existing(saved) {
                port(virtualPort.toPort()) { target(localPort.toPort()) }
                // The key is ours and outlives the command; kmp-tor would
                // otherwise wipe it when the job completes.
                destroyKeyOnJobCompletion(false)
            }
        } else {
            TorCmd.Onion.Add.new(ED25519_V3) {
                port(virtualPort.toPort()) { target(localPort.toPort()) }
                destroyKeyOnJobCompletion(false)
            }
        }

        Log.i(TAG, "ADD_ONION: sending")
        val entry = try {
            enqueue(command)
        } catch (failure: Exception) {
            // The daemon outlived whatever made us publish again, and the
            // service from last time is still up — it dies only with the control
            // connection, and that never dropped. Same key, so same address:
            // that is a success, not an error to retry forever.
            val existing = serviceId
            if (existing != null && saved != null && COLLISION.containsMatchIn(failure.message.orEmpty())) {
                Log.i(TAG, "ADD_ONION: already published as $existing.onion — reusing it")
                return@withContext OnionService(address = "$existing.onion", key = null)
            }
            throw failure
        }

        // `OnionAddress.value` is the bare 56-character service ID — kmp-tor
        // does not include the suffix, where tor's own control reply and the
        // reference client both use "<id>.onion". Publishing the bare form put
        // a host in every card that fails `^[a-z2-7]{56}\.onion$`, so every card
        // an Android device produced was rejected by whoever received it with
        // "that code has no onion address". Normalised here, at the one place
        // the address enters the system.
        val id = entry.publicKey.address().value.removeSuffix(".onion")
        val address = "$id.onion"
        Log.i(TAG, "ADD_ONION: got $address")

        // Recorded before returning, so the HS_DESC observer — which has been
        // running since the runtime came up — can recognise our uploads from
        // this moment on.
        serviceId = id

        OnionService(
            address = address,
            // Returned whenever a key was actually created — decided by whether
            // we reused one, not by whether one was offered. The caller persists
            // it, because the address is derived from it and losing it points
            // every friend's saved card at a service that no longer exists.
            key = if (saved == null) entry.privateKey?.base64() else null,
        )
    }

    /**
     * A hidden-service directory event.
     *
     * The payload here is **not** a raw control line: kmp-tor trims the event
     * name before dispatching to a typed observer, so what arrives is
     * `UPLOADED <address> <authType> <HsDir>` and never the string `HS_DESC`.
     * The first version of this matched `HS_DESC\s+UPLOADED` — ported from the
     * reference client, which reads raw lines — and therefore could never fire.
     * Every startup burned the full descriptor timeout and then reported itself
     * unreachable, permanently.
     *
     * This process publishes exactly one hidden service, so any upload is ours
     * once we have an address. That avoids depending on which field tor puts the
     * address in, which varies between versions.
     */
    private fun onHsDesc(line: String) {
        if (serviceId == null) return
        if (!UPLOADED.containsMatchIn(line)) return

        _events.tryEmit(CoreTorEvent.DescriptorPublished)
    }

    override suspend fun dial(host: String, port: Int, timeoutMs: Long): Stream {
        val socks = currentSocksPort
            ?: throw LinkException("tor is not running", DialFailure.NO_TOR)

        return withContext(Dispatchers.IO) {
            Socks5.connect("127.0.0.1", socks, host, port, timeoutMs)
        }
    }

    override fun stop() {
        started = false
        socksPort = null
    }

    /** The orderly shutdown, for callers that can suspend. */
    suspend fun stopAndWait() {
        stop()
        runCatching { withContext(Dispatchers.IO) { runtime.stopDaemonAsync() } }
    }

    /**
     * Bridge kmp-tor's callback `enqueue` to a suspending call.
     *
     * Used rather than a convenience extension because `enqueue` is on the
     * interface itself and so cannot move underneath us.
     */
    private suspend fun enqueue(command: TorCmd.Unprivileged<HiddenServiceEntry>): HiddenServiceEntry =
        suspendCancellableCoroutine { continuation ->
            val job = runtime.enqueue(
                command,
                OnFailure { failure ->
                    if (continuation.isActive) continuation.resumeWithException(failure)
                },
                OnSuccess { entry ->
                    if (continuation.isActive) continuation.resume(entry)
                },
            )
            continuation.invokeOnCancellation { runCatching { job.cancel(null) } }
        }

    private companion object {
        const val TAG = "TorChat"

        /**
         * The action word of an HS_DESC payload, which is the first token —
         * the event name has already been trimmed off by the time we see it.
         */
        val UPLOADED = Regex("""^\s*UPLOADED\b""", RegexOption.IGNORE_CASE)

        /** Tor's reply when a service with this key is already running. */
        val COLLISION = Regex("""\b550\b.*collision""", RegexOption.IGNORE_CASE)
    }
}
