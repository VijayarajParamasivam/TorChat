package com.torchat.core.engine

import com.torchat.core.Constants
import com.torchat.core.card.Card
import com.torchat.core.card.CardException
import com.torchat.core.card.PeerCard
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Friend
import com.torchat.core.model.FriendState
import com.torchat.core.model.Identity
import com.torchat.core.model.Message
import com.torchat.core.net.LoopbackListener
import com.torchat.core.port.IdentityStore
import com.torchat.core.port.SettingsStore
import com.torchat.core.port.Store
import com.torchat.core.port.Stream
import com.torchat.core.port.TorEvent
import com.torchat.core.port.TorProvider
import com.torchat.core.wire.DialFailure
import com.torchat.core.wire.Link
import com.torchat.core.wire.LinkContext
import com.torchat.core.wire.LinkException
import com.torchat.core.wire.handshakeOverStream
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The outcome of a manual "try now". */
data class ConnectResult(val ok: Boolean, val reasons: List<String>)

/**
 * The engine. Owns the friend list, keeps links to everyone alive, and moves
 * messages between the application and the wire (§10).
 *
 * Every connection is an onion service. There is no second path and no fallback,
 * which is the point: no IP address is ever published, announced or dialled. A
 * friend knows your onion address and nothing else, and an onion address is a
 * public key rather than a location. That is not a mode that can be switched off
 * — it is the only thing the code can do.
 *
 * Connections are symmetric: either side may dial, and whoever gets there first
 * wins.
 *
 * **A peer that dials in is not accepted automatically.** §9.3 originally said
 * it should be, reasoning that the only way it could know your address is that
 * you handed it your card — which is true, and still leaves the card as a
 * standing invitation that cannot be withdrawn. Removing someone lasted only
 * until their next redial, when they were silently accepted again. So a stranger
 * that proves its identity becomes a *request* ([FriendState.PENDING]), and
 * nothing is dialled, delivered or announced until the person using this app
 * accepts it. Pasting someone's code is itself that decision, so a friendship
 * you start needs no second confirmation.
 *
 * **Concurrency.** All mutable state — the link map, the dialling set, the friend
 * book, the retry policy — is touched only on [engineContext], a single-threaded
 * dispatcher. That reproduces the atomicity the reference implementation gets for
 * free from JavaScript's event loop. Suspending calls still interleave at their
 * suspension points, exactly as `await` does there, so the guards around dialling
 * and adoption are the same guards for the same reasons.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Engine(
    initialIdentity: Identity,
    private val store: Store,
    private val settings: SettingsStore,
    private val tor: TorProvider,
    parentScope: CoroutineScope? = null,
    /** Where a profile edit is persisted. Optional, because tests have nowhere to put it. */
    private val identities: IdentityStore? = null,
) {

    /** Replaced only by [setProfile]; the keys inside it never change. */
    @Volatile
    private var identity: Identity = initialIdentity

    /** Who we are right now, as a friend would see it. */
    val profile: com.torchat.core.model.Profile get() = identity.profile

    private val job = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val scope = CoroutineScope(job + Dispatchers.IO + CoroutineName("torchat-engine"))

    /** Serialises every mutation. See the note on concurrency above. */
    private val engineContext = Dispatchers.Default.limitedParallelism(1) + job

    /**
     * Events are emitted with `tryEmit` from code that cannot suspend, and with
     * a small buffer a single slow collector made that fail for *every*
     * collector. A first Tor bootstrap logs a few hundred lines; a UI busy
     * re-querying on each of them filled the old 64-slot buffer, and the
     * `MessageReceived` that should have raised a notification was simply never
     * delivered to anyone. The buffer is now deep enough that only a collector
     * that has stopped altogether can lose anything, and then it loses the
     * oldest — never the event being emitted now.
     */
    private val _events = MutableSharedFlow<EngineEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    /**
     * Who has a live link right now. State, not events, for the same reason as
     * [card]: a screen that attaches late must still see who is online.
     */
    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    /**
     * Our contact code, or null until there is an address to put in it.
     *
     * A StateFlow rather than something the UI refreshes when told to. [events]
     * is a `replay = 0` SharedFlow emitted with `tryEmit`, so a notification
     * published before a collector attaches is dropped — and the observer that
     * missed it went on showing a card built before the onion address existed.
     * That card carries an empty endpoint list, and the person it is sent to is
     * told "that code has no onion address". State that is always readable
     * cannot go stale that way.
     */
    private val _card = MutableStateFlow<String?>(null)
    val card: StateFlow<String?> = _card.asStateFlow()

    private val links = mutableMapOf<String, Link>()
    private val dialing = mutableSetOf<String>()
    private val retry = RetryPolicy()

    private val book = FriendBook(store) { emit(EngineEvent.FriendsChanged) }
    private val messenger = Messenger(store, links::get, ::emit)

    private var listener: LoopbackListener? = null

    /** Set once by [stop]. Nothing may be restarted after it. */
    @Volatile
    private var stopped = false

    /** The pending attempt to bring a dead Tor back, if there is one. */
    private var torRestart: Job? = null
    private var torRestartAttempts = 0

    /**
     * Tor reported itself dead ([TorEvent.Down]) and has not been brought back.
     *
     * The only thing that triggers a restart. `tor.isReady` is not the same
     * question: Tor closes its SOCKS listener while the network is disabled, so
     * a phone hopping between networks reads "not ready" for a few seconds with
     * the daemon perfectly alive — and restarting it then collided with the
     * onion service it was still running ("550 Onion address collision"),
     * failing over and over while the real daemon carried on underneath.
     */
    @Volatile
    private var torDown = false

    @Volatile
    private var onion: String? = null
    @Volatile
    private var cardCode: String? = null

    // --- lifecycle ----------------------------------------------------------

    /**
     * Load state, bind the local listener, bring Tor up, publish the onion, and
     * start dialling.
     *
     * Failure to start Tor is fatal and deliberately so. There is nothing to fall
     * back to — falling back would mean connecting over a plain IP, which is the
     * one thing this design promises never to do.
     */
    suspend fun start() = withContext(engineContext) {
        book.load()
        refreshStatus()

        // Loopback, ephemeral port. The only thing that should ever connect here
        // is our own Tor, forwarding from the onion service (§6.3).
        val bound = LoopbackListener.bind()
        listener = bound
        scope.launch { acceptLoop(bound) }

        scope.launch { watchTor() }

        try {
            startTor(bound.port)
        } catch (failure: Throwable) {
            // Leaving the socket bound would leak it for the life of the process.
            bound.close()
            listener = null
            throw failure
        }

        scope.launch { redialLoop() }
        redialAll()
    }

    private suspend fun startTor(localPort: Int) {
        tor.start()

        // start() returns once bootstrapping is complete, so say so here rather
        // than relying on the provider's last progress event having reached us.
        // A missed "100%" left the status stuck at "connecting" on a node that
        // was fully up.
        _status.value = _status.value.copy(bootstrapPercent = 100)

        // Returns in milliseconds. Reachability comes later, as an event — see
        // TorEvent.DescriptorPublished. Waiting for it here meant the address
        // existed for two minutes while the app behaved as though it did not,
        // and any card shared in that window carried an empty endpoint list.
        val service = tor.publish(localPort, Constants.ONION_PORT, settings.onionKey)

        // Checked against the same rule that guards the friend list, because a
        // provider returning something subtly wrong — a bare service ID with no
        // ".onion" suffix, say — is otherwise invisible: the address looks set,
        // the card is built, and only the person receiving it finds out it can
        // never be dialled.
        if (!Endpoint.isOnion(service.address)) {
            throw IllegalStateException(
                "tor returned an address that is not a v3 onion: ${service.address}"
            )
        }

        onion = service.address

        // The address is derived from this key. Lose it and every friend's copy
        // of your card points at a service that no longer exists.
        service.key?.let { settings.onionKey = it }

        rebuildCard()
        refreshStatus()

        log("your address is ${service.address}")
        log("waiting for it to publish — you can message friends now; they can reach you once it has")
    }

    private suspend fun watchTor() {
        tor.events.collect { event ->
            when (event) {
                is TorEvent.Log -> log(event.line)

                is TorEvent.Bootstrap -> withContext(engineContext) {
                    _status.value = _status.value.copy(bootstrapPercent = event.percent)
                    // Through refreshStatus, so `torRunning` is re-read rather
                    // than left at whatever it was when the last phase changed.
                    refreshStatus()
                }

                TorEvent.DescriptorPublished -> withContext(engineContext) {
                    if (!_status.value.published) {
                        log("your address is published — friends can reach you now")
                    }
                    refreshStatus(published = true)
                }

                // Tor dying takes every link with it. Tear them down explicitly
                // so each friend is reported offline once, rather than looking
                // online while every send silently fails (§10.6).
                TorEvent.Down -> withContext(engineContext) {
                    log("tor went down — every connection is gone until it is back")
                    for (link in links.values.toList()) link.close("tor went down")
                    links.clear()
                    // Back to the start of the phase ladder: what follows is a
                    // fresh bootstrap, and showing "reachable" through it would
                    // be a lie about the one thing the status line is for.
                    _status.value = _status.value.copy(bootstrapPercent = 0)
                    torDown = true
                    refreshStatus(published = false)
                    scheduleTorRestart()
                }
            }
        }
    }

    /**
     * Bring a dead Tor back, with backoff.
     *
     * §10.6 says Tor going down is fatal to connectivity, and it is — there is
     * nothing to fall back to. It does not have to be fatal to the *session*.
     * An OOM reaper, an OEM battery manager or a crashed daemon took the whole
     * app offline until somebody noticed and relaunched it; the fix is the same
     * thing a person would do, done immediately. The onion key is reused, so the
     * address every friend holds stays valid.
     */
    private fun scheduleTorRestart() {
        if (stopped || !torDown || torRestart?.isActive == true) return
        val bound = listener ?: return

        val wait = TOR_RESTART_DELAYS_MS[
            torRestartAttempts.coerceAtMost(TOR_RESTART_DELAYS_MS.lastIndex)
        ]
        torRestartAttempts++
        _status.value = _status.value.copy(torRestarting = true)
        log("restarting tor in ${wait / 1000}s")

        torRestart = scope.launch {
            delay(wait)
            val failure = runCatching { withContext(engineContext) { startTor(bound.port) } }
                .exceptionOrNull()

            withContext(engineContext) {
                torRestart = null
                if (failure != null) {
                    log("tor did not come back: ${failure.message ?: "unknown error"}")
                    scheduleTorRestart()
                    return@withContext
                }

                torRestartAttempts = 0
                torDown = false
                _status.value = _status.value.copy(torRestarting = false)
                log("tor is back — reconnecting")
                refreshStatus()
                // Everyone failed while Tor was down, through no fault of
                // theirs. Waiting out a backoff earned that way helps nobody.
                for (friend in book.all) retry.reset(friend.id)
                redialAll()
            }
        }
    }

    /** Restart Tor now, unless it is already running or already coming back. */
    suspend fun restartTor() = withContext(engineContext) {
        if (!torDown || stopped) return@withContext
        torRestart?.cancel()
        torRestart = null
        torRestartAttempts = 0
        scheduleTorRestart()
    }

    suspend fun stop() {
        stopped = true
        withContext(engineContext) {
            torRestart?.cancel()
            for (link in links.values.toList()) link.close("shutting down")
            links.clear()
            _online.value = emptySet()
        }
        listener?.close()
        listener = null
        tor.stop()
        store.flush()
        scope.cancel()
    }

    // --- the platform's view of the world -----------------------------------

    /**
     * The device's network came, went, or changed.
     *
     * Tor cannot see this — it only notices when its sockets start failing,
     * which takes minutes — and neither can a link, which only notices after a
     * minute of silence. Meanwhile every friend failed a dial or two and was
     * sent to the back of a backoff that could be five minutes long, so a phone
     * that walked out of wifi range stayed "offline" long after it had mobile
     * data again.
     *
     * @param changed a *different* network than before — wifi to mobile, say —
     *   rather than the same one reconnecting. Every existing circuit was built
     *   over the old one.
     */
    suspend fun onNetworkChanged(available: Boolean, changed: Boolean = false) =
        withContext(engineContext) {
            val was = _status.value.networkAvailable
            _status.value = _status.value.copy(networkAvailable = available)

            if (!available) {
                if (was) log("no network — messages will wait in your outbox")
                refreshStatus()
                return@withContext
            }

            if (!was) log("network is back — reconnecting to everyone")
            refreshStatus()
            if (was && !changed) return@withContext

            // Links over the old network are almost certainly dead; ask rather
            // than assume, so one that survived the switch is kept.
            for (link in links.values.toList()) link.probe(PROBE_TIMEOUT_MS)
            for (friend in book.all) retry.reset(friend.id)
            if (torDown) scheduleTorRestart()

            scope.launch {
                // Give Tor a moment to notice the new route before asking it
                // for circuits, or the first round of dials all time out.
                delay(NETWORK_SETTLE_MS)
                redialAll()
            }
        }

    /**
     * A periodic wake-up from the platform — an alarm while the device sleeps.
     *
     * Checks the links are really alive, dials anyone who is due, and restarts
     * Tor if it is down and nothing is already bringing it back. Cheap when
     * everything is healthy: one ping per link.
     */
    suspend fun nudge() = withContext(engineContext) {
        if (stopped) return@withContext
        for (link in links.values.toList()) link.probe(PROBE_TIMEOUT_MS)
        if (torDown) scheduleTorRestart() else redialAll()
    }

    /**
     * Dial everyone who is not connected, now, ignoring backoff. What a person
     * means by "reconnect" or pull-to-refresh.
     */
    suspend fun reconnectAll() = withContext(engineContext) {
        if (stopped) return@withContext
        for (friend in book.all) retry.reset(friend.id)
        if (torDown) scheduleTorRestart() else redialAll()
    }

    // --- identity and card --------------------------------------------------

    private fun context(): LinkContext = LinkContext(
        identity = identity.profile,
        signPrivate = identity.sign.private,
        boxPrivate = identity.box.private,
        cardCode = cardCode ?: rebuildCard(),
    )

    /**
     * Where friends reach us. Exactly one address, and it is not a location.
     *
     * Nothing else is ever added here. This function being this short is the
     * whole privacy guarantee — there is no branch that could leak an IP, because
     * there is no IP in the program to leak.
     */
    private fun localEndpoints(): List<Endpoint> =
        onion?.let { listOf(Endpoint.onion(it, Constants.ONION_PORT)) } ?: emptyList()

    private fun rebuildCard(): String =
        Card.create(identity, localEndpoints()).also {
            cardCode = it
            // Only published once it is worth sharing. A card with no endpoint
            // is fine for a handshake — the peer reads our keys from it — but
            // handing one to a friend gives them nothing to dial.
            _card.value = if (onion != null) it else null
        }

    suspend fun myCard(): String = withContext(engineContext) { rebuildCard() }

    /**
     * Rename ourselves: persisted, put in the card, and told to everyone who is
     * connected (§7.6 `profile`). Friends who are offline pick the new name up
     * from the card on their next connection.
     */
    suspend fun setProfile(name: String?, sigil: String?): Identity = withContext(engineContext) {
        identity = identity.withProfile(name, sigil)
        identities?.write(identity)
        rebuildCard()

        val frame = buildJsonObject {
            put("t", "profile")
            put("name", identity.name)
            put("sigil", identity.sigil)
        }
        for (link in links.values) link.send(frame)

        log("you are now ${identity.name}")
        identity
    }

    // --- friends ------------------------------------------------------------

    /**
     * Add someone from a pasted contact code, then try them immediately.
     *
     * @throws CardException with a message written for the person reading it.
     */
    suspend fun addFriend(input: String?): Friend = withContext(engineContext) {
        val text = (input ?: "").trim()
        if (!text.replace(Regex("\\s+"), "").startsWith(Card.PREFIX)) {
            throw CardException("paste their full ${Card.PREFIX}… code")
        }

        val peer = Card.parse(text)
        if (peer.id == identity.id) throw CardException("that is your own code")

        if (peer.endpoints.none { Endpoint.isOnion(it.host) }) {
            throw CardException("that code has no onion address — they need to send a new one")
        }

        val (upserted, isNew) = book.upsert(peer)

        // Pasting someone's code is an explicit decision, so it settles any
        // earlier one: a peer you had blocked, or one sitting in the request
        // list, becomes a friend without a second confirmation.
        val friend =
            if (upserted.isAccepted) upserted else book.setState(peer.id, FriendState.ACCEPTED)!!

        log("${if (isNew) "added" else "updated"} ${friend.name} ${friend.id}")
        refreshStatus()

        scope.launch { connect(friend) }
        friend
    }

    /**
     * Accept a pending request: they become a friend, and we dial them.
     *
     * We dial rather than wait, because they are demonstrably reachable — they
     * were dialling us moments ago — and their outbox is holding whatever they
     * tried to say while they waited.
     */
    suspend fun acceptRequest(id: String): Boolean = withContext(engineContext) {
        val friend = book.setState(id, FriendState.ACCEPTED) ?: return@withContext false

        log("accepted ${friend.name}")
        retry.reset(id)
        refreshStatus()
        scope.launch { connect(friend) }
        true
    }

    /** Refuse a pending request. They are dropped silently from then on. */
    suspend fun rejectRequest(id: String): Boolean = withContext(engineContext) {
        val friend = book.setState(id, FriendState.BLOCKED) ?: return@withContext false

        links[id]?.close("blocked")
        log("rejected ${friend.name}")
        refreshStatus()
        true
    }

    /**
     * Remove a friend.
     *
     * Blocked rather than deleted. Deleting only forgot them: they still held
     * our card, kept redialling, and were accepted again as if nothing had
     * happened. [unblock] is the way back.
     */
    suspend fun removeFriend(id: String): Boolean = withContext(engineContext) {
        links[id]?.close("removed")
        val friend = book.setState(id, FriendState.BLOCKED) ?: return@withContext false

        log("removed ${friend.name}")
        refreshStatus()
        true
    }

    /**
     * Forget a blocked peer entirely.
     *
     * The record goes, so the next time they dial in they arrive as a fresh
     * request rather than silently reconnecting — which is the difference
     * between changing your mind and never having decided.
     */
    suspend fun unblock(id: String): Boolean = withContext(engineContext) {
        book.delete(id).also { refreshStatus() }
    }

    suspend fun friends(): List<FriendSummary> = withContext(engineContext) {
        book.withState(FriendState.ACCEPTED).map { FriendSummary.of(it, links.containsKey(it.id)) }
    }

    /** Peers waiting on a decision. */
    suspend fun requests(): List<FriendSummary> = withContext(engineContext) {
        book.withState(FriendState.PENDING).map { FriendSummary.of(it, online = false) }
    }

    suspend fun blocked(): List<FriendSummary> = withContext(engineContext) {
        book.withState(FriendState.BLOCKED).map { FriendSummary.of(it, online = false) }
    }

    suspend fun friend(id: String): Friend? = withContext(engineContext) { book.get(id) }

    suspend fun resolve(query: String?): Friend? = withContext(engineContext) { book.resolve(query) }

    // --- connections --------------------------------------------------------

    private suspend fun acceptLoop(bound: LoopbackListener) {
        while (scope.isActive) {
            val stream = try {
                bound.accept()
            } catch (_: Exception) {
                return // the listener closed; we are shutting down
            }
            onInbound(stream)
        }
    }

    private fun onInbound(stream: Stream) {
        // greetFirst = false: we accepted, so we wait to be spoken to. Greeting
        // here would write into a rendezvous stream Tor has not finished joining,
        // and the frame would be dropped without a word (§7.2).
        val link = Link(stream, context(), greetFirst = false)

        link.onReady = { scope.launch(engineContext) { adopt(link) } }
        link.onFailed = { reason -> log("rejected inbound link: $reason") }

        link.start(scope)
    }

    /**
     * Take ownership of a link that reached ready (§10.2).
     *
     * Runs only on [engineContext].
     */
    private fun adopt(link: Link) {
        val peer = link.peer ?: return

        // A link can die between "ready" and adoption. Storing a dead one is
        // permanent damage: its close event has already fired and its listeners
        // are gone, so the friend would stay in the live-links map forever, the
        // redial sweep would skip them for having a "live" link, and only a
        // restart would bring them back.
        if (link.isClosed) {
            retry.penalise(peer.id)
            return
        }

        // Both sides dialled at once; keep the connection we already trust —
        // unless it has stopped answering. A peer only redials when *its* end of
        // our link has died, and the half-open husk left at this end says
        // nothing until its ping timeout runs out. Refusing the newcomer in
        // favour of it kept a friend locked out for up to a minute after every
        // network change. A live link hears the peer's ping every
        // PING_INTERVAL_MS, so silence well past that means it is gone.
        // A link that has already closed but whose close has not reached this
        // dispatcher yet is no connection at all, and must not turn away the
        // one replacing it.
        links[peer.id]?.let { existing ->
            val silentFor = System.currentTimeMillis() - existing.lastHeardAt
            if (!existing.isClosed && silentFor <= STALE_LINK_MS) {
                link.close("already connected to this peer")
                return
            }
            if (!existing.isClosed) log("replacing a silent connection to ${peer.name}")
            links.remove(peer.id)
            existing.close("replaced by a fresh connection")
        }

        // Whether we have agreed to talk to them, decided before anything is
        // wired up. §9.3 originally accepted anyone who proved their identity,
        // which made a shared card a standing invitation that could not be
        // withdrawn: removing someone lasted until their next redial.
        val known = book.get(peer.id)
        when {
            known == null -> {
                // A stranger. Their keys check out and their card is real —
                // that is exactly what makes the request worth showing rather
                // than dropping — but nothing happens until you say so.
                val (asked, _) = book.upsert(peer, stateIfNew = FriendState.PENDING)
                link.close("waiting to be accepted")
                log("${asked.name} ${asked.id} wants to connect")
                emit(EngineEvent.RequestReceived(asked.id, asked.name, asked.sigil))
                emit(EngineEvent.FriendsChanged)
                refreshStatus()
                return
            }

            known.isPending -> {
                // Already in the list waiting on a decision. They will keep
                // redialling; that must not raise the same request again.
                book.upsert(peer, stateIfNew = FriendState.PENDING)
                link.close("waiting to be accepted")
                return
            }

            known.isBlocked -> {
                // Nothing is sent back. To them this is indistinguishable from
                // being offline, which is the only honest answer — telling
                // someone they are blocked is information they can act on.
                link.close("blocked")
                return
            }
        }

        val (friend, isNew) = book.upsert(peer)
        links[peer.id] = link
        _online.value = links.keys.toSet()
        retry.reset(peer.id)

        if (isNew) log("new peer accepted: ${friend.name} ${friend.id}")
        log("connected to ${friend.name}")
        emit(EngineEvent.Status(peer.id, friend.name, online = true))
        refreshStatus()

        link.onClose = { scope.launch(engineContext) { onLinkClosed(peer.id, friend.name, link) } }
        // Set last: assigning this flushes anything that arrived while we were
        // hopping onto this dispatcher.
        link.onMessage = { frame -> scope.launch(engineContext) { onFrame(peer.id, frame) } }

        link.send(buildJsonObject {
            put("t", "endpoint")
            put("endpoints", buildJsonArray {
                for (endpoint in localEndpoints()) {
                    add(buildJsonObject {
                        put("type", endpoint.type)
                        put("host", endpoint.host)
                        put("port", endpoint.port)
                    })
                }
            })
        })

        messenger.flushOutbox(peer.id)
    }

    private fun onLinkClosed(peerId: String, name: String, link: Link) {
        // Only evict if the closing link is still the registered one — otherwise
        // a superseded duplicate's close event evicts the live link.
        if (links[peerId] !== link) return

        links.remove(peerId)
        _online.value = links.keys.toSet()
        log("lost $name")
        emit(EngineEvent.Status(peerId, name, online = false))
        refreshStatus()
    }

    private suspend fun dialEndpoint(endpoint: Endpoint, expectId: String): Link {
        if (!tor.isReady) throw LinkException("tor is not running", DialFailure.NO_TOR)

        val stream = try {
            tor.dial(endpoint.host, endpoint.port, Constants.DIAL_TIMEOUT_MS)
        } catch (failure: LinkException) {
            throw failure
        } catch (failure: Exception) {
            throw LinkException(failure.message ?: "the dial failed", DialFailure.UNREACHABLE)
        }

        return handshakeOverStream(
            stream = stream,
            ctx = context(),
            scope = scope,
            expectId = expectId,
            timeoutMs = Constants.DIAL_TIMEOUT_MS,
        )
    }

    private suspend fun connect(friend: Friend, verbose: Boolean = false): ConnectResult =
        withContext(engineContext) {
            if (links.containsKey(friend.id) || dialing.contains(friend.id)) {
                return@withContext ConnectResult(false, listOf("already connecting"))
            }

            val endpoints = DialPolicy.orderEndpoints(friend.endpoints, onion)
            if (endpoints.isEmpty()) {
                if (verbose) log("${friend.name} has no onion address on record")
                return@withContext ConnectResult(false, listOf("their card has no onion address"))
            }

            val failures = mutableListOf<String>()
            dialing += friend.id

            val link = try {
                DialPolicy.raceEndpoints(
                    endpoints = endpoints,
                    dial = { endpoint -> dialEndpoint(endpoint, friend.id) },
                    onFailure = { endpoint, error ->
                        failures += DialPolicy.explainDialFailure(endpoint, error)
                    },
                )
            } finally {
                dialing -= friend.id
            }

            if (link != null) {
                adopt(link)
                return@withContext ConnectResult(true, emptyList())
            }

            retry.penalise(friend.id)
            reportFailure(friend, failures, verbose)
            ConnectResult(false, failures)
        }

    /**
     * Force an immediate attempt and report everything.
     *
     * A manual "try now" always answers, unlike a background redial — see
     * [reportFailure].
     */
    suspend fun tryNow(peerId: String): ConnectResult {
        val friend = withContext(engineContext) {
            if (links.containsKey(peerId)) null else book.get(peerId)?.also { retry.reset(peerId) }
        } ?: return ConnectResult(true, emptyList())

        return connect(friend, verbose = true)
    }

    private fun reportFailure(friend: Friend, failures: List<String>, verbose: Boolean) {
        if (!retry.shouldReport(friend.id, failures.joinToString("|")) && !verbose) return

        log("could not reach ${friend.name}:")
        for (reason in failures) log("  $reason")
    }

    /** Every 30 s, dial everyone who has no link and is past their next-try time. */
    private suspend fun redialLoop() {
        while (scope.isActive) {
            delay(Constants.REDIAL_INTERVAL_MS)
            redialAll()
        }
    }

    private suspend fun redialAll() = withContext(engineContext) {
        val now = System.currentTimeMillis()
        for (friend in book.all) {
            // Pending and blocked peers are never dialled: one has not been
            // answered yet, the other has.
            if (!friend.isAccepted) continue
            if (links.containsKey(friend.id) || dialing.contains(friend.id)) continue
            if (!retry.isDue(friend.id, now)) continue
            if (friend.endpoints.isEmpty()) continue

            scope.launch { connect(friend) }
        }
    }

    // --- messages -----------------------------------------------------------

    private fun onFrame(peerId: String, frame: JsonObject) {
        val friend = book.get(peerId) ?: return

        when (frame.text("t")) {
            "msg" -> messenger.receive(peerId, friend.name, frame)

            "ack" -> messenger.acknowledge(peerId, frame)

            "profile" -> {
                book.rename(friend, frame.text("name"), frame.text("sigil"))
                emit(EngineEvent.FriendsChanged)
            }

            // Only onion addresses survive the merge, so a peer that sent an IP —
            // whether an old build or a hostile one — cannot get us to dial it.
            "endpoint" -> book.mergeEndpoints(friend, readEndpoints(frame["endpoints"]))

            // Unknown types are ignored, not errors (§7.6).
            else -> Unit
        }
    }

    suspend fun sendText(peerId: String, body: String): Message = withContext(engineContext) {
        if (!book.has(peerId)) throw IllegalArgumentException("not a friend")
        val message = messenger.compose(peerId, body)

        // Somebody just tried to talk to them, which is the best evidence there
        // is that now is a good time to dial. Leaving it to the sweep meant a
        // message to a friend in backoff sat queued for up to five minutes
        // after they came back.
        val friend = book.get(peerId)
        if (friend != null && friend.isAccepted && !links.containsKey(peerId) && !dialing.contains(peerId)) {
            retry.reset(peerId)
            scope.launch { connect(friend) }
        }
        message
    }

    suspend fun history(peerId: String, limit: Int = 200): List<Message> =
        withContext(engineContext) { messenger.history(peerId, limit) }

    // --- internals ----------------------------------------------------------

    private fun refreshStatus(published: Boolean = _status.value.published) {
        _status.value = _status.value.copy(
            onion = onion,
            published = published,
            torRunning = tor.isReady,
            // Accepted friends only. Counting the whole book put blocked
            // strangers into "2 of 7 online".
            friends = book.withState(FriendState.ACCEPTED).size,
            online = links.size,
            requests = book.withState(FriendState.PENDING).size,
        )
        _online.value = links.keys.toSet()
        emit(EngineEvent.SelfChanged(_status.value))
    }

    private fun emit(event: EngineEvent) {
        _events.tryEmit(event)
    }

    private fun log(text: String) = emit(EngineEvent.Log(text))

    private companion object {

        /** Waits between attempts to bring a dead Tor back. The last repeats. */
        val TOR_RESTART_DELAYS_MS = longArrayOf(3_000, 10_000, 30_000, 60_000, 120_000)

        /** How long a probed link has to answer before it is declared dead. */
        const val PROBE_TIMEOUT_MS = 30_000L

        /** After a network change, before asking Tor for fresh circuits. */
        const val NETWORK_SETTLE_MS = 3_000L

        /**
         * Silence after which an adopted link is presumed dead when the peer
         * dials in again: one missed keepalive plus margin.
         */
        const val STALE_LINK_MS = Constants.PING_INTERVAL_MS + 10_000L

        fun readEndpoints(value: kotlinx.serialization.json.JsonElement?): List<Endpoint> {
            val array = value as? JsonArray ?: return emptyList()

            return array.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val host = entry.text("host") ?: return@mapNotNull null
                Endpoint(
                    type = entry.text("type") ?: "",
                    host = host,
                    port = entry.text("port")?.toIntOrNull() ?: 0,
                )
            }
        }

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
    }
}
