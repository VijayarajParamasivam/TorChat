package com.torchat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torchat.core.card.Card
import com.torchat.core.engine.Engine
import com.torchat.core.engine.EngineStatus
import com.torchat.core.model.FriendState
import com.torchat.core.model.Identity
import com.torchat.data.Backup
import com.torchat.notify.Channels
import com.torchat.notify.MessageNotifier
import com.torchat.service.Session
import com.torchat.service.TorChatService
import com.torchat.ui.common.ConversationRow
import com.torchat.ui.common.PeerRow
import com.torchat.ui.common.Repository
import com.torchat.ui.common.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What every screen observes, shared across the activity.
 *
 * The engine belongs to [TorChatService], not to this class — the UI coming and
 * going must not take the connection with it. This only watches the engine's
 * state and the content provider, and forwards what the person does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val engine: StateFlow<Engine?> = Session.engineFlow

    private val _identity = MutableStateFlow(Session.identity)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    val status: StateFlow<EngineStatus> = engine
        .flatMapLatest { it?.status ?: flowOf(EngineStatus()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineStatus())

    val online: StateFlow<Set<String>> = engine
        .flatMapLatest { it?.online ?: flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Our contact code, or null until there is an onion address to put in it. */
    val card: StateFlow<String?> = engine
        .flatMapLatest { it?.card ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val running: StateFlow<Boolean> = engine.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val fatal: StateFlow<String?> = Session.fatal

    val log: StateFlow<List<String>> = Session.log

    /** What the person typed into the chat search. */
    val query = MutableStateFlow("")

    val conversations: StateFlow<List<ConversationRow>> =
        combine(Repository.conversations(app), online, query) { rows, online, query ->
            rows.asSequence()
                .map { it.copy(online = it.peerId in online) }
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.peerId.contains(query, ignoreCase = true) }
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val peers: StateFlow<List<PeerRow>> =
        combine(Repository.peers(app), online) { rows, online ->
            rows.map { it.copy(online = it.id in online) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val friends: StateFlow<List<PeerRow>> = peers
        .map { rows -> rows.filter { it.state == FriendState.ACCEPTED }.sortedWith(compareBy({ !it.online }, { it.name.lowercase() })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val requests: StateFlow<List<PeerRow>> = peers
        .map { rows -> rows.filter { it.state == FriendState.PENDING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blocked: StateFlow<List<PeerRow>> = peers
        .map { rows -> rows.filter { it.state == FriendState.BLOCKED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<Stats> = Repository.stats(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Stats())

    fun peer(peerId: String): StateFlow<PeerRow?> = peers
        .map { rows -> rows.firstOrNull { it.id == peerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- identity -----------------------------------------------------------

    /** First launch. Keys are generated here; nothing is contacted to do it. */
    fun createIdentity(name: String, sigil: String) {
        val identity = Identity.create(name, sigil.ifBlank { "*" })
        adopt(identity, onionKey = null)
    }

    /** Restore a backup — §4.3's checks run before anything is written. */
    fun restore(text: String): Result<Identity> = runCatching {
        val (identity, onionKey) = Backup.parse(text)
        adopt(identity, onionKey)
        identity
    }

    private fun adopt(identity: Identity, onionKey: String?) {
        val secure = Session.secureStore(app)
        secure.write(identity)
        if (onionKey != null) secure.onionKey = onionKey
        Session.identity = identity
        Session.prefs(app).stoppedByUser = false
        _identity.value = identity
        TorChatService.start(app)
    }

    fun backup(): String? {
        val identity = Session.identity ?: return null
        return Backup.export(identity, Session.secureStore(app).onionKey)
    }

    fun setProfile(name: String?, sigil: String?) {
        viewModelScope.launch {
            val engine = Session.engine
            val updated = if (engine != null) {
                engine.setProfile(name, sigil)
            } else {
                // No node running: still a valid edit, picked up on the next start.
                val current = Session.identity ?: return@launch
                current.withProfile(name, sigil).also { Session.secureStore(app).write(it) }
            }
            Session.identity = updated
            _identity.value = updated
        }
    }

    // --- friends ------------------------------------------------------------

    /**
     * Add someone from a pasted code. Waits briefly for the node if it is still
     * starting — adding works before Tor is up, the dial simply follows later.
     */
    suspend fun addFriend(code: String): Result<String> = runCatching {
        val engine = Session.engine
            ?: withTimeoutOrNull(ENGINE_WAIT_MS) { Session.engineFlow.filterNotNull().first() }
            ?: throw IllegalStateException(app.getString(com.torchat.R.string.node_starting))
        engine.addFriend(code).name
    }

    /** Parse a code without adding it, for a preview. Null if it is not a valid card. */
    fun preview(code: String) = runCatching { Card.parse(code.trim()) }.getOrNull()

    fun accept(peerId: String) = act { it.acceptRequest(peerId) }

    fun reject(peerId: String) = act { it.rejectRequest(peerId) }

    fun remove(peerId: String) {
        act { it.removeFriend(peerId) }
        viewModelScope.launch(Dispatchers.IO) {
            Channels.deleteConversation(app, peerId)
            MessageNotifier(app).apply {
                cancelConversation(peerId)
                removeShortcut(peerId)
            }
        }
    }

    fun unblock(peerId: String) = act { it.unblock(peerId) }

    fun tryNow(peerId: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = Session.engine?.tryNow(peerId)
            onDone(result?.ok == true)
        }
    }

    fun reconnectAll() = act { it.reconnectAll() }

    fun setMuted(peerId: String, name: String, muted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            Session.store(app).setMuted(peerId, muted)
            Channels.setMuted(app, peerId, name, muted)
        }
    }

    // --- conversations ------------------------------------------------------

    fun markRead(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Repository.markRead(app, peerId)
            MessageNotifier(app).cancelConversation(peerId)
        }
    }

    fun clearConversation(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Repository.clearConversation(app, peerId)
            MessageNotifier(app).cancelConversation(peerId)
        }
    }

    fun send(peerId: String, body: String) {
        val text = body.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val engine = Session.engine
                ?: withTimeoutOrNull(ENGINE_WAIT_MS) { Session.engineFlow.filterNotNull().first() }
                ?: return@launch
            withContext(Dispatchers.Default) { runCatching { engine.sendText(peerId, text) } }
        }
    }

    // --- the node -----------------------------------------------------------

    fun startNode() {
        Session.prefs(app).stoppedByUser = false
        TorChatService.start(app)
    }

    fun restartNode() = TorChatService.restart(app)

    fun stopNode() = TorChatService.stop(app)

    private fun act(block: suspend (Engine) -> Unit) {
        viewModelScope.launch {
            val engine = Session.engine ?: return@launch
            runCatching { block(engine) }
        }
    }

    private companion object {
        const val ENGINE_WAIT_MS = 6_000L
    }
}
