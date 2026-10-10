package com.torchat.ui.settings

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.PopupMenu
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.torchat.R
import com.torchat.core.Constants
import com.torchat.databinding.FragmentChannelsBinding
import com.torchat.databinding.ItemChannelBinding
import com.torchat.notify.Channels
import com.torchat.service.Session
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.promptText
import com.torchat.ui.common.toast

/**
 * Every notification channel the app owns, and the three things that can be
 * done to one: create it, modify it, delete it.
 *
 *  - **Create**: the button makes a channel for a chat that has none, and the
 *    menu re-creates the fixed ones.
 *  - **Modify**: rename a channel; mute or unmute a chat (which replaces its
 *    channel, since Android never lets an app raise a channel's importance).
 *  - **Delete**: remove a channel. A per-chat one falls back to "Messages".
 *
 * Tapping a channel opens it in system settings, where the person — not the
 * app — decides its sound, vibration and whether it shows at all.
 */
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private val adapter = ChannelAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            binding.intro.setText(R.string.channels_unsupported)
            binding.fab.isVisible = false
            return
        }

        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ -> openSettings(adapter.getItem(position)) }
        binding.fab.setOnClickListener { createForChat() }
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed while the person was in the system screen.
        refresh()
    }

    private fun refresh() {
        adapter.submit(Channels.list(requireContext()))
    }

    // --- create -------------------------------------------------------------

    private fun createForChat() {
        val friends = model.friends.value
        if (friends.isEmpty()) {
            requireContext().toast(R.string.empty_peers_title)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.channel_pick_friend)
            .setItems(friends.map { it.name }.toTypedArray()) { _, which ->
                val friend = friends[which]
                Channels.ensureConversation(requireContext(), friend.id, friend.name, friend.muted)
                requireContext().toast(R.string.channel_created)
                refresh()
            }
            .show()
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.menu_channels, menu)

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            if (item.itemId != R.id.action_reset_channels) return false
            Channels.createBase(requireContext())
            requireContext().toast(R.string.channel_created)
            refresh()
            return true
        }
    }

    // --- modify and delete --------------------------------------------------

    private fun showPopup(anchor: View, channel: Channels.Info) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_channel_popup, popup.menu)
        val isChat = channel.peerId != null
        popup.menu.findItem(R.id.channel_mute).apply {
            isVisible = isChat
            setTitle(if (channel.muted) R.string.unmute else R.string.mute)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.channel_settings -> openSettings(channel)
                R.id.channel_rename -> promptText(R.string.channel_rename_title, channel.name, Constants.MAX_NAME * 2) { name ->
                    if (name.isNotEmpty() && Channels.rename(requireContext(), channel.id, name)) {
                        requireContext().toast(R.string.channel_renamed)
                        refresh()
                    }
                }
                R.id.channel_mute -> {
                    val peer = channel.peerId ?: return@setOnMenuItemClickListener true
                    val muted = !channel.muted
                    Session.store(requireContext()).setMuted(peer, muted)
                    Channels.setMuted(requireContext(), peer, channel.name, muted)
                    requireContext().toast(if (muted) R.string.channel_muted else R.string.channel_unmuted)
                    refresh()
                }
                R.id.channel_delete -> {
                    Channels.delete(requireContext(), channel.id)
                    requireContext().toast(R.string.channel_deleted)
                    refresh()
                }
            }
            true
        }
        popup.show()
    }

    private fun openSettings(channel: Channels.Info) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private inner class ChannelAdapter : BaseAdapter() {

        private var rows: List<Channels.Info> = emptyList()

        fun submit(next: List<Channels.Info>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = convertView?.tag as? ItemChannelBinding
                ?: ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false).also { it.root.tag = it }
            val channel = rows[position]

            holder.name.text = channel.name
            holder.channelId.text = channel.id
            holder.details.text = listOfNotNull(
                importance(channel.importance),
                when (channel.group) {
                    Channels.GROUP_NODE -> getString(R.string.channel_group_node)
                    Channels.GROUP_CHATS -> getString(R.string.channel_group_chats)
                    else -> null
                },
            ).joinToString(" · ")
            holder.icon.setImageResource(
                when {
                    channel.importance == NotificationManager.IMPORTANCE_NONE || channel.muted -> R.drawable.ic_notifications_off
                    channel.peerId != null -> R.drawable.ic_chat
                    else -> R.drawable.ic_notifications
                }
            )
            holder.more.setOnClickListener { showPopup(it, channel) }
            return holder.root
        }

        private fun importance(value: Int): String = getString(
            when (value) {
                NotificationManager.IMPORTANCE_NONE -> R.string.importance_none
                NotificationManager.IMPORTANCE_MIN -> R.string.importance_min
                NotificationManager.IMPORTANCE_LOW -> R.string.importance_low
                NotificationManager.IMPORTANCE_DEFAULT -> R.string.importance_default
                else -> R.string.importance_high
            }
        )
    }
}
