package com.torchat.ui.settings

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.torchat.BuildConfig
import com.torchat.R
import com.torchat.core.Constants
import com.torchat.service.Power
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.promptText
import com.torchat.ui.common.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings, as a preference screen. Switches write straight to [com.torchat.data.Prefs]. */
class SettingsFragment : PreferenceFragmentCompat() {

    private val model: MainViewModel by activityViewModels()

    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = model.backup() ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                }.exceptionOrNull()
            }
            requireContext().toast(
                if (failure == null) getString(R.string.backup_saved)
                else getString(R.string.backup_failed, failure.message ?: "")
            )
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        find("profile_name").setOnPreferenceClickListener {
            promptText(R.string.pref_name, model.identity.value?.name.orEmpty(), Constants.MAX_NAME) { name ->
                if (name.isNotEmpty()) model.setProfile(name, null)
            }
            true
        }
        find("profile_sigil").setOnPreferenceClickListener {
            promptText(R.string.pref_sigil, model.identity.value?.sigil.orEmpty(), Constants.MAX_SIGIL) { sigil ->
                if (sigil.isNotEmpty()) model.setProfile(null, sigil)
            }
            true
        }
        find("my_code").setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settings_to_myCard)
            true
        }
        find("battery").setOnPreferenceClickListener {
            Power.requestExemption(requireContext())
            true
        }
        find("restart").setOnPreferenceClickListener {
            model.restartNode()
            requireContext().toast(R.string.restart_node)
            true
        }
        find("channels").setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settings_to_channels)
            true
        }
        find("backup").setOnPreferenceClickListener {
            saveBackup.launch("torchat-identity-backup.json")
            true
        }
        find("log").setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settings_to_log)
            true
        }
        find("diagnostics").setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settings_to_diagnostics)
            true
        }
        find("version").summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectWhileStarted(model.identity) { identity ->
            find("profile_name").summary = identity?.name
            find("profile_sigil").summary = identity?.sigil
            find("my_code").summary = identity?.id
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system's battery screen is how this changes.
        find("battery").summary = getString(
            if (Power.isIgnoringOptimizations(requireContext())) R.string.pref_battery_off else R.string.pref_battery_on
        )
    }

    private fun find(key: String): Preference = findPreference(key)!!
}
