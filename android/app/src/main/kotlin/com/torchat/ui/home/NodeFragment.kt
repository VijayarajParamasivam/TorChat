package com.torchat.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.torchat.R
import com.torchat.core.engine.EngineStatus
import com.torchat.core.engine.TorPhase
import com.torchat.databinding.FragmentNodeBinding
import com.torchat.databinding.ViewHealthRowBinding
import com.torchat.notify.MessageNotifier
import com.torchat.service.Power
import com.torchat.ui.MainViewModel
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.MeshView
import com.torchat.ui.common.collectWhileStarted
import com.torchat.ui.common.copyText
import java.text.NumberFormat
import kotlinx.coroutines.flow.combine

/**
 * Your node: the mesh around it, where it stands, its address, and anything
 * on the phone that could stop it running.
 */
class NodeFragment : Fragment() {

    private var _binding: FragmentNodeBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()

    private val home get() = parentFragment as? HomeFragment

    /**
     * Registered in code, for as long as this screen is visible, rather than in
     * the manifest: battery saver only matters while someone is looking at the
     * warning about it, and Android no longer delivers this broadcast to
     * manifest receivers anyway.
     */
    private val powerSaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderHealth()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentNodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mesh.onNodeClick = { peerId -> home?.openChat(peerId) }
        binding.statOnline.statLabel.setText(R.string.node_peers_online)
        binding.statFriends.statLabel.setText(R.string.node_peers_known)
        binding.statOutbox.statLabel.setText(R.string.node_outbox)

        binding.copyOnion.setOnClickListener {
            model.status.value.onion?.let { requireContext().copyText(getString(R.string.node_onion_label), it) }
        }
        binding.addressCard.setOnClickListener { home?.openMyCode() }

        setUpHealthRow(binding.healthNotifications, R.string.health_notifications) {
            // The per-app notification screen only exists from Android 8; before
            // that, the app's details page is where notifications are toggled.
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${requireContext().packageName}"))
            }
            runCatching { startActivity(intent) }
        }
        setUpHealthRow(binding.healthBattery, R.string.health_battery) { Power.requestExemption(requireContext()) }
        setUpHealthRow(binding.healthPower, R.string.health_power_saver) {
            runCatching { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
        }

        collectWhileStarted(combine(model.status, model.running, model.fatal) { s, r, f -> Triple(s, r, f) }) { (status, running, fatal) ->
            renderStatus(status, running, fatal)
        }
        collectWhileStarted(model.friends) { renderMesh() }
        collectWhileStarted(model.identity) { identity ->
            identity ?: return@collectWhileStarted
            binding.myAvatar.setImageDrawable(IdenticonDrawable(identity.id))
            binding.myName.text = identity.name
            binding.myId.text = identity.id
            renderMesh()
        }
        collectWhileStarted(model.stats) { stats ->
            binding.statFriends.statValue.text = NumberFormat.getIntegerInstance().format(stats.friends)
            binding.statOutbox.statValue.text = NumberFormat.getIntegerInstance().format(stats.outbox)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            powerSaverReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        renderHealth()
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(powerSaverReceiver) }
        super.onStop()
    }

    private fun renderMesh() {
        val identity = model.identity.value ?: return
        val self = MeshView.Node(identity.id, getString(R.string.you), MeshView.State.ONLINE)
        val peers = model.friends.value.map {
            MeshView.Node(it.id, it.name, if (it.online) MeshView.State.ONLINE else MeshView.State.OFFLINE)
        } + model.requests.value.map { MeshView.Node(it.id, it.name, MeshView.State.PENDING) }
        binding.mesh.setNodes(self, peers)
    }

    private fun renderStatus(status: EngineStatus, running: Boolean, fatal: String?) {
        binding.statOnline.statValue.text = NumberFormat.getIntegerInstance().format(status.online)
        binding.onion.text = status.onion ?: getString(R.string.node_onion_pending)
        binding.copyOnion.isVisible = status.onion != null

        val (title, detail, dot) = when {
            // Between start attempts: the service is about to try again.
            !running && fatal != null -> Triple(getString(R.string.phase_retrying), "${getString(R.string.phase_failed)}: $fatal", R.drawable.bg_dot_warning)
            !running -> Triple(getString(R.string.phase_stopped), getString(R.string.phase_stopped_detail), R.drawable.bg_dot_offline)
            status.torRestarting -> Triple(getString(R.string.phase_restarting), getString(R.string.phase_bootstrapping_detail), R.drawable.bg_dot_warning)
            else -> when (status.phase) {
                TorPhase.OFFLINE -> Triple(getString(R.string.phase_offline), getString(R.string.phase_offline_detail), R.drawable.bg_dot_error)
                TorPhase.BOOTSTRAPPING -> Triple(getString(R.string.phase_bootstrapping), getString(R.string.phase_bootstrapping_detail), R.drawable.bg_dot_warning)
                TorPhase.PUBLISHING -> Triple(getString(R.string.phase_publishing), getString(R.string.phase_publishing_detail), R.drawable.bg_dot_warning)
                TorPhase.PROPAGATING -> Triple(getString(R.string.phase_propagating), getString(R.string.phase_propagating_detail), R.drawable.bg_dot_warning)
                TorPhase.REACHABLE -> Triple(getString(R.string.phase_reachable), getString(R.string.phase_reachable_detail), R.drawable.bg_dot_online)
            }
        }
        binding.phaseTitle.text = title
        binding.phaseDetail.text = detail
        binding.phaseDot.setBackgroundResource(dot)

        val bootstrapping = running && status.phase == TorPhase.BOOTSTRAPPING
        binding.bootstrap.isVisible = bootstrapping
        binding.bootstrap.setProgressCompat(status.bootstrapPercent, true)

        binding.nodeAction.setText(if (running) R.string.restart_node else R.string.start_node)
        binding.nodeAction.setOnClickListener { if (running) model.restartNode() else model.startNode() }
    }

    private fun renderHealth() {
        val binding = _binding ?: return
        val context = requireContext()
        val notificationsOff = !MessageNotifier(context).canPost
        val optimised = !Power.isIgnoringOptimizations(context)
        val saver = Power.isPowerSaveMode(context)

        binding.healthNotifications.root.isVisible = notificationsOff
        binding.healthBattery.root.isVisible = optimised
        binding.healthPower.root.isVisible = saver
        binding.healthOk.isVisible = !notificationsOff && !optimised && !saver
    }

    private fun setUpHealthRow(row: ViewHealthRowBinding, text: Int, fix: () -> Unit) {
        row.healthText.setText(text)
        row.healthFix.setOnClickListener { fix() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a settings screen is the usual way these change.
        renderHealth()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
