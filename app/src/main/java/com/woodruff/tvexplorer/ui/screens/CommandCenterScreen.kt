package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.woodruff.tvexplorer.nest.CameraGl
import com.woodruff.tvexplorer.nest.CameraStills
import com.woodruff.tvexplorer.nest.CameraStreams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.CameraStream
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.nest.Rtc
import com.woodruff.tvexplorer.nest.TextureRenderer
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.components.FocusButton
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.ui.theme.glassPanel
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.Qr
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.webrtc.EglRenderer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * TVs decode only a few live videos at once, and each live view counts against Google's limits,
 * so the grid streams the first few cameras; the rest start when selected.
 */
private const val MAX_LIVE = 4

/** A camera is showing full screen (the Command Center hides its tabs). */
internal val cameraFullScreen = androidx.compose.runtime.mutableStateOf(false)

/** Set by Home's camera tiles before opening the Command center, to show that camera full screen. */
internal var openCameraAt: Int? = null

/** A doorbell ring while the Command Center is open: camera [index] full screen, which rang at [rangAt]. */
internal data class CameraRequest(val index: Int, val rangAt: Long)

/** Show this camera full screen while the Command center is already open (a doorbell ring). */
internal val cameraRequests = kotlinx.coroutines.flow.MutableSharedFlow<CameraRequest>(extraBufferCapacity = 1)

/**
 * Command center: live Google Home / Nest cameras, either on their own or next to the latest
 * notifications ("all on one"). Select a camera for full screen; Left/Right flips
 * between cameras and Back returns to the grid.
 */
@Composable
fun CommandCenterScreen(vm: MainViewModel) {
    val live by Nest.state.collectAsStateWithLifecycle()
    // The UI gallery's sample cameras (no network there), else the real ones.
    val state = DashboardSamples.nest ?: live
    // Opened from a Home camera tile: go straight to that camera full screen.
    var full by remember { mutableStateOf(openCameraAt.also { openCameraAt = null }) }
    // The camera last shown full screen: back in the grid it takes the focus, not camera 1.
    var lastFull by remember { mutableIntStateOf(0) }
    var askProject by remember { mutableStateOf(false) }
    // A doorbell ring showed the camera full screen: rings from then on show their time on it.
    var ringsSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { if (DashboardSamples.nest == null) Nest.open() }
    LaunchedEffect(Unit) { cameraRequests.collect { full = it.index; if (ringsSince == null) ringsSince = it.rangAt } }
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler {
        val f = full
        if (f != null) { lastFull = f; full = null; ringsSince = null } else openSidebar()
    }

    val cams = state.cameras
    val fullIndex = full
    // Full screen: it glides in over the grid, and the Command Center's tabs step aside under it.
    val isFull = state.step == NestStep.CONNECTED && fullIndex != null && cams.isNotEmpty()
    // Opened straight onto a camera, the grid isn't built under it: its cameras would start streaming
    // only to stop again.
    val glide = remember { FullScreenGlide(shown = isFull, gridUnder = fullIndex == null) }
    LaunchedEffect(isFull) { if (isFull) glide.open() else glide.close() }
    DisposableEffect(Unit) { onDispose { cameraFullScreen.value = false } }

    Box(Modifier.fillMaxSize()) {
        // The grid, also under a camera opening from it (or closing back to it) until that covers it.
        if (!isFull || glide.gridUnder) Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp)) {
            // The title over the cameras; the controls over the notifications column, exactly as wide as
            // it (the same weights and gap as the row below).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Command center", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(2.2f))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.step == NestStep.CONNECTED) {
                        val ctx = LocalContext.current
                        HubTile(
                            if (Appearance.allInOne) Sym.GridView else Sym.Dashboard,
                            if (Appearance.allInOne) "Cameras only" else "All on one", Modifier.weight(1f),
                        ) { Appearance.toggleAllInOne(ctx) }
                        HubTile(Sym.Refresh, "Refresh", Modifier.weight(1f)) { Nest.refresh() }
                    } else {
                        Spacer(Modifier.weight(2f))
                    }
                    HubTile(Sym.Home, "Home", Modifier.weight(1f)) { vm.show(Screen.HOME) }
                }
            }
            state.error?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }

            when (state.step) {
                NestStep.NEED_GOOGLE -> SetupText(
                    "Cameras use the same Google Cloud keys as Google Drive. Set those up first (Google Drive in the menu), then come back here.",
                ) { Button(onClick = { vm.show(Screen.DRIVE) }) { Text("Set up Google keys") } }
                NestStep.NEED_PROJECT -> SetupText(
                    "One-time setup with Google Device Access:\n\n" +
                        "1.  In Google Cloud (the project you made for Drive) open APIs & Services → Library and enable \"Smart Device Management API\".\n" +
                        "2.  Go to console.nest.google.com/device-access, accept the terms and pay Google's one-time \$5 fee.\n" +
                        "3.  Create project: any name; for OAuth client ID paste the same Client ID you use for Drive; skip events.\n" +
                        "4.  Copy the Project ID it shows and enter it here (or in the phone's Settings tab).",
                ) { Button(onClick = { askProject = true }) { Text("Enter Project ID") } }
                NestStep.SIGN_IN -> SignInPanel(state)
                NestStep.CONNECTED -> {
                    // Offline before any camera was listed: say so calmly; they appear by themselves.
                    val networkUp by com.woodruff.tvexplorer.util.NetworkWatch.online.collectAsStateWithLifecycle()
                    val offlinePage: @Composable (Modifier) -> Unit = { m ->
                        com.woodruff.tvexplorer.ui.components.OfflinePage(
                            com.woodruff.tvexplorer.ui.components.OfflineWords.title(networkUp, "Google Home"),
                            com.woodruff.tvexplorer.ui.components.OfflineWords.body(networkUp, "Your cameras", "Google Home"),
                            modifier = m, onAction = { Nest.refresh() },
                        )
                    }
                    if (state.loading && cams.isEmpty()) {
                        EmptyState("Loading cameras…")
                    } else if (Appearance.allInOne) {
                        Row(Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (state.offline && cams.isEmpty()) offlinePage(Modifier.weight(2.2f))
                            else CameraGrid(cams, columns = if (cams.size <= 1) 1 else 2, Modifier.weight(2.2f), focusOn = lastFull, covered = isFull) { full = it; ringsSince = null }
                            SidePanel(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else if (state.offline && cams.isEmpty()) {
                        offlinePage(Modifier)
                    } else {
                        CameraGrid(cams, columns = if (cams.size <= 1) 1 else if (cams.size <= 4) 2 else 3, Modifier.padding(top = 4.dp), focusOn = lastFull, covered = isFull) { full = it; ringsSince = null }
                    }
                }
            }
        }
        // The camera full screen, over the grid and the pills, while it shows or glides in or out.
        if (glide.shown && cams.isNotEmpty()) {
            FullCamera(cams, (fullIndex ?: lastFull).coerceIn(0, cams.size - 1), glide, open = isFull, ringsSince = ringsSince, onChange = { full = it })
        }
    }

    if (askProject) TextInputDialog(
        "Device Access Project ID", "", "Save",
        onConfirm = { Nest.saveProject(it) }, onDismiss = { askProject = false },
        hint = "From console.nest.google.com/device-access → your project. It looks like 1a2b3c4d-5e6f-…",
    )
}

@Composable
private fun SetupText(text: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        action()
    }
}

/** Same layout rule as Drive: the QR code first at a fixed size, so a 960dp-wide TV can't squeeze it away. */
@Composable
private fun SignInPanel(state: NestState) {
    Row(Modifier.fillMaxSize().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(250.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            val url = state.qrUrl
            if (url != null) {
                val qr = remember(url) { Qr.bitmap(url, 640).asImageBitmap() }
                Image(qr, contentDescription = "QR code", modifier = Modifier.fillMaxSize())
            } else Text("Preparing…", color = Color.Black)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect your cameras", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Scan with your phone (same Wi-Fi as this TV) and sign in to Google. On Google's page, choose your home and " +
                    "turn on every camera and doorbell you want here, then allow access.",
                color = Palette.muted,
            )
            OutlinedButton(onClick = { Nest.startSignIn() }) { Text("New QR code") }
        }
    }
}

/**
 * [focusOn]: the camera that takes the focus (the last if there are fewer now). [covered]: a camera
 * full screen is over it (still gliding in): the focus is that camera's then, and comes back here as
 * it closes.
 */
@Composable
private fun CameraGrid(cams: List<Camera>, columns: Int, modifier: Modifier, focusOn: Int, covered: Boolean, onOpen: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusAt = minOf(focusOn, cams.lastIndex)
    // Plain scrolling rows, not a lazy grid: live video is an Android view, and Android views in
    // lazy layouts crashed when the grid closed (leaving the Command center).
    // Room inside the scrolling column for a focused tile to grow: it clips at its top and bottom
    // edges, which cut the first and last rows' outlines.
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        cams.withIndex().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((i, cam) in row) key(cam.name) {
                    // Whether this tile has the focus, read only by its video (the focused one plays
                    // smoothest), so moving across the grid recomposes nothing.
                    val focused = remember { mutableStateOf(false) }
                    TvCard(
                        onClick = { onOpen(i) },
                        shape = Shapes.card,
                        modifier = Modifier.weight(1f).aspectRatio(16f / 9f).then(if (i == focusAt) Modifier.focusRequester(focus) else Modifier)
                            .onFocusChanged { focused.value = it.isFocused },
                        focusedScale = 1.03f,
                        borderWidth = 2.dp, focusedBorderColor = Palette.focusRim,
                    ) {
                        // Edge to edge, in the tile's own rounded shape (the card's), as on Home: the picture
                        // fills the whole tile and grows with it when focused (a TextureView, drawn under the
                        // focus ring; a SurfaceView sat letterboxed in an inset and didn't follow the focus
                        // scale). CardStyle's lit rim over it.
                        Box(Modifier.fillMaxSize().border(1.dp, Palette.rim, Shapes.card)) {
                            if (i < MAX_LIVE && cam.webRtc) LiveVideo(cam, audio = false, fill = true, focused = { focused.value })
                            else Placeholder(if (cam.webRtc) "Select to watch" else "This camera model doesn't support live view here")
                            CameraLabel(cam, Modifier.align(Alignment.BottomStart))
                        }
                    }
                }
                // Keep a lone last camera the same size as the others.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    // Also when a camera turned back half-way in closes: the grid stayed under it, so isn't new.
    LaunchedEffect(cams.size, covered) { if (!covered) runCatching { focus.requestFocus() } }
}

/**
 * A camera full screen, over the whole window (the Command center's pills too). It comes and goes as
 * [glide] moves it; [open]: it's opening or open, not closing. [ringsSince]: a doorbell ring showed
 * it, and rings from then on show their time over the camera that rang ("RANG AT 7:42 PM").
 */
@Composable
private fun FullCamera(cams: List<Camera>, index: Int, glide: FullScreenGlide, open: Boolean, ringsSince: Long?, onChange: (Int) -> Unit) {
    val cam = cams[index]
    val focus = remember { FocusRequester() }
    val times by com.woodruff.tvexplorer.nest.Doorbell.ringTimes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clock = remember { android.text.format.DateFormat.getTimeFormat(context) }
    // A picture shows, live or held while it reconnects (the scrim behind the words is only over a picture).
    var picture by remember { mutableStateOf(CameraPicture.NONE) }
    Box(
        Modifier
            .overWindow(LocalView.current)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || cams.size < 2) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { onChange((index + 1) % cams.size); true }
                    Key.DirectionLeft -> { onChange((index - 1 + cams.size) % cams.size); true }
                    else -> false
                }
            }
            // The focus stays here, as when nothing else was on screen: the grid and the pills are
            // still under it while it glides in.
            .focusProperties {
                up = FocusRequester.Cancel; down = FocusRequester.Cancel
                left = FocusRequester.Cancel; right = FocusRequester.Cancel
            }
            .focusable(),
    ) {
        // One video view for the whole visit: Left/Right moves it to the next camera's stream and
        // clears the last picture (a view per camera set GL up and tore it down on the main thread).
        // Drawn only while the page covers everything around it, never faded or moved (see
        // FullScreenGlide), and with its sound only then.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = if (glide.live) 1f else 0f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) { LiveVideo(cam, audio = glide.live, onPicture = { picture = it }) }
        // The page: black over the picture until it shows (then cleared), and the camera's name. It
        // grows into place as it fades in, as a screen does, and shrinks away going.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = glide.page.value
                    alpha = p.coerceIn(0f, 1f)
                    val s = TvMotion.PageScale + (1f - TvMotion.PageScale) * p
                    scaleX = s; scaleY = s
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            // The black, a layer of its own fading as the picture clears (and back): nothing is drawn again for it.
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = glide.cover.value.coerceIn(0f, 1f); compositingStrategy = CompositingStrategy.ModulateAlpha }
                    .background(Color.Black),
            )
            if (picture != CameraPicture.NONE) CameraScrim(Modifier.align(Alignment.TopStart))
            CameraCaption(
                eyebrow = ringsSince?.let { since -> times[cam.name]?.takeIf { it >= since } }?.let { rangAt(it, clock) },
                title = cam.label,
                hint = cameraHint("Back for all cameras", cams.size),
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
    // Into it as it opens (and again when it turns back while closing: the grid took the focus then).
    LaunchedEffect(open) { if (open) runCatching { focus.requestFocus() } }
}

/**
 * How a camera full screen comes and goes. Its live picture is a SurfaceView, which Android shows
 * under the window through a hole the view cuts in everything drawn there: no layer can fade or move
 * the picture (the hole is all or nothing), and an offscreen copy of it would hide it. So the page
 * around it (black, with the camera's name) grows in over the grid first, as a screen does; only once
 * that all but covers the screen is the picture drawn, under the page's black, which then clears.
 * Closing, the black comes back over the picture first, the picture stops being drawn, and the page
 * shrinks away. All of it in drawing layers ([TvMotion]'s Live numbers): nothing is composed or laid
 * out again per frame, and the picture's view is made once per visit (turning back half-way keeps it).
 */
@Stable
internal class FullScreenGlide(shown: Boolean, gridUnder: Boolean) {
    /** The page: 0 gone to 1 in (its fade and growth). */
    val page = Animatable(0f, visibilityThreshold = 0.002f)
    /** The page's black over the picture: 1 covering it to 0 clear. */
    val cover = Animatable(1f, visibilityThreshold = 0.002f)
    /** The picture is drawn: it then shows through the whole window, whatever is drawn under it. */
    var live by mutableStateOf(false)
        private set
    /** The full screen is there at all. */
    var shown by mutableStateOf(shown)
        private set
    /** The grid is kept under a camera opening from it (or closing back to it). */
    var gridUnder by mutableStateOf(gridUnder)
        private set

    suspend fun open(): Unit = coroutineScope {
        shown = true
        // Opened straight onto a camera (from Home, a doorbell ring): the Command center glides in
        // around it, and its pills go at once.
        if (!gridUnder) cameraFullScreen.value = true
        val pageIn = launch { page.animateTo(1f, TvMotion.ScreenIn) }
        if (!live) {
            while (pageIn.isActive && page.value < TvMotion.LiveSwap) withFrameNanos { }
            live = true
        }
        cover.animateTo(0f, TvMotion.LiveClear)
        pageIn.join()
        // All in, picture and all: the grid and the pills go, unseen under it.
        gridUnder = false
        cameraFullScreen.value = true
    }

    suspend fun close(): Unit = coroutineScope {
        cameraFullScreen.value = false
        if (!shown) return@coroutineScope
        // The grid comes back under it (and takes the focus at once), and the pills with it.
        gridUnder = true
        val dark = launch { cover.animateTo(1f, TvMotion.LiveDim) }
        if (live) {
            while (dark.isActive && cover.value < TvMotion.LiveSwap) withFrameNanos { }
            live = false
        }
        page.animateTo(0f, TvMotion.ScreenOut)
        shown = false
    }
}

/**
 * Laid out over the whole [window] (its root view) from where this sits, which must reach the
 * window's bottom right corner, as the Command center's screen does under its pills. So a camera
 * opening from the grid covers the pills too (they go, and come back, unseen under it), and its video
 * view keeps one size all along (a SurfaceView resized reallocates its picture's buffers).
 */
private fun Modifier.overWindow(window: View): Modifier = layout { measurable, constraints ->
    val w = window.width
    val h = window.height
    if (w <= 0 || h <= 0 || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(constraints.maxWidth - w, constraints.maxHeight - h)
    }
}

/**
 * One live camera view, streaming only while Woodruff is on screen: with the slideshow, a video
 * or another app on top, the stream stops (it was decoding underneath) and resumes on return.
 * Views of the same camera share one stream, in this activity or another (the doorbell over a film).
 * [focused]: a tile has the focus, read only by its video (the focused tile plays smoothest).
 * [onPicture]: what it shows (live frames, the last picture held while it reconnects, or none),
 * for what its host draws around it (the doorbell's scrim, Home's LIVE badge).
 */
@Composable
internal fun LiveVideo(cam: Camera, audio: Boolean, fill: Boolean = false, focused: () -> Boolean = { true }, onPicture: (CameraPicture) -> Unit = {}) {
    // The UI gallery has no WebRTC: its sample views are drawn with the same words and veil.
    val sample = CameraSamples.views?.get(cam.name)
    if (sample != null) {
        SampleVideo(sample, fill, onPicture)
        return
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (lifecycle.isAtLeast(Lifecycle.State.STARTED)) LiveStream(cam, audio, fill, focused, onPicture)
    else {
        Placeholder("Paused")
        LaunchedEffect(Unit) { onPicture(CameraPicture.NONE) }
    }
}

/**
 * A video view, what it renders into ([draws]), and how to take a frame from it, clear it ([wipe])
 * and let it go. The stream's frames reach it through [sink]; [pictured] says whether it has drawn one.
 */
private class Renderer(
    val view: View,
    private val draws: VideoSink,
    val grab: (EglRenderer.FrameListener) -> Unit,
    /** Calls back (on the GL thread) once the view next draws a frame into its picture. */
    private val afterNextFrame: (() -> Unit) -> Unit,
    /** The view's picture goes (another camera is coming into it). */
    private val wipe: () -> Unit,
    /** A tile's frame rate follows its focus. */
    val focus: (Boolean) -> Unit,
    val release: () -> Unit,
) {
    /** The camera it shows (read on the GL thread too, by a frame grab). */
    @Volatile var camera: String? = null

    /**
     * Whether the view shows a picture: it has drawn a frame since it was made or last cleared (a
     * frame given to it may not be drawn yet: a tile still setting up, or skipping it). Main thread.
     */
    val pictured = mutableStateOf(false)
    /** Moved on by [clear], so word of a frame from before then changes nothing. */
    @Volatile private var epoch = 0
    /** A frame reached it since it was made or cleared (WebRTC's decoder thread). */
    @Volatile private var seen = false
    private val main = Handler(Looper.getMainLooper())

    /** What the stream draws into (WebRTC's decoder thread): the view. */
    val sink = VideoSink { frame ->
        if (!seen) {
            seen = true
            // In case the view's own word never comes: a frame it was given this long ago shows by now.
            val e = epoch
            main.postDelayed({ if (epoch == e) pictured.value = true }, DRAWN_FALLBACK_MS)
        }
        draws.onFrame(frame)
    }

    init { watchForPicture() }

    /** Its next drawn frame is the picture of what it shows now (main thread). */
    private fun watchForPicture() {
        val e = epoch
        afterNextFrame { main.post { if (epoch == e) pictured.value = true } }
    }

    /** Another camera is coming into the view: the last one's picture goes now (main thread). */
    fun clear() {
        epoch++
        seen = false
        pictured.value = false
        wipe()
        watchForPicture()
    }

    private companion object {
        /** A view given a frame this long ago counts as showing a picture, should its own word be lost. */
        const val DRAWN_FALLBACK_MS = 1_000L
    }
}

/** A tile: the picture fills it and takes its rounded corners (a TextureView, on the tiles' shared GL thread). */
private fun tileRenderer(context: Context): Renderer {
    val t = TextureRenderer(context)
    return Renderer(t, t, { l -> t.addFrameListener(l, 0.3f) }, t::afterNextFrame, t::clearImage, t::setFocused, t::release)
}

/** Full screen: the whole frame, letterboxed if need be (a SurfaceView, the cheapest to draw). */
private fun fullRenderer(context: Context): Renderer {
    val s = SurfaceViewRenderer(context).apply {
        init(Rtc.egl.eglBaseContext, null)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        setEnableHardwareScaler(true)
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    // After a switch nothing is drawn until the new camera's first frame: a frame of the last camera
    // still on its way to the GL thread would otherwise bring its picture back after the clear.
    val sink = object : VideoSink {
        @Volatile var waiting = false
        override fun onFrame(frame: VideoFrame) {
            if (waiting) { waiting = false; s.disableFpsReduction() }
            s.onFrame(frame)
        }
    }
    return Renderer(
        s, sink, { l -> s.addFrameListener(l, 0.3f) },
        // Set up already (init above), and never held back by a frame rate: the next frame that reaches
        // its surface is drawn (after a switch, the sink above lets frames through again first).
        afterNextFrame = { drawn -> s.addFrameListener({ drawn() }, 0f) },
        wipe = { sink.waiting = true; s.pauseVideo(); s.clearImage() },
        focus = {},
        // Letting go waits for its GL thread to clean up: done in the background.
        release = { CameraGl.later { s.release() } },
    )
}

/**
 * One WebRTC live view. The renderer lives exactly as long as this composable; the stream is shared
 * (CameraStreams): the view joins the camera's running stream, or starts one, and lets go when it
 * leaves. Off to another screen, the stream stays up a moment for it, so Home or the grid to full
 * screen (and back) and a doorbell ring show the picture at once; Woodruff leaving the screen closes it.
 * [fill]: a tile — the picture fills it and takes its rounded corners (a TextureView); otherwise
 * the whole frame is shown, letterboxed if need be (a SurfaceView, the cheapest to draw). The view
 * stays when [cam] changes (full screen's Left/Right): only the stream it shows is swapped.
 * Over the picture, [CameraOverlay] says what the stream is doing: it's live only while frames come.
 */
@Composable
private fun LiveStream(cam: Camera, audio: Boolean, fill: Boolean, focused: () -> Boolean, onPicture: (CameraPicture) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val renderer = remember { if (fill) tileRenderer(context) else fullRenderer(context) }
    // Declared first so it's undone last (effects end in reverse order): the renderer is released
    // only after its picture has been detached from the stream below.
    DisposableEffect(Unit) {
        onDispose { runCatching { renderer.release() }.onFailure { Log.w("LiveVideo", "release", it) } }
    }
    // A tile's frame rate follows its focus, set on the view straight from the state: no recomposition.
    val isFocused by rememberUpdatedState(focused)
    LaunchedEffect(renderer) { snapshotFlow { isFocused() }.collect { renderer.focus(it) } }
    val wantsAudio by rememberUpdatedState(audio)
    var hold by remember { mutableStateOf<CameraStreams.Hold?>(null) }
    DisposableEffect(cam.name, lifecycle) {
        // Another camera in the same view (full screen's Left/Right): the last one's picture goes now.
        if (renderer.camera.let { it != null && it != cam.name }) renderer.clear()
        renderer.camera = cam.name
        // Attached while this screen is started. Stopping (a film, another app) lets go at once,
        // straight from the lifecycle rather than the next recomposition.
        var mine: CameraStreams.Hold? = null
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && mine == null) {
                val h = CameraStreams.acquire(context, cam, renderer.sink, wantsAudio)
                mine = h
                hold = h
            } else if (event == Lifecycle.Event.ON_STOP) {
                mine?.let { CameraStreams.release(it, linger = false) }
                mine = null
                hold = null
            }
        }
        lifecycle.addObserver(watcher)
        onDispose {
            lifecycle.removeObserver(watcher)
            // Off to another screen: the stream stays up a moment, in case that one shows this camera too.
            mine?.let { CameraStreams.release(it, linger = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
            mine = null
        }
    }
    LaunchedEffect(audio) { hold?.let { CameraStreams.setAudio(it, audio) } }
    val stream = hold?.stream
    // Keyed by the stream: after a switch it's the new camera's state at once, not the last one's for a frame.
    val status = if (stream == null) CameraStream.Status.Connecting else key(stream) { stream.status.collectAsState().value }
    // What the view shows, for its host: live only while the stream's frames come.
    val held by renderer.pictured
    val shows = cameraPicture(status, held, tile = fill)
    val reportPicture by rememberUpdatedState(onPicture)
    LaunchedEffect(shows) { reportPicture(shows) }
    // Keep a small recent frame of this camera for tiles that aren't streaming.
    LaunchedEffect(status, cam.name) {
        val name = cam.name
        while (status == CameraStream.Status.Live) {
            delay(1_500)
            // Kept only while the view still shows this camera (full screen may have moved on).
            runCatching { renderer.grab(EglRenderer.FrameListener { bmp -> if (bmp != null && renderer.camera == name) CameraStills.put(name, bmp) }) }
            delay(20_000)
        }
    }
    // A tile with no picture of its own yet holds the camera's last one while it connects or reconnects.
    val still = if (fill) {
        val stills by CameraStills.stills.collectAsStateWithLifecycle()
        val bmp = stills[cam.name]?.bitmap
        remember(bmp) { bmp?.asImageBitmap() }
    } else null
    val long = rememberLongTrouble(com.woodruff.tvexplorer.nest.troubleSince(status))
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // A tile's video in a layer of its own: each frame redraws just the video, not the tile's
        // label and frame around it.
        AndroidView(factory = { renderer.view }, modifier = if (fill) Modifier.fillMaxSize().graphicsLayer() else Modifier.fillMaxSize())
        CameraOverlay(status, long, held, still, big = !fill)
    }
}

/**
 * A camera's symbol and a few words in place of its picture, on the camera views' dark. Smaller in a
 * short tile (Home's), and a little above its middle there, so the words clear the camera's name.
 */
@Composable
internal fun Placeholder(text: String) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0B0E13)), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 120.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (compact) Modifier.padding(bottom = 22.dp) else Modifier) {
            TvIcon(Sym.Videocam, size = if (compact) 22.dp else 36.dp, tint = Color.White.copy(alpha = 0.6f))
            Text(
                text, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 2.dp else 6.dp),
            )
        }
    }
}

@Composable
private fun CameraLabel(cam: Camera, modifier: Modifier) {
    Text(
        cam.label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "All on one": the latest notifications beside the cameras (the time is at the top right). */
@Composable
private fun SidePanel(modifier: Modifier) {
    val notices by Notices.items.collectAsStateWithLifecycle()
    Column(
        modifier.glassPanel(RoundedCornerShape(26.dp)).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PanelTitle("Notifications")
        if (notices.isEmpty()) Text("Nothing new", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        notices.take(12).forEach { n ->
            Column {
                Text(
                    n.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (n.read) null else androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                if (n.body.isNotBlank()) Text(n.body, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                Text(Format.date(n.at), color = Palette.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A Command Center control: a symbol over a short label in a glass tile, white when focused. */
@Composable
private fun HubTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Palette.surfaceHigh, focusedColor = Palette.text,
        content = Palette.text, focusedContent = Palette.background,
        modifier = modifier.height(64.dp),
        focusedScale = 1.05f,
    ) { tint ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            TintIcon(icon, tint.color, size = 22.dp)
            RowText(
                label, tint.color, Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text.uppercase(), color = Palette.accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp).width(200.dp))
}
