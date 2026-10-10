package com.torchat.core.engine

import com.torchat.core.card.PeerCard
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Friend
import com.torchat.core.model.FriendState
import com.torchat.core.port.Store

/** The result of recording what a card told us. */
data class Upsert(val friend: Friend, val isNew: Boolean)

/**
 * Who you know: the friend list, its addresses, and how it reaches disk (§9).
 *
 * Nothing here dials, listens, or touches the network — it is the record of who
 * exists and where they said they were. That separation is deliberate: the rule
 * that only onion addresses are ever stored lives in one function
 * ([Endpoint.mergeOnions]), so there is exactly one place to check that no IP can
 * enter the friend list.
 */
class FriendBook(
    private val store: Store,
    private val onChange: () -> Unit = {},
) {

    private val byId = LinkedHashMap<String, Friend>()

    fun load() {
        byId.clear()
        // Loading from disk is an address path like any other, so it goes through
        // the same merge — a friend file hand-edited to contain an IP does not
        // get to bypass the rule.
        for (friend in store.readFriends()) {
            byId[friend.id] = friend.copy(endpoints = Endpoint.mergeOnions(friend.endpoints))
        }
    }

    val all: List<Friend> get() = byId.values.toList()

    val size: Int get() = byId.size

    fun get(id: String): Friend? = byId[id]

    fun has(id: String): Boolean = byId.containsKey(id)

    fun delete(id: String): Boolean {
        val existed = byId.remove(id) != null
        if (existed) save()
        return existed
    }

    fun save() {
        store.writeFriends(byId.values.toList())
        onChange()
    }

    /**
     * Record what a card or a live peer told us about themselves.
     *
     * A **fresh** card applies everything: name, sigil, keys, `cardTs`, merged
     * endpoints. A **stale** one keeps its endpoints as fallbacks only, merged
     * behind what we already hold — cards are signed but not sequenced, so
     * pasting an old one after a newer one would otherwise reinstate a dead onion
     * ahead of the live one (§9.2).
     */
    fun upsert(
        peer: PeerCard,
        now: Long = System.currentTimeMillis(),
        /** Only used for someone we have never seen; an existing state is never overwritten. */
        stateIfNew: FriendState = FriendState.ACCEPTED,
    ): Upsert {
        val existing = byId[peer.id]
        val isNew = existing == null
        val base = existing ?: Friend(id = peer.id, addedAt = now, state = stateIfNew)

        // A card refreshes what they are called and where they are. It never
        // moves them between states: only the person using this app does that,
        // or a peer could re-accept itself by dialling in with a fresh card.
        val updated =
            if (base.isStale(peer, isNew)) base.keepAsFallback(peer) else base.applyCard(peer)

        byId[peer.id] = updated
        save()
        return Upsert(updated, isNew)
    }

    /** Move someone between pending, accepted and blocked. */
    fun setState(id: String, state: FriendState): Friend? {
        val updated = byId[id]?.copy(state = state) ?: return null
        byId[id] = updated
        save()
        return updated
    }

    fun withState(state: FriendState): List<Friend> = byId.values.filter { it.state == state }

    /** A peer renaming itself mid-session. Labels only — never keys or addresses. */
    fun rename(friend: Friend, name: String?, sigil: String?): Friend {
        val updated = friend.renamed(name, sigil)
        byId[updated.id] = updated
        save()
        return updated
    }

    fun mergeEndpoints(friend: Friend, endpoints: List<Endpoint>): Friend {
        val updated = friend.withEndpoints(endpoints)
        byId[updated.id] = updated
        save()
        return updated
    }

    /**
     * Look someone up by exact ID, by a unique ID fragment (hyphens ignored, case
     * insensitive), or by exact display name.
     *
     * A match is returned only when **exactly one** candidate matches; ambiguity
     * resolves to nothing (§9.4).
     */
    fun resolve(query: String?): Friend? {
        val trimmed = (query ?: "").trim()
        if (trimmed.isEmpty()) return null

        val upper = trimmed.uppercase()
        byId[upper]?.let { return it }

        val needle = upper.replace("-", "")
        val matches = byId.values.filter { friend ->
            friend.id.replace("-", "").contains(needle) ||
                friend.name.equals(trimmed, ignoreCase = true)
        }

        return matches.singleOrNull()
    }
}
