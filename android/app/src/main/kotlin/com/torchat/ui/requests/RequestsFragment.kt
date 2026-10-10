package com.torchat.ui.requests

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.torchat.R
import com.torchat.databinding.FragmentRequestsBinding
import com.torchat.databinding.ItemRequestBinding
import com.torchat.databinding.ItemSectionBinding
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.PeerRow
import com.torchat.ui.common.collectWhileStarted
import kotlinx.coroutines.flow.combine

/**
 * People who dialled in with your code and proved who they are, waiting on a
 * decision — and people you have blocked.
 *
 * A request exists because a contact card is a standing invitation: anyone who
 * has yours can reach you. Accepting makes them a friend; rejecting drops them
 * silently from then on, which to them is indistinguishable from being offline.
 */
class RequestsFragment : Fragment() {

    private var _binding: FragmentRequestsBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private val adapter = RequestAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.adapter = adapter
        binding.empty.emptyIcon.setImageResource(R.drawable.ic_person_add)
        binding.empty.emptyTitle.setText(R.string.requests)
        binding.empty.emptyBody.setText(R.string.requests_empty)
        binding.empty.emptyAction.isVisible = false

        collectWhileStarted(combine(model.requests, model.blocked) { r, b -> r to b }) { (requests, blocked) ->
            val rows = buildList {
                if (requests.isNotEmpty()) {
                    add(Row.Section(getString(R.string.requests_header)))
                    requests.forEach { add(Row.Person(it, blocked = false)) }
                }
                if (blocked.isNotEmpty()) {
                    add(Row.Section(getString(R.string.blocked_header)))
                    blocked.forEach { add(Row.Person(it, blocked = true)) }
                }
            }
            adapter.submit(rows)
            binding.empty.root.isVisible = rows.isEmpty()
        }
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private sealed interface Row {
        data class Section(val title: String) : Row
        data class Person(val peer: PeerRow, val blocked: Boolean) : Row
    }

    /** One ListView, two row types: headers and people. */
    private inner class RequestAdapter : BaseAdapter() {

        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(parent.context)
            return when (val row = rows[position]) {
                is Row.Section -> {
                    val holder = convertView?.tag as? ItemSectionBinding
                        ?: ItemSectionBinding.inflate(inflater, parent, false).also { it.root.tag = it }
                    holder.section.text = row.title
                    holder.root
                }

                is Row.Person -> {
                    val holder = convertView?.tag as? ItemRequestBinding
                        ?: ItemRequestBinding.inflate(inflater, parent, false).also { it.root.tag = it }
                    holder.avatar.setImageDrawable(IdenticonDrawable(row.peer.id))
                    holder.name.text = row.peer.name
                    holder.peerId.text = row.peer.id

                    if (row.blocked) {
                        holder.secondary.isVisible = false
                        holder.primary.setText(R.string.unblock)
                        holder.primary.setOnClickListener { model.unblock(row.peer.id) }
                    } else {
                        holder.secondary.isVisible = true
                        holder.secondary.setText(R.string.reject)
                        holder.secondary.setOnClickListener { model.reject(row.peer.id) }
                        holder.primary.setText(R.string.accept)
                        holder.primary.setOnClickListener { model.accept(row.peer.id) }
                    }
                    holder.root
                }
            }
        }
    }
}
