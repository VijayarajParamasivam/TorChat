package com.torchat.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic JSON, so both ends of a card signature produce byte-identical
 * input (ARCHITECTURE.md §5.2).
 *
 * The language's own encoder is not usable for this: key order, whitespace and
 * escaping are all unspecified, and a signature computed over one arrangement of
 * bytes will not verify against another. The rules are:
 *
 *  - object keys sorted by **code-unit order**, which is Kotlin's natural String
 *    ordering and JavaScript's default `Array.sort` — the two agree,
 *  - no whitespace anywhere,
 *  - standard JSON string escaping, matching `JSON.stringify` exactly,
 *  - absent values encoded as `null`.
 *
 * Numbers are re-emitted as the literal text they arrived as. That is what makes
 * a round trip through parse-then-canonicalise reproduce the original bytes,
 * which is the property the signature actually depends on.
 *
 * The most important rule is the one about *what* gets canonicalised: on parse
 * the payload is "the received object minus `sig`", not a fixed schema. A card
 * carrying an unknown extra field must still verify, because the sender signed
 * that field too. That is the forward-compatibility hinge.
 */
object CanonicalJson {

    /** Lenient in the same places `JSON.parse` is not strict, and no more. */
    val parser = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encode(value: JsonElement?): String = buildString { write(value, this) }

    private fun write(value: JsonElement?, out: StringBuilder) {
        when (value) {
            null, is JsonNull -> out.append("null")

            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    write(element, out)
                }
                out.append(']')
            }

            is JsonObject -> {
                out.append('{')
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    escape(key, out)
                    out.append(':')
                    write(value[key], out)
                }
                out.append('}')
            }

            is JsonPrimitive ->
                // `isString` is what distinguishes the string "1" from the number
                // 1; for anything unquoted the original literal is passed through
                // rather than reformatted through a Double.
                if (value.isString) escape(value.content, out) else out.append(value.content)
        }
    }

    /**
     * JSON string escaping as `JSON.stringify` performs it.
     *
     * Note what is deliberately *not* escaped: non-ASCII characters are emitted
     * raw as UTF-8, and so are U+007F and U+2028/U+2029. Escaping any of those
     * would be valid JSON but would not match the reference encoder, and the
     * signature would fail on any card containing one.
     */
    private fun escape(text: String, out: StringBuilder) {
        out.append('"')
        var index = 0
        while (index < text.length) {
            when (val ch = text[index]) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> when {
                    ch < ' ' -> out.append(unicodeEscape(ch))

                    // A lone surrogate cannot be encoded as UTF-8. Well-formed
                    // `JSON.stringify` emits it escaped rather than producing
                    // invalid output, and a clamped display name is short enough
                    // to have had a surrogate pair cut in half by the clamp.
                    ch.isHighSurrogate() ->
                        if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                            out.append(ch).append(text[index + 1])
                            index++
                        } else {
                            out.append(unicodeEscape(ch))
                        }

                    ch.isLowSurrogate() -> out.append(unicodeEscape(ch))

                    else -> out.append(ch)
                }
            }
            index++
        }
        out.append('"')
    }

    private fun unicodeEscape(ch: Char) = "\\u%04x".format(ch.code)
}
