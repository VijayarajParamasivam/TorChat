package com.torchat.core.model

import com.torchat.core.Constants

/**
 * The public face of an identity: an ID, a display name and a short sigil.
 *
 * Only the ID means anything. It is a hash of a signing key, so it cannot be
 * claimed by anyone without that key. The name and sigil are labels their owner
 * chooses and can change at will — they arrive over the wire from someone else's
 * machine, so they are clamped here rather than trusted (§4.2).
 *
 * The clamping lives in exactly one place on purpose. In the reference client it
 * was written out by hand at four entry points — identity creation, profile
 * edits, card parsing, and a peer renaming itself mid-session — with slightly
 * different rules in each, and one of them forgot to trim.
 */
data class Profile(val id: String, val name: String, val sigil: String) {

    companion object {

        const val DEFAULT_NAME = "anon"
        const val DEFAULT_SIGIL = "*"

        /** A display name safe to store and print. */
        fun name(value: String?, fallback: String = DEFAULT_NAME): String {
            val source = if (value.isNullOrEmpty()) fallback else value
            return source.trim().take(Constants.MAX_NAME).ifEmpty { fallback }
        }

        /** A sigil: one or two characters, never more. */
        fun sigil(value: String?, fallback: String = DEFAULT_SIGIL): String =
            (if (value.isNullOrEmpty()) fallback else value).take(Constants.MAX_SIGIL)

        fun of(id: String, rawName: String?, rawSigil: String?) =
            Profile(id, name(rawName), sigil(rawSigil))
    }
}
