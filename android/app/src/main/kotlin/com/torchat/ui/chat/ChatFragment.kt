package com.torchat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.torchat.R
import com.torchat.databinding.FragmentChatBinding
import com.torchat.notify.MessageNotifier
import com.torchat.service.Session
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.Format
import com.torchat.ui.common.PeerRow
import com.torchat.ui.common.Repository
import com.torchat.ui.common.actionBar
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.toast

/**
 * One conversation.
 *
 * Messages come straight from the content provider and are re-read whenever it
 * changes — a send, an arrival, an ack turning one tick into two — so the
 * thread cannot fall out of step with what is stored.
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private val adapter = MessageAdapter()

    private val peerId: String by lazy { requireArguments().getString(MessageNotifier.ARG_PEER)!! }
    private var peer: PeerRow? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val layout = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messages.layoutManager = layout
        binding.messages.adapter = adapter
        binding.messages.itemAnimator?.changeDuration = 120

        binding.input.doAfterTextChanged { binding.send.isEnabled = !it.isNullOrBlank() }
        binding.send.setOnClickListener { send() }
        binding.tryNow.setOnClickListener { tryNow() }

        collectWhileStarted(model.peer(peerId)) { row ->
            if (row == null) return@collectWhileStarted
            peer = row
            actionBar?.title = row.name
            actionBar?.subtitle = if (row.online) getString(R.string.online) else Format.lastSeen(requireContext(), row.lastSeen)
            binding.offlineHint.isVisible = !row.online
            binding.offlineText.text = getString(R.string.chat_offline_hint, row.name)
            activity?.invalidateOptionsMenu()
        }

        collectWhileStarted(Repository.messages(requireContext(), peerId)) { messages ->
            val atBottom = !binding.messages.canScrollVertically(1)
            val newestIsMine = messages.lastOrNull()?.mine == true
            adapter.submitList(ChatItem.build(messages)) {
                // Follow the conversation if we were already at the end, or if
                // we just sent something; otherwise leave the person where they
                // were reading. The diff runs on a background thread and can
                // finish after the person has left the chat, so the view may
                // already be gone — touching it then crashed the app.
                val list = _binding?.messages ?: return@submitList
                if (atBottom || newestIsMine) list.scrollToPosition(adapter.itemCount - 1)
            }
            if (isResumed && messages.any { !it.mine }) model.markRead(peerId)
        }

        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        // Visible now, and only now: a notification for a message the person
        // is looking at is noise, but one for a chat left open behind the home
        // screen is the whole point.
        Session.visibleConversation.value = peerId
        model.markRead(peerId)
    }

    override fun onPause() {
        if (Session.visibleConversation.value == peerId) Session.visibleConversation.value = null
        super.onPause()
    }

    private fun send() {
        val text = binding.input.text?.toString().orEmpty()
        if (text.isBlank()) return
        model.send(peerId, text)
        binding.input.text?.clear()
    }

    private fun tryNow() {
        requireContext().toast(R.string.trying)
        model.tryNow(peerId) { ok ->
            if (ok && isAdded) requireContext().toast(R.string.reached)
        }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.menu_chat, menu)

        override fun onPrepareMenu(menu: Menu) {
            menu.findItem(R.id.action_mute).setTitle(if (peer?.muted == true) R.string.unmute else R.string.mute)
            menu.findItem(R.id.action_try_now).isVisible = peer?.online != true
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_try_now -> tryNow()
                R.id.action_profile -> findNavController().navigate(
                    R.id.action_chat_to_friendProfile,
                    bundleOf(MessageNotifier.ARG_PEER to peerId),
                )
                R.id.action_mute -> peer?.let { model.setMuted(peerId, it.name, !it.muted) }
                R.id.action_clear -> MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_history)
                    .setMessage(R.string.clear_history_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.clear_history) { _, _ -> model.clearConversation(peerId) }
                    .show()
                else -> return false
            }
            return true
        }
    }

    override fun onDestroyView() {
        binding.messages.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
