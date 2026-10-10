package com.torchat.core.model

import com.torchat.core.crypto.Crypto
import com.torchat.core.crypto.KeyPair

/**
 * An identity is two keypairs generated locally, and nothing else (§4).
 *
 * There is no signup, no account server, and no directory to be looked up in.
 * The ID is a hash of the Ed25519 public key, so holding the private half is the
 * only proof of ownership that exists or is needed.
 *
 * **Anyone holding this record can be you.** It never leaves the device unless
 * the user explicitly moves it, and on Android it lives behind the Keystore
 * (§11).
 */
data class Identity(
    val id: String,
    val name: String,
    val sigil: String,
    val sign: KeyPair,
    val box: KeyPair,
    val createdAt: Long,
) {

    val profile: Profile get() = Profile(id, name, sigil)

    fun withProfile(newName: String?, newSigil: String?) = copy(
        name = Profile.name(newName, name),
        sigil = Profile.sigil(newSigil, sigil),
    )

    companion object {

        /** Generate a brand new identity. Nothing is contacted to do this. */
        fun create(
            name: String?,
            sigil: String? = Profile.DEFAULT_SIGIL,
            now: Long = System.currentTimeMillis(),
        ): Identity {
            val signing = Crypto.generateSigningPair()
            val box = Crypto.generateBoxPair()

            return Identity(
                id = Crypto.deriveId(signing.public),
                name = Profile.name(name),
                sigil = Profile.sigil(sigil),
                sign = signing,
                box = box,
                createdAt = now,
            )
        }
    }
}
