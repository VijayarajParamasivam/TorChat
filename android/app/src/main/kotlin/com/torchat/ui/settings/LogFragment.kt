package com.torchat.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.torchat.R
import com.torchat.databinding.FragmentLogBinding
import com.torchat.service.Session
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.copyText

/** What Tor and the engine are doing, line by line — for when something is slow or stuck. */
class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ArrayAdapter(requireContext(), R.layout.item_log, R.id.line, mutableListOf())
        binding.list.adapter = adapter

        collectWhileStarted(model.log) { lines ->
            adapter.clear()
            adapter.addAll(lines)
            binding.empty.isVisible = lines.isEmpty()
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.menu_log, menu)

            override fun onMenuItemSelected(item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.action_copy_log -> requireContext().copyText(getString(R.string.log_title), model.log.value.joinToString("\n"))
                    R.id.action_clear_log -> Session.clearLog()
                    else -> return false
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
