package com.torchat.data

import com.torchat.core.crypto.KeyPair
import com.torchat.core.json.CanonicalJson
import com.torchat.core.model.Endpoint
import com.torchat.core.model.Friend
import com.torchat.core.model.FriendState
import com.torchat.core.model.Identity
import com.torchat.core.model.Message
import com.torchat.core.model.MessageState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * On-disk shapes.
 *
 * Written by hand rather than through `@Serializable` so the stored form is a
 * deliberate choice rather than a consequence of a field rename. These records
 * outlive any single build of the app: an identity that stops loading is an
 * identity lost, since there is no server holding a copy.
 */
object Records {

    // --- identity -----------------------------------------------------------

    fun identityToJson(identity: Identity): String = buildJsonObject {
        put("id", identity.id)
        put("name", identity.name)
        put("sigil", identity.sigil)
        put("sign", buildJsonObject {
            put("public", identity.sign.public)
            put("private", identity.sign.private)
        })
        put("box", buildJsonObject {
            put("public", identity.box.public)
            put("private", identity.box.private)
        })
        put("createdAt", identity.createdAt)
    }.toString()

    fun identityFromJson(text: String): Identity? = runCatching {
        val root = CanonicalJson.parser.parseToJsonElement(text).jsonObject
        Identity(
            id = root.str("id")!!,
            name = root.str("name") ?: "anon",
            sigil = root.str("sigil") ?: "*",
            sign = root.keyPair("sign")!!,
            box = root.keyPair("box")!!,
            createdAt = root.num("createdAt") ?: 0L,
        )
    }.getOrNull()

    // --- friends ------------------------------------------------------------

    fun friendsToJson(friends: List<Friend>): String = buildJsonArray {
        for (friend in friends) {
            add(buildJsonObject {
                put("id", friend.id)
                put("name", friend.name)
                put("sigil", friend.sigil)
                friend.sign?.let { put("sign", it) }
                friend.box?.let { put("box", it) }
                put("cardTs", friend.cardTs)
                put("addedAt", friend.addedAt)
                put("state", friend.state.wire)
                put("endpoints", buildJsonArray {
                    for (endpoint in friend.endpoints) {
                        add(buildJsonObject {
                            put("type", endpoint.type)
                            put("host", endpoint.host)
                            put("port", endpoint.port)
                        })
                    }
                })
            })
        }
    }.toString()

    fun friendsFromJson(text: String): List<Friend> = runCatching {
        val root = CanonicalJson.parser.parseToJsonElement(text) as JsonArray
        root.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.str("id") ?: return@mapNotNull null
            Friend(
                id = id,
                name = entry.str("name") ?: "anon",
                sigil = entry.str("sigil") ?: "*",
                sign = entry.str("sign"),
                box = entry.str("box"),
                cardTs = entry.num("cardTs") ?: 0L,
                addedAt = entry.num("addedAt") ?: 0L,
                // Records written before requests existed have no state and
                // were, by definition, friends.
                state = FriendState.fromWire(entry.str("state")),
                endpoints = (entry["endpoints"] as? JsonArray).orEmpty().mapNotNull { raw ->
                    val e = raw as? JsonObject ?: return@mapNotNull null
                    val host = e.str("host") ?: return@mapNotNull null
                    Endpoint(e.str("type") ?: "", host, e.num("port")?.toInt() ?: 0)
                },
            )
        }
    }.getOrElse { emptyList() }

    // --- conversations ------------------------------------------------------

    fun messagesToJson(messages: List<Message>): String = buildJsonArray {
        for (message in messages) {
            add(buildJsonObject {
                put("id", message.id)
                put("ts", message.ts)
                put("body", message.body)
                put("mine", message.mine)
                put("state", message.state.wire)
            })
        }
    }.toString()

    fun messagesFromJson(text: String): List<Message> = runCatching {
        val root = CanonicalJson.parser.parseToJsonElement(text) as JsonArray
        root.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            Message(
                id = entry.str("id") ?: return@mapNotNull null,
                ts = entry.num("ts") ?: 0L,
                body = entry.str("body") ?: "",
                mine = entry.str("mine") == "true",
                state = MessageState.fromWire(entry.str("state")),
            )
        }
    }.getOrElse { emptyList() }

    // --- helpers ------------------------------------------------------------

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.num(key: String): Long? = str(key)?.toLongOrNull()

    private fun JsonObject.keyPair(key: String): KeyPair? {
        val entry = this[key] as? JsonObject ?: return null
        return KeyPair(
            public = entry.str("public") ?: return null,
            private = entry.str("private") ?: return null,
        )
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
        this ?: emptyList()
}
