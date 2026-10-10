package com.torchat.ui.friend

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.torchat.R
import com.torchat.databinding.FragmentFriendProfileBinding
import com.torchat.notify.Channels
import com.torchat.notify.MessageNotifier
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.Format
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.PeerRow
import com.torchat.ui.common.actionBar
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.toast

/**
 * Who a friend is, as far as this app can prove: an ID that is the hash of
 * their key, the onion addresses they have given, and nothing else. Their name
 * is a label they chose.
 */
class FriendProfileFragment : Fragment() {

    private var _binding: FragmentFriendProfileBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private val peerId: String by lazy { requireArguments().getString(MessageNotifier.ARG_PEER)!! }
    private var peer: PeerRow? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentFriendProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.avatar.setImageDrawable(IdenticonDrawable(peerId))
        binding.peerId.text = peerId

        binding.message.setOnClickListener {
            findNavController().navigate(R.id.action_friendProfile_to_chat, bundleOf(MessageNotifier.ARG_PEER to peerId))
        }
        binding.tryNow.setOnClickListener {
            requireContext().toast(R.string.trying)
            model.tryNow(peerId) { ok -> if (ok && isAdded) requireContext().toast(R.string.reached) }
        }
        binding.mute.setOnClickListener { peer?.let { model.setMuted(peerId, it.name, !it.muted) } }
        binding.channelSettings.setOnClickListener { openChannelSettings() }
        binding.remove.setOnClickListener { confirmRemove() }

        collectWhileStarted(model.peer(peerId)) { row ->
            if (row == null) return@collectWhileStarted
            peer = row
            actionBar?.title = row.name
            binding.name.text = row.name
            binding.presence.text = if (row.online) getString(R.string.online) else Format.lastSeen(requireContext(), row.lastSeen)
            binding.presence.setTextColor(requireContext().getColor(if (row.online) R.color.tc_signal else R.color.tc_text_faint))
            binding.ring.setBackgroundResource(if (row.online) R.drawable.bg_ring_online else R.drawable.bg_ring_offline)
            binding.onions.text = row.endpoints.joinToString("\n") { "${it.host}:${it.port}" }
            binding.added.text = getString(
                R.string.friend_added,
                DateUtils.formatDateTime(requireContext(), row.addedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
            )
            binding.mute.setText(if (row.muted) R.string.unmute else R.string.mute)
            binding.mute.setIconResource(if (row.muted) R.drawable.ic_notifications else R.drawable.ic_notifications_off)
        }
    }

    /** This chat's own channel in system settings — ringtone, vibration, the lot. */
    private fun openChannelSettings() {
        val context = requireContext()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Channels.forMessage(context, peerId))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
        runCatching { startActivity(intent) }
    }

    private fun confirmRemove() {
        val name = peer?.name ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_friend)
            .setMessage(getString(R.string.remove_friend_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                model.remove(peerId)
                findNavController().popBackStack(R.id.homeFragment, false)
            }
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
