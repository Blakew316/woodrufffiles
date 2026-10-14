package com.woodruff.tvexplorer.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.components.ProgressDialog
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.screens.AppsScreen
import com.woodruff.tvexplorer.ui.screens.BrowserScreen
import com.woodruff.tvexplorer.ui.screens.HomeScreen
import com.woodruff.tvexplorer.ui.screens.TransferScreen
import com.woodruff.tvexplorer.ui.theme.Palette
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember

/** Main window: a side rail of destinations and the active screen, plus global overlays. */
@Composable
fun AppRoot(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val browser by vm.browser.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val server by TransferService.state.collectAsStateWithLifecycle()
    val homeFocus = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().background(Palette.background)) {
        Column(
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(Palette.surface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("TV Explorer", style = MaterialTheme.typography.titleLarge, color = Palette.accent, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
            RailItem("Home", Icons.Filled.Home, screen == Screen.HOME, Modifier.focusRequester(homeFocus)) { vm.show(Screen.HOME) }
            roots.forEach { root ->
                val active = screen == Screen.BROWSER &&
                    browser.folder?.absolutePath?.startsWith(root.dir.absolutePath) == true
                RailItem(root.title, if (root.removable) Icons.Filled.SdStorage else Icons.Filled.Storage, active) {
                    vm.openRoot(root.dir)
                }
            }
            RailItem("Apps", Icons.Filled.Apps, screen == Screen.APPS) { vm.show(Screen.APPS) }
            RailItem(if (server.running) "Transfer (on)" else "Wireless transfer", Icons.Filled.Wifi, screen == Screen.TRANSFER) {
                vm.show(Screen.TRANSFER)
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm, roots)
                Screen.BROWSER -> BrowserScreen(vm)
                Screen.APPS -> AppsScreen(vm)
                Screen.TRANSFER -> TransferScreen(vm)
            }
            MessageBanner(message, vm::dismissMessage, Modifier.align(Alignment.BottomCenter))
        }
    }

    operation?.let { ProgressDialog(it.title, it.progress, vm::cancelOperation) }
    LaunchedEffect(Unit) { runCatching { homeFocus.requestFocus() } }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Palette.surfaceHigh else Palette.surface,
            contentColor = if (active) Palette.accent else Palette.text,
            focusedContainerColor = Palette.accent,
            focusedContentColor = Palette.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon)
            Spacer(Modifier.width(14.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Toast-like status line that hides itself after a few seconds. */
@Composable
private fun MessageBanner(message: String?, onTimeout: () -> Unit, modifier: Modifier) {
    LaunchedEffect(message) {
        if (message != null) { delay(4000); onTimeout() }
    }
    AnimatedVisibility(visible = message != null, modifier = modifier.padding(bottom = 32.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = Palette.surfaceHigh, contentColor = Palette.text),
        ) {
            Text(message.orEmpty(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}

