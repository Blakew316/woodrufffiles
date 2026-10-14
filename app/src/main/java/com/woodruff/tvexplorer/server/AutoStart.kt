package com.woodruff.tvexplorer.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the phone connection when the TV boots and after Woodruff is updated, so the phone app
 * (including from away, through Tailscale) works without anyone opening Woodruff on the TV first.
 * On by default; Settings → Phone connection turns it off.
 */
object AutoStart {
    private const val PREFS = "startup"
    private const val KEY = "phoneOnBoot"

    fun enabled(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun setEnabled(c: Context, on: Boolean) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Both are protected broadcasts: only the system can send them.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AutoStart.enabled(context)) return
        // Boot and app updates are allowed to start a foreground service from the background.
        runCatching { TransferService.start(context) }.onFailure { Log.w("BootReceiver", "couldn't start the phone connection", it) }
    }
}
