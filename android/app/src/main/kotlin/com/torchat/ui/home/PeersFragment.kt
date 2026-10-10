package com.torchat.ui.home

import android.content.SharedPreferences
import android.os.Bundle
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.torchat.R
import com.torchat.data.Prefs
import com.torchat.databinding.FragmentPeersBinding
import com.torchat.service.Session
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.Format
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.PeerRow
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.copyText

/**
 * Every friend as a node: a GridView of tiles, or — switched from the action
 * bar — the same GridView at one column, showing rows. Online peers sort first
 * and wear a green ring.
 */
class PeersFragment : Fragment() {

    private var _binding: FragmentPeersBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private val prefs: Prefs by lazy { Session.prefs(requireContext()) }
    private val adapter = PeerAdapter()

    private val home get() = parentFragment as? HomeFragment

    private val layoutListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.PEERS_GRID) applyLayout()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentPeersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.grid.adapter = adapter
        binding.grid.setOnItemClickListener { _, _, position, _ -> home?.openChat(adapter.getItem(position).id) }
        registerForContextMenu(binding.grid)

        binding.empty.emptyTitle.setText(R.string.empty_peers_title)
        binding.empty.emptyBody.setText(R.string.empty_peers_body)
        binding.empty.emptyAction.setOnClickListener { home?.openMyCode() }

        applyLayout()
        prefs.register(layoutListener)

        collectWhileStarted(model.friends) { rows ->
            adapter.submit(rows)
            binding.empty.root.isVisible = rows.isEmpty()
        }
    }

    private fun applyLayout() {
        val binding = _binding ?: return
        val grid = prefs.peersAsGrid
        adapter.asGrid = grid
        binding.grid.numColumns = if (grid) GridView.AUTO_FIT else 1
        binding.grid.verticalSpacing = resources.getDimensionPixelSize(if (grid) R.dimen.grid_gap else R.dimen.list_gap)
        adapter.notifyDataSetChanged()
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, info: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, view, info)
        val row = adapter.getItem((info as AdapterView.AdapterContextMenuInfo).position)
        requireActivity().menuInflater.inflate(R.menu.menu_conversation_context, menu)
        menu.setHeaderTitle(row.name)
        menu.findItem(R.id.context_mark_read).isVisible = false
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        if (!isResumed) return false
        val info = item.menuInfo as? AdapterView.AdapterContextMenuInfo ?: return false
        if (info.targetView.parent != binding.grid) return false
        val row = adapter.getItem(info.position)

        when (item.itemId) {
            R.id.context_open -> home?.openChat(row.id)
            R.id.context_profile -> home?.openProfile(row.id)
            R.id.context_copy_id -> requireContext().copyText(getString(R.string.friend_id), row.id)
            R.id.context_remove -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.remove_friend)
                .setMessage(getString(R.string.remove_friend_confirm, row.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove) { _, _ -> model.remove(row.id) }
                .show()
            else -> return super.onContextItemSelected(item)
        }
        return true
    }

    override fun onDestroyView() {
        prefs.unregister(layoutListener)
        binding.grid.adapter = null
        _binding = null
        super.onDestroyView()
    }

    /** Two row layouts, one adapter: tiles for the grid, rows for the list. */
    private class PeerAdapter : BaseAdapter() {

        var asGrid = true
        private var rows: List<PeerRow> = emptyList()

        fun submit(next: List<PeerRow>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id.hashCode().toLong()
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (asGrid) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val layout = if (asGrid) R.layout.item_peer_grid else R.layout.item_peer_list
            val view = convertView?.takeIf { it.getTag(R.id.avatar) == layout }
                ?: LayoutInflater.from(parent.context).inflate(layout, parent, false).also {
                    it.setTag(R.id.avatar, layout)
                    it.tag = Holder(it)
                }
            val holder = view.tag as Holder
            val row = rows[position]
            val context = parent.context

            if (holder.avatar.tag != row.id) {
                holder.avatar.setImageDrawable(IdenticonDrawable(row.id))
                holder.avatar.tag = row.id
            }
            holder.ring.setBackgroundResource(if (row.online) R.drawable.bg_ring_online else R.drawable.bg_ring_offline)
            holder.name.text = row.name
            holder.shortId.text = if (asGrid) Format.shortId(row.id) else row.id
            holder.status.text = if (row.online) context.getString(R.string.online) else Format.lastSeen(context, row.lastSeen)
            holder.status.setTextColor(context.getColor(if (row.online) R.color.tc_signal else R.color.tc_text_faint))
            return view
        }

        private class Holder(view: View) {
            val ring: View = view.findViewById(R.id.ring)
            val avatar: ImageView = view.findViewById(R.id.avatar)
            val name: TextView = view.findViewById(R.id.name)
            val shortId: TextView = view.findViewById(R.id.short_id)
            val status: TextView = view.findViewById(R.id.status)
        }
    }
}
