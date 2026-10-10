package com.torchat.ui.home

import android.os.Bundle
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.torchat.R
import com.torchat.core.model.MessageState
import com.torchat.databinding.FragmentChatsBinding
import com.torchat.databinding.ItemConversationBinding
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.ConversationRow
import com.torchat.ui.common.Format
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.copyText

/**
 * The chat list: a ListView of conversations, newest first.
 *
 * Long-press opens a context menu; pull down to dial everyone now.
 */
class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private val adapter = ConversationAdapter()

    private val home get() = parentFragment as? HomeFragment

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ ->
            home?.openChat(adapter.getItem(position).peerId)
        }
        registerForContextMenu(binding.list)

        binding.refresh.setColorSchemeResources(R.color.tc_primary, R.color.tc_signal)
        binding.refresh.setProgressBackgroundColorSchemeResource(R.color.tc_surface_high)
        binding.refresh.setOnRefreshListener {
            model.reconnectAll()
            // Dialling happens in the background over minutes, not seconds;
            // the spinner only acknowledges the request.
            binding.refresh.postDelayed({ _binding?.refresh?.isRefreshing = false }, REFRESH_MS)
        }

        binding.empty.emptyTitle.setText(R.string.empty_chats_title)
        binding.empty.emptyBody.setText(R.string.empty_chats_body)
        binding.empty.emptyIcon.setImageResource(R.drawable.ic_chat)
        binding.empty.emptyAction.setOnClickListener { home?.openMyCode() }
        binding.requestsBanner.setOnClickListener { home?.openRequests() }

        collectWhileStarted(model.conversations) { rows ->
            adapter.submit(rows)
            binding.empty.root.isVisible = rows.isEmpty() && model.query.value.isBlank()
        }
        collectWhileStarted(model.requests) { requests ->
            binding.requestsBanner.isVisible = requests.isNotEmpty()
            binding.requestsText.text =
                resources.getQuantityString(R.plurals.requests_banner, requests.size, requests.size)
        }
    }

    // --- context menu (long-press) ------------------------------------------

    override fun onCreateContextMenu(menu: ContextMenu, view: View, info: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, view, info)
        val row = adapter.getItem((info as AdapterView.AdapterContextMenuInfo).position)
        requireActivity().menuInflater.inflate(R.menu.menu_conversation_context, menu)
        menu.setHeaderTitle(row.name)
        menu.findItem(R.id.context_mark_read).isVisible = row.unread > 0
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        // Every fragment in the pager receives this; only handle our own menu.
        if (!isResumed) return false
        val info = item.menuInfo as? AdapterView.AdapterContextMenuInfo ?: return false
        if (info.targetView.parent != binding.list) return false
        val row = adapter.getItem(info.position)

        when (item.itemId) {
            R.id.context_open -> home?.openChat(row.peerId)
            R.id.context_profile -> home?.openProfile(row.peerId)
            R.id.context_mark_read -> model.markRead(row.peerId)
            R.id.context_copy_id -> requireContext().copyText(getString(R.string.friend_id), row.peerId)
            R.id.context_remove -> confirmRemove(row)
            else -> return super.onContextItemSelected(item)
        }
        return true
    }

    private fun confirmRemove(row: ConversationRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_friend)
            .setMessage(getString(R.string.remove_friend_confirm, row.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> model.remove(row.peerId) }
            .show()
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * A BaseAdapter with the view-holder pattern: rows are recycled and their
     * child views looked up once, so scrolling a long list allocates nothing.
     */
    private class ConversationAdapter : BaseAdapter() {

        private var rows: List<ConversationRow> = emptyList()

        fun submit(next: List<ConversationRow>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].peerId.hashCode().toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? ItemConversationBinding)
                ?: ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    .also { it.root.tag = it }
            val row = rows[position]
            val context = parent.context

            if (holder.avatar.tag != row.peerId) {
                holder.avatar.setImageDrawable(IdenticonDrawable(row.peerId))
                holder.avatar.tag = row.peerId
            }
            holder.presence.setBackgroundResource(if (row.online) R.drawable.bg_dot_online else R.drawable.bg_dot_offline)
            holder.name.text = row.name
            holder.muted.isVisible = row.muted
            holder.time.text = Format.short(context, if (row.lastTs > 0) row.lastTs else row.addedAt)

            holder.preview.text = when {
                row.lastBody == null -> context.getString(R.string.no_messages_yet)
                row.lastMine -> context.getString(R.string.draft_you, row.lastBody)
                else -> row.lastBody
            }

            // Our own last message carries its delivery state, as in the thread.
            holder.state.isVisible = row.lastMine && row.lastState != null
            holder.state.setImageResource(
                when (row.lastState) {
                    MessageState.DELIVERED -> R.drawable.ic_done_all
                    MessageState.SENT -> R.drawable.ic_check
                    else -> R.drawable.ic_schedule
                }
            )
            holder.state.setColorFilter(
                context.getColor(if (row.lastState == MessageState.DELIVERED) R.color.tc_signal else R.color.tc_text_faint)
            )

            holder.unread.isVisible = row.unread > 0
            holder.unread.text = if (row.unread > 99) "99+" else row.unread.toString()
            holder.time.setTextColor(context.getColor(if (row.unread > 0) R.color.tc_signal else R.color.tc_text_faint))

            return holder.root
        }
    }

    private companion object {
        const val REFRESH_MS = 1_200L
    }
}
