package com.torchat.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.torchat.MainActivity
import com.torchat.R
import com.torchat.databinding.FragmentOnboardingBinding
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First launch: make the keys, become a node.
 *
 * There is no sign-up because there is nobody to sign up with. Pressing the
 * button generates an Ed25519 and an X25519 key pair on this phone, derives the
 * TorChat ID from the first, and starts Tor.
 */
class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val result = text?.let { model.restore(it) }
                ?: Result.failure(IllegalArgumentException(getString(R.string.unknown_error)))

            result
                .onSuccess { identity ->
                    requireContext().toast(getString(R.string.restored, identity.name))
                    finish()
                }
                .onFailure { failure ->
                    requireContext().toast(getString(R.string.restore_failed, failure.message ?: ""))
                }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mesh.showDemo()

        binding.create.setOnClickListener {
            val name = binding.name.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                binding.nameLayout.error = getString(R.string.onboarding_name_required)
                return@setOnClickListener
            }
            binding.nameLayout.error = null
            binding.create.isEnabled = false
            model.createIdentity(name, binding.sigil.text?.toString().orEmpty().trim())
            finish()
        }

        binding.restore.setOnClickListener {
            pickBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    private fun finish() {
        (activity as? MainActivity)?.onIdentityReady()
        findNavController().navigate(R.id.action_onboarding_to_home)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
