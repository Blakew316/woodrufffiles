package com.woodruff.tvexplorer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
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
import com.woodruff.tvexplorer.notify.Notice
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import kotlinx.coroutines.delay

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
    val driveState by drive.state.collectAsStateWithLifecycle()
    val server by TransferService.state.collectAsStateWithLifecycle()
    val notices by Notices.items.collectAsStateWithLifecycle()
    val latestNotice by Notices.latest.collectAsStateWithLifecycle()
    val unread = notices.count { !it.read }
    val homeFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val open = railFocused || RailPreview.open

    Box(Modifier.fillMaxSize().background(Palette.backdrop)) {
        // Optional background photo (Settings → Screensaver & background), dimmed so text stays readable.
        Appearance.background?.let { path ->
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.78f)))
        }
        // Content keeps the slim rail's width to its left, so it never reflows when the menu opens:
        // the open menu slides over the dashboard instead.
        Box(Modifier.fillMaxSize().padding(start = RAIL_CLOSED).onFocusChanged { contentFocused = it.hasFocus }.focusRequester(contentFocus).focusRestorer().focusGroup()) {
                // Switch screens in one step, not a cross-fade: during a fade the Command center's
                // live video views were torn down by the transition, which crashed on some TVs.
                key(screen) {
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
                MessageBanner(message, vm::dismissMessage, Modifier.align(Alignment.BottomCenter))
                MessageBanner(driveState.message, drive::dismissMessage, Modifier.align(Alignment.BottomCenter))
                NoticeBanner(latestNotice, Modifier.align(Alignment.TopEnd)) { vm.show(Screen.NOTIFICATIONS) }
        }
        // Dim the dashboard while the menu is open over it.
        val scrim by animateFloatAsState(if (open) 1f else 0f, tween(220), label = "scrim")
        if (scrim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(Brush.horizontalGradient(0f to Palette.background.copy(alpha = 0.92f), 0.6f to Palette.background.copy(alpha = 0.55f), 1f to Palette.background.copy(alpha = 0.25f))))
        // Side menu: icons only until it has focus, then it opens to full labels over the content.
        val railWidth by animateDpAsState(if (open) RAIL_OPEN else RAIL_CLOSED, tween(220), label = "rail")
        Column(
            Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(if (open) Palette.surface else Palette.background.copy(alpha = 0.6f))
                .onFocusChanged { railFocused = it.hasFocus }
                // Right goes back to where you were in the content (the open menu covers its left edge,
                // so a plain geometric search would skip the first column).
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                        runCatching { contentFocus.requestFocus() }
                        contentFocused
                    } else false
                }
                .focusRestorer { homeFocus }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 28.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
                Brand(open, Modifier.padding(start = 6.dp, bottom = 24.dp))
                RailItem("Home", Icons.Rounded.Home, screen == Screen.HOME, open, Modifier.focusRequester(homeFocus)) { vm.show(Screen.HOME) }
                RailItem("Command center", Icons.Rounded.Videocam, screen == Screen.CAMERAS, open) { vm.show(Screen.CAMERAS) }
                // Library: Movies and TV Shows stream from those Drive folders; Photos and Videos are
                // what was picked from Google Photos, saved on the TV.
                val driveTop = driveState.stack.firstOrNull()
                RailItem("Movies", Icons.Rounded.Movie, screen == Screen.DRIVE && driveTop == DrivePlace.Movies, open) {
                    drive.openRoot(DrivePlace.Movies); vm.show(Screen.DRIVE)
                }
                RailItem("TV Shows", Icons.Rounded.Tv, screen == Screen.DRIVE && driveTop == DrivePlace.TvShows, open) {
                    drive.openRoot(DrivePlace.TvShows); vm.show(Screen.DRIVE)
                }
                val inFolder = { dir: java.io.File -> screen == Screen.BROWSER && browser.folder?.path?.startsWith(dir.path) == true }
                RailItem("Photos", Icons.Rounded.PhotoLibrary, inFolder(GooglePhotos.folder), open) {
                    vm.openRoot(GooglePhotos.folder.apply { mkdirs() })
                }
                RailItem("Videos", Icons.Rounded.VideoLibrary, inFolder(GooglePhotos.videoFolder), open) {
                    vm.openRoot(GooglePhotos.videoFolder.apply { mkdirs() })
                }
                RailItem("Mail", Icons.Rounded.Email, screen == Screen.MAIL, open) { vm.show(Screen.MAIL) }
                RailItem(
                    if (unread > 0) "Notifications · $unread" else "Notifications",
                    Icons.Rounded.Notifications, screen == Screen.NOTIFICATIONS, open, badge = unread > 0,
                ) { vm.show(Screen.NOTIFICATIONS) }
                RailItem("Notes", Icons.Rounded.EditNote, screen == Screen.NOTES, open) { vm.show(Screen.NOTES) }
                RailLabel("Connect", open)
                RailItem(if (server.running) "Phone · Connected" else "Phone", Icons.Rounded.PhoneIphone, screen == Screen.TRANSFER, open, badge = server.running) {
                    vm.show(Screen.TRANSFER)
                }
                RailItem("Settings", Icons.Rounded.Settings, screen == Screen.SETTINGS, open) { vm.show(Screen.SETTINGS) }
        }
    }

    operation?.let { ProgressDialog(it.title, it.progress, vm::cancelOperation) }
    driveState.operation?.let { ProgressDialog(it.title, it.progress, drive::cancelOperation) }
    com.woodruff.tvexplorer.ui.screens.UpdatePrompt(vm)
    LaunchedEffect(Unit) {
        // Start in the content with the menu closed; fall back to the menu if nothing there takes focus.
        delay(300)
        runCatching { contentFocus.requestFocus() }
        delay(100)
        if (!contentFocused) runCatching { homeFocus.requestFocus() }
    }
}

private val RAIL_CLOSED = 84.dp
private val RAIL_OPEN = 260.dp

/** Gallery hook: draws the menu open without giving it focus. */
internal object RailPreview { var open by mutableStateOf(false) }

/** Top-left brand: the Woodruff Family reel emblem (transparent outside the ring) and the wordmark. */
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
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
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
            colors = SurfaceDefaults.colors(containerColor = Palette.surfaceHigh, contentColor = Palette.text),
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
            colors = SurfaceDefaults.colors(containerColor = Palette.surfaceHigh, contentColor = Palette.text),
        ) {
            Text(message.orEmpty(), modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp))
        }
    }
}

