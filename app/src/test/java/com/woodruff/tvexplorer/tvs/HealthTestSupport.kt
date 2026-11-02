package com.woodruff.tvexplorer.tvs

import com.woodruff.tvexplorer.nest.DoorbellListening
import com.woodruff.tvexplorer.nest.doorbellAlerts
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A fixed place and time for health report tests: Sunday, Nov 2 2026, 3:00 PM in Chicago, 12-hour clock. */
internal object HealthTest {
    val zone: TimeZone = TimeZone.getTimeZone("America/Chicago")
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    /** [month] 1-based. */
    fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    val now: Long = at(2026, 11, 2, 15, 0)

    fun clock(now: Long = this.now): HealthClock {
        val time = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }
        val date = SimpleDateFormat("MMM d", Locale.US).apply { timeZone = zone }
        return HealthClock(now, zone, { time.format(java.util.Date(it)) }, { date.format(java.util.Date(it)) })
    }

    /**
     * The doorbell row of a TV with the family's subscription ([subscription]), its cameras connected or not
     * ([cameras]), hearing it from [from] (the TV that passed a ring on, if any) and its listener doing [listening].
     */
    fun bell(subscription: Boolean = true, cameras: Boolean = true, from: String? = null, listening: DoorbellListening = DoorbellListening.Own) =
        doorbellFact(doorbellAlerts(subscription = subscription, camerasHere = cameras, relaysOut = false, relayFrom = from, listening = listening))

    /** A TV where everything is fine: every sign-in made, up to date, no crash, plenty of room, every key saved. */
    fun facts(
        name: String = "Den",
        signIns: Map<HealthGrant, SignInFact> = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) },
        doorbell: DoorbellFact = bell(),
        update: UpdateFact = UpdateFact("upToDate", latestName = "2.0.171"),
        installAllowed: Boolean? = true,
        popUpsAllowed: Boolean? = true,
        crash: CrashFact? = null,
        crashesClearedAt: Long = 0,
        exits: List<AppExit>? = emptyList(),
        freeBytes: Long? = 12_400L * 1024 * 1024 * 1024 / 1000,
        keys: KeysFact = KeysFact(googleClientId = true, googleClientSecret = true, camerasProject = true, tmdb = true),
        sdk: Int = 31,
    ) = HealthFacts(
        id = "den-id", name = name, version = "2.0.171", sdk = sdk, uptimeMs = 2 * DAY,
        signIns = signIns, doorbell = doorbell, update = update,
        installed = InstallFact("Installed fresh Oct 12, 9:10 AM · updated Nov 1, 3:02 AM by Woodruff itself", "Woodruff itself", at(2026, 10, 12, 9, 10), at(2026, 11, 1, 3, 2)),
        installAllowed = installAllowed, popUpsAllowed = popUpsAllowed, crash = crash, crashesClearedAt = crashesClearedAt, exits = exits, freeBytes = freeBytes, keys = keys,
    )

    fun report(f: HealthFacts = facts(), now: Long = this.now) = buildHealth(f, clock(now))

    const val CRASH = "Version 2.0.171 · Nov 1, 9:41 PM · thread main\n" +
        "java.lang.IllegalStateException: The player was released\n" +
        "\tat androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:2861)\n" +
        "\tat com.woodruff.tvexplorer.player.PlayerActivity.onStop(PlayerActivity.kt:410)\n" +
        "\tat android.app.Activity.performStop(Activity.java:8501)\n"
}
