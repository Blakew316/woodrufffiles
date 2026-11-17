package com.woodruff.tvexplorer.nowplaying

import android.os.SystemClock

/**
 * What the Apple TVs plugged into this TV say about their own screens. Woodruff on an Apple TV tells this TV
 * (POST /api/appletv, with the pairing key as the family's phones and Macs do, or passed on by the hub with this
 * TV's link key) every 10 s or so while it's the app on the Apple TV's screen, each time for the next
 * [DEFAULT_SECONDS] or so, and that it isn't once it leaves (for the background, or the Apple TV's screen saver).
 * Each Apple TV's word is its own ("from", its id: one's "off" never cancels another's "on"), and each one's newest
 * counts. What it said lasts that long and no longer: an Apple TV switched off, or off the network, stops counting
 * by itself. Timed on the TV's steady clock (elapsedRealtime), which standby doesn't stop and the time of day
 * doesn't move.
 *
 * An Apple TV can't see which input this TV is on, and goes on saying so for as long as a film plays in it, whatever
 * this TV shows meanwhile. So as its word turns from off to on, and for its first [SETTLE_MS] (while the TV comes to
 * the Apple TV's input), this TV notes what it shows itself ([Word]): the input it's on, if Android names one here,
 * and whatever its media sessions showed from before. What it shows later is weighed against that ([showsAppleTv]).
 */
object AppleTvScreen {
    /** The shortest and the longest one word from an Apple TV lasts, and how long one that doesn't say lasts. */
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60
    const val DEFAULT_SECONDS = 25

    /**
     * How long after a word turns on this TV goes on noting what it shows: the TV coming to the Apple TV's input
     * (HDMI-CEC switches it within a few seconds of the Apple TV waking; someone with the TV's remote, a little later).
     */
    const val SETTLE_MS = 30_000L

    /** At most this many of the sessions seen as a word began are kept ([Word.before]). */
    private const val MAX_BEFORE = 16
    /** At most this many Apple TVs' words are kept, and each one's id to this length. */
    private const val MAX_SENDERS = 8
    private const val MAX_ID = 100

    /**
     * One Apple TV's run of words: from when its word turned from off to on ([since]) until the last of them runs
     * out ([until]), on the steady clock, with what this TV showed as it began.
     */
    internal data class Word(
        val since: Long,
        val until: Long,
        /**
         * The input this TV showed playing as the word began (the last it showed within [SETTLE_MS]): the Apple TV's,
         * as Android names it here ("HDMI 2"). Null when it showed none: Android then names nothing for the Apple TV's
         * input on this TV (as on the Sonys), and an input playing later is another one, unless [onApp].
         */
        val input: Playing? = null,
        /**
         * The TV showed an app of its own playing instead, all along (it hadn't come to the Apple TV yet): which input
         * is the Apple TV's is still to be seen, and the first one playing is taken for it.
         */
        val onApp: Boolean = false,
        /** Every media session this TV showed as the word began (within [SETTLE_MS]): what was there from before. */
        val before: List<Playing> = emptyList(),
    )

    /** Each Apple TV's word, by its id ("" for one that doesn't say), the newest last. */
    private val words = LinkedHashMap<String, Word>()

    /**
     * The input this TV has shown named for an Apple TV ("Apple TV HDMI 4"), once it has (in this run of Woodruff):
     * an input playing that isn't it isn't the Apple TV's ([showsAppleTv]).
     */
    @Volatile internal var namedInput: Playing? = null
        private set

    /**
     * An Apple TV ([from]: its id; blank for one that doesn't say) said Woodruff is on its screen ([on]) for the next
     * [forSeconds] (kept within 5 to 60), or that it isn't, at [now]. [seen]: what this TV's media sessions show now,
     * the most recent first (null when they can't be seen: no Notification access); asked as its word turns on and
     * for its first [SETTLE_MS], and after that while which input is the Apple TV's is still to be seen.
     */
    @Synchronized
    fun said(on: Boolean, forSeconds: Int = DEFAULT_SECONDS, now: Long = SystemClock.elapsedRealtime(), from: String = "", seen: () -> List<Playing>? = { null }) {
        val who = from.trim().take(MAX_ID)
        val last = words.remove(who)?.takeIf { live(it, now) }
        words.values.removeAll { !live(it, now) }
        if (!on) return
        val until = now + forSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS) * 1000L
        words[who] = when {
            last == null || now - last.since < SETTLE_MS -> noted(last, now, until, seen())
            last.input == null && last.onApp -> learned(last, until, seen())
            else -> last.copy(until = until)
        }
        while (words.size > MAX_SENDERS) words.remove(words.keys.first())
    }

    /** The Apple TVs' words that hold at [now]: none when no Apple TV says it's on its screen. */
    @Synchronized
    internal fun words(now: Long = SystemClock.elapsedRealtime()): List<Word> = words.values.filter { live(it, now) }

    /** Whether Woodruff is on an Apple TV's screen at [now]: what each Apple TV last said, for as long as it said. */
    fun onScreen(now: Long = SystemClock.elapsedRealtime()): Boolean = words(now).isNotEmpty()

    /** Every word forgotten, and the input named for an Apple TV with them (tests). */
    @Synchronized
    internal fun forget() {
        words.clear()
        namedInput = null
    }

    private fun live(w: Word, now: Long) = now >= w.since && now < w.until

    /** [shows] (what this TV's media sessions show) names an input for an Apple TV: that one is known from now on. */
    internal fun noteNamed(shows: List<Playing>) {
        shows.firstOrNull(::namedForAppleTv)?.let { namedInput = it }
    }

    /**
     * The run of words [last] (null: it begins now, at [now]) lasting until [until], with what this TV shows now
     * ([shows]; null: it can't be seen). What plays decides the input: a playing input is the Apple TV's, an app of the
     * TV's own playing means the TV isn't on the Apple TV's input yet. With nothing playing, what was noted stands.
     */
    internal fun noted(last: Word?, now: Long, until: Long, shows: List<Playing>?): Word {
        val since = last?.since ?: now
        if (shows == null) return last?.copy(until = until) ?: Word(since, until)
        noteNamed(shows)
        val shown = shownOf(shows)
        val before = (last?.before.orEmpty() + shows).distinctBy { Triple(it.title.lowercase(), it.app.lowercase(), it.positionMs / 1_000) }
        val kept = Word(since, until, last?.input, last?.onApp == true, before.takeLast(MAX_BEFORE))
        return when {
            shown == null || !shown.playing -> kept
            isInput(shown) -> kept.copy(input = shown, onApp = false)
            else -> kept.copy(input = null, onApp = true)
        }
    }

    /** [last], on an app as it began, lasting until [until]: the first input this TV shows playing ([shows]) is the Apple TV's. */
    internal fun learned(last: Word, until: Long, shows: List<Playing>?): Word {
        shows?.let(::noteNamed)
        val shown = shows?.let(::shownOf)?.takeIf { it.playing && isInput(it) }
        return if (shown != null) last.copy(until = until, input = shown, onApp = false) else last.copy(until = until)
    }
}
