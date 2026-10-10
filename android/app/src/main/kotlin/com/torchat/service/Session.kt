package com.torchat.service

import android.content.Context
import com.torchat.core.engine.Engine
import com.torchat.core.model.Identity
import com.torchat.data.Prefs
import com.torchat.data.SecureStore
import com.torchat.data.SqlStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide state: the one live engine, and the stores behind it.
 *
 * The engine is owned by [TorChatService]; screens reach it by binding to that
 * service, and broadcast receivers — which cannot bind — read it from here.
 * There is exactly one identity and one onion address per install, so a
 * process-wide holder matches the thing it holds.
 */
object Session {

    /**
     * A flow rather than a plain reference: a screen can be up before the
     * service has finished starting, and needs to attach the moment it has.
     */
    val engineFlow = MutableStateFlow<Engine?>(null)

    var engine: Engine?
        get() = engineFlow.value
        set(value) {
            engineFlow.value = value
        }

    @Volatile
    var identity: Identity? = null

    /**
     * Which conversation is actually on screen right now — set when a chat is
     * resumed and cleared when it is paused.
     *
     * Tied to the screen's lifecycle on purpose. It used to be set when a chat
     * opened and cleared only when it closed, so pressing Home with a chat open
     * left it set: every later message from that friend was treated as already
     * seen, and none of them ever raised a notification.
     */
    val visibleConversation = MutableStateFlow<String?>(null)

    /** Set when the node could not start. Cleared on the next successful start. */
    val fatal = MutableStateFlow<String?>(null)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun appendLog(line: String) {
        _log.update { (it + line).takeLast(LOG_LINES) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private var secure: SecureStore? = null
    private var store: SqlStore? = null
    private var prefs: Prefs? = null

    @Synchronized
    fun secureStore(context: Context): SecureStore =
        secure ?: SecureStore(context).also { secure = it }

    @Synchronized
    fun store(context: Context): SqlStore =
        store ?: SqlStore(context.applicationContext).also { store = it }

    @Synchronized
    fun prefs(context: Context): Prefs =
        prefs ?: Prefs(context.applicationContext).also { prefs = it }

    /** Read the identity from the Keystore-backed store if it is not loaded yet. */
    fun loadIdentity(context: Context): Identity? =
        identity ?: secureStore(context).read().also { identity = it }

    private const val LOG_LINES = 400
}
