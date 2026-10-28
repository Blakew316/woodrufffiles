package com.woodruff.tvexplorer.ui.screens

import android.util.Log
import com.woodruff.tvexplorer.nest.CameraStills
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.CameraStream
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.nest.Rtc
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.CardStyle
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.glassPanel
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.Qr
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * TVs decode only a few live videos at once, and each live view counts against Google's limits,
 * so the grid streams the first few cameras; the rest start when selected.
 */
private const val MAX_LIVE = 4

/** A camera is showing full screen (the Command Center hides its tabs). */
internal val cameraFullScreen = androidx.compose.runtime.mutableStateOf(false)

/** Set by Home's camera tiles before opening the Command center, to show that camera full screen. */
internal var openCameraAt: Int? = null

/** Show this camera full screen while the Command center is already open (a doorbell ring). */
internal val cameraRequests = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)

/**
 * Command center: live Google Home / Nest cameras, either on their own or next to the latest
 * notifications ("all on one"). Select a camera for full screen; Left/Right flips
 * between cameras and Back returns to the grid.
 */
@Composable
fun CommandCenterScreen(vm: MainViewModel) {
    val state by Nest.state.collectAsStateWithLifecycle()
    // Opened from a Home camera tile: go straight to that camera full screen.
    var full by remember { mutableStateOf(openCameraAt.also { openCameraAt = null }) }
    var askProject by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Nest.open() }
    LaunchedEffect(Unit) { cameraRequests.collect { full = it } }
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler { if (full != null) full = null else openSidebar() }

    val cams = state.cameras
    val fullIndex = full
    // Full screen: the Command Center's tabs step aside.
    val isFull = state.step == NestStep.CONNECTED && fullIndex != null && cams.isNotEmpty()
    LaunchedEffect(isFull) { cameraFullScreen.value = isFull }
    DisposableEffect(Unit) { onDispose { cameraFullScreen.value = false } }
    if (state.step == NestStep.CONNECTED && fullIndex != null && cams.isNotEmpty()) {
        FullCamera(cams, fullIndex.coerceIn(0, cams.size - 1), onChange = { full = it })
        return
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Command center", style = MaterialTheme.typography.headlineLarge)
            }
            if (state.step == NestStep.CONNECTED) {
                val ctx = LocalContext.current
                ToolButton(
                    if (Appearance.allInOne) Icons.Rounded.GridView else Icons.Rounded.Dashboard,
                    if (Appearance.allInOne) "Cameras only" else "All on one",
                    { Appearance.toggleAllInOne(ctx) },
                )
                ToolButton(Icons.Rounded.Refresh, null, { Nest.refresh() }, Modifier.padding(start = 10.dp))
            }
            ToolButton(Icons.Rounded.Home, "Home", { vm.show(Screen.HOME) }, Modifier.padding(start = 10.dp))
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
                if (state.loading && cams.isEmpty()) {
                    EmptyState("Loading cameras…")
                } else if (Appearance.allInOne) {
                    Row(Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        CameraGrid(cams, columns = if (cams.size <= 1) 1 else 2, Modifier.weight(2.2f)) { full = it }
                        SidePanel(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    CameraGrid(cams, columns = if (cams.size <= 1) 1 else if (cams.size <= 4) 2 else 3, Modifier.padding(top = 4.dp)) { full = it }
                }
            }
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

@Composable
private fun CameraGrid(cams: List<Camera>, columns: Int, modifier: Modifier, onOpen: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    // Plain scrolling rows, not a lazy grid: live video is an Android view, and Android views in
    // lazy layouts crashed when the grid closed (leaving the Command center).
    // Room inside the scrolling column for a focused tile to grow: it clips at its top and bottom
    // edges, which cut the first and last rows' outlines.
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        cams.withIndex().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((i, cam) in row) key(cam.name) {
                    Surface(
                        onClick = { onOpen(i) },
                        modifier = Modifier.weight(1f).aspectRatio(16f / 9f).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                    ) {
                        // Inset so the focus border isn't hidden under the video surface.
                        Box(Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(18.dp))) {
                            if (i < MAX_LIVE && cam.webRtc) LiveVideo(cam, audio = false)
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
    LaunchedEffect(cams.size) { runCatching { first.requestFocus() } }
}

@Composable
private fun FullCamera(cams: List<Camera>, index: Int, onChange: (Int) -> Unit) {
    val cam = cams[index]
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || cams.size < 2) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { onChange((index + 1) % cams.size); true }
                    Key.DirectionLeft -> { onChange((index - 1 + cams.size) % cams.size); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        key(cam.name) { LiveVideo(cam, audio = true) }
        Column(Modifier.align(Alignment.TopStart).padding(28.dp)) {
            Text(cam.label, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                if (cams.size > 1) "◀ ▶ switch camera  ·  Back for all cameras" else "Back for all cameras",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * One live camera view, streaming only while Woodruff is on screen: with the slideshow, a video
 * or another app on top, the stream stops (it was decoding underneath) and resumes on return.
 */
@Composable
internal fun LiveVideo(cam: Camera, audio: Boolean, fill: Boolean = false) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (lifecycle.isAtLeast(Lifecycle.State.STARTED)) LiveStream(cam, audio, fill)
    else Placeholder("Paused")
}

/** A video view, what it renders into, and how to take a frame from it and let it go. */
private class Renderer(
    val view: View,
    val sink: org.webrtc.VideoSink,
    val grab: (org.webrtc.EglRenderer.FrameListener) -> Unit,
    val release: () -> Unit,
)

/**
 * One WebRTC live view. The renderer and stream live exactly as long as this composable.
 * [fill]: a tile — the picture fills it and takes its rounded corners (a TextureView); otherwise
 * the whole frame is shown, letterboxed if need be (a SurfaceView, the cheapest to draw).
 */
@Composable
private fun LiveStream(cam: Camera, audio: Boolean, fill: Boolean) {
    val context = LocalContext.current
    val renderer = remember {
        if (fill) com.woodruff.tvexplorer.nest.TextureRenderer(context, Rtc.egl.eglBaseContext).let { t ->
            Renderer(t, t, { l -> t.addFrameListener(l, 0.3f) }, { t.release() })
        } else SurfaceViewRenderer(context).apply {
            init(Rtc.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.let { s -> Renderer(s, s, { l -> s.addFrameListener(l, 0.3f) }, { s.release() }) }
    }
    val stream = remember { CameraStream(context, cam, renderer.sink) }
    DisposableEffect(Unit) {
        stream.start()
        onDispose {
            stream.close()
            runCatching { renderer.release() }.onFailure { Log.w("LiveVideo", "release", it) }
        }
    }
    LaunchedEffect(audio) { stream.setAudio(audio) }
    val status by stream.status.collectAsState()
    // Keep a small recent frame of this camera for tiles that aren't streaming.
    LaunchedEffect(status) {
        while (status == CameraStream.Status.Live) {
            delay(1_500)
            runCatching { renderer.grab(org.webrtc.EglRenderer.FrameListener { bmp -> CameraStills.put(cam.name, bmp) }) }
            delay(20_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { renderer.view }, modifier = Modifier.fillMaxSize())
        when (val s = status) {
            CameraStream.Status.Live -> Unit
            CameraStream.Status.Connecting -> Placeholder("Connecting…")
            is CameraStream.Status.Failed -> Placeholder(s.message + "\nRetrying…")
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E13)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TvIcon(Icons.Rounded.Videocam, size = 36.dp, tint = Color.White.copy(alpha = 0.6f))
            Text(text, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
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

@Composable
private fun PanelTitle(text: String) {
    Text(text.uppercase(), color = Palette.accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp).width(200.dp))
}
