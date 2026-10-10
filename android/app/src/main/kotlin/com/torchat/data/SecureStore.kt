package com.torchat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.torchat.core.model.Identity
import com.torchat.core.port.IdentityStore
import com.torchat.core.port.SettingsStore

/**
 * The identity and the onion key, behind the Keystore (§11).
 *
 * These two are the secrets that cannot be regenerated. The identity's private
 * keys *are* who you are — anyone holding them can be you — and the onion key is
 * what every friend's saved card points at, so losing it makes you unreachable
 * by everybody who already knows you.
 *
 * Conversations can live in ordinary files; these cannot.
 */
class SecureStore(context: Context) : IdentityStore, SettingsStore {

    private val prefs: SharedPreferences = run {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "torchat-secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): Identity? =
        prefs.getString(KEY_IDENTITY, null)?.let(Records::identityFromJson)

    override fun write(identity: Identity) {
        // commit rather than apply: this is the one record whose loss cannot be
        // recovered from, so it is worth blocking until it is on disk.
        prefs.edit().putString(KEY_IDENTITY, Records.identityToJson(identity)).commit()
    }

    override var onionKey: String?
        get() = prefs.getString(KEY_ONION, null)
        set(value) {
            prefs.edit().putString(KEY_ONION, value).commit()
        }

    /**
     * Wipe everything. Used when starting over from scratch — there is no
     * account to close, so this is the whole of "delete my account".
     */
    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_IDENTITY = "identity"
        const val KEY_ONION = "onionKey"
    }
}
