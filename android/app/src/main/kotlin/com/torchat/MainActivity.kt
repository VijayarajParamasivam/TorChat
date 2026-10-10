package com.torchat

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.torchat.core.engine.EngineStatus
import com.torchat.core.engine.TorPhase
import com.torchat.databinding.ActivityMainBinding
import com.torchat.service.Session
import com.torchat.service.TorChatService
import com.torchat.ui.MainViewModel
import com.torchat.widget.NodeWidgetProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The one activity. It owns the action bar and the navigation host; every
 * screen is a fragment in res/navigation/nav_graph.xml.
 *
 * It also starts the node and binds to it whenever it is visible: starting is
 * what keeps the node alive after the screen goes, binding is how screens
 * reach it while they are up.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val model: MainViewModel by viewModels()

    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = host.navController
        appBarConfiguration = AppBarConfiguration(setOf(R.id.homeFragment, R.id.onboardingFragment))
        setupActionBarWithNavController(navController, appBarConfiguration)

        applyInsets()

        // No identity yet: everything else is unreachable until there is one.
        if (Session.identity == null && navController.currentDestination?.id != R.id.onboardingFragment) {
            navController.navigate(R.id.action_global_onboarding)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Each screen owns its own subtitle; only home shows the node's.
            if (destination.id != R.id.homeFragment) supportActionBar?.subtitle = null
            renderStatus(model.status.value, model.running.value, model.fatal.value)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(model.status, model.running, model.fatal) { status, running, fatal -> Triple(status, running, fatal) }
                    .collect { (status, running, fatal) -> renderStatus(status, running, fatal) }
            }
        }

        if (Session.identity != null) askForNotifications()
    }

    /**
     * Android 15 draws every app edge to edge, so the bars' space is handed out
     * here: the top to the action bar, the bottom — the navigation bar, or the
     * keyboard when it is up — to the screens. A chat composer that slid under
     * the keyboard would make the app unusable.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.appBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.navHost.updatePadding(left = bars.left, right = bars.right, bottom = max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onStart() {
        super.onStart()
        // From the foreground, where Android allows a foreground service to
        // start — so a node the system killed while we were away comes back
        // the moment the person returns.
        if (TorChatService.shouldRun(this)) {
            TorChatService.start(this)
            bound = bindService(Intent(this, TorChatService::class.java), connection, 0)
        }
        NodeWidgetProvider.requestRefresh(this)
    }

    override fun onStop() {
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A notification, the widget or a shortcut while we are already open.
        navController.handleDeepLink(intent)
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    /** Called by onboarding once there is an identity to start. */
    fun onIdentityReady() {
        askForNotifications()
        if (TorChatService.shouldRun(this)) {
            bound = bindService(Intent(this, TorChatService::class.java), connection, 0)
        }
    }

    /**
     * On the home screen the action bar's subtitle is the node's heartbeat:
     * a coloured dot and one line saying where it stands.
     */
    private fun renderStatus(status: EngineStatus, running: Boolean, fatal: String?) {
        val onHome = navController.currentDestination?.id == R.id.homeFragment
        val joining = running && (status.phase == TorPhase.BOOTSTRAPPING || status.phase == TorPhase.PUBLISHING || status.torRestarting)
        binding.nodeProgress.visibility = if (onHome && joining) View.VISIBLE else View.GONE

        if (!onHome) return
        val (label, color) = when {
            // Between attempts to start Tor the engine is gone but the node is not
            // stopped — it is about to try again, and saying "stopped" sent people
            // looking for a button to press.
            !running && fatal != null -> getString(R.string.phase_retrying) to R.color.tc_warning
            !running -> getString(R.string.phase_stopped) to R.color.tc_offline
            status.torRestarting -> getString(R.string.phase_restarting) to R.color.tc_warning
            status.phase == TorPhase.OFFLINE -> getString(R.string.phase_offline) to R.color.tc_error
            status.phase == TorPhase.REACHABLE ->
                "${getString(R.string.phase_reachable)} · ${getString(R.string.widget_online, status.online, status.friends)}" to R.color.tc_signal
            else -> NodeWidgetProvider.phaseLabel(this, status) to R.color.tc_warning
        }

        val subtitle = SpannableStringBuilder("● ").apply {
            setSpan(ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, color)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(label)
        }
        supportActionBar?.subtitle = subtitle
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
