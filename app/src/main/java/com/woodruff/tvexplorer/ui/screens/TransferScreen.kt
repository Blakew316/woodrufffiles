package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.theme.Palette
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
                Text(if (state.error != null) "Not connected" else "Starting…", color = Color.Black)
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
            } else {
                state.error?.let { Text(it, color = Palette.danger) }
                ToolButton(Sym.PlayArrow, "Turn on phone connection", start)
            }
        }
    }
}
