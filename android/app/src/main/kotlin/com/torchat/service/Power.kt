package com.torchat.service

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * What the phone's power management is doing to the node, and the way out.
 *
 * There is no push server to wake this app up — a design with no server cannot
 * have one — so whether messages arrive with the screen off depends entirely on
 * Android letting the node run. Battery optimisation is the biggest obstacle,
 * and the person is the only one who can lift it.
 */
object Power {

    fun isIgnoringOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    fun isPowerSaveMode(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false

    /**
     * Ask to be exempted, falling back to the general list on phones whose
     * vendor removed the direct prompt.
     */
    @SuppressLint("BatteryLife") // the whole point of the app is to stay reachable
    fun requestExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(fallback) }
        } catch (_: SecurityException) {
            runCatching { context.startActivity(fallback) }
        }
    }
}
