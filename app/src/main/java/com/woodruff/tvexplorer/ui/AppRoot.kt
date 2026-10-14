package com.woodruff.tvexplorer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import com.woodruff.tvexplorer.ui.screens.CommandCenterScreen
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.ui.screens.NotesScreen
import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    Box(Modifier.fillMaxSize().background(Palette.backdrop)) {
        // Optional background photo (Settings → Screensaver & background), dimmed so text stays readable.
        Appearance.background?.let { path ->
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.78f)))
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(250.dp)
                    .fillMaxHeight()
                    .background(Palette.background.copy(alpha = 0.6f))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 28.dp, horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Brand(Modifier.padding(start = 8.dp, bottom = 24.dp))
                RailItem("Home", Icons.Rounded.Home, screen == Screen.HOME, Modifier.focusRequester(homeFocus)) { vm.show(Screen.HOME) }
                RailItem("Command center", Icons.Rounded.Videocam, screen == Screen.CAMERAS) { vm.show(Screen.CAMERAS) }
                RailItem("Google Drive", Icons.Rounded.AddToDrive, screen == Screen.DRIVE, brand = R.drawable.brand_google_drive) { vm.show(Screen.DRIVE) }
                RailItem("Mail", Icons.Rounded.Email, screen == Screen.MAIL) { vm.show(Screen.MAIL) }
                RailItem(
                    if (unread > 0) "Notifications · $unread" else "Notifications",
                    Icons.Rounded.Notifications, screen == Screen.NOTIFICATIONS,
                ) { vm.show(Screen.NOTIFICATIONS) }
                RailItem("Notes", Icons.Rounded.EditNote, screen == Screen.NOTES) { vm.show(Screen.NOTES) }
                RailLabel("This TV")
                roots.forEach { root ->
                    val active = screen == Screen.BROWSER &&
                        browser.folder?.absolutePath?.startsWith(root.dir.absolutePath) == true
                    RailItem(root.title, if (root.removable) Icons.Rounded.SdStorage else Icons.Rounded.Storage, active) {
                        vm.openRoot(root.dir)
                    }
                }
                RailItem("Apps", Icons.Rounded.Apps, screen == Screen.APPS) { vm.show(Screen.APPS) }
                RailLabel("Connect")
                RailItem(if (server.running) "Phone · Connected" else "Phone", Icons.Rounded.PhoneIphone, screen == Screen.TRANSFER) {
                    vm.show(Screen.TRANSFER)
                }
                RailItem("Settings", Icons.Rounded.Settings, screen == Screen.SETTINGS) { vm.show(Screen.SETTINGS) }
            }
            Box(Modifier.fillMaxSize()) {
                Crossfade(targetState = screen, label = "screen") { s ->
                    when (s) {
                        Screen.HOME -> HomeScreen(vm, roots)
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
        }
    }

    operation?.let { ProgressDialog(it.title, it.progress, vm::cancelOperation) }
    driveState.operation?.let { ProgressDialog(it.title, it.progress, drive::cancelOperation) }
    LaunchedEffect(Unit) {
        if (Appearance.startOnCameras) vm.show(Screen.CAMERAS) else runCatching { homeFocus.requestFocus() }
    }
}

/** Top-left brand: the Woodruff Films reel emblem (transparent outside the ring) and the wordmark. */
@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.brand_logo), contentDescription = "Woodruff Films logo", modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("WOODRUFF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Palette.text, letterSpacing = 1.sp)
            Text("FILMS", style = MaterialTheme.typography.labelMedium, color = Palette.accent, letterSpacing = 6.sp)
        }
    }
}

@Composable
private fun RailLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.muted,
        modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun RailItem(label: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, brand: Int? = null, onClick: () -> Unit) {
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
            if (brand != null) Image(painterResource(brand), contentDescription = null, modifier = Modifier.size(24.dp)) else TvIcon(icon)
            Spacer(Modifier.width(14.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
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
                    when (n.source) { NoticeSource.PHONE -> "FROM YOUR PHONE"; NoticeSource.MAIL -> "NEW MAIL"; NoticeSource.SYSTEM -> "WOODRUFF FILMS" },
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

