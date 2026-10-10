package com.torchat.core.model

import com.torchat.core.card.PeerCard

/**
 * Someone you know: their identity, what they call themselves, and where to
 * reach them (§9).
 *
 * `id` is the only field that is proof — it is a hash of their signing key.
 * Everything else is either taken from a signed card or is a label they chose.
 *
 * `cardTs` exists to settle a conflict. Cards are signed but not sequenced, so
 * pasting an old one after a newer one would otherwise reinstate a dead onion
 * ahead of the live one. Identity stays trusted, because it is proven by the
 * key — but what a card *claims about now* is not, if we already hold something
 * more recent.
 */
/**
 * Whether we have agreed to talk to this person.
 *
 * The original design had no such thing: §9.3 accepted any peer that proved its
 * identity, on the reasoning that the only way it could know your onion address
 * is that you handed it your card. That holds — but it means the card is a
 * standing invitation with no way to withdraw it. Removing someone only forgot
 * them until their next redial, when they were silently accepted again.
 */
enum class FriendState(val wire: String) {
    /** Dialled in, proved who they are, waiting on a decision. */
    PENDING("pending"),

    /** A friend: dialled, delivered to, kept online. */
    ACCEPTED("accepted"),

    /** Rejected or removed. Their links are dropped without a word. */
    BLOCKED("blocked");

    companion object {
        fun fromWire(value: String?): FriendState =
            entries.firstOrNull { it.wire == value } ?: ACCEPTED
    }
}

data class Friend(
    val id: String,
    val name: String = Profile.DEFAULT_NAME,
    val sigil: String = Profile.DEFAULT_SIGIL,
    val sign: String? = null,
    val box: String? = null,
    val endpoints: List<Endpoint> = emptyList(),
    val cardTs: Long = 0L,
    val addedAt: Long = 0L,
    val state: FriendState = FriendState.ACCEPTED,
) {

    val isAccepted: Boolean get() = state == FriendState.ACCEPTED
    val isBlocked: Boolean get() = state == FriendState.BLOCKED
    val isPending: Boolean get() = state == FriendState.PENDING

    /**
     * Is this card older than the one we already believe?
     *
     * Never true for someone new — there is nothing to be older than.
     */
    fun isStale(peer: PeerCard, isNew: Boolean): Boolean =
        !isNew && cardTs > 0L && peer.ts > 0L && peer.ts < cardTs

    /** Take everything a fresh card says about them. */
    fun applyCard(peer: PeerCard) = copy(
        name = Profile.name(peer.name),
        sigil = Profile.sigil(peer.sigil),
        sign = peer.sign,
        box = peer.box,
        cardTs = if (peer.ts > 0L) peer.ts else cardTs,
        endpoints = Endpoint.mergeOnions(peer.endpoints, endpoints),
    )

    /**
     * Keep an older card's addresses as fallbacks, without letting it overwrite
     * the profile or reorder the live address behind itself.
     */
    fun keepAsFallback(peer: PeerCard) = copy(
        endpoints = Endpoint.mergeOnions(endpoints, peer.endpoints),
    )

    /** A peer renaming itself mid-session. Labels only — never keys or addresses. */
    fun renamed(newName: String?, newSigil: String?) = copy(
        name = Profile.name(newName, name),
        sigil = Profile.sigil(newSigil, sigil),
    )

    fun withEndpoints(incoming: List<Endpoint>) = copy(
        endpoints = Endpoint.mergeOnions(incoming, endpoints),
    )
}
