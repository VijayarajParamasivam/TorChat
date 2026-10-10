package com.torchat

import android.app.Application
import com.torchat.notify.Channels
import com.torchat.service.NodeNetworkObserver
import com.torchat.service.Session
import com.torchat.tor.AndroidTorProvider

class TorChatApp : Application() {

    /**
     * One per process, living as long as the process does. kmp-tor allows one
     * runtime per working directory, so the node restarting — after a crash of
     * the daemon, or the person pressing restart — must reuse this one rather
     * than build a second over the same files.
     */
    val networkObserver: NodeNetworkObserver by lazy { NodeNetworkObserver(this) }
    val tor: AndroidTorProvider by lazy { AndroidTorProvider(this, networkObserver) }

    override fun onCreate() {
        super.onCreate()

        // There is no sign-in: either an identity exists on this device or it
        // does not.
        Session.loadIdentity(this)

        // Channels exist before anything can try to post on them.
        Channels.createBase(this)

        // The service is deliberately **not** started here. Application.onCreate
        // runs whenever the process is created — including when the system
        // recreates it in the background — and starting a foreground service
        // from the background has been forbidden since Android 12. MainActivity,
        // the boot receiver and notification actions start it instead, each
        // from a moment Android permits.
    }
}
