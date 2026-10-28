package com.woodruff.tvexplorer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.photos.GooglePhotos
import com.woodruff.tvexplorer.ui.screens.CommandCenterScreen
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.ui.screens.NotesScreen
import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.woodruff.tvexplorer.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.components.ProgressDialog
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.screens.AppsScreen
import com.woodruff.tvexplorer.ui.screens.BrowserScreen
import com.woodruff.tvexplorer.ui.screens.DriveScreen
import com.woodruff.tvexplorer.ui.screens.HomeScreen
import com.woodruff.tvexplorer.ui.screens.MailScreen
import com.woodruff.tvexplorer.ui.screens.NotificationsScreen
import com.woodruff.tvexplorer.ui.screens.SettingsScreen
import com.woodruff.tvexplorer.ui.screens.TransferScreen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
import com.woodruff.tvexplorer.notify.Notice
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import kotlinx.coroutines.delay
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.platform.LocalContext
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Main window: branded side rail, the active screen, and global overlays. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val drive: DriveViewModel = viewModel()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val browser by vm.browser.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // Only the library the Drive screen is in: the full Drive state changes on every download
    // progress tick and would redraw the whole window with it.
    val driveTop by remember(drive) { drive.state.map { it.stack.firstOrNull() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(drive.state.value.stack.firstOrNull())
    // Only whether the phone connection is up: its state also carries a request log that changes
    // with every phone request, which would redraw the whole window while the phone app is open.
    val phoneConnected by remember { TransferService.state.map { it.running }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(TransferService.state.value.running)
    val notices by Notices.items.collectAsStateWithLifecycle()
    val latestNotice by Notices.latest.collectAsStateWithLifecycle()
    val unread = notices.count { !it.read }
    val homeFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    var enterContent by remember { mutableIntStateOf(0) }
    // Asked for the sidebar (Left at the edge, Back on Home): its items become focusable and the
    // current screen's item takes the focus.
    var railWanted by remember { mutableStateOf(false) }
    val railActive = remember { FocusRequester() }
    val open = railFocused || RailPreview.open

    // Voice search: the menu's Search item, or the remote's search button. Speech when the TV has a
    // recognizer, otherwise typing. Searches the Movies and TV Shows already loaded.
    val searchContext = LocalContext.current
    var searchResults by remember { mutableStateOf<Pair<String, List<Pair<DrivePlace, com.woodruff.tvexplorer.cloud.DriveFile>>>?>(null) }
    var typingSearch by remember { mutableStateOf(false) }
    val showResults: (String) -> Unit = { q ->
        val found = drive.searchLibraries(q)
        if (found.isEmpty()) vm.say("Nothing called \u201c$q\u201d in Movies or TV Shows")
        else searchResults = q to found
        Unit
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(showResults)
    }
    val startSearch: () -> Unit = {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie or show")
        if (runCatching { speech.launch(i) }.isFailure) typingSearch = true
        Unit
    }
    LaunchedEffect(Unit) { VoiceSearch.requests.collect { startSearch() } }

    Box(Modifier.fillMaxSize().then(if (Appearance.background == null) Modifier.ambientBackdrop() else Modifier.background(Palette.background))) {
        // Optional background photo (Settings → Screensaver & background), dimmed so text stays readable.
        Appearance.background?.let { path ->
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.78f)))
        }
        // No focusRestorer here or on the menu: it pins the last focused item of a lazy list, and when
        // that list went away with the old screen, un-pinning it crashed the app ("Release should only
        // be called once") on pressing Right from the menu. Focus enters the screen at its first item.
        // Content keeps the slim rail's width to its left, so it never reflows when the menu opens:
        // the open menu slides over the dashboard instead.
        // Full width: the sidebar is hidden until called, like the Apple TV app. Left at a screen's
        // left edge (where focus has nowhere further to go) slides it in.
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Box(
            Modifier.fillMaxSize()
                .onFocusChanged { contentFocused = it.hasFocus }
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && contentFocused) {
                        // Only a fresh press opens the menu: holding Left to run back along a row
                        // stops at its first item instead of overshooting into the menu.
                        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left) && e.nativeKeyEvent.repeatCount == 0) railWanted = true
                        true
                    } else false
                }
                .focusRequester(contentFocus).focusGroup(),
        ) {
                // Switch screens in one step, not a cross-fade: during a fade the Command center's
                // live video views were torn down by the transition, which crashed on some TVs.
                CompositionLocalProvider(LocalOpenSidebar provides { railWanted = true }) {
                key(screen) {
                  // Each screen eases in (a quick fade and a few dp of rise) instead of popping in.
                  // Only the new screen animates; the old one is gone at once (a cross-fade kept the
                  // camera views alive during the switch, which crashed on some TVs).
                  ScreenEnter {
                    when (screen) {
                        Screen.HOME -> HomeScreen(vm, drive)
                        Screen.BROWSER -> BrowserScreen(vm)
                        Screen.DRIVE -> DriveScreen(vm, drive)
                        Screen.MAIL -> MailScreen(vm)
                        Screen.NOTIFICATIONS -> NotificationsScreen(vm)
                        Screen.NOTES -> NotesScreen(vm)
                    Screen.CAMERAS -> CommandCenterScreen(vm)
                        Screen.APPS -> AppsScreen(vm)
                        Screen.TRANSFER -> TransferScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                    }
                  }
                }
                }
                MessageBanner(message, vm::dismissMessage, Modifier.align(Alignment.BottomCenter))
                DriveMessage(drive, Modifier.align(Alignment.BottomCenter))
                NoticeBanner(latestNotice, Modifier.align(Alignment.TopEnd)) { vm.show(Screen.NOTIFICATIONS) }
        }
        // Dim the dashboard while the menu is open over it.
        // Animated values are read only while drawing / laying out, so the 220 ms open and close
        // animations don't recompose the whole window on every frame.
        // One fully damped spring drives the menu's width, its colour, its labels and this dimming
        // together: it starts quickly, eases to a stop without overshoot, and picks up smoothly if
        // the menu reverses half-way (a timed curve restarts and jerks).
        val reveal = animateFloatAsState(if (open) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 420f), label = "rail")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }.background(Brush.horizontalGradient(0f to Palette.background.copy(alpha = 0.92f), 0.6f to Palette.background.copy(alpha = 0.55f), 1f to Palette.background.copy(alpha = 0.25f))))
        // Side menu: icons only until it has focus, then it opens to full labels over the content.
        // Opens quick and settles softly (no linear robot slide).
        Column(
            Modifier
                // Clipped to the width the layout below reports, so the closed menu (width 0) draws
                // nothing. (Placed after the layout, the clip used the full open width it measures,
                // and the "hidden" menu stayed painted over the left of every screen.)
                .clipToBounds()
                // The rows are measured once at the width the menu is heading to; each animation frame
                // then only changes how much of it shows (re-measuring 13 rows per frame cost frames).
                .layout { measurable, constraints ->
                    // Always measured at its open width (hidden is width 0, which can't hold the rows).
                    val target = RAIL_OPEN.roundToPx()
                    val closed = RAIL_CLOSED.toPx()
                    val w = (closed + (RAIL_OPEN.toPx() - closed) * reveal.value).roundToInt()
                    val p = measurable.measure(constraints.copy(minWidth = target, maxWidth = target))
                    layout(w, p.height) { p.place(0, 0) }
                }
                .fillMaxHeight()
                // Fades from the slim tint to the open menu's frosted glass with the slide (drawn, not recomposed).
                .drawBehind { drawRect(androidx.compose.ui.graphics.lerp(Palette.surface, Palette.glass, reveal.value)) }
                .onFocusChanged { railFocused = it.hasFocus }
                // Right goes into the content (the open menu covers its left edge, so a plain geometric
                // search would skip the first column).
                .onPreviewKeyEvent { e ->
                    when {
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> {
                            runCatching { contentFocus.requestFocus() }
                            contentFocused
                        }
                        // Back from the menu returns to the screen (on Home it leaves the app, as
                        // before). Handled here, where the key arrives, not with a BackHandler: each
                        // screen adds its own BackHandler when it opens, after AppRoot's, and that one
                        // won — Back went Home or up a folder behind the menu, and then out of the app.
                        e.key == Key.Back && screen != Screen.HOME -> {
                            if (e.type == KeyEventType.KeyUp) runCatching { contentFocus.requestFocus() }
                            true
                        }
                        else -> false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 28.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
              CompositionLocalProvider(LocalRailActive provides railActive, LocalRailFocusable provides (open || railWanted)) {
                Brand(open, Modifier.padding(start = 6.dp, bottom = 24.dp))
                // Choosing a screen in the menu goes straight into it (the menu closes behind).
                val go: (() -> Unit) -> Unit = { action -> action(); enterContent++ }
                RailItem("Home", Icons.Rounded.Home, screen == Screen.HOME, open, Modifier.focusRequester(homeFocus)) { go { vm.show(Screen.HOME) } }
                RailItem("Search", Icons.Rounded.Mic, false, open) { startSearch() }
                RailItem("Command center", Icons.Rounded.Videocam, screen == Screen.CAMERAS, open) { go { vm.show(Screen.CAMERAS) } }
                // Library: Movies and TV Shows stream from those Drive folders; Photos and Videos are
                // what was picked from Google Photos, saved on the TV.
                RailItem("Movies", Icons.Rounded.Movie, screen == Screen.DRIVE && driveTop == DrivePlace.Movies, open) {
                    go { drive.openRoot(DrivePlace.Movies); vm.show(Screen.DRIVE) }
                }
                RailItem("TV Shows", Icons.Rounded.Tv, screen == Screen.DRIVE && driveTop == DrivePlace.TvShows, open) {
                    go { drive.openRoot(DrivePlace.TvShows); vm.show(Screen.DRIVE) }
                }
                val inFolder = { dir: java.io.File -> screen == Screen.BROWSER && browser.folder?.path?.startsWith(dir.path) == true }
                RailItem("Photos", Icons.Rounded.PhotoLibrary, inFolder(GooglePhotos.folder), open) {
                    go { vm.openRoot(GooglePhotos.folder.apply { mkdirs() }) }
                }
                RailItem("Videos", Icons.Rounded.VideoLibrary, inFolder(GooglePhotos.videoFolder), open) {
                    go { vm.openRoot(GooglePhotos.videoFolder.apply { mkdirs() }) }
                }
                RailItem("Mail", Icons.Rounded.Email, screen == Screen.MAIL, open) { go { vm.show(Screen.MAIL) } }
                RailItem(
                    if (unread > 0) "Notifications · $unread" else "Notifications",
                    Icons.Rounded.Notifications, screen == Screen.NOTIFICATIONS, open, badge = unread > 0,
                ) { go { vm.show(Screen.NOTIFICATIONS) } }
                RailItem("Notes", Icons.Rounded.EditNote, screen == Screen.NOTES, open) { go { vm.show(Screen.NOTES) } }
                RailLabel("Connect", open)
                RailItem(if (phoneConnected) "Phone · Connected" else "Phone", Icons.Rounded.PhoneIphone, screen == Screen.TRANSFER, open, badge = phoneConnected) {
                    go { vm.show(Screen.TRANSFER) }
                }
                RailItem("Settings", Icons.Rounded.Settings, screen == Screen.SETTINGS, open) { go { vm.show(Screen.SETTINGS) } }
              }
        }
    }

    operation?.let { ProgressDialog(it.title, it.progress, vm::cancelOperation) }
    DriveProgress(drive)
    if (typingSearch) {
        TextInputDialog("Search movies and shows", "", "Search", onConfirm = { q -> typingSearch = false; if (q.isNotBlank()) showResults(q.trim()) }, onDismiss = { typingSearch = false })
    }
    searchResults?.let { (q, found) ->
        ActionMenu(
            title = "\u201c$q\u201d", subtitle = "${found.size} found",
            onDismiss = { searchResults = null },
            actions = found.map { (place, f) ->
                MenuAction(com.woodruff.tvexplorer.cloud.Library.label(f) + " · " + place.title, if (f.isFolder) Icons.Rounded.Tv else Icons.Rounded.Movie) {
                    if (f.isFolder) { drive.openRoot(place); drive.open(DrivePlace.Folder(f.id, f.name)); vm.show(Screen.DRIVE) }
                    else {
                        val key = com.woodruff.tvexplorer.player.MediaRef.Drive(f.id).key
                        searchContext.startActivity(com.woodruff.tvexplorer.player.PlayerActivity.intent(searchContext, listOf(key), listOf(f.name), key))
                    }
                }
            },
        )
    }
    com.woodruff.tvexplorer.ui.screens.UpdatePrompt(vm)
    // Doorbell: show it full screen when Woodruff is on screen (the notice covers other apps).
    LaunchedEffect(Unit) {
        com.woodruff.tvexplorer.nest.Doorbell.rings.collect { camera ->
            if (!com.woodruff.tvexplorer.notify.AppVisibility.foreground) return@collect
            val i = com.woodruff.tvexplorer.nest.Nest.state.value.cameras.indexOfFirst { it.name == camera }.coerceAtLeast(0)
            if (vm.screen.value == Screen.CAMERAS) com.woodruff.tvexplorer.ui.screens.cameraRequests.tryEmit(i)
            else { com.woodruff.tvexplorer.ui.screens.openCameraAt = i; vm.show(Screen.CAMERAS) }
        }
    }
    LaunchedEffect(railWanted) {
        if (!railWanted) return@LaunchedEffect
        withFrameNanos {}
        if (runCatching { railActive.requestFocus() }.isFailure || !railFocused) runCatching { homeFocus.requestFocus() }
        withFrameNanos {}
        railWanted = false
    }
    // Back, as on Apple TV: from Home it brings up the sidebar (from the sidebar, see its key handler).
    androidx.activity.compose.BackHandler(enabled = !railFocused && screen == Screen.HOME) { railWanted = true }
    // A screen chosen in the side menu: move into it once it's on screen (two frames: composed and
    // laid out), so the next press acts on the screen, not the menu.
    LaunchedEffect(enterContent) {
        if (enterContent == 0) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        // Unless the screen already put the focus somewhere itself (the title you left from, say).
        if (!contentFocused) runCatching { contentFocus.requestFocus() }
    }
    // A screen changed from inside the content (Back to Home, a dashboard shortcut): the focused
    // item went away with the old screen, so put focus in the new one unless it took it itself.
    LaunchedEffect(screen) {
        withFrameNanos {}
        withFrameNanos {}
        if (!railFocused && !contentFocused) runCatching { contentFocus.requestFocus() }
    }
    // The smoothness meter's name for what's on screen: Movies and TV Shows apart, and set again on
    // coming back from the player (which counts its frames as "Player").
    val frameLabel = when (screen) {
        Screen.DRIVE -> driveTop?.title ?: "Google Drive"
        else -> screen.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    LaunchedEffect(frameLabel) { com.woodruff.tvexplorer.util.FrameStats.screen = frameLabel }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { com.woodruff.tvexplorer.util.FrameStats.screen = frameLabel }
    LaunchedEffect(Unit) {
        // Start in the content with the menu closed; fall back to the menu if nothing there takes focus.
        delay(300)
        runCatching { contentFocus.requestFocus() }
        delay(100)
        if (!contentFocused) railWanted = true
    }
}

/**
 * Apple's ease-out: moves off quickly and glides to a stop, with no visible end — the curve behind
 * the "buttery" feel of tvOS transitions.
 */
internal val SmoothEase = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * A screen (or a page over it) easing in, as on Apple TV: a fade with a gentle zoom from just
 * under full size. Runs in the draw layer only, so it costs no recomposition or layout.
 */
@Composable
internal fun ScreenEnter(content: @Composable () -> Unit) {
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(320, easing = SmoothEase)) }
    // ModulateAlpha: fades without first drawing the whole screen into an offscreen buffer.
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = p.value
        val s = 0.97f + 0.03f * p.value
        scaleX = s; scaleY = s
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }) { content() }
}

/**
 * How lists and grids scroll to follow the focus. Compose's TV default keeps the focused item
 * pinned a third of the way in, so every press slides the whole page. This only scrolls when the
 * focus gets near an edge, keeping a margin so the next row or column is already in view, on a
 * quick, fully damped spring: moves feel immediate and settle without bounce.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal val SmoothBringIntoView = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        // Fully damped: glides to a stop without bounce; soft enough to feel smooth, quick enough
        // that holding a direction never leaves the focus behind.
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 700f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.16f
        val trailing = offset + size
        return when {
            // Too big for the margins: show its start if it can't fit, else center it.
            size >= containerSize -> offset
            size > containerSize - 2 * margin -> offset - (containerSize - size) / 2f
            offset < margin -> offset - margin
            trailing > containerSize - margin -> trailing - (containerSize - margin)
            else -> 0f
        }
    }
}

private val RAIL_CLOSED = 0.dp

/** The rail item for the screen on show, which takes the focus when the sidebar is called. */
private val LocalRailActive = androidx.compose.runtime.staticCompositionLocalOf<FocusRequester?> { null }
/** False while the sidebar is hidden: its items can't be reached by accident from the screen. */
private val LocalRailFocusable = androidx.compose.runtime.compositionLocalOf { true }
private val RAIL_OPEN = 260.dp

/**
 * Brings up the sidebar. A screen's Back at its top calls it (as on Apple TV) rather than going
 * straight Home; Home is chosen in the sidebar.
 */
internal val LocalOpenSidebar = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** The remote's search button asks for a voice search (MainActivity forwards it here). */
internal object VoiceSearch {
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** Gallery hook: draws the menu open without giving it focus. */
internal object RailPreview { var open by mutableStateOf(false) }

/** Drive's progress dialog, collected here so download ticks redraw only the dialog. */
@Composable
private fun DriveProgress(drive: DriveViewModel) {
    val s by drive.state.collectAsStateWithLifecycle()
    s.operation?.let { ProgressDialog(it.title, it.progress, drive::cancelOperation) }
}

@Composable
private fun DriveMessage(drive: DriveViewModel, modifier: Modifier) {
    val message by remember(drive) { drive.state.map { it.message }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
    MessageBanner(message, drive::dismissMessage, modifier)
}

/** Top-left brand: the Woodruff Family app-icon tile (the triangle emblem) and the wordmark. */
@Composable
private fun Brand(open: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.brand_logo), contentDescription = "Woodruff Family logo", modifier = Modifier.size(44.dp))
        if (open) Column(Modifier.padding(start = 12.dp)) {
            Text("WOODRUFF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Palette.text, letterSpacing = 1.sp)
            Text("FAMILY", style = MaterialTheme.typography.labelMedium, color = Palette.accent, letterSpacing = 6.sp)
        }
    }
}

@Composable
private fun RailLabel(text: String, open: Boolean) {
    Text(
        if (open) text.uppercase() else "",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.muted,
        modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun RailItem(label: String, icon: ImageVector, active: Boolean, open: Boolean, modifier: Modifier = Modifier, badge: Boolean = false, onClick: () -> Unit) {
    val activeFocus = LocalRailActive.current
    val focusable = LocalRailFocusable.current
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (active && activeFocus != null) Modifier.focusRequester(activeFocus) else Modifier)
            .focusProperties { canFocus = focusable }
            .fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Palette.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (active) Palette.accent else Palette.text,
            focusedContainerColor = Palette.accent,
            focusedContentColor = Palette.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TvIcon(icon)
                // Closed menu: a dot stands in for "· 3" or "· Connected".
                if (badge && !open) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(RoundedCornerShape(50)).background(Palette.accent))
            }
            if (open) {
                Spacer(Modifier.width(14.dp))
                Text(label, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * In-app notification card (top right) for messages from the phone and new mail while the app
 * is open. Outside the app the same notice appears as an overlay or system notification.
 */
@Composable
private fun NoticeBanner(notice: Notice?, modifier: Modifier, onOpen: () -> Unit) {
    LaunchedEffect(notice) { if (notice != null) { delay(7000); Notices.dismissLatest() } }
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier.padding(top = 24.dp, end = 24.dp),
    ) {
        val n = notice ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = Palette.glass, contentColor = Palette.text),
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Palette.rim), shape = RoundedCornerShape(20.dp)),
            modifier = Modifier.width(360.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    when (n.source) { NoticeSource.PHONE -> "FROM YOUR PHONE"; NoticeSource.MAIL -> "NEW MAIL"; NoticeSource.SYSTEM -> "WOODRUFF FAMILY" },
                    style = MaterialTheme.typography.labelSmall, color = Palette.accent,
                )
                Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 3)
            }
        }
    }
}

/** Toast-like status pill that slides up and hides itself after a few seconds. */
@Composable
private fun MessageBanner(message: String?, onTimeout: () -> Unit, modifier: Modifier) {
    LaunchedEffect(message) {
        if (message != null) { delay(4500); onTimeout() }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier.padding(bottom = 36.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            colors = SurfaceDefaults.colors(containerColor = Palette.glass, contentColor = Palette.text),
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Palette.rim), shape = RoundedCornerShape(28.dp)),
        ) {
            Text(message.orEmpty(), modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp))
        }
    }
}

