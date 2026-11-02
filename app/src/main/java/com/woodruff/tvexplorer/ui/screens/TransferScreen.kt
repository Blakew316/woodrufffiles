package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.FocusButton
import com.woodruff.tvexplorer.ui.components.OfflineWords
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.rememberOfflineShown
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.util.OpenResult
import com.woodruff.tvexplorer.util.Opener
import com.woodruff.tvexplorer.util.rememberQrBitmap

/**
 * Pair a phone: the QR code opens the companion web app (files, send-to-TV, settings). The
 * pairing key is persistent, so a phone that scanned once stays connected.
 */
@Composable
fun TransferScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by TransferService.state.collectAsStateWithLifecycle()
    // The ongoing notification needs this on Android 13+; the connection works either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TransferService.start(context)
    }
    val start = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else TransferService.start(context)
    }
    LaunchedEffect(Unit) { if (!state.running) TransferService.start(context) }
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler { openSidebar() }
    // Waiting for the home network: said only after the grace every offline state shares
    // (offline-states.md §3.0), since most wake-ups find the Wi-Fi again within a few seconds.
    val offline = rememberOfflineShown(state.waiting)

    // QR first at a fixed size, text gets the rest: TVs give apps only ~960x540dp.
    Row(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(250.dp).clip(RoundedCornerShape(20.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            val url = state.url
            if (state.running && url != null) {
                // Made off the main thread; the white card shows for the moment it takes.
                rememberQrBitmap(url, 640)?.let { Image(it, contentDescription = "QR code to connect your phone", modifier = Modifier.fillMaxSize()) }
            } else {
                Text(if (offline || state.error != null) "No code yet" else "Starting…", color = Color.Black)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Text("Connect your phone", style = MaterialTheme.typography.headlineLarge)
            if (state.running) {
                Text(state.url.orEmpty(), color = Palette.accent, style = MaterialTheme.typography.bodyMedium)
                // Tailscale on the TV: a second link that works away from home.
                var away by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(state.url) {
                    while (true) { away = withContext(Dispatchers.IO) { TransferService.awayUrl() }; delay(10_000) }
                }
                away?.let { url ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Box(Modifier.size(110.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                            rememberQrBitmap(url, 330)?.let { Image(it, contentDescription = "QR code for away from home", modifier = Modifier.fillMaxSize()) }
                        }
                        Column {
                            Text("Away from home (Tailscale)", style = MaterialTheme.typography.titleSmall)
                            Text(url, color = Palette.accent, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.log.take(4).forEach { Text("· $it", color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            } else if (state.waiting) {
                if (offline) WaitingForNetwork {
                    val r = Opener.firstThatStarts(
                        context,
                        listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
                        "",
                    )
                    if (r is OpenResult.Failed) vm.say("Open the TV’s own settings to connect it to Wi-Fi")
                }
            } else if (!state.on) {
                // Off: Android didn't let it start, or it was stopped from its notification.
                state.error?.let { Text(it, color = Palette.danger) }
                ToolButton(Sym.PlayArrow, "Turn on phone connection", start)
            }
            // (Starting: the card says so, and there's nothing to press.)
        }
    }
}

/**
 * The phone connection is on, waiting for this TV to be on the home network (just after it turned on
 * or woke, the router restarting): calm and muted, never red, as offline is everywhere. The code shows
 * up by itself the moment there's a network; the TV's own network settings are one press away.
 */
@Composable
private fun WaitingForNetwork(onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvIcon(Sym.CloudOff, size = 18.dp, tint = Palette.muted)
            Text(OfflineWords.OFFLINE, color = Palette.muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "The code appears here by itself once this TV is on Wi-Fi or Ethernet.",
            color = Palette.muted, style = MaterialTheme.typography.bodyMedium,
        )
    }
    // The glass pill of the app's themed screens (RoundButton's look, without its gap for a row).
    FocusButton(
        onClick = onSettings,
        shape = Shapes.pill,
        color = Palette.surfaceHigh, focusedColor = Palette.text,
        content = Palette.text, focusedContent = Palette.background,
        modifier = Modifier.padding(top = 14.dp),
        focusedScale = 1.08f,
    ) { tint ->
        Row(Modifier.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            TintIcon(Sym.Settings, tint.color, size = 22.dp)
            RowText("Network settings", tint.color, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        // The lit rim, focused or not.
        Spacer(Modifier.matchParentSize().border(1.dp, Palette.rim, Shapes.pill))
    }
}
