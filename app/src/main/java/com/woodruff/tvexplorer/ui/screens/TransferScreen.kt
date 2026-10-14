package com.woodruff.tvexplorer.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.woodruff.tvexplorer.util.Qr

/** Starts the LAN server and shows how to reach it from a phone or computer. */
@Composable
fun TransferScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by TransferService.state.collectAsStateWithLifecycle()
    // The ongoing notification needs this on Android 13+; the server works either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TransferService.start(context)
    }
    val start = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else TransferService.start(context)
    }

    BackHandler { vm.show(Screen.HOME) }

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Send files to this TV", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Use any phone or computer on the same Wi-Fi. On an iPhone, the upload button can pick straight from " +
                    "iCloud Drive and your Photo Library. Uploads go to the folder you choose, including USB drives.",
                color = Palette.muted,
            )
            if (state.running) {
                ToolButton(Icons.Filled.Stop, "Stop server", { TransferService.stop(context) })
                Text("1. Scan the QR code with your phone, or open this address in a browser:", color = Palette.muted)
                Text(state.url.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Palette.accent)
                Text(
                    "The address includes a private key, so only people who can see this screen can connect. " +
                        "Stopping the server invalidates it.",
                    color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                state.log.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            } else {
                ToolButton(Icons.Filled.PlayArrow, "Start server", start)
                state.error?.let { Text(it, color = Palette.danger) }
            }
        }
        val url = state.url
        if (state.running && url != null) {
            val qr = remember(url) { Qr.bitmap(url).asImageBitmap() }
            Image(
                qr, contentDescription = "QR code for $url",
                modifier = Modifier.size(320.dp).clip(RoundedCornerShape(16.dp)).background(Color.White),
            )
        }
    }
}
