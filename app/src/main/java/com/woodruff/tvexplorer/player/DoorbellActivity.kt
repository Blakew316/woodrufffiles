package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.ui.components.rememberOfflineShown
import com.woodruff.tvexplorer.ui.motion.AppMotion
import com.woodruff.tvexplorer.ui.motion.DpadGate
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.useAppMotion
import com.woodruff.tvexplorer.ui.screens.CameraCaption
import com.woodruff.tvexplorer.ui.screens.CameraPicture
import com.woodruff.tvexplorer.ui.screens.CameraScrim
import com.woodruff.tvexplorer.ui.screens.CameraWords
import com.woodruff.tvexplorer.ui.screens.LiveVideo
import com.woodruff.tvexplorer.ui.screens.cameraHint
import com.woodruff.tvexplorer.ui.screens.rangAt
import com.woodruff.tvexplorer.ui.theme.ThemeSetting
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.util.NetworkWatch
import kotlinx.coroutines.Job
import java.text.DateFormat

/**
 * The doorbell's camera, live with its sound, over whatever showed when the doorbell rang
 * ([Origin]): the film (OK on the player's "Someone's at the door" card: the player keeps its place
 * underneath), Woodruff's slideshow, Woodruff as the TV's screensaver, or one of Woodruff's screens
 * (which stays exactly as it was underneath). Back returns there; Left / Right go through the other
 * cameras. A ring while it's up shows that camera, with the time it rang. Over anything but a film
 * it closes by itself 2 minutes after the last key press, and when it's left (Home, standby), so a
 * camera nobody is watching doesn't stream on with the doorbell's sound. While it's up, no update
 * restarts Woodruff under it ([com.woodruff.tvexplorer.update.OnScreen.Live]); one waiting goes in
 * once it has closed, and puts back what was under it.
 */
class DoorbellActivity : ComponentActivity(), com.woodruff.tvexplorer.update.OnScreen.Live {
    /** What the camera opened over: what Back returns to, and whether it closes by itself. */
    enum class Origin { FILM, SLIDESHOW, SCREENSAVER, SCREEN }

    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    /** Left for something else and not back: it goes (see onStop). */
    private val leave = Runnable { if (!isFinishing) finish() }
    private var origin = Origin.FILM
    private var watch: Doorbell.Watch? = null
    /** The latest ring while it's up: the view switches to that camera. */
    private var asked by mutableStateOf<Doorbell.Ring?>(null)
    /** Going: with the TV's window animations off, the black cover fades in first. */
    private var leaving by mutableStateOf(false)
    /** The TV's window animations are off: the picture fades from and to black itself. */
    private var fades = false
    /** Reading whether the cameras are connected (off the main thread); until it's done that isn't news. */
    private var checking: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSetting.load(this)
        checking = Nest.warm() // lists the cameras if this TV hasn't yet (off the main thread)
        origin = runCatching { Origin.valueOf(intent.getStringExtra(EXTRA_ORIGIN).orEmpty()) }.getOrDefault(Origin.FILM)
        val camera = intent.getStringExtra(EXTRA_CAMERA)
        // When the ring that opened it rang (from the player's card: the doorbell remembers).
        val since = intent.getLongExtra(EXTRA_AT, 0L).takeIf { it > 0 }
            ?: camera?.let { Doorbell.ringTimes.value[it] } ?: Long.MAX_VALUE
        val back = backPhrase(origin, intent.getStringExtra(EXTRA_BACK_TO))
        // The app's motion plays at its own speed, even on a TV with Android's animations turned off; and
        // whether Android will glide this screen in and out at all (its window animation scale).
        window.decorView.useAppMotion(lifecycle)
        AppMotion.refresh(this)
        fades = !(AppMotion.windowsAnimate || AppMotion.reduced)
        val clock = android.text.format.DateFormat.getTimeFormat(this)
        setContent { TvExplorerTheme { Camera(camera, since, back, clock) } }
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(leave)
        com.woodruff.tvexplorer.remote.TvScreen.attach(this)
        // Another ring while it's up: that camera, here (it's the notice; nothing pops up over it).
        // Going away already, it lets the ring go: its card pops up a moment later instead.
        watch = Doorbell.watchFor(Doorbell.Host.VIEW) { ring ->
            if (isFinishing || leaving) Doorbell.unclaim(ring)
            else { asked = ring; armAutoClose() }
        }
        armAutoClose()
    }

    /**
     * Opened again while it's up or still opening (it's single-top): another doorbell rang just then,
     * on a screen that hadn't seen this one yet. Its camera shows here, so there's one view and one Back.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val camera = intent.getStringExtra(EXTRA_CAMERA) ?: return
        val at = intent.getLongExtra(EXTRA_AT, 0L).takeIf { it > 0 } ?: Doorbell.ringTimes.value[camera] ?: System.currentTimeMillis()
        if (!isFinishing && !leaving) asked = Doorbell.Ring(0, camera, at, Doorbell.Host.VIEW)
        armAutoClose()
    }

    /** Left on purpose (the remote's Home, another app): over Woodruff's slideshow, that ends too, so Woodruff opens to itself next time. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (origin == Origin.SLIDESHOW) com.woodruff.tvexplorer.screensaver.SlideshowActivity.leftFromAbove()
    }

    override fun onStop() {
        watch?.stop()
        watch = null
        main.removeCallbacks(autoClose)
        com.woodruff.tvexplorer.remote.TvScreen.detach(this)
        super.onStop()
        // Left for something else (Home, standby, another app): over a film it waits (Back returns to
        // the film as it was); anywhere else the visitor is long gone by the time anyone comes back.
        // Not at once: opened from the TV's screensaver, Android may start it behind the screensaver
        // and stop it again until the screensaver has gone.
        if (origin != Origin.FILM && !isChangingConfigurations) main.postDelayed(leave, LEAVE_GRACE_MS)
    }

    override fun onDestroy() {
        main.removeCallbacks(leave)
        main.removeCallbacks(autoClose)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any key while it's up: someone's watching, the 2 minutes start again.
        if (event.action == KeyEvent.ACTION_DOWN) armAutoClose()
        // Left / Right held: the next camera only once the last one's move has been drawn, as on
        // Woodruff's other screens, instead of a camera (and a stream to start) per repeat.
        if (DpadGate.shouldDrop(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun armAutoClose() {
        main.removeCallbacks(autoClose)
        if (autoCloses(origin)) main.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    /** Away as Back does it: the pop-up's window animation, or the cover fading to black first. */
    private fun close() {
        if (isFinishing) return
        if (fades) leaving = true else finish()
    }

    @Composable
    private fun Camera(camera: String?, since: Long, back: String, clock: DateFormat) {
        val state by Nest.state.collectAsStateWithLifecycle()
        val times by Doorbell.ringTimes.collectAsStateWithLifecycle()
        val networkUp by NetworkWatch.online.collectAsStateWithLifecycle()
        val cams = state.cameras
        // The camera that rang first, then Left / Right go through the rest. A ring while it's up
        // asks for its camera (another doorbell, or this one again). One the list doesn't have yet
        // (just added) shows as soon as the list has it, unless Left / Right chose another meanwhile.
        var wanted by remember { mutableStateOf(camera) }
        val ring = asked
        LaunchedEffect(ring) { if (ring != null) wanted = ring.camera }
        var index by remember { mutableIntStateOf(-1) }
        LaunchedEffect(cams, wanted) {
            val i = cams.indexOfFirst { it.name == wanted }
            if (i >= 0) index = i else if (index !in cams.indices) index = if (cams.isEmpty()) -1 else 0
        }
        val cam = cams.getOrNull(index)
        // Offline words only after the grace every offline state waits (a TV just woken is usually
        // back on its Wi-Fi by then), and gone the moment it's back.
        val offline = rememberOfflineShown(!networkUp)
        // Whether the cameras are connected has been read since this opened (a moment, off the main
        // thread): before that, a view Woodruff just started again isn't told they aren't.
        var checked by remember { mutableStateOf(checking?.isCompleted != false) }
        LaunchedEffect(Unit) { checking?.join(); checked = true }
        val focus = remember { FocusRequester() }
        // Android glides this screen in like a pop-up, and back out. With the TV's window animations
        // off it would just appear (and vanish going): its picture fades in from black instead, and
        // back to black before it closes. A black cover over it fades: the live picture is a
        // SurfaceView, which no layer can fade.
        val cover = remember { if (fades) Animatable(1f) else null }
        LaunchedEffect(leaving) {
            if (cover == null) return@LaunchedEffect
            if (!leaving) cover.animateTo(0f, tween(TvMotion.PresentMs, easing = TvMotion.GlideEase))
            else { cover.animateTo(1f, tween(TvMotion.DismissMs, easing = TvMotion.GlideEase)); finish() }
        }
        BackHandler(enabled = cover != null) { leaving = true }
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || cams.size < 2 || index < 0) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { index = (index + 1) % cams.size; wanted = null; true }
                        Key.DirectionLeft -> { index = (index - 1 + cams.size) % cams.size; wanted = null; true }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            DoorbellPage(
                eyebrow = cam?.let { c -> times[c.name]?.takeIf { it >= since } }?.let { rangAt(it, clock) },
                title = cam?.label ?: "Doorbell",
                hint = cameraHint(back, cams.size),
                message = when {
                    // (It only opens with the cameras connected; this is in case they were signed out meanwhile.)
                    state.step != NestStep.CONNECTED -> if (checked) "Connect your cameras in the Command Center first" else "Connecting…"
                    cam != null && !cam.webRtc -> "This camera model doesn’t support live view here"
                    offline -> "Offline · reconnecting…"
                    cam == null -> "Connecting…"
                    else -> null
                },
            ) { onPicture ->
                // One video view for the whole visit, as full screen in the Command Center has: Left /
                // Right (or another doorbell ringing) moves it to that camera's stream. A view per
                // camera set its GL up on the main thread at every switch, holding up the next press.
                if (cam != null) LiveVideo(cam, audio = true, onPicture = onPicture)
            }
            // Over everything, a layer of its own that fades: nothing is drawn again for it.
            if (cover != null) Box(
                Modifier.matchParentSize()
                    .graphicsLayer { alpha = cover.value.coerceIn(0f, 1f); compositingStrategy = CompositingStrategy.ModulateAlpha }
                    .background(Color.Black),
            )
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    companion object {
        private const val EXTRA_CAMERA = "camera"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_AT = "at"
        private const val EXTRA_BACK_TO = "backTo"

        /** Without a key press for this long it closes by itself (not over a film). */
        internal const val AUTO_CLOSE_MS = 2 * 60_000L
        /** Stopped for this long (not over a film): it was left, and goes. */
        internal const val LEAVE_GRACE_MS = 3_000L

        /** Over the film (OK on the player's card): [camera] is the one that rang (its resource name, as [Doorbell.rings] gives it). */
        fun intent(context: Context, camera: String): Intent =
            Intent(context, DoorbellActivity::class.java).putExtra(EXTRA_CAMERA, camera)

        /** For [ring], over [origin]; [backTo] names the Woodruff screen it opened over ("Home"), for its hint. */
        fun intent(context: Context, ring: Doorbell.Ring, origin: Origin, backTo: String? = null): Intent =
            intent(context, ring.camera).putExtra(EXTRA_ORIGIN, origin.name).putExtra(EXTRA_AT, ring.at)
                .apply { if (backTo != null) putExtra(EXTRA_BACK_TO, backTo) }
    }
}

/** What Back does, in the hint (doorbell.md §3.1). */
internal fun backPhrase(origin: DoorbellActivity.Origin, backTo: String?): String = when (origin) {
    DoorbellActivity.Origin.FILM -> "Back to keep watching"
    DoorbellActivity.Origin.SLIDESHOW -> "Back to the slideshow"
    DoorbellActivity.Origin.SCREENSAVER -> "Back to close"
    DoorbellActivity.Origin.SCREEN -> "Back to ${backTo ?: "Woodruff"}"
}

/** Whether the camera closes by itself after 2 minutes without a key press: anywhere but over a film. */
internal fun autoCloses(origin: DoorbellActivity.Origin): Boolean = origin != DoorbellActivity.Origin.FILM

/**
 * The doorbell view's page: the [picture] (it says what it shows, through its argument: live frames,
 * the last picture held while it reconnects, or words) or [message] in its place, and over it the
 * caption, on the scrim whenever there's a picture under it. Always dark (a camera view), in both themes.
 */
@Composable
internal fun DoorbellPage(
    eyebrow: String?, title: String, hint: String, message: String?,
    picture: @Composable (onPicture: (CameraPicture) -> Unit) -> Unit,
) {
    var shown by remember { mutableStateOf(CameraPicture.NONE) }
    LaunchedEffect(message) { if (message != null) shown = CameraPicture.NONE }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (message == null) picture { shown = it } else CameraWords(message)
        if (shown != CameraPicture.NONE && message == null) CameraScrim(Modifier.align(Alignment.TopStart))
        CameraCaption(eyebrow, title, hint, Modifier.align(Alignment.TopStart))
    }
}
