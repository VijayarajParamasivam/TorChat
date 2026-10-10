package com.torchat.core.model

import com.torchat.core.Constants

/**
 * Where a peer can be reached.
 *
 * In this app there is exactly one kind, and that is the whole privacy design.
 * An onion address is a public key rather than a location, so publishing one
 * tells nobody where you are — whereas a single IP endpoint slipping into a card
 * or a friend record would undo everything.
 */
data class Endpoint(val type: String, val host: String, val port: Int) {

    companion object {

        /** A v3 onion address: 56 base32 characters and the suffix. */
        private val ONION = Regex("^[a-z2-7]{56}\\.onion$", RegexOption.IGNORE_CASE)

        fun isOnion(host: String?): Boolean = host != null && ONION.matches(host)

        /** Our own address, as it goes into a contact card. */
        fun onion(host: String, port: Int = Constants.ONION_PORT) =
            Endpoint("onion", host, if (port <= 0) Constants.ONION_PORT else port)

        /** Is this something we are willing to dial? */
        fun isDialable(endpoint: Endpoint?, ownOnion: String? = null): Boolean =
            endpoint != null && isOnion(endpoint.host) && endpoint.host != ownOnion

        /**
         * The choke point (§9.1).
         *
         * **Every path that could introduce an address goes through here** —
         * parsing a card, a live peer announcing itself, loading the friend file
         * from disk. Anything that is not an onion address is **dropped**, not
         * deprioritised: there is no ordering in which a rejected address gets
         * used. Incoming addresses go ahead of existing ones, duplicates collapse
         * on `host:port`, and the list is capped.
         *
         * This single function is the privacy guarantee. A peer that sent an IP —
         * whether an old build or a hostile one — cannot get us to dial it.
         */
        fun mergeOnions(
            incoming: List<Endpoint> = emptyList(),
            existing: List<Endpoint> = emptyList(),
            limit: Int = Constants.MAX_ENDPOINTS,
        ): List<Endpoint> {
            val seen = mutableSetOf<String>()
            val merged = mutableListOf<Endpoint>()

            for (candidate in incoming + existing) {
                if (!isOnion(candidate.host)) continue
                if (!seen.add("${candidate.host}:${candidate.port}")) continue
                merged += onion(candidate.host, candidate.port)
                if (merged.size == limit) break
            }

            return merged
        }
    }
}
