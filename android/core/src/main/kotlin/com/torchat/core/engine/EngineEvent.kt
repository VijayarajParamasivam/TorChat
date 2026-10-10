package com.torchat.core.engine

import com.torchat.core.model.Friend
import com.torchat.core.model.Message

/** What the engine tells the application. The UI observes; it never reaches in. */
sealed interface EngineEvent {

    /** The friend list changed — added, removed, renamed, or re-addressed. */
    data object FriendsChanged : EngineEvent

    /** A friend came online or went offline. Emitted exactly once per transition. */
    data class Status(val peerId: String, val name: String, val online: Boolean) : EngineEvent

    /**
     * A stranger proved who they are and is waiting on a decision.
     *
     * Raised once, when the request first appears. They will keep redialling
     * while they wait, and each of those must not raise it again.
     */
    data class RequestReceived(
        val peerId: String,
        val name: String,
        val sigil: String,
    ) : EngineEvent

    data class MessageReceived(
        val peerId: String,
        val friendName: String,
        val message: Message,
    ) : EngineEvent

    /** A peer acknowledged one of ours. The only thing that sets `delivered`. */
    data class Delivered(val peerId: String, val messageId: String) : EngineEvent

    /** A conversation changed in bulk — an outbox flush, typically. */
    data class HistoryChanged(val peerId: String) : EngineEvent

    data class Log(val text: String) : EngineEvent

    /** Our own address and Tor's state changed. */
    data class SelfChanged(val status: EngineStatus) : EngineEvent
}

/** Where we stand: our address, whether Tor is up, and how many friends are on. */
data class EngineStatus(
    val onion: String? = null,
    val port: Int = com.torchat.core.Constants.ONION_PORT,
    /** Is the descriptor confirmed uploaded, so friends can actually reach us? */
    val published: Boolean = false,
    val torRunning: Boolean = false,
    val bootstrapPercent: Int = 0,
    /** Accepted friends only — people waiting on a decision are [requests]. */
    val friends: Int = 0,
    val online: Int = 0,
    /** Strangers who proved who they are and are waiting on a decision. */
    val requests: Int = 0,
    /**
     * Does the device have a network at all? Reported by the platform, because
     * Tor cannot tell "no network" apart from "every relay is slow".
     */
    val networkAvailable: Boolean = true,
    /** Tor died and the engine is bringing it back. */
    val torRestarting: Boolean = false,
) {

    /**
     * The one true reading of where startup has got to.
     *
     * Derived in one place because the phases are not independent and every
     * caller that re-derives them invents a different gap. The first version
     * tested `bootstrapPercent in 1..99`, which silently fell through to
     * "starting tor" the moment it hit 100 — the app looked like it had gone
     * backwards at exactly the point it had succeeded.
     */
    val phase: TorPhase
        get() = when {
            !networkAvailable -> TorPhase.OFFLINE
            bootstrapPercent < 100 -> TorPhase.BOOTSTRAPPING
            onion == null -> TorPhase.PUBLISHING
            !published -> TorPhase.PROPAGATING
            else -> TorPhase.REACHABLE
        }
}

enum class TorPhase {
    /**
     * The device has no network. Nothing can be dialled and nobody can reach
     * us, and saying so is better than showing a bootstrap that cannot finish.
     */
    OFFLINE,

    /** Downloading the consensus. Slow, and the one that needs a percentage. */
    BOOTSTRAPPING,

    /** Tor is up; the onion service is being created. */
    PUBLISHING,

    /** We have an address, but its descriptor is not confirmed uploaded yet. */
    PROPAGATING,

    /** Friends can reach us. */
    REACHABLE,
}

/** A friend as the UI sees them. Private bookkeeping stays in the engine. */
data class FriendSummary(
    val id: String,
    val name: String,
    val sigil: String,
    val online: Boolean,
    val hasAddress: Boolean,
) {
    companion object {
        fun of(friend: Friend, online: Boolean) = FriendSummary(
            id = friend.id,
            name = friend.name,
            sigil = friend.sigil,
            online = online,
            hasAddress = friend.endpoints.isNotEmpty(),
        )
    }
}
