package com.torchat.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.torchat.databinding.FragmentDiagnosticsBinding
import com.torchat.service.Power
import com.torchat.ui.ComponentLauncher
import com.torchat.ui.MainViewModel
import com.torchat.ui.scan.ScanContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Each of Android's four component kinds, activated by its own mechanism, with
 * the result written to a console underneath. Every button goes through
 * [ComponentLauncher] — the same methods the rest of the app uses.
 */
class DiagnosticsFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    private val model: MainViewModel by activityViewModels()

    private val scan = registerForActivityResult(ScanContract()) { code ->
        report("activity result: ${code?.let { "a code of ${it.length} chars" } ?: "cancelled"}")
    }

    private var connection: ServiceConnection? = null

    /** Registered at runtime, only while the switch is on. */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            report("broadcast ${intent.action?.substringAfterLast('.')}: battery saver ${if (Power.isPowerSaveMode(context)) "on" else "off"}")
        }
    }
    private var receiverRegistered = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()

        // Activities
        binding.activityExplicit.setOnClickListener {
            report("startActivityForResult → ScanActivity (explicit)")
            ComponentLauncher.openScanner(scan)
        }
        binding.activityImplicit.setOnClickListener {
            val card = model.card.value ?: return@setOnClickListener report("no code yet — tor has not published an address")
            report("startActivity(ACTION_SEND, text/plain) → chooser (implicit)")
            ComponentLauncher.shareCode(context, card)
        }
        binding.activityImport.setOnClickListener {
            val card = model.card.value ?: return@setOnClickListener report("no code yet — tor has not published an address")
            report("startActivity → ImportCardActivity with EXTRA_TEXT")
            ComponentLauncher.openImport(context, card)
        }

        // Services
        binding.serviceStart.setOnClickListener {
            report("startForegroundService → TorChatService: ${if (ComponentLauncher.startNode(context)) "accepted" else "refused by Android"}")
        }
        binding.serviceBind.setOnClickListener {
            if (connection != null) return@setOnClickListener report("already bound")
            val next = ComponentLauncher.connection { message -> report(message) }
            val ok = ComponentLauncher.bindNode(context, next)
            if (ok) connection = next
            report("bindService → ${if (ok) "binding" else "failed"}")
        }
        binding.serviceUnbind.setOnClickListener { unbind() }
        binding.serviceStop.setOnClickListener {
            unbind()
            ComponentLauncher.stopNode(context)
            report("stopService → TorChatService stopping")
        }

        // Broadcast receivers
        binding.receiverKeepalive.setOnClickListener {
            ComponentLauncher.sendKeepAlive(context)
            report("sendBroadcast(KEEP_ALIVE) → KeepAliveReceiver: links probed, due friends dialled")
        }
        binding.receiverWidget.setOnClickListener {
            ComponentLauncher.refreshWidget(context)
            report("sendBroadcast(WIDGET_REFRESH) → NodeWidgetProvider")
        }
        binding.receiverDynamic.setOnCheckedChangeListener { _, on -> setDynamicReceiver(on) }

        // Content providers
        binding.providerQuery.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ComponentLauncher.queryProvider(context.contentResolver) }
                        .getOrElse { "query failed: ${it.message}" }
                }
                report("ContentResolver.query(content://com.torchat.provider/…)\n$result")
            }
        }
        binding.providerFile.setOnClickListener {
            val card = model.card.value ?: return@setOnClickListener report("no code yet — tor has not published an address")
            val ok = ComponentLauncher.shareQrImage(context, card)
            report("FileProvider.getUriForFile → ${if (ok) "content:// URI granted to the chosen app" else "failed"}")
        }
    }

    private fun setDynamicReceiver(on: Boolean) {
        val context = requireContext()
        if (on && !receiverRegistered) {
            ContextCompat.registerReceiver(
                context, powerReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            report("registerReceiver(POWER_SAVE_MODE_CHANGED) — toggle battery saver to see it fire")
        } else if (!on && receiverRegistered) {
            runCatching { context.unregisterReceiver(powerReceiver) }
            receiverRegistered = false
            report("unregisterReceiver")
        }
    }

    private fun unbind() {
        val current = connection ?: return report("not bound")
        ComponentLauncher.unbindNode(requireContext(), current)
        connection = null
        report("unbindService")
    }

    private fun report(line: String) {
        val binding = _binding ?: return
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        binding.console.append("[$stamp] $line\n")
    }

    override fun onDestroyView() {
        connection?.let { ComponentLauncher.unbindNode(requireContext(), it) }
        connection = null
        if (receiverRegistered) runCatching { requireContext().unregisterReceiver(powerReceiver) }
        receiverRegistered = false
        _binding = null
        super.onDestroyView()
    }
}
