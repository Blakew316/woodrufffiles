package com.woodruff.tvexplorer.ui

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.woodruff.tvexplorer.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.ui.components.ProgressDialog
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
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import kotlinx.coroutines.delay
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.FocusPlatter
import com.woodruff.tvexplorer.ui.motion.GlideBetween
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.focusPlatterHost
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState
import com.woodruff.tvexplorer.ui.motion.rememberFocusPlatter
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.components.rememberRowTint
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Main window: branded side rail, the active screen, and global overlays. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val drive: DriveViewModel = viewModel()
    val screen by vm.screen.collectAsStateWithLifecycle()
    // Only the folder the browser is in (for the Family Memories item): the full browser state
    // changes with every listing, selection and sort, and would redraw the menu with it.
    val browserPath by remember(vm) { vm.browser.map { it.folder?.path }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(vm.browser.value.folder?.path)
    // Only the library the Drive screen is in: the full Drive state changes on every download
    // progress tick and would redraw the whole window with it.
    val driveTop by remember(drive) { drive.state.map { it.stack.firstOrNull() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(drive.state.value.stack.firstOrNull())
    // The library Streaming last showed (Movies or TV Shows), to open it again.
    var lastLibrary by remember { mutableStateOf<DrivePlace>(DrivePlace.Movies) }
    LaunchedEffect(driveTop) { driveTop?.takeIf { it == DrivePlace.Movies || it == DrivePlace.TvShows }?.let { lastLibrary = it } }
    val homeFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    // The screen on show (not one gliding out under it): where the focus goes into the content, as it
    // went into the content before (its first item, unless the screen puts it somewhere itself).
    val screenFocus = remember { FocusRequester() }
    val switchScope = rememberCoroutineScope()
    val enterScreen: () -> Unit = {
        if (runCatching { screenFocus.requestFocus() }.isFailure) runCatching { contentFocus.requestFocus() }
    }
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
        // Optional background photo (Settings → Screensaver & background), dimmed so text stays
        // readable. Dimmed as it's drawn (a colour filter), not by a see-through layer over the
        // whole screen: the same picture, one full-screen fill fewer on every frame.
        Appearance.background?.let { path ->
            val scrim = Palette.background
            val dimmed = remember(scrim) { ColorFilter.colorMatrix(ColorMatrix(scrimMatrix(scrim, 0.78f))) }
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, colorFilter = dimmed, modifier = Modifier.fillMaxSize())
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
                CompositionLocalProvider(LocalOpenSidebar provides { railWanted = true }) {
                // A screen chosen in the side menu fades in growing into place while the one it
                // replaces fades out over it, on springs, in their drawing layers (nothing laid out
                // again per frame). The old one goes at once instead when the focus was in it (Back to
                // Home, a shortcut on Home: the focus moves into the new screen as it always did) or
                // when it shows live video (the Command center, Home's cameras): those views are torn
                // down in an ordinary recomposition, as they must be (a cross-fade doing it crashed some TVs).
                GlideBetween(
                    if (screen in COMMAND_SCREENS) Screen.CAMERAS else screen,
                    Modifier.fillMaxSize(),
                    enter = TvMotion.ScreenIn,
                    exit = TvMotion.ScreenOut,
                    animateFirst = true,
                    instant = { old, _ -> contentFocused || old == Screen.CAMERAS || (old == Screen.HOME && Appearance.homeLive) },
                    holder = { leaving ->
                        if (!leaving) Modifier.focusRequester(screenFocus).focusGroup()
                        // A screen on its way out never keeps the focus: a press that lands in it goes
                        // on into the new screen.
                        else Modifier.onFocusChanged { if (it.hasFocus) switchScope.launch { runCatching { screenFocus.requestFocus() } } }.focusGroup()
                    },
                    layer = { shown, entering, _ ->
                        alpha = shown
                        val from = if (entering) TvMotion.ScreenEnterScale else TvMotion.ScreenLeaveScale
                        val s = from + (1f - from) * shown
                        scaleX = s; scaleY = s
                    },
                ) { s ->
                    when (s) {
                        Screen.HOME -> HomeScreen(vm, drive)
                        Screen.BROWSER -> BrowserScreen(vm)
                        Screen.DRIVE -> DriveScreen(vm, drive)
                        // The Command center's pages switch inside it (its pills), with the live one.
                        Screen.MAIL, Screen.NOTIFICATIONS, Screen.NOTES, Screen.CAMERAS -> CommandHub(screen, vm)
                        Screen.APPS -> AppsScreen(vm)
                        Screen.TRANSFER -> TransferScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                    }
                }
                }
                MainMessage(vm, Modifier.align(Alignment.BottomCenter))
                DriveMessage(drive, Modifier.align(Alignment.BottomCenter))
                NoticeBanner(Modifier.align(Alignment.TopEnd)) { vm.show(Screen.NOTIFICATIONS) }
        }
        // Dim the dashboard while the menu is open over it.
        // One fully damped spring drives the menu's slide and this dimming together: it starts
        // quickly, eases to a stop without overshoot, and picks up smoothly if the menu reverses
        // half-way (a timed curve restarts and jerks). Both read it only in their drawing layers, so
        // opening and closing the menu recompose and lay out nothing.
        val reveal = animateFloatAsState(if (open) 1f else 0f, TvMotion.RailReveal, label = "rail")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }.background(Brush.horizontalGradient(0f to Palette.background.copy(alpha = 0.92f), 0.6f to Palette.background.copy(alpha = 0.55f), 1f to Palette.background.copy(alpha = 0.25f))))
        // Side menu: hidden until called, then it glides in from the left edge with its labels, over
        // the content, and back out the same way. Its white highlight glides from item to item.
        val railPlatter = rememberFocusPlatter()
        Column(
            Modifier
                // Laid out once, at its open width; each frame of the glide only moves its layer (out
                // past the left edge by however much of it is hidden: closed, it draws nothing).
                .width(RAIL_OPEN)
                .fillMaxHeight()
                .graphicsLayer { translationX = -(1f - reveal.value) * size.width }
                .drawBehind { drawRect(Palette.glass) }
                .onFocusChanged { railFocused = it.hasFocus }
                // Right goes into the content (the open menu covers its left edge, so a plain geometric
                // search would skip the first column).
                .onPreviewKeyEvent { e ->
                    when {
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> {
                            enterScreen()
                            contentFocused
                        }
                        // Back from the menu returns to the screen (on Home it leaves the app, as
                        // before). Handled here, where the key arrives, not with a BackHandler: each
                        // screen adds its own BackHandler when it opens, after AppRoot's, and that one
                        // won — Back went Home or up a folder behind the menu, and then out of the app.
                        e.key == Key.Back && screen != Screen.HOME -> {
                            if (e.type == KeyEventType.KeyUp) enterScreen()
                            true
                        }
                        else -> false
                    }
                }
                // On the menu's glass, behind its items; before the padding, so the first and last
                // items' grown platters aren't cut off.
                .focusPlatterHost(railPlatter, RailItemShape, Palette.text)
                .padding(vertical = 28.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
              CompositionLocalProvider(LocalRailActive provides railActive, LocalRailFocusable provides (open || railWanted)) {
                Brand(Modifier.padding(start = 6.dp, bottom = 24.dp))
                // Choosing a screen in the menu goes straight into it (the menu closes behind).
                val go: (() -> Unit) -> Unit = { action -> action(); enterContent++ }
                RailItem("Home", Sym.Home, screen == Screen.HOME, railPlatter, Modifier.focusRequester(homeFocus)) { go { vm.show(Screen.HOME) } }
                // Streaming: Movies and TV Shows (switched at the top of the tab), from those Drive folders.
                RailItem("Streaming", Sym.Streaming, screen == Screen.DRIVE && (driveTop == DrivePlace.Movies || driveTop == DrivePlace.TvShows), railPlatter) {
                    go { drive.openRoot(lastLibrary); vm.show(Screen.DRIVE) }
                }
                // Family Memories: the photos and videos picked from Google Photos, saved on the TV.
                val inFolder = { dir: java.io.File -> screen == Screen.BROWSER && browserPath?.startsWith(dir.path) == true }
                RailItem("Family Memories", Sym.PhotoLibrary, inFolder(GooglePhotos.folder) || inFolder(GooglePhotos.videoFolder), railPlatter) {
                    go { vm.openRoot(GooglePhotos.folder.apply { mkdirs() }) }
                }
                // Command Center: cameras, mail, notifications and notes (switched at the top).
                RailItem("Command Center", Sym.Videocam, screen in COMMAND_SCREENS, railPlatter) { go { vm.show(Screen.CAMERAS) } }
                // Set apart at the bottom, so it's clear it's Settings you're going to.
                Spacer(Modifier.weight(1f))
                RailItem("Settings", Sym.Settings, screen == Screen.SETTINGS, railPlatter) { go { vm.show(Screen.SETTINGS) } }
              }
        }
    }

    OperationProgress(vm)
    DriveProgress(drive)
    if (typingSearch) {
        TextInputDialog("Search movies and shows", "", "Search", onConfirm = { q -> typingSearch = false; if (q.isNotBlank()) showResults(q.trim()) }, onDismiss = { typingSearch = false })
    }
    searchResults?.let { (q, found) ->
        ActionMenu(
            title = "\u201c$q\u201d", subtitle = "${found.size} found",
            onDismiss = { searchResults = null },
            actions = found.map { (place, f) ->
                MenuAction(com.woodruff.tvexplorer.cloud.Library.label(f) + " · " + place.title, if (f.isFolder) Sym.Tv else Sym.Movie) {
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
        if (!contentFocused) enterScreen()
    }
    // A screen changed from inside the content (Back to Home, a dashboard shortcut): the focused
    // item went away with the old screen, so put focus in the new one unless it took it itself.
    LaunchedEffect(screen) {
        withFrameNanos {}
        withFrameNanos {}
        if (!railFocused && !contentFocused) enterScreen()
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
        // Start in the content with the menu closed (unless the screen already took the focus, as the
        // Command Center does on a notification tap); fall back to the menu if nothing there takes focus.
        delay(300)
        if (!contentFocused) enterScreen()
        delay(100)
        if (!contentFocused) railWanted = true
    }
}

/**
 * A page easing in over the one before it (a title picked on a title's page), as on Apple TV: a fade
 * with a gentle zoom from just under full size, on the screens' spring. Runs in the draw layer only,
 * so it costs no recomposition or layout. [animate] false: there at once (the window it's in is
 * gliding in already).
 */
@Composable
internal fun ScreenEnter(animate: Boolean = true, content: @Composable () -> Unit) {
    val p = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { p.animateTo(1f, TvMotion.ScreenIn) }
    // ModulateAlpha: fades without first drawing the whole screen into an offscreen buffer.
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = p.value
        val s = TvMotion.ScreenEnterScale + (1f - TvMotion.ScreenEnterScale) * p.value
        scaleX = s; scaleY = s
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }) { content() }
}

/**
 * How lists and grids scroll to follow the focus. Compose's TV default keeps the focused item
 * pinned a third of the way in, so every press slides the whole page. This only scrolls when the
 * focus gets near an edge, keeping a margin so the next row or column is already in view, on a
 * quick, fully damped spring: moves feel immediate and settle without bounce. Lists that set their
 * own (PivotScroll) don't use this.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal val SmoothBringIntoView = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    // The app's one scrolling spring (fully damped: glides to a stop without bounce, and a held
    // direction never leaves the focus behind), so every list scrolls alike.
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = TvMotion.ColumnScroll

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

/**
 * A colour matrix that draws a picture as if [scrim] lay over it at [alpha]: each colour keeps
 * (1 - alpha) of its own value and takes alpha of the scrim's (out of 255); its transparency is
 * left as it is. In one pass, the same pixels as the picture with the scrim drawn over it (exactly,
 * for an opaque picture or one over the scrim's own colour, as the background photo is).
 */
internal fun scrimMatrix(scrim: Color, alpha: Float): FloatArray {
    val keep = 1f - alpha
    return floatArrayOf(
        keep, 0f, 0f, 0f, scrim.red * alpha * 255f,
        0f, keep, 0f, 0f, scrim.green * alpha * 255f,
        0f, 0f, keep, 0f, scrim.blue * alpha * 255f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** A side menu item's corners, and its gliding highlight's. */
private val RailItemShape = RoundedCornerShape(14.dp)

/** The rail item for the screen on show, which takes the focus when the sidebar is called. */
private val LocalRailActive = androidx.compose.runtime.staticCompositionLocalOf<FocusRequester?> { null }
/** False while the sidebar is hidden: its items can't be reached by accident from the screen. */
private val LocalRailFocusable = androidx.compose.runtime.compositionLocalOf { true }
private val RAIL_OPEN = 260.dp

/** The screens the Command Center tab holds. */
private val COMMAND_SCREENS = setOf(Screen.CAMERAS, Screen.MAIL, Screen.NOTIFICATIONS, Screen.NOTES)

/**
 * The Command Center tab: cameras, mail, notifications and notes, switched with the pills across
 * the top. It stays put while switching (so the focus stays on the pills); the pills step aside
 * while a camera is full screen.
 */
@Composable
private fun CommandHub(screen: Screen, vm: MainViewModel) {
    val tabs = listOf(Screen.CAMERAS to "Cameras", Screen.MAIL to "Mail", Screen.NOTIFICATIONS to "Notifications", Screen.NOTES to "Notes")
    val chosen = tabs.indexOfFirst { it.first == screen }.coerceAtLeast(0)
    val tabsFocus = remember { FocusRequester() }
    var hubFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Column(Modifier.fillMaxSize().onFocusChanged { hubFocused = it.hasFocus }) {
        if (!com.woodruff.tvexplorer.ui.screens.cameraFullScreen.value) {
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 36.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                com.woodruff.tvexplorer.ui.components.TopTabs(
                    tabs.map { it.second }, selected = chosen,
                    onSelect = { i -> vm.show(tabs[i].first) },
                    modifier = Modifier.weight(1f).focusRequester(tabsFocus),
                )
                HubClock()
            }
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.MAIL -> MailScreen(vm)
                Screen.NOTIFICATIONS -> NotificationsScreen(vm)
                Screen.NOTES -> NotesScreen(vm)
                else -> CommandCenterScreen(vm)
            }
        }
    }
    // A tab put up from elsewhere (Home's "At a glance", say) with nothing here taking the focus:
    // AppRoot's fallback would put it on the first pill, Cameras, where OK switches tabs. Put it on
    // the chosen pill instead, stepping across from the first (the pills have no handles of their own).
    LaunchedEffect(screen) {
        withFrameNanos {} // once the pills are laid out
        if (!hubFocused && runCatching { tabsFocus.requestFocus() }.isSuccess) {
            repeat(chosen) { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right) }
        }
    }
}

/** The time and date, at the Command Center's top right. */
@Composable
private fun HubClock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Ticks on each new minute (and only then).
    LaunchedEffect(Unit) { while (true) { delay(untilNextMinute(System.currentTimeMillis())); now = System.currentTimeMillis() } }
    // Made once, not on every tick: building a date format loads the language's month and day names.
    val time = remember { java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()) }
    val day = remember { java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()) }
    Column(horizontalAlignment = Alignment.End) {
        Text(time.format(java.util.Date(now)), style = MaterialTheme.typography.headlineMedium)
        Text(day.format(java.util.Date(now)), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * How long from [now] until just after the next minute begins. The small margin keeps a wake-up a
 * few milliseconds early (timers and the wall clock drift apart) from reading the old minute, which
 * would then stay on screen for another whole minute.
 */
internal fun untilNextMinute(now: Long): Long = 60_000 - now.mod(60_000L) + 50

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

/** A copy, move, delete or zip's progress dialog, collected here so its ticks (every 120 ms) redraw only the dialog. */
@Composable
private fun OperationProgress(vm: MainViewModel) {
    val operation by vm.operation.collectAsStateWithLifecycle()
    operation?.let { ProgressDialog(it.title, it.progress, vm::cancelOperation) }
}

@Composable
private fun DriveMessage(drive: DriveViewModel, modifier: Modifier) {
    val message by remember(drive) { drive.state.map { it.message }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
    MessageBanner(message, drive::dismissMessage, modifier)
}

/** The window's own messages ("Pasted 3 items"), collected here so one coming or going redraws only the banner. */
@Composable
private fun MainMessage(vm: MainViewModel, modifier: Modifier) {
    val message by vm.message.collectAsStateWithLifecycle()
    MessageBanner(message, vm::dismissMessage, modifier)
}

/**
 * Top-left brand: the Woodruff Family app-icon tile (the triangle emblem) and the wordmark. Always
 * all there: the menu glides out of sight closed, and keeps its wordmark and labels as it glides.
 */
@Composable
private fun Brand(modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    // Decoded once, off the main thread: read while composing, the PNG held up the first frame
    // (the menu starts hidden, so it's ready long before it shows).
    val logo by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeResource(resources, R.drawable.brand_logo)?.asImageBitmap() }.getOrNull() }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp)) {
            logo?.let { Image(it, contentDescription = "Woodruff Family logo", modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.padding(start = 12.dp)) {
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

/**
 * A side menu item. Monochrome, as on Apple TV: the current screen's sits in soft glass, and the
 * focused one turns white, the menu's white [platter] gliding over to it from the item the focus left
 * while its symbol and name turn dark as they draw.
 */
@Composable
private fun RailItem(label: String, icon: ImageVector, active: Boolean, platter: FocusPlatter, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val activeFocus = LocalRailActive.current
    val focusable = LocalRailFocusable.current
    val motion = rememberFocusMotionState()
    val tint = rememberRowTint(motion, Palette.text, Palette.background)
    val glass = if (active) Palette.text.copy(alpha = 0.12f) else Color.Transparent
    TvCard(
        onClick = onClick,
        shape = RailItemShape,
        modifier = modifier
            .then(if (active && activeFocus != null) Modifier.focusRequester(activeFocus) else Modifier)
            .focusProperties { canFocus = focusable }
            .fillMaxWidth(),
        motion = motion,
        focusedScale = TvMotion.RailScale,
        color = glass,
        // The soft glass gives way to the platter.
        focusedColor = glass.copy(alpha = 0f),
        platter = platter,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TintIcon(icon, tint.color)
            Spacer(Modifier.width(14.dp))
            RowText(
                label, tint.color, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/**
 * In-app notification card (top right) for messages from the phone and new mail while the app
 * is open. Outside the app the same notice appears as an overlay or system notification.
 */
@Composable
private fun NoticeBanner(modifier: Modifier, onOpen: () -> Unit) {
    // Collected here, so a notice coming or going redraws only this card.
    val notice by Notices.latest.collectAsStateWithLifecycle()
    LaunchedEffect(notice) { if (notice != null) { delay(7000); Notices.dismissLatest() } }
    // The last one shown, so it's still there as it glides away (a new one replaces it at once).
    val kept = remember { Kept<com.woodruff.tvexplorer.notify.Notice>() }
    notice?.let { kept.value = it }
    // Glides down into place from the top edge, and back up (see bannerGlide).
    AnimatedVisibility(
        visible = notice != null,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = modifier.padding(top = 24.dp, end = 24.dp),
    ) {
        val n = notice ?: kept.value ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = Palette.glass, contentColor = Palette.text),
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Palette.rim), shape = RoundedCornerShape(20.dp)),
            modifier = Modifier.bannerGlide(transition, fromTop = true).width(360.dp),
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

/** Toast-like status pill that glides up into place and hides itself after a few seconds. */
@Composable
private fun MessageBanner(message: String?, onTimeout: () -> Unit, modifier: Modifier) {
    LaunchedEffect(message) {
        if (message != null) { delay(4500); onTimeout() }
    }
    // The last words shown, so they're still there as the pill glides away (new ones replace them at once).
    val kept = remember { Kept<String>() }
    message?.let { kept.value = it }
    AnimatedVisibility(
        visible = message != null,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = modifier.padding(bottom = 36.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            colors = SurfaceDefaults.colors(containerColor = Palette.glass, contentColor = Palette.text),
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Palette.rim), shape = RoundedCornerShape(28.dp)),
            modifier = Modifier.bannerGlide(transition, fromTop = false),
        ) {
            Text((message ?: kept.value).orEmpty(), modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp))
        }
    }
}

/** The last thing a banner showed, for it to keep showing while it glides away. */
private class Kept<T : Any> { var value: T? = null }

/**
 * A banner gliding in from its edge (the top, or the bottom) with a soft settle as it fades in, and
 * quicker back out, on [TvMotion]'s banner springs: moved and faded in its drawing layer (ModulateAlpha),
 * so nothing is laid out again per frame. Driven by the AnimatedVisibility's own [transition], which
 * keeps the banner until it's gone.
 */
@Composable
private fun Modifier.bannerGlide(transition: Transition<EnterExitState>, fromTop: Boolean): Modifier {
    val shown = transition.animateFloat(
        transitionSpec = { if (targetState == EnterExitState.Visible) TvMotion.BannerIn else TvMotion.BannerOut },
        label = "banner",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    return graphicsLayer {
        val p = shown.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * size.height * (if (fromTop) -1f else 1f)
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }
}
