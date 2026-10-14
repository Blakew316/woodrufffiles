package com.woodruff.tvexplorer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.woodruff.tvexplorer.ui.screens.TransferScreen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.ThemeSetting
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
    val homeFocus = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().background(Palette.backdrop)) {
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
            RailItem("Home", Icons.Filled.Home, screen == Screen.HOME, Modifier.focusRequester(homeFocus)) { vm.show(Screen.HOME) }
            RailItem("Google Drive", Icons.Filled.AddToDrive, screen == Screen.DRIVE) { vm.show(Screen.DRIVE) }
            RailLabel("This TV")
            roots.forEach { root ->
                val active = screen == Screen.BROWSER &&
                    browser.folder?.absolutePath?.startsWith(root.dir.absolutePath) == true
                RailItem(root.title, if (root.removable) Icons.Filled.SdStorage else Icons.Filled.Storage, active) {
                    vm.openRoot(root.dir)
                }
            }
            RailLabel("Tools")
            RailItem("Apps", Icons.Filled.Apps, screen == Screen.APPS) { vm.show(Screen.APPS) }
            RailItem(if (server.running) "Send files · On" else "Send files", Icons.Filled.Wifi, screen == Screen.TRANSFER) {
                vm.show(Screen.TRANSFER)
            }
            RailLabel("Display")
            val context = LocalContext.current
            RailItem(
                if (Palette.light) "Dark mode" else "Light mode",
                if (Palette.light) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                active = false,
            ) { ThemeSetting.toggle(context) }
        }
        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = screen, label = "screen") { s ->
                when (s) {
                    Screen.HOME -> HomeScreen(vm, roots)
                    Screen.BROWSER -> BrowserScreen(vm)
                    Screen.DRIVE -> DriveScreen(vm, drive)
                    Screen.APPS -> AppsScreen(vm)
                    Screen.TRANSFER -> TransferScreen(vm)
                }
            }
            MessageBanner(message, vm::dismissMessage, Modifier.align(Alignment.BottomCenter))
            MessageBanner(driveState.message, drive::dismissMessage, Modifier.align(Alignment.BottomCenter))
        }
    }

    operation?.let { ProgressDialog(it.title, it.progress, vm::cancelOperation) }
    driveState.operation?.let { ProgressDialog(it.title, it.progress, drive::cancelOperation) }
    LaunchedEffect(Unit) { runCatching { homeFocus.requestFocus() } }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.accent),
            contentAlignment = Alignment.Center,
        ) { TvIcon(Icons.Filled.PlayArrow, size = 30.dp, tint = Palette.onAccent) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Woodruff", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Palette.text)
            Text("SHOWS", style = MaterialTheme.typography.labelMedium, color = Palette.accent, letterSpacing = 3.sp)
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
private fun RailItem(label: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
            TvIcon(icon)
            Spacer(Modifier.width(14.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
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

