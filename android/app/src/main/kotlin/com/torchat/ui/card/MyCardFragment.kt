package com.torchat.ui.card

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.torchat.R
import com.torchat.databinding.FragmentMyCardBinding
import com.torchat.ui.ComponentLauncher
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.QrCode
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.copyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your contact code, as a QR and as text — the only discovery mechanism there
 * is (§5). It appears only once there is an onion address to put in it: a card
 * without one gives the person it is sent to nothing to dial.
 */
class MyCardFragment : Fragment() {

    private var _binding: FragmentMyCardBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentMyCardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        collectWhileStarted(model.identity) { identity ->
            identity ?: return@collectWhileStarted
            binding.avatar.setImageDrawable(IdenticonDrawable(identity.id))
            binding.name.text = identity.name
            binding.peerId.text = identity.id
        }

        collectWhileStarted(model.card) { card -> render(card) }
    }

    private fun render(card: String?) {
        val ready = card != null
        binding.waiting.isVisible = !ready
        binding.status.isVisible = !ready
        binding.copy.isEnabled = ready
        binding.share.isEnabled = ready
        binding.shareQr.isEnabled = ready
        binding.code.text = card ?: ""

        if (card == null) {
            binding.qr.setImageDrawable(null)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { QrCode.encode(card) }
            val drawable = bitmap?.let {
                BitmapDrawable(resources, it).apply {
                    // Scaled up without smoothing, so the modules stay sharp.
                    isFilterBitmap = false
                }
            }
            _binding?.qr?.setImageDrawable(drawable)
        }

        binding.copy.setOnClickListener { requireContext().copyText(getString(R.string.my_code), card) }
        binding.share.setOnClickListener { ComponentLauncher.shareCode(requireContext(), card) }
        binding.shareQr.setOnClickListener { ComponentLauncher.shareQrImage(requireContext(), card) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
