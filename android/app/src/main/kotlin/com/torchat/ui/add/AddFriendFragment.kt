package com.torchat.ui.add

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.torchat.R
import com.torchat.databinding.FragmentAddFriendBinding
import com.torchat.ui.ComponentLauncher
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.pasteText
import com.torchat.ui.common.toast
import com.torchat.ui.scan.ScanContract
import kotlinx.coroutines.launch

/**
 * Add a friend from their contact code — pasted, scanned, or handed over by a
 * deep link.
 *
 * The code is verified here, on the phone, before anything is dialled: its
 * signature, and that the ID really is the hash of the key it carries. The
 * preview only appears for a code that passed.
 */
class AddFriendFragment : Fragment() {

    private var _binding: FragmentAddFriendBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()

    /** The scanner is a separate activity, started for its result. */
    private val scan = registerForActivityResult(ScanContract()) { code ->
        if (code != null) binding.code.setText(code)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentAddFriendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.code.doAfterTextChanged { render(it?.toString().orEmpty()) }
        binding.paste.setOnClickListener { requireContext().pasteText()?.let { binding.code.setText(it) } }
        binding.scan.setOnClickListener { ComponentLauncher.openScanner(scan) }
        binding.add.setOnClickListener { add() }

        // Arrived with a code already: from torchat://add?code=…
        if (savedInstanceState == null) {
            arguments?.getString(ARG_CODE)?.let { binding.code.setText(it) }
        }
    }

    private fun render(text: String) {
        val card = if (text.isBlank()) null else model.preview(text)
        binding.preview.isVisible = card != null
        binding.add.isEnabled = card != null
        binding.codeLayout.error = if (text.isNotBlank() && card == null) getString(R.string.import_bad) else null

        card ?: return
        binding.previewAvatar.setImageDrawable(IdenticonDrawable(card.id))
        binding.previewName.text = card.name
        binding.previewId.text = card.id
    }

    private fun add() {
        val code = binding.code.text?.toString().orEmpty()
        binding.add.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            model.addFriend(code)
                .onSuccess { name ->
                    requireContext().toast(getString(R.string.added_friend, name))
                    findNavController().popBackStack()
                }
                .onFailure { failure ->
                    binding.codeLayout.error = failure.message ?: getString(R.string.unknown_error)
                    binding.add.isEnabled = true
                }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val ARG_CODE = "code"
    }
}
