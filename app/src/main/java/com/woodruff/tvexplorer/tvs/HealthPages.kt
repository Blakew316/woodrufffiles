package com.woodruff.tvexplorer.tvs

// How a health report is laid out for whoever shows it: the TV list's statuses, Settings' first
// row, and every row of a TV's report page (health-tv.md §3.2–3.5). Plain logic over [HealthReport]
// and how the hub sees each TV ([TvHealthView]); Settings maps the rows to its own, and the tests
// read them to check the TV and the phone carry the same words.

// ---- How the hub sees a TV, and the rows the TV shows (health-tv.md §3.3–3.5) ------------------

/** Where a TV's health stands for whoever shows it. */
enum class TvHealthKind(val json: String) {
    /** Asked, no answer yet. */
    CHECKING("checking"),
    GOOD("good"),
    ATTENTION("attention"),
    /** Not reachable (its link isn't refused): off or asleep. */
    OFF("off"),
    /** It answers but turns down this TV's link key (finding 55): Link again. */
    REFUSED("refused"),
    /** Found on the network, but the report request timed out. */
    NO_ANSWER("noAnswer"),
    /** It answers but has no health report (an older Woodruff). */
    OLD("old");

    /** A TV the first page counts as needing attention (one that's off doesn't). */
    val needsAttention: Boolean get() = this == ATTENTION || this == REFUSED

    companion object {
        fun of(json: String?): TvHealthKind? = entries.firstOrNull { it.json == json }
    }
}

/**
 * One TV as a health page shows it: [kind], the report ([report]: the current one, or the last
 * known for a TV that's off, refuses its link or didn't answer), when it arrived ([checkedAt]),
 * when the TV last answered ([seen]) and, for an older Woodruff, its [version].
 */
data class TvHealthView(
    val name: String,
    val isThis: Boolean,
    val kind: TvHealthKind,
    val report: HealthReport? = null,
    val checkedAt: Long? = null,
    val seen: Long? = null,
    val version: String? = null,
)

/** The symbol a row shows, kept apart from the TV's symbol set so this stays plain logic. */
enum class RowIcon { CHECK_CIRCLE, ALERT_CIRCLE, TV, LINK_OFF, LINK, UPDATE, DRIVE, PHOTOS, CALENDAR, CAMERAS, DOORBELL, INFO, INSTALL, POWER, STORAGE, KEY, REFRESH, ACTIVITY }

/** What OK does on a row. */
enum class RowAction { NONE, SIGN_IN, KEYS, VERSION, CHECK, RELINK, UNLINK }

/** One row of a TV's report page: what both Settings and the tests read. */
data class HealthRow(
    val key: String,
    val icon: RowIcon,
    val title: String,
    val value: String?,
    val attention: Boolean = false,
    val about: String? = null,
    val detail: String? = null,
    val spaced: Boolean = false,
    val action: RowAction = RowAction.NONE,
    val grant: HealthGrant? = null,
    val danger: Boolean = false,
)

/** "Den answered but turned down this TV’s link…": the about text of a TV whose link stopped working. */
fun refusedAbout(tv: String): String =
    "$tv answered but turned down this TV’s link. That happens after $tv is reset or reinstalled, or after “Stop other TVs seeing this one” on $tv. " +
        "To link it again: on $tv, open Settings → TVs & phones → Other TVs → Link this TV to another, then choose Link again here and type the code it shows."

/** A TV's status in the TV list (§3.1): "All good", "Needs attention", "Off · seen 2 h ago", "Link again"… */
fun listStatus(v: TvHealthView, clock: HealthClock): String = when (v.kind) {
    TvHealthKind.CHECKING -> "Checking…"
    TvHealthKind.GOOD -> "All good"
    TvHealthKind.ATTENTION -> "Needs attention"
    TvHealthKind.OFF -> v.seen?.let { "Off · seen ${clock.ago(it)}" } ?: "Off"
    TvHealthKind.REFUSED -> "Link again"
    TvHealthKind.NO_ANSWER -> "Didn’t answer"
    TvHealthKind.OLD -> "Needs a newer Woodruff"
}

/** The sentence a TV's row says about it on the TV list (and its report's summary): what needs attention, or why there's no report. */
fun aboutTv(v: TvHealthView): String? = when (v.kind) {
    TvHealthKind.CHECKING -> null
    TvHealthKind.GOOD, TvHealthKind.ATTENTION -> v.report?.sentence
    TvHealthKind.OFF -> if (v.report != null) "Off or asleep. These are the last things it reported." else "${v.name} hasn’t reported yet. Turn it on to see how it’s doing."
    TvHealthKind.REFUSED -> refusedAbout(v.name)
    TvHealthKind.NO_ANSWER -> "${v.name} is on the network but didn’t reply. Try Check every TV again."
    TvHealthKind.OLD -> "Health reports start with the next version. ${v.name} updates by itself when nobody’s using it."
}

/**
 * Settings' first row: "All good", "Den needs attention", "2 TVs need attention", "Checking…" (and
 * for this TV alone "Needs attention"). A name too long for "<name> needs attention" within 26
 * characters says "1 TV needs attention" instead, so a sentence is never cut in the middle.
 */
fun healthOverview(thisTv: TvHealthView, linked: List<TvHealthView>): String {
    val all = listOf(thisTv) + linked
    val needing = all.filter { it.kind.needsAttention }
    if (linked.isEmpty()) return when {
        thisTv.kind == TvHealthKind.CHECKING -> "Checking…"
        needing.isNotEmpty() -> "Needs attention"
        else -> "All good"
    }
    return when {
        needing.isEmpty() && all.any { it.kind == TvHealthKind.CHECKING } -> "Checking…"
        needing.isEmpty() -> "All good"
        needing.size == 1 -> "${needing[0].name} needs attention".takeIf { it.length <= 26 } ?: "1 TV needs attention"
        else -> "${needing.size} TVs need attention"
    }
}

/** The summary row's title, value and symbol for [v] (health-tv.md §3.4 and §5). */
fun summaryRow(v: TvHealthView, clock: HealthClock): HealthRow {
    val checked = v.checkedAt?.let { "Checked ${clock.ago(it)}" }
    val n = v.report?.attention?.size ?: 0
    return when (v.kind) {
        TvHealthKind.CHECKING -> HealthRow("summary", RowIcon.TV, "Checking ${v.name}…", null)
        TvHealthKind.GOOD -> HealthRow("summary", RowIcon.CHECK_CIRCLE, "All good", checked,
            about = "Sign-ins, updates and storage are fine, and Woodruff hasn’t closed unexpectedly this week.")
        // The words say it; "Checked 3 min ago" is no status, so it stays muted (amber is for values that need a person).
        TvHealthKind.ATTENTION -> HealthRow("summary", RowIcon.ALERT_CIRCLE, if (n == 1) "1 thing needs attention" else "$n things need attention", checked,
            about = v.report?.sentence)
        TvHealthKind.OFF -> HealthRow("summary", RowIcon.TV, "Off", v.seen?.let { "Last seen ${clock.ago(it)}" }, about = aboutTv(v))
        TvHealthKind.REFUSED -> HealthRow("summary", RowIcon.LINK_OFF, "Its link stopped working", "Link again", attention = true, about = aboutTv(v))
        TvHealthKind.NO_ANSWER -> HealthRow("summary", RowIcon.TV, "Didn’t answer", null, about = aboutTv(v))
        TvHealthKind.OLD -> HealthRow("summary", RowIcon.UPDATE, "Needs a newer Woodruff", v.version, about = aboutTv(v))
    }
}

/**
 * Every row of a TV's report page, in order: the summary, then (once there is a report) its
 * groups, then what can be done. A report shown for a TV that's off, refuses its link or didn't
 * answer is its last one: every value muted, no attention colour. [checking]: a Check for updates
 * under way; [asked]: one was asked for on this page.
 */
fun reportRows(v: TvHealthView, clock: HealthClock, checking: Boolean = false, asked: Boolean = false): List<HealthRow> {
    val rows = mutableListOf(summaryRow(v, clock))
    val r = v.report.takeIf { v.kind != TvHealthKind.CHECKING && v.kind != TvHealthKind.OLD }
    val live = v.kind == TvHealthKind.GOOD || v.kind == TvHealthKind.ATTENTION
    if (r != null) {
        fun on(a: Boolean) = a && live
        // Sign-ins.
        val icons = mapOf(HealthGrant.DRIVE to RowIcon.DRIVE, HealthGrant.PHOTOS to RowIcon.PHOTOS, HealthGrant.CALENDAR to RowIcon.CALENDAR, HealthGrant.CAMERAS to RowIcon.CAMERAS)
        HealthGrant.entries.forEachIndexed { i, g ->
            val s = r.signIns.getValue(g)
            val about = when (s.state) {
                SignInState.EXPIRED -> "Google ended this sign-in. " + if (v.isThis) "OK signs in again." else g.whereOn(v.name)
                SignInState.KEYS_REJECTED -> KEYS_DETAIL
                else -> SIGN_IN_ABOUT
            }
            val action = when {
                !live -> RowAction.NONE
                s.state == SignInState.EXPIRED -> RowAction.SIGN_IN
                s.state == SignInState.KEYS_REJECTED -> RowAction.KEYS
                else -> RowAction.NONE
            }
            rows += HealthRow("signin.${g.json}", icons.getValue(g), g.title, s.text, on(s.attention), about, spaced = i == 0, action = action, grant = g)
        }
        rows += HealthRow("doorbell", RowIcon.DOORBELL, "Doorbell alerts", r.doorbell.text, on(r.doorbell.attention), r.doorbell.detail)
        // Updates.
        val waiting = r.updates.attention
        // Updates install by themselves, unless this TV asks before each one (then each is offered).
        val updatesAbout = listOfNotNull(
            (if (r.updates.asks) asksDetail(v.name, v.isThis) else UPDATES_ABOUT).takeIf { r.updates.auto },
            waitingDetail(v.name).takeIf { waiting },
        ).joinToString(" ").ifEmpty { null }
        rows += HealthRow(
            "version", RowIcon.UPDATE, "Version ${r.version}", r.updates.text, on(waiting), updatesAbout,
            detail = listOfNotNull(r.installed.summary.ifEmpty { null }, r.updates.reason).joinToString("\n").ifEmpty { null },
            spaced = true, action = if (v.isThis) RowAction.VERSION else RowAction.NONE,
        )
        r.lastUpdateTry?.let { rows += HealthRow("lastTry", RowIcon.INFO, "Last update try", it.text, on(true), detail = it.reason) }
        r.installPermission?.let { rows += HealthRow("permission", RowIcon.INSTALL, "Installing updates", it.text, on(it.attention), permissionDetail(v.name)) }
        // Stability.
        val crash = r.lastCrash
        rows += HealthRow("crash", RowIcon.ALERT_CIRCLE, "Last crash", crash?.text ?: "None", on(crash?.attention == true),
            crash?.let { CRASH_ABOUT }, crash?.lines, spaced = true)
        r.closedByAndroid?.let { rows += HealthRow("closed", RowIcon.POWER, "Closed by Android", it.text, on(it.attention), CLOSED_ABOUT, it.detail) }
        // Space and keys.
        var first = true
        r.freeSpace?.let { rows += HealthRow("space", RowIcon.STORAGE, "Free space", it.text, on(it.attention), spaceDetail(v.name), spaced = true); first = false }
        rows += HealthRow("keys", RowIcon.KEY, "Keys", r.keys.text, about = KEYS_ABOUT, spaced = first)
    }
    // What can be done.
    val actions = mutableListOf<HealthRow>()
    if (live) {
        // "Checking…" while it runs, then how it went (the Version row's words).
        val value = when {
            checking -> "Checking…"
            asked -> r?.updates?.text
            else -> null
        }
        actions += HealthRow("check", RowIcon.UPDATE, "Check for updates", value, action = RowAction.CHECK)
    }
    if (v.kind == TvHealthKind.REFUSED) actions += HealthRow("relink", RowIcon.LINK, "Link again", null, about = refusedAbout(v.name), action = RowAction.RELINK)
    if (!v.isThis && v.kind != TvHealthKind.CHECKING) {
        actions += HealthRow("unlink", RowIcon.LINK_OFF, "Unlink this TV", null, about = "${v.name} stops showing on this TV’s Home and in Watch together.", action = RowAction.UNLINK, danger = true)
    }
    actions.forEachIndexed { i, a -> rows += if (i == 0) a.copy(spaced = true) else a }
    return rows
}

/** Up to 7 of a report's rows for a TV's about panel, the ones that need attention first. */
fun insideRows(v: TvHealthView, clock: HealthClock): List<HealthRow> {
    val facts = reportRows(v, clock).filter { it.key != "summary" && it.action != RowAction.CHECK && it.action != RowAction.RELINK && it.action != RowAction.UNLINK }
    return (facts.filter { it.attention } + facts.filterNot { it.attention }).take(7)
}
