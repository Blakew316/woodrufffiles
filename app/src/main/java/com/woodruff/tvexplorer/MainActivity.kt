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

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasAccess = Permissions.hasStorageAccess(this)
        ThemeSetting.load(this)
        Notices.load(this)
        com.woodruff.tvexplorer.util.CrashLog.report(this)
        Appearance.load(this)
        handleOpen(intent)
        setContent {
            TvExplorerTheme {
                // No background here: the window and AppRoot's backdrop already paint the whole screen.
                // Lists and grids follow the focus with a quick, bounce-free scroll (see SmoothBringIntoView).
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.woodruff.tvexplorer.ui.SmoothBringIntoView,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (hasAccess) AppRoot(vm) else PermissionScreen(onRecheck = ::recheck)
                    }
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
        com.woodruff.tvexplorer.update.Updater.maybeAuto(this, opening = true)
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

    private var lastRepeatAt = 0L

    /**
     * Holding an arrow: Android repeats it every ~50 ms. When a frame takes longer than that, the
     * repeats queue up and the focus keeps sliding after the button is let go, which is what makes
     * scrolling feel laggy. Repeats that are already stale, or arrive faster than the screen can
     * follow (one per 70 ms), are dropped; single presses always go through.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0 && isArrow(event.keyCode)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - event.eventTime > 120 || now - lastRepeatAt < 70) return true
            lastRepeatAt = now
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isArrow(code: Int) = code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
        code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT

    /** The remote's search button (if the TV hands it to apps): voice search. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            com.woodruff.tvexplorer.ui.VoiceSearch.requests.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppVisibility.lastInput = System.currentTimeMillis()
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
        com.woodruff.tvexplorer.util.FrameStats.attach(this)
        recheck() // the user may have just granted access in Settings
    }

    override fun onPause() {
        com.woodruff.tvexplorer.util.FrameStats.detach(this)
        super.onPause()
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
