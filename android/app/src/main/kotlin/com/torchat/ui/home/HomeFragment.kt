package com.torchat.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.tabs.TabLayoutMediator
import com.torchat.R
import com.torchat.data.Prefs
import com.torchat.databinding.FragmentHomeBinding
import com.torchat.notify.MessageNotifier
import com.torchat.service.Session
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.toast

/**
 * Home: three swipeable tabs — Chats, Peers, My node — under an action bar
 * whose menu holds search, your code, the peers layout switch, requests,
 * settings and diagnostics.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()
    private val prefs: Prefs by lazy { Session.prefs(requireContext()) }

    private var chatsBadge: BadgeDrawable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.pager.adapter = Pages(this)
        binding.pager.offscreenPageLimit = 2

        // The swipe view: tabs and pager kept in step by the mediator.
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = getString(TABS[position])
        }.attach()
        chatsBadge = binding.tabs.getTabAt(TAB_CHATS)?.orCreateBadge?.apply {
            isVisible = false
            backgroundColor = requireContext().getColor(R.color.tc_signal)
            badgeTextColor = requireContext().getColor(R.color.tc_on_signal)
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Adding a friend makes sense from the chats and peers tabs;
                // on the node tab the button would be noise.
                if (position == TAB_NODE) binding.fab.hide() else binding.fab.show()
                activity?.invalidateOptionsMenu()
            }
        })

        binding.fab.setOnClickListener { findNavController().navigate(R.id.action_home_to_addFriend) }

        collectWhileStarted(model.stats) { stats ->
            chatsBadge?.isVisible = stats.unread > 0
            chatsBadge?.number = stats.unread
        }

        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.menu_home, menu)

            val search = menu.findItem(R.id.action_search).actionView as SearchView
            search.queryHint = getString(R.string.search_chats)
            search.setQuery(model.query.value, false)
            search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(text: String?): Boolean {
                    model.query.value = text.orEmpty()
                    // Searching means the chat list.
                    if (!text.isNullOrEmpty()) binding.pager.currentItem = TAB_CHATS
                    return true
                }
            })
        }

        override fun onPrepareMenu(menu: Menu) {
            val toggle = menu.findItem(R.id.action_toggle_view)
            toggle.isVisible = binding.pager.currentItem == TAB_PEERS
            toggle.setTitle(if (prefs.peersAsGrid) R.string.view_as_list else R.string.view_as_grid)
            toggle.setIcon(if (prefs.peersAsGrid) R.drawable.ic_list else R.drawable.ic_grid)
            menu.findItem(R.id.action_requests).title =
                getString(R.string.requests) + model.requests.value.size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            val nav = findNavController()
            when (item.itemId) {
                R.id.action_my_code -> nav.navigate(R.id.action_home_to_myCard)
                R.id.action_requests -> nav.navigate(R.id.action_home_to_requests)
                R.id.action_settings -> nav.navigate(R.id.action_home_to_settings)
                R.id.action_diagnostics -> nav.navigate(R.id.action_home_to_diagnostics)
                R.id.action_reconnect -> {
                    model.reconnectAll()
                    requireContext().toast(R.string.trying)
                }
                R.id.action_toggle_view -> {
                    prefs.peersAsGrid = !prefs.peersAsGrid
                    activity?.invalidateOptionsMenu()
                }
                else -> return false
            }
            return true
        }
    }

    /** Open a chat. Shared by every tab, so one place decides how. */
    fun openChat(peerId: String) {
        findNavController().navigate(R.id.action_home_to_chat, bundleOf(MessageNotifier.ARG_PEER to peerId))
    }

    fun openProfile(peerId: String) {
        findNavController().navigate(R.id.action_home_to_friendProfile, bundleOf(MessageNotifier.ARG_PEER to peerId))
    }

    fun openMyCode() = findNavController().navigate(R.id.action_home_to_myCard)

    fun openRequests() = findNavController().navigate(R.id.action_home_to_requests)

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private class Pages(host: Fragment) : FragmentStateAdapter(host) {
        override fun getItemCount() = TABS.size
        override fun createFragment(position: Int): Fragment = when (position) {
            TAB_CHATS -> ChatsFragment()
            TAB_PEERS -> PeersFragment()
            else -> NodeFragment()
        }
    }

    private companion object {
        const val TAB_CHATS = 0
        const val TAB_PEERS = 1
        const val TAB_NODE = 2
        val TABS = intArrayOf(R.string.tab_chats, R.string.tab_peers, R.string.tab_node)
    }
}
