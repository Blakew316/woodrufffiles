package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.components.ConfirmDialog
import com.woodruff.tvexplorer.update.Updater
import com.woodruff.tvexplorer.util.Opener

/** Installs the downloaded update, first asking for "Install unknown apps" if it isn't allowed yet. */
fun startUpdate(context: Context, vm: MainViewModel) {
    if (!Opener.canInstallPackages(context)) {
        Opener.openInstallPermissionSettings(context)
        vm.say("Allow Woodruff Family to install apps, then choose Install again")
        return
    }
    Updater.install(context)
}

/** What Settings shows next to the version. */
fun updateStatus(s: Updater.State): String = when (s.step) {
    Updater.Step.IDLE -> "Check for updates"
    Updater.Step.CHECKING -> "Checking…"
    Updater.Step.UP_TO_DATE -> s.message ?: "Up to date"
    Updater.Step.DOWNLOADING -> "Downloading ${(s.progress * 100).toInt()}%"
    Updater.Step.READY -> "${s.latestName} ready · Install" + (s.message?.let { " (last try: $it)" } ?: "")
    Updater.Step.INSTALLING -> "Installing…"
    Updater.Step.ERROR -> s.message ?: "Couldn't check"
}

/**
 * When an update is ready but needs a tap (older Android, or the first update after installing
 * from Downloader), offer it once per version when Woodruff is open. Silent updates need no dialog:
 * they install the next time Woodruff isn't on screen.
 */
@Composable
fun UpdatePrompt(vm: MainViewModel) {
    val context = LocalContext.current
    val s by Updater.state.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf<Int?>(null) }
    var autoTried by remember { mutableStateOf<Int?>(null) }
    if (s.step != Updater.Step.READY || s.latest == null || s.latest == dismissed || Updater.canInstallSilently(context)) return
    // Allowed to install: go straight to Android's own "Update" screen (one press), no dialog of ours.
    // Only once per version; if that didn't go through, the dialog below offers it again with the reason.
    if (Opener.canInstallPackages(context) && autoTried != s.latest) {
        LaunchedEffect(s.latest) { autoTried = s.latest; Updater.install(context, manual = false) }
        return
    }
    ConfirmDialog(
        "Update available",
        "Woodruff Family ${s.latestName} is ready. Installing takes a few seconds, then Woodruff opens again." +
            (s.message?.let { "\n\nLast try: $it" } ?: ""),
        "Install",
        onConfirm = { startUpdate(context, vm) },
        onDismiss = { dismissed = s.latest },
    )
}
