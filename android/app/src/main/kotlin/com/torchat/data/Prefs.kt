package com.torchat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * The person's choices about how the app behaves. Nothing secret lives here —
 * keys and the onion key are in [SecureStore].
 *
 * Backed by the default preferences file so the settings screen's preference
 * widgets read and write the same values.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** Bring the node back after a reboot. On by default: there is no push to fall back on. */
    val startOnBoot: Boolean get() = prefs.getBoolean(START_ON_BOOT, true)

    /** Hold a partial wake lock while the node runs. Off by default; it costs battery. */
    val alwaysOn: Boolean get() = prefs.getBoolean(ALWAYS_ON, false)

    /** Show message text in notifications rather than just "new message". */
    val previews: Boolean get() = prefs.getBoolean(PREVIEWS, true)

    /** Peers as a grid (true) or a list (false). */
    var peersAsGrid: Boolean
        get() = prefs.getBoolean(PEERS_GRID, true)
        set(value) = prefs.edit { putBoolean(PEERS_GRID, value) }

    /**
     * The person asked the node to stop. Remembered so a boot or an app update
     * does not bring back something they deliberately turned off.
     */
    var stoppedByUser: Boolean
        get() = prefs.getBoolean(STOPPED_BY_USER, false)
        set(value) = prefs.edit { putBoolean(STOPPED_BY_USER, value) }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val START_ON_BOOT = "start_on_boot"
        const val ALWAYS_ON = "always_on"
        const val PREVIEWS = "previews"
        const val PEERS_GRID = "peers_grid"
        const val STOPPED_BY_USER = "stopped_by_user"
    }
}
