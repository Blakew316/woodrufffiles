package com.woodruff.tvexplorer.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.OpenResult
import com.woodruff.tvexplorer.util.Opener
import com.woodruff.tvexplorer.util.Permissions

/**
 * Shown until storage access is granted. [onRecheck] runs when the user comes back from Settings
 * (the activity re-checks on resume too). If no settings screen exists, explains the adb route.
 */
@Composable
fun PermissionScreen(onRecheck: () -> Unit) {
    val context = LocalContext.current
    var noSettingsScreen by remember { mutableStateOf(false) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onRecheck() }
    val button = remember { FocusRequester() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TvIcon(Icons.Rounded.FolderOpen, size = 72.dp, tint = Palette.text)
            Text("Allow access to your files", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (Build.VERSION.SDK_INT >= 30)
                    "Woodruff Family needs \"All files access\" to browse, copy and manage files on this TV and on USB drives. " +
                        "Turn it on in the next screen, then press Back."
                else "Woodruff Family needs storage permission to browse and manage your files.",
                color = Palette.muted,
            )
            Button(
                modifier = Modifier.focusRequester(button),
                onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val r = Opener.firstThatStarts(context, Permissions.requestAllFilesIntents(context), "")
                        noSettingsScreen = r is OpenResult.Failed
                    } else legacy.launch(Permissions.legacyPermissions)
                },
            ) { Text("Grant access") }
            if (noSettingsScreen) {
                Text(
                    "This TV doesn't have a settings screen for this permission. From a computer on the same network, " +
                        "enable ADB debugging on the TV and run:",
                    color = Palette.muted,
                )
                Text(Permissions.adbHint(context), color = Palette.accent)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { button.requestFocus() } }
}
