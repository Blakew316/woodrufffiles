package com.woodruff.tvexplorer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.screens.PermissionScreen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.ThemeSetting
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.util.Permissions

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)

    /** Refreshes the storage list when a USB drive or SD card is plugged in or removed. */
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refreshRoots()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasAccess = Permissions.hasStorageAccess(this)
        ThemeSetting.load(this)
        Notices.load(this)
        Appearance.load(this)
        handleOpen(intent)
        setContent {
            TvExplorerTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) {
                    if (hasAccess) AppRoot(vm) else PermissionScreen(onRecheck = ::recheck)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        // Media broadcasts come from the system, so the receiver doesn't need to be exported.
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart() {
        super.onStart()
        TvScreen.attach(this)
        AppVisibility.foreground = true
        // Keep the phone connection (and new-mail checks) running while the app is in use.
        if (hasAccess) runCatching { TransferService.start(this) }
        armIdle()
    }

    override fun onStop() {
        TvScreen.detach(this)
        AppVisibility.foreground = false
        idle.removeCallbacks(startSlideshow)
        super.onStop()
    }

    /** In-app screensaver: after [Appearance.idleMinutes] without a button press, show the slideshow. */
    private val idle = Handler(Looper.getMainLooper())
    private val startSlideshow = Runnable { if (AppVisibility.foreground && hasAccess) SlideshowActivity.start(this) }

    private fun armIdle() {
        idle.removeCallbacks(startSlideshow)
        val minutes = Appearance.idleMinutes
        if (minutes > 0) idle.postDelayed(startSlideshow, minutes * 60_000L)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        armIdle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Notification taps land on the matching screen. */
    private fun handleOpen(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            "notifications" -> vm.show(Screen.NOTIFICATIONS)
            "home" -> vm.show(Screen.HOME)
        }
    }

    override fun onResume() {
        super.onResume()
        recheck() // the user may have just granted access in Settings
    }

    override fun onDestroy() {
        unregisterReceiver(mediaReceiver)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }

    private fun recheck() {
        val now = Permissions.hasStorageAccess(this)
        if (now && !hasAccess) vm.refreshRoots()
        hasAccess = now
    }
}
