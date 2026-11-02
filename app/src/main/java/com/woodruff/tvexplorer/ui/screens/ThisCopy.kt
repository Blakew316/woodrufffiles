package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.woodruff.tvexplorer.update.Updater
import java.util.Date

// Settings → Updates → This copy: how this copy of Woodruff came to be (a fresh install, after an
// uninstall, is what erases every sign-in). The row says when it last changed in a few words that fit
// its value column; the panel beside it tells the whole story. Dates and times are written as the TV
// writes them: a TV set to 24-hour time gets 24-hour times.

/**
 * The row's value: "Updated Nov 2" once this copy has been updated, "Fresh install Oct 13" when it
 * hasn't since it was installed; null when Android didn't say. [day] writes a short date as the TV does
 * ([tvDay]).
 */
internal fun thisCopyValue(facts: Updater.InstallFacts, day: (Long) -> String): String? = when {
    facts.firstInstall <= 0 -> null
    facts.lastUpdate > facts.firstInstall + UPDATE_GAP_MS -> "Updated ${day(facts.lastUpdate)}"
    else -> "Fresh install ${day(facts.firstInstall)}"
}

/**
 * The whole story, for the row's panel: "Installed fresh Oct 13 at 3:12 AM · updated Nov 2 at 3:12 AM by
 * Woodruff itself", and on a line of its own when the TV's key store lost the sign-ins' key. [at] writes a
 * date and time as the TV does ([tvDayTime]).
 */
internal fun thisCopyStory(facts: Updater.InstallFacts, at: (Long) -> String): String {
    val how = when {
        facts.firstInstall <= 0 -> "Installed by ${facts.installedBy}"
        facts.lastUpdate > facts.firstInstall + UPDATE_GAP_MS ->
            "Installed fresh ${at(facts.firstInstall)} · updated ${at(facts.lastUpdate)} by ${facts.installedBy}"
        else -> "Installed fresh ${at(facts.firstInstall)} by ${facts.installedBy}"
    }
    val lost = facts.signInsLostAt?.let { "Sign-ins lost ${at(it)}: the TV’s key store lost their key" }
    return listOfNotNull(how, lost).joinToString("\n")
}

/** An update counts when Android's last-update time is over a minute after the install, as [Updater.InstallFacts.summary] reads it. */
private const val UPDATE_GAP_MS = 60_000L

/** "Nov 2" as this TV writes a short date ("2 Nov" in some languages), with its year when it isn't this year's. */
internal fun tvDay(c: Context, ms: Long): String =
    DateUtils.formatDateTime(c, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

/** "Nov 2 at 3:12 AM", or "Nov 2 at 15:12" on a TV set to 24-hour time. */
internal fun tvDayTime(c: Context, ms: Long): String = "${tvDay(c, ms)} at ${DateFormat.getTimeFormat(c).format(Date(ms))}"
