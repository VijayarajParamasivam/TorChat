package com.torchat.core.card

import com.torchat.core.Constants
import com.torchat.core.crypto.Base64Url
import com.torchat.core.crypto.Crypto
import com.torchat.core.json.CanonicalJson
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Identity
import com.torchat.core.model.Profile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a verified card told us about a peer. */
data class PeerCard(
    val id: String,
    val sign: String,
    val box: String,
    val name: String,
    val sigil: String,
    val endpoints: List<Endpoint>,
    val ts: Long,
)

/**
 * A card was rejected. The message is written for a person to read and act on,
 * because this runs on text somebody pasted in.
 */
class CardException(message: String) : Exception(message)

/**
 * A contact card is the text you send a friend out of band — over any messenger,
 * email, a QR code, a sticky note. It is the **only** discovery mechanism in the
 * system (§5). There is no directory, no lookup service, nothing that can be
 * taken offline.
 *
 * "Where" is a single onion address, which is a public key rather than a
 * location. Handing a card to the wrong person costs you an unwanted connection
 * attempt, not your location — it reveals no IP, no ISP, no country.
 */
object Card {

    const val PREFIX = Constants.CARD_PREFIX

    /** Build a signed card for the local identity. */
    fun create(
        identity: Identity,
        endpoints: List<Endpoint> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): String {
        val payload = buildJsonObject {
            put("v", 1)
            put("id", identity.id)
            put("sign", identity.sign.public)
            put("box", identity.box.public)
            put("name", identity.name)
            put("sigil", identity.sigil)
            put("endpoints", buildJsonArray {
                for (endpoint in endpoints) {
                    add(buildJsonObject {
                        // The declared type is preserved rather than forced,
                        // because the payload is signed and must serialise
                        // identically on both sides. Filtering happens when a
                        // card is *read*, not when it is written (§5.3).
                        put("type", endpoint.type)
                        put("host", endpoint.host)
                        put("port", endpoint.port)
                    })
                }
            })
            put("ts", now)
        }

        val signature = Crypto.sign(identity.sign.private, CanonicalJson.encode(payload))

        val card = JsonObject(payload + ("sig" to JsonPrimitive(signature)))
        return PREFIX + Base64Url.encode(card.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Parse and fully verify a card (§5.4).
     *
     * Every step is checked and every failure produces a message a human can act
     * on. Nothing here is a partial success: a card whose signature does not
     * verify is rejected entirely, with no partial trust.
     */
    fun parse(code: String?, now: Long = System.currentTimeMillis()): PeerCard {
        // Whitespace goes first — a code that got line-wrapped by an email client
        // is still the code the sender meant to send.
        val trimmed = (code ?: "").replace(Regex("\\s+"), "")
        if (!trimmed.startsWith(PREFIX)) {
            throw CardException("that does not look like a TorChat code")
        }

        val decoded = Base64Url.decodeOrNull(trimmed.removePrefix(PREFIX))
            ?: throw CardException("code is damaged — it was probably truncated when copied")

        val card = try {
            CanonicalJson.parser.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            throw CardException("code is damaged — it was probably truncated when copied")
        }

        // Strictly the number 1, not the string "1" — the version gate is the one
        // place a coerced comparison would let a differently-shaped card through.
        val version = card["v"]
        val versionNumber = (version as? JsonPrimitive)
            ?.takeIf { !it.isString && it !== JsonNull }
            ?.content
            ?.toLongOrNull()
        if (versionNumber != 1L) throw CardException("unsupported card version $version")

        val id = card["id"]?.asString()
        val sign = card["sign"]?.asString()
        val box = card["box"]?.asString()
        if (id.isNullOrEmpty() || sign.isNullOrEmpty() || box.isNullOrEmpty()) {
            throw CardException("code is missing fields")
        }

        // The ID must be the hash of the signing key, so nobody can wear another
        // person's ID. This is what makes spoofing impossible rather than merely
        // difficult (§4.1).
        if (!Crypto.idMatchesKey(id, sign)) {
            throw CardException("code is forged — the ID does not match its key")
        }

        // "The received object minus sig" — not a fixed schema. A card carrying
        // an unknown extra field must still verify, because the sender signed it
        // too (§5.2).
        val signature = card["sig"]?.asString()
        val payload = JsonObject(card - "sig")
        if (!Crypto.verify(sign, CanonicalJson.encode(payload), signature)) {
            throw CardException("signature check failed — the code was altered")
        }

        // A card dated in the future would outrank every real card that person
        // ever makes again (§9.2). One day of slack covers an unset clock;
        // beyond that is wrong.
        val ts = card["ts"]?.asLong() ?: 0L
        if (ts > now + Constants.CARD_FUTURE_SLACK_MS) {
            throw CardException("code is dated in the future — check the sender's clock")
        }

        return PeerCard(
            id = id,
            sign = sign,
            box = box,
            name = Profile.name(card["name"]?.asString()),
            sigil = Profile.sigil(card["sigil"]?.asString()),
            endpoints = readEndpoints(card["endpoints"]),
            ts = ts,
        )
    }

    /**
     * Read the endpoint list as it arrived, without filtering.
     *
     * Filtering is deliberately not done here: it belongs to
     * [Endpoint.mergeOnions], the single choke point every address path goes
     * through (§9.1). Doing it in two places is how one of them ends up with a
     * different rule.
     */
    private fun readEndpoints(value: JsonElement?): List<Endpoint> {
        val array = value as? JsonArray ?: return emptyList()

        return array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val host = entry["host"]?.asString() ?: return@mapNotNull null
            Endpoint(
                type = entry["type"]?.asString() ?: "",
                host = host,
                port = entry["port"]?.asLong()?.toInt() ?: 0,
            )
        }
    }

    /**
     * The text of any primitive, mirroring JavaScript's `String()` coercion, but
     * with an explicit JSON `null` treated as absent rather than as the four
     * characters "null".
     */
    private fun JsonElement.asString(): String? =
        if (this is JsonPrimitive && this !== JsonNull) content else null

    /** A number, coerced from a quoted one the way `Number()` would. */
    private fun JsonElement.asLong(): Long? = asString()?.toLongOrNull()
}
