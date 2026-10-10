package com.torchat.core.port

import com.torchat.core.model.Friend
import com.torchat.core.model.Identity
import com.torchat.core.model.Message
import com.torchat.core.model.MessageState

/**
 * Persistence (§11). Everything the client knows lives on the device and nowhere
 * else — there is no remote copy, and that is the point.
 *
 * Implementations must write **atomically**: a crash mid-write must not corrupt
 * the identity. Conversation writes are debounced (~300 ms) because delivery
 * receipts mutate messages that were already saved, so an append-only log makes
 * this awkward; hold the log in memory and write back on a short debounce.
 *
 * Note the `mine` parameter on every message lookup. Message IDs are chosen by
 * whoever composed the message, so the two directions share an ID space that
 * neither side controls both halves of. **Nothing may ever match across that
 * boundary** (§8.6): without the scoping, a peer picking an ID that collides with
 * one of ours could mark our message delivered merely by sending a message, or
 * have their message mistaken for a duplicate of ours.
 */
interface Store {

    fun readFriends(): List<Friend>

    fun writeFriends(friends: List<Friend>)

    fun appendMessage(peerId: String, message: Message)

    /**
     * Move one message to a new state.
     *
     * @param mine which half of the ID space to search — never both.
     * @return the updated message, or null if there was no such message.
     */
    fun updateMessageState(peerId: String, id: String, mine: Boolean, state: MessageState): Message?

    /** Used by the duplicate check on receipt, so always with `mine = false`. */
    fun hasMessage(peerId: String, id: String, mine: Boolean): Boolean

    /** Everything of ours the peer has not acknowledged — `mine && state != delivered`. */
    fun undelivered(peerId: String): List<Message>

    fun recentMessages(peerId: String, limit: Int): List<Message>

    /** Force any debounced writes out. Called on shutdown. */
    fun flush()
}

/**
 * The small, separate secrets.
 *
 * `onionKey` belongs here rather than in the identity record because it is a
 * different kind of secret, but losing it is just as bad: the onion address is
 * derived from it alone, and every friend's saved card points at that address.
 */
interface SettingsStore {
    var onionKey: String?
}

/**
 * Where the identity lives. On Android this is behind the Keystore — an
 * `EncryptedSharedPreferences`-equivalent, or a Keystore-held AES key wrapping
 * the record. Conversations can live in a normal database; the identity cannot.
 *
 * **Treat it as a password.** It holds private keys in the clear once decrypted,
 * and anyone holding it can be you.
 */
interface IdentityStore {
    fun read(): Identity?
    fun write(identity: Identity)
}
