package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.FocusButton
import com.woodruff.tvexplorer.ui.components.FocusWhenReady
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.update.Updater
import com.woodruff.tvexplorer.util.Opener

/**
 * Installs the downloaded update. Android's installer has the last word: some TVs (a Sony did)
 * answer "not allowed" when asked whether Woodruff may install apps, while their Settings show it
 * allowed, so that answer no longer stops the update. The installer asks for the permission itself
 * when it really needs it, and says why when it refuses (shown as "last try" in Settings → Updates).
 * Only a profile that forbids installing apps from outside the Play Store stops it here, saying so.
 */
fun startUpdate(context: Context, vm: MainViewModel, how: Updater.How = Updater.How.CHOSEN) {
    if (installsBlockedByProfile(context)) {
        vm.say("This TV's profile doesn't allow installing apps from outside the Play Store (a kids profile, parental controls or a work setup). Switch to the main profile, then choose Install again")
        return
    }
    if (!Opener.canInstallPackages(context) && !Updater.promptWorked(context)) vm.say("Installing… If the TV asks, allow Woodruff Family to install apps")
    Updater.install(context, how)
}

/** A user restriction on this profile against apps from outside the Play Store, which no toggle in Settings lifts. */
private fun installsBlockedByProfile(context: Context): Boolean {
    val um = context.getSystemService(android.os.UserManager::class.java) ?: return false
    return runCatching {
        um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            (android.os.Build.VERSION.SDK_INT >= 29 && um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
    }.getOrDefault(false)
}

/**
 * What Settings shows next to the version, and on its first page by Updates: for a day after an
 * update, when it was ([updated], from [updatedLately]: no notice says so, this is where anyone
 * curious looks), unless there's news since (a check, a download, an update waiting); otherwise
 * [updateStatus].
 */
fun versionStatus(s: Updater.State, updated: String?): String =
    if (updated != null && (s.step == Updater.Step.IDLE || (s.step == Updater.Step.UP_TO_DATE && s.message == null))) updated
    else updateStatus(s)

/**
 * "Updated today at 3:12 AM" ("yesterday at…" on day two) for a day after this copy of Woodruff was
 * updated ([lastUpdate], as Android keeps it); null after that, or when it was installed fresh rather
 * than updated ([firstInstall]). [time] writes the time as the TV does; [short]: just "Updated
 * today", for Settings' first page. Visible for tests.
 */
internal fun updatedLately(
    firstInstall: Long, lastUpdate: Long, now: Long, time: java.text.DateFormat, short: Boolean = false,
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String? {
    if (lastUpdate <= firstInstall + 60_000 || now < lastUpdate || now - lastUpdate >= 24 * 3_600_000L) return null
    fun day(ms: Long) = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val word = if (day(lastUpdate) == day(now)) "today" else "yesterday"
    if (short) return "Updated $word"
    val clock = (time.clone() as java.text.DateFormat).apply { timeZone = zone }
    return "Updated $word at ${clock.format(java.util.Date(lastUpdate))}"
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
 * An update that needs a press (Android 11 and older ask for every one, and so do some newer TVs),
 * offered as Woodruff comes on screen: never in the middle of using it, never while nobody's there
 * ([Updater.offer]). Where Android's own "Update" screen is known to work, it opens straight away:
 * one press, nothing of ours in front of it. Otherwise a calm card says what's ready, with Install
 * already focused and Later beside it; Later holds the offer back for a while, and Settings →
 * Updates installs it any time. Silent updates need neither: they install the next time nobody's
 * using Woodruff.
 */
@Composable
fun UpdatePrompt(vm: MainViewModel) {
    val context = LocalContext.current
    val offer by Updater.offer.collectAsStateWithLifecycle()
    val s by Updater.state.collectAsStateWithLifecycle()
    val build = offer ?: return
    // Offered for the build that's ready (a newer download meanwhile replaces it).
    if (s.step != Updater.Step.READY || s.latest != build) return
    if (Opener.canInstallPackages(context) || Updater.promptWorked(context)) {
        LaunchedEffect(build) { Updater.offered(); Updater.install(context, Updater.How.OFFERED) }
        return
    }
    UpdateOffer(
        name = s.latestName ?: "",
        lastTry = s.message,
        onInstall = { Updater.offered(); startUpdate(context, vm, Updater.How.OFFERED) },
        onLater = { Updater.later(context, build) },
    )
}

/**
 * The offer card (frosted dialog glass): what's ready, why it asks, and two glass pills that turn solid
 * white with focus, Install first and already focused, so one press installs. Back is Later.
 */
@Composable
internal fun UpdateOffer(name: String, lastTry: String?, onInstall: () -> Unit, onLater: () -> Unit) {
    val install = remember { FocusRequester() }
    DialogFrame("Woodruff $name is ready", onLater) {
        Text(offerBody(lastTry), color = Palette.muted, style = MaterialTheme.typography.bodyLarge)
        ButtonRow {
            OfferButton("Install", onInstall, Modifier.focusRequester(install))
            OfferButton("Later", onLater)
        }
    }
    FocusWhenReady(install)
}

/** A dialog pill: glass with the lit rim at rest, solid white with ink words when focused. */
@Composable
private fun OfferButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusButton(
        onClick = onClick,
        shape = Shapes.pill,
        color = Palette.surfaceHigh, focusedColor = Palette.text,
        content = Palette.text, focusedContent = Palette.background,
        modifier = modifier,
    ) { tint ->
        RowText(
            label, tint.color, Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
            style = MaterialTheme.typography.titleSmall, maxLines = 1,
        )
        // The lit rim, focused or not.
        Spacer(Modifier.matchParentSize().border(1.dp, Palette.rim, Shapes.pill))
    }
}

/** The offer's words, with the last try's reason when there was one. Visible for tests. */
internal fun offerBody(lastTry: String?): String =
    "This TV asks before each update. Installing takes a few seconds, then Woodruff opens again." +
        (lastTry?.let { "\n\nLast try: $it." } ?: "")
