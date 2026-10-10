package com.torchat.data

import com.torchat.core.crypto.Crypto
import com.torchat.core.json.CanonicalJson
import com.torchat.core.model.Identity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The identity backup file (§4.3) — the only way an identity moves between
 * devices, and the same format the desktop client reads and writes:
 *
 * ```
 * { "torchat": 1, "identity": { … }, "onionKey": "ED25519-V3:…" }
 * ```
 *
 * The onion key belongs in here as much as the signing key does: every friend's
 * saved card points at the onion address, and that address is derived from the
 * onion key alone. A backup that restored the name and lost the address would
 * leave the person unreachable by everyone who already knows them.
 */
object Backup {

    private const val KEY_PREFIX = "ED25519-V3:"
    private const val PROBE = "torchat-backup-check"

    fun export(identity: Identity, onionKey: String?): String {
        val record = CanonicalJson.parser.parseToJsonElement(Records.identityToJson(identity))
        return buildJsonObject {
            put("torchat", 1)
            put("identity", record)
            // Written in Tor's own ADD_ONION spelling, which is what the desktop
            // client stores and hands straight back to its control port.
            put("onionKey", onionKey?.let { if (it.startsWith(KEY_PREFIX)) it else KEY_PREFIX + it }?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }

    /**
     * Read and verify a backup before anything is adopted.
     *
     * @return the identity and the onion key in the form this app stores it
     *   (no type prefix), or null for a backup that predates the field.
     * @throws IllegalArgumentException with a message written for a person.
     */
    fun parse(text: String): Pair<Identity, String?> {
        val root = runCatching { CanonicalJson.parser.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("that file is not a TorChat backup") }

        val record = (root["identity"] as? JsonObject) ?: root
        val identity = Records.identityFromJson(record.toString())
            ?: throw IllegalArgumentException("that file is not a TorChat identity backup")

        // The three checks of §4.3: the ID is the hash of the key, the private
        // half really goes with the public half, and the box key parses.
        if (!Crypto.idMatchesKey(identity.id, identity.sign.public)) {
            throw IllegalArgumentException("the backup is corrupt: its ID does not match its key")
        }
        val matches = runCatching {
            Crypto.verify(identity.sign.public, PROBE, Crypto.sign(identity.sign.private, PROBE))
        }.getOrDefault(false)
        if (!matches || !Crypto.parsesAsBoxPrivate(identity.box.private)) {
            throw IllegalArgumentException("the backup is corrupt: a private key does not match its public half")
        }

        val onionKey = (root["onionKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.removePrefix(KEY_PREFIX)
            ?.takeIf { it.isNotBlank() }

        return identity to onionKey
    }
}
