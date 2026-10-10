package com.torchat.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.torchat.MainActivity
import com.torchat.R
import com.torchat.TorChatApp
import com.torchat.core.engine.Engine
import com.torchat.core.engine.EngineEvent
import com.torchat.core.engine.EngineStatus
import com.torchat.data.LegacyImport
import com.torchat.data.Prefs
import com.torchat.data.TorChatContract
import com.torchat.notify.Channels
import com.torchat.notify.MessageNotifier
import com.torchat.widget.NodeWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The process that actually is TorChat.
 *
 * On desktop the client simply stays alive; on Android the moment the UI leaves
 * the foreground the system will kill Tor, the onion service disappears, and
 * message delivery silently ends. So Tor, the local listener and the engine all
 * live in this foreground service (§13).
 *
 * Started *and* bound. It is started (`startForegroundService`) so it outlives
 * every screen, and screens bind to it (`bindService`) to reach the engine.
 *
 * What keeps it running, and what used to stop it:
 *
 *  - **Service type `specialUse`.** It was `dataSync`, which Android 15 limits
 *    to six hours a day; after that the system stops the service, and a node
 *    that had been fine all morning went silent every afternoon. `dataSync`
 *    also may not start from a boot broadcast. A connection that must stay up
 *    indefinitely, because no server exists to hold messages, is exactly what
 *    `specialUse` is for.
 *  - **Retries instead of giving up.** If Tor cannot start — no network yet,
 *    say — it tries again with backoff rather than sitting dead until someone
 *    opens the app.
 *  - **The network observer**, the **keep-alive alarm** and an optional
 *    **wake lock**: see [NodeNetworkObserver], [KeepAlive] and the "always on"
 *    setting.
 *
 * Expect OEM battery managers to fight this regardless. The honest guarantee is
 * "delivery while the service is alive", and there is no push fallback available
 * to a design with no server.
 */
class TorChatService : LifecycleService() {

    /** What a bound screen gets: the engine, as a flow that tracks restarts. */
    inner class LocalBinder : Binder() {
        val engine: StateFlow<Engine?> get() = Session.engineFlow
        val service: TorChatService get() = this@TorChatService
    }

    private val binder = LocalBinder()
    private val app get() = application as TorChatApp
    private val prefs: Prefs get() = Session.prefs(this)
    private val notifier by lazy { MessageNotifier(this) }

    private var foreground = false
    private var engineJob: Job? = null
    private var channelSync: Job? = null
    private var widgetRefresh: Job? = null
    private var startAttempts = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewal: Job? = null
    private var lastStatus: Pair<String, String>? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.ALWAYS_ON -> applyWakeLock()
            Prefs.PREVIEWS -> lifecycleScope.launch(Dispatchers.IO) { notifier.refreshAll() }
        }
    }

    /** Unread and outbox totals changed: the widget and the status line show them. */
    private val statsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshWidgetSoon()
            Session.engine?.let { updateStatus(it.status.value) }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Channels.createBase(this)

        foreground = goForeground(initialNotification())
        if (!foreground) {
            // Android refused: we were started, or restarted, from the
            // background. Say so rather than vanish.
            notifier.showStopped()
            stopSelf()
            return
        }
        notifier.cancelStopped()

        app.networkObserver.listener = NodeNetworkObserver.Listener { available, changed ->
            lifecycleScope.launch { Session.engine?.onNetworkChanged(available, changed) }
        }
        app.networkObserver.start()

        prefs.register(prefsListener)
        applyWakeLock()
        contentResolver.registerContentObserver(TorChatContract.Stats.CONTENT_URI, false, statsObserver)
        KeepAlive.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!foreground) return START_NOT_STICKY

        // Every startForegroundService() must be answered with startForeground(),
        // even when the service is already in the foreground.
        goForeground(statusNotification(lastStatus ?: initialText()))

        when (intent?.action) {
            ACTION_STOP -> {
                prefs.stoppedByUser = true
                lifecycleScope.launch { stopEverything() }
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                prefs.stoppedByUser = false
                lifecycleScope.launch {
                    shutDownEngine()
                    startAttempts = 0
                    startEngine()
                }
            }

            else -> {
                prefs.stoppedByUser = false
                if (Session.engine == null && engineJob == null && !prefs.stoppedByUser) startEngine()
            }
        }

        // If the system kills us anyway, come back and republish.
        return START_STICKY
    }

    // --- the engine ---------------------------------------------------------

    private fun startEngine() {
        val identity = Session.loadIdentity(this) ?: run {
            // Nothing to be without an identity. The UI creates one first.
            lifecycleScope.launch { stopEverything() }
            return
        }

        val secure = Session.secureStore(this)
        val store = Session.store(this)
        val engine = Engine(identity, store, secure, app.tor, identities = secure)

        Session.engine = engine
        Session.fatal.value = null

        engineJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) { LegacyImport.runOnce(this@TorChatService, store) }

            launch { watchEvents(engine) }
            launch { watchStatus(engine) }

            engine.onNetworkChanged(app.networkObserver.isNetworkConnected())
            syncChannelsSoon()

            val failure = runCatching { engine.start() }.exceptionOrNull() ?: run {
                startAttempts = 0
                // Anything that arrived while the node was down and is still
                // unread gets its notification back.
                withContext(Dispatchers.IO) { notifier.refreshAll() }
                return@launch
            }

            // Tor could not start. Try again rather than give up: the usual
            // cause is a network that was not there yet.
            val wait = START_RETRY_DELAYS_MS[startAttempts.coerceAtMost(START_RETRY_DELAYS_MS.lastIndex)]
            startAttempts++
            val reason = failure.message ?: getString(R.string.phase_failed)
            Log.w(TAG, "the node could not start (attempt $startAttempts): $reason", failure)
            Session.appendLog("could not start: $reason — retrying in ${wait / 1000}s")
            Session.fatal.value = reason
            updateStatus(getString(R.string.phase_failed), "$reason · retrying in ${wait / 1000}s", force = true)

            withContext(NonCancellable) {
                runCatching { engine.stop() }
                runCatching { app.tor.stopAndWait() }
            }
            if (Session.engine === engine) Session.engine = null
            engineJob = null

            delay(wait)
            if (Session.engine == null && engineJob == null && !prefs.stoppedByUser) startEngine()
        }
    }

    private suspend fun shutDownEngine() {
        val job = engineJob
        engineJob = null
        val engine = Session.engine
        Session.engine = null
        job?.cancel()

        withContext(NonCancellable) {
            runCatching { engine?.stop() }
            // The daemon has to be stopped explicitly, not just flagged as
            // gone: an orphaned tor keeps the onion address published with
            // nothing answering it, which looks to a friend exactly like being
            // ignored.
            runCatching { app.tor.stopAndWait() }
        }
        refreshWidgetSoon()
    }

    private suspend fun stopEverything() {
        shutDownEngine()
        Session.fatal.value = null
        KeepAlive.cancel(this)
        app.networkObserver.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(statsObserver) }
        prefs.unregister(prefsListener)

        if (Session.engine != null) {
            // Being destroyed without having been asked to stop — the system
            // reclaiming us, or stopService(). Take Tor down with us, briefly.
            runBlocking { withTimeoutOrNull(SHUTDOWN_BUDGET_MS) { shutDownEngine() } }
            app.networkObserver.stop()
        }
        releaseWakeLock()
        NodeWidgetProvider.requestRefresh(this)
        super.onDestroy()
    }

    /**
     * Android 15+ calls this when a time-limited foreground service type runs
     * out. `specialUse` has no limit, but should that ever change the service
     * must stop within seconds or the app is killed — so stop, and tell the
     * person.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timed out")
        notifier.showStopped()
        lifecycleScope.launch { stopEverything() }
    }

    // --- what the engine says -----------------------------------------------

    private suspend fun watchEvents(engine: Engine) {
        val store = Session.store(this)
        engine.events.collect { event ->
            when (event) {
                is EngineEvent.MessageReceived -> withContext(Dispatchers.IO) {
                    // The conversation the person is looking at does not need a
                    // notification for a message they can already see.
                    if (Session.visibleConversation.value == event.peerId) {
                        store.markRead(event.peerId)
                    } else {
                        notifier.showConversation(event.peerId)
                    }
                }

                // A request is worth interrupting for: nothing happens until it
                // is answered, and the person asking is sitting there redialling.
                is EngineEvent.RequestReceived -> withContext(Dispatchers.IO) {
                    notifier.showRequest(event.peerId, event.name)
                }

                is EngineEvent.Status -> withContext(Dispatchers.IO) {
                    store.setLastSeen(event.peerId, System.currentTimeMillis())
                }

                EngineEvent.FriendsChanged -> syncChannelsSoon()

                is EngineEvent.Log -> {
                    Session.appendLog(event.text)
                    // Also to logcat: a Tor bootstrap that stalls is otherwise
                    // invisible from outside the app.
                    Log.i(TAG, event.text)
                }

                else -> Unit
            }
        }
    }

    private suspend fun watchStatus(engine: Engine) {
        engine.status.collect { status ->
            updateStatus(status)
            refreshWidgetSoon()
        }
    }

    /**
     * Keep each chat's notification channel and shortcut in step with the
     * friend list. Coalesced, because a single connection can save the friend
     * list several times in a row.
     */
    private fun syncChannelsSoon() {
        channelSync?.cancel()
        channelSync = lifecycleScope.launch(Dispatchers.IO) {
            delay(CHANNEL_SYNC_DELAY_MS)
            val store = Session.store(this@TorChatService)
            val friends = store.readFriends()
            val muted = friends.filter { store.isMuted(it.id) }.map { it.id }.toSet()
            Channels.sync(this@TorChatService, friends, muted)
            for (friend in friends) {
                if (!friend.isAccepted) {
                    notifier.removeShortcut(friend.id)
                    notifier.cancelConversation(friend.id)
                }
                if (!friend.isPending) notifier.cancelRequest(friend.id)
            }
        }
    }

    private fun refreshWidgetSoon() {
        widgetRefresh?.cancel()
        widgetRefresh = lifecycleScope.launch {
            delay(WIDGET_REFRESH_DELAY_MS)
            NodeWidgetProvider.requestRefresh(this@TorChatService)
        }
    }

    // --- the status notification --------------------------------------------

    private fun updateStatus(status: EngineStatus) {
        val title = NodeWidgetProvider.phaseLabel(this, status)
        val outbox = runCatching {
            contentResolver.query(TorChatContract.Stats.CONTENT_URI, null, null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(TorChatContract.Stats.OUTBOX)) else 0
            } ?: 0
        }.getOrDefault(0)

        val text = buildString {
            append(getString(R.string.widget_online, status.online, status.friends))
            if (outbox > 0) append(" · $outbox ${getString(R.string.node_outbox)}")
        }
        updateStatus(title, text)
    }

    private fun updateStatus(title: String, text: String, force: Boolean = false) {
        val next = title to text
        if (!force && next == lastStatus) return
        lastStatus = next
        runCatching {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(this).notify(MessageNotifier.ID_STATUS, statusNotification(next))
            }
        }
    }

    private fun initialText() = getString(R.string.phase_bootstrapping) to getString(R.string.app_name)

    private fun initialNotification(): Notification = statusNotification(initialText())

    private fun statusNotification(content: Pair<String, String>): Notification =
        NotificationCompat.Builder(this, Channels.NODE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.tc_primary))
            .setContentTitle(content.first)
            .setContentText(content.second)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntentCompat.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT, false,
                )
            )
            .addAction(
                R.drawable.ic_refresh,
                getString(R.string.restart_node),
                PendingIntentCompat.getService(
                    this, 2, Intent(this, TorChatService::class.java).setAction(ACTION_RESTART),
                    PendingIntent.FLAG_UPDATE_CURRENT, false,
                ),
            )
            .addAction(
                R.drawable.ic_power,
                getString(R.string.stop_node),
                PendingIntentCompat.getService(
                    this, 1, Intent(this, TorChatService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT, false,
                ),
            )
            .build()

    /**
     * Enter the foreground. Android 14+ requires the type at the call site as
     * well as in the manifest. Returns false if Android refused — which it does
     * for a start from the background on Android 12+.
     */
    private fun goForeground(notification: Notification): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    MessageNotifier.ID_STATUS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(MessageNotifier.ID_STATUS, notification)
            }
            true
        } catch (failure: Exception) {
            Log.w(TAG, "could not enter the foreground", failure)
            false
        }

    // --- the wake lock ------------------------------------------------------

    /**
     * Always-on mode: hold the processor awake while the node runs.
     *
     * Acquired with a timeout and renewed before it lapses, never open-ended —
     * so if anything here ever forgets to release it, Android cleans up within
     * the hour rather than the phone staying awake until it is rebooted.
     */
    private fun applyWakeLock() {
        if (!prefs.alwaysOn) {
            releaseWakeLock()
            return
        }

        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorChat:always-on")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
            ?: return
        lock.acquire(WAKE_LOCK_MS)

        if (wakeRenewal?.isActive != true) {
            wakeRenewal = lifecycleScope.launch {
                while (true) {
                    delay(WAKE_LOCK_RENEW_MS)
                    if (!prefs.alwaysOn) break
                    wakeLock?.acquire(WAKE_LOCK_MS)
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeRenewal?.cancel()
        wakeRenewal = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.torchat.action.STOP"
        const val ACTION_RESTART = "com.torchat.action.RESTART"

        private const val TAG = "TorChat"

        private val START_RETRY_DELAYS_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000)
        private const val SHUTDOWN_BUDGET_MS = 4_000L
        private const val CHANNEL_SYNC_DELAY_MS = 400L
        private const val WIDGET_REFRESH_DELAY_MS = 300L
        private const val WAKE_LOCK_MS = 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 50 * 60 * 1000L

        /**
         * Should the node be running? Yes if there is an identity and the person
         * has not stopped it — and, for a boot, if they asked for that.
         */
        fun shouldRun(context: Context, forBoot: Boolean = false): Boolean {
            val prefs = Session.prefs(context)
            if (Session.loadIdentity(context) == null) return false
            if (prefs.stoppedByUser) return false
            return !forBoot || prefs.startOnBoot
        }

        /**
         * Start the node. Safe to call repeatedly.
         *
         * Android 12+ throws `ForegroundServiceStartNotAllowedException` for a
         * start from the background, and an uncaught one kills the process.
         * Caught here, and turned into a notification the person can tap —
         * a node that quietly fails to start is the worst outcome there is.
         */
        fun start(context: Context): Boolean {
            val failure = runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TorChatService::class.java))
            }.exceptionOrNull() ?: return true

            Log.w(TAG, "could not start the service", failure)
            Session.fatal.value = context.getString(R.string.notif_stopped_body)
            MessageNotifier(context).showStopped()
            return false
        }

        /** Tear the node down and bring it straight back. */
        fun restart(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TorChatService::class.java).setAction(ACTION_RESTART),
                )
            }
        }

        /**
         * Stop the node at the person's request. Remembered, so neither a reboot
         * nor the keep-alive alarm brings back something they turned off.
         */
        fun stop(context: Context) {
            Session.prefs(context).stoppedByUser = true
            Session.fatal.value = null
            context.stopService(Intent(context, TorChatService::class.java))
        }
    }
}
