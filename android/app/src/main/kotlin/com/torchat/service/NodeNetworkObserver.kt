package com.torchat.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.matthewnelson.kmp.tor.runtime.NetworkObserver

/**
 * Tells Tor — and the engine — when the phone's network comes, goes or changes.
 *
 * Tor has no idea what the operating system's network is doing; it notices a
 * dead connection only when its sockets start failing, which on a phone that
 * just walked out of wifi range can take minutes. kmp-tor turns this observer's
 * reports into `DisableNetwork`, so on a loss Tor stops burning battery on
 * doomed connections, and on a return it drops every stale one and rebuilds at
 * once.
 *
 * The engine gets the same report through [listener], because it has its own
 * stale state to clear: links built over the old network, and friends parked
 * in a backoff they earned while there was no network at all.
 *
 * Watches the *default* network, which is the one traffic actually uses. A
 * handover from wifi to mobile data arrives as the new network becoming
 * default, sometimes before and sometimes after the old one is lost — so
 * whether something changed is decided by comparing against the last network
 * seen, not the current one.
 */
class NodeNetworkObserver(context: Context) : NetworkObserver() {

    fun interface Listener {
        fun onNetwork(available: Boolean, changed: Boolean)
    }

    @Volatile
    var listener: Listener? = null

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var current: Network? = null

    @Volatile
    private var lastSeen: Network? = null

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = lastSeen != null && lastSeen != network
            current = network
            lastSeen = network
            Log.i(TAG, "network available${if (changed) " (changed)" else ""}")
            report(Connectivity.Connected)
            listener?.onNetwork(available = true, changed = changed)
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            if (isNetworkConnected()) return

            Log.i(TAG, "network lost")
            report(Connectivity.Disconnected)
            listener?.onNetwork(available = false, changed = false)
        }
    }

    override fun isNetworkConnected(): Boolean {
        val network = connectivity?.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** kmp-tor's hook, named apart from Object.notify() so nothing resolves to the wrong one. */
    private fun report(connectivity: Connectivity) = notify(connectivity)

    /** Start watching. Owned by the service, so it runs exactly as long as the node does. */
    @Synchronized
    fun start() {
        if (registered || connectivity == null) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        }.onFailure { Log.w(TAG, "could not watch the network", it) }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
        current = null
    }

    private companion object {
        const val TAG = "TorChat"
    }
}
