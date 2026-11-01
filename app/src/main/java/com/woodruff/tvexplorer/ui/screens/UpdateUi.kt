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

/**
 * Installs the downloaded update. Android's installer has the last word: some TVs (a Sony did)
 * answer "not allowed" when asked whether Woodruff may install apps, while their Settings show it
 * allowed, so that answer no longer stops the update. The installer asks for the permission itself
 * when it really needs it, and says why when it refuses (shown as "last try" in Settings → Updates).
 * Only a profile that forbids installing apps from outside the Play Store stops it here, saying so.
 */
fun startUpdate(context: Context, vm: MainViewModel) {
    if (installsBlockedByProfile(context)) {
        vm.say("This TV's profile doesn't allow installing apps from outside the Play Store (a kids profile, parental controls or a work setup). Switch to the main profile, then choose Install again")
        return
    }
    if (!Opener.canInstallPackages(context)) vm.say("Installing… If the TV asks, allow Woodruff Family to install apps")
    Updater.install(context)
}

/** A user restriction on this profile against apps from outside the Play Store, which no toggle in Settings lifts. */
private fun installsBlockedByProfile(context: Context): Boolean {
    val um = context.getSystemService(android.os.UserManager::class.java) ?: return false
    return runCatching {
        um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            (android.os.Build.VERSION.SDK_INT >= 29 && um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
    }.getOrDefault(false)
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
