package com.torchat.core.support

import com.torchat.core.Constants
import com.torchat.core.model.Friend
import com.torchat.core.model.Message
import com.torchat.core.model.MessageState
import com.torchat.core.port.SettingsStore
import com.torchat.core.port.Store

/**
 * A [Store] that keeps everything in memory, with the same `mine` scoping the
 * real one must have. Used to test the delivery contract without a database.
 */
class InMemoryStore : Store {

    private val friends = mutableListOf<Friend>()
    private val conversations = mutableMapOf<String, MutableList<Message>>()

    var flushes = 0
        private set

    private fun log(peerId: String) = conversations.getOrPut(peerId) { mutableListOf() }

    override fun readFriends(): List<Friend> = friends.toList()

    override fun writeFriends(friends: List<Friend>) {
        this.friends.clear()
        this.friends += friends
    }

    override fun appendMessage(peerId: String, message: Message) {
        val log = log(peerId)
        log += message
        while (log.size > Constants.HISTORY_CAP) log.removeAt(0)
    }

    override fun updateMessageState(
        peerId: String,
        id: String,
        mine: Boolean,
        state: MessageState,
    ): Message? {
        val log = log(peerId)
        val index = log.indexOfFirst { it.id == id && it.mine == mine }
        if (index < 0) return null

        return log[index].copy(state = state).also { log[index] = it }
    }

    override fun hasMessage(peerId: String, id: String, mine: Boolean): Boolean =
        log(peerId).any { it.id == id && it.mine == mine }

    override fun undelivered(peerId: String): List<Message> =
        log(peerId).filter { it.mine && it.state != MessageState.DELIVERED }

    override fun recentMessages(peerId: String, limit: Int): List<Message> =
        log(peerId).takeLast(limit)

    override fun flush() {
        flushes++
    }
}

class InMemorySettings(override var onionKey: String? = null) : SettingsStore
