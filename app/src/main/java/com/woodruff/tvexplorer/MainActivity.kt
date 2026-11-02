package com.woodruff.tvexplorer

import com.woodruff.tvexplorer.ui.motion.AppMotion
import com.woodruff.tvexplorer.ui.motion.useAppMotion
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
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.motion.DpadGate
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.screens.PermissionScreen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.ThemeSetting
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.util.Permissions
import com.woodruff.tvexplorer.util.Presence
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    /** Streaming's state, the same one the screens show (made by them, on this thread, when first needed). */
    private val drive: DriveViewModel by viewModels()
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
        // The app's motion plays at its own speed, even on a TV with Android's animations turned off.
        window.decorView.useAppMotion(lifecycle)
        setContent {
            TvExplorerTheme {
                // No background here: AppRoot's backdrop and the permission screen paint the whole screen.
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
        // Both of those paint the whole window, opaquely, on every screen, so the window's own
        // background colour under them was a full-screen fill drawn for nothing on every frame.
        window.setBackgroundDrawable(null)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        // Media broadcasts come from the system, so the receiver doesn't need to be exported.
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        watchBusy()
        // The in-app slideshow's timer, while Woodruff is up: set again whenever its setting changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { snapshotFlow { Appearance.idleMinutes }.collect { armIdle() } }
        }
    }

    override fun onStart() {
        super.onStart()
        TvScreen.attach(this)
        AppVisibility.foreground = true
        // Keep the phone connection (and new-mail checks) running while the app is in use.
        if (hasAccess) runCatching { TransferService.start(this) }
        // An update that needs a press is offered now, as Woodruff comes on screen, unless it was
        // opened for something in particular (a notification, the doorbell): that comes first.
        com.woodruff.tvexplorer.update.Updater.maybeAuto(this, opening = true, offer = hasAccess && !openedFor)
        openedFor = false
    }

    /** This start was for a particular screen ([EXTRA_OPEN]): no update offer in front of it. */
    private var openedFor = false

    override fun onStop() {
        TvScreen.detach(this)
        AppVisibility.foreground = false
        idle.removeCallbacks(startSlideshow)
        super.onStop()
    }

    /** In-app screensaver: after [Appearance.idleMinutes] without anyone using Woodruff, the slideshow. */
    private val idle = Handler(Looper.getMainLooper())
    private val startSlideshow = Runnable { armIdle() }

    /**
     * Sets the slideshow's timer from the last time anyone used Woodruff, anywhere in it: a key in a
     * title page, a menu or the filters counts as much as one here ([Presence]). When the time comes
     * it looks again, so keys pressed meanwhile push it back instead of the slideshow starting under
     * someone's fingers.
     */
    private fun armIdle() {
        idle.removeCallbacks(startSlideshow)
        val wait = Presence.slideshowInMs(Appearance.idleMinutes) ?: return
        when {
            wait > 0 -> idle.postDelayed(startSlideshow, wait)
            AppVisibility.foreground && hasAccess -> SlideshowActivity.start(this)
        }
    }

    /** A copy, move or zip, or a Google Drive download, under way: an update waits for it to finish. */
    private var busy: Presence.Check? = null

    private fun watchBusy() {
        busy?.stop()
        val files = vm
        // Streaming's only once there's access: the screens that make it aren't up before.
        val streaming = if (hasAccess) drive else null
        busy = Presence.whileBusy { files.operation.value != null || streaming?.state?.value?.operation != null }
    }

    /**
     * Holding an arrow: Android repeats it about 20 times a second. When drawing falls behind, the
     * repeats queue up and the focus keeps sliding after the button is let go, which is what makes
     * scrolling feel laggy. [DpadGate] lets a repeat through only once the previous move has been
     * drawn, and drops stale ones; single presses always go through. (Dialog windows, whose keys
     * never come through here, have the same gate: Modifier.dpadGate().) Each key that goes through
     * is timed for Settings → Smoothness: the frames after it count as navigating, and a key that
     * keeps the main thread busy shows up in the phone's Smoothness Report.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (DpadGate.shouldDrop(event)) return true
        val started = com.woodruff.tvexplorer.util.FrameStats.keyStarted(event)
        try {
            return super.dispatchKeyEvent(event)
        } finally {
            com.woodruff.tvexplorer.util.FrameStats.keyFinished(event, started)
        }
    }

    /** The remote's search button (if the TV hands it to apps): voice search. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            com.woodruff.tvexplorer.ui.VoiceSearch.requests.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Notification taps land on the matching screen. */
    private fun handleOpen(intent: Intent?) {
        // (Only when this brings Woodruff on screen: a tap while it's showing starts nothing.)
        openedFor = intent?.getStringExtra(EXTRA_OPEN) != null && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            "notifications" -> vm.show(Screen.NOTIFICATIONS)
            "home" -> vm.show(Screen.HOME)
        }
    }

    override fun onResume() {
        super.onResume()
        // The TV's window animations may have been turned on or off in Android's settings meanwhile.
        AppMotion.refresh(this)
        com.woodruff.tvexplorer.util.FrameStats.attach(this)
        recheck() // the user may have just granted access in Settings
    }

    override fun onPause() {
        com.woodruff.tvexplorer.util.FrameStats.detach(this)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(mediaReceiver)
        busy?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }

    private fun recheck() {
        val now = Permissions.hasStorageAccess(this)
        val granted = now && !hasAccess
        if (granted) vm.refreshRoots()
        hasAccess = now
        if (granted) watchBusy()
    }
}
