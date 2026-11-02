package com.woodruff.tvexplorer.player

import android.content.Context
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.Stamped

/**
 * Per-show player choices, remembered across episodes: audio and subtitle language, and where the
 * intro ends (for "Skip intro"). A show is its title without the episode part, so
 * "The Office S02E03" and "The Office - S2 E4" are the same show.
 */
object ShowPrefs {
    private const val PREFS = "player_shows"
    /** Subtitles were turned off for this show. */
    const val OFF = "off"

    fun showKey(title: String): String =
        title.replace(EXTENSION, "") // file extension
            .replace(SEPARATORS, " ")
            .replace(EPISODE_PART, "")
            .replace(SPACES, " ").trim().lowercase().ifBlank { title.lowercase() }

    /** Whether [title] names its show ("Show S01E02"), not just the episode ("S01E013_Pilot"). */
    fun namesShow(title: String): Boolean =
        title.replace(EXTENSION, "").replace(SEPARATORS, " ").replace(EPISODE_PART, "").isNotBlank()

    // Compiled once: the library matches every show against what's been watched with these, and
    // Continue watching tells every title it lists apart with them.
    private val EXTENSION = Regex("""\.[A-Za-z0-9]{2,4}$""")
    private val SEPARATORS = Regex("""[._]+""")
    private val EPISODE_PART = Regex("""(?i)[\s\-]*(s\d{1,2}\s*[ex]\s*\d{1,3}|\d{1,2}x\d{2}|season\s*\d+|episode\s*\d+).*$""")
    private val SPACES = Regex("""\s+""")
    private val EPISODE = Regex("""(?i)(^|[^a-z0-9])(s\d{1,2}\s*[ex]\s*\d{1,3}|\d{1,2}x\d{2}|episode\s*\d+|ep\s*\d+)""")

    /** "Show S01E02", "Show 1x02", "Episode 4": a TV episode rather than a movie. */
    fun isEpisode(title: String): Boolean = EPISODE.containsMatchIn(title)

    /** A language tag ("en", "eng", "en-US") that is English. */
    fun isEnglish(lang: String?): Boolean = lang?.lowercase()?.let { it == "en" || it == "eng" || it.startsWith("en-") || it.startsWith("en_") } == true

    /** A known language other than English ("und", "mis" and missing tags don't count: unknown). */
    fun isForeign(lang: String?): Boolean {
        val l = lang?.lowercase()?.trim().orEmpty()
        return l.isNotEmpty() && l !in setOf("und", "mis", "mul", "zxx", "unknown") && !isEnglish(l)
    }

    // Each choice is kept with when it was made ("@" + its key), so the family's other TVs take the
    // newest one ([FamilySync]). One from before that has no time and counts as the oldest.
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun audio(c: Context, show: String): String? = p(c).getString("a:$show", null)
    fun text(c: Context, show: String): String? = p(c).getString("t:$show", null)
    fun setLanguages(c: Context, show: String, audio: String?, text: String?) {
        if (audio == null && text == null) return
        val at = System.currentTimeMillis()
        synchronized(this) {
            p(c).edit().apply {
                if (audio != null) putString("a:$show", audio).putLong("@a:$show", at)
                if (text != null) putString("t:$show", text).putLong("@t:$show", at)
            }.apply()
        }
        FamilySync.changed(c)
    }

    fun introEnd(c: Context, show: String): Long = p(c).getLong("i:$show", 0L)
    fun setIntroEnd(c: Context, show: String, ms: Long) {
        val at = System.currentTimeMillis()
        synchronized(this) {
            // Taken off, its time stays: the other TVs take theirs off too.
            p(c).edit().apply { if (ms > 0) putLong("i:$show", ms) else remove("i:$show"); putLong("@i:$show", at) }.apply()
        }
        FamilySync.changed(c)
    }

    /** A key this keeps per show: "i:" skip-intro mark, "a:" audio or "t:" subtitle language. */
    internal fun isChoice(key: String): Boolean = key.startsWith("i:") || key.startsWith("a:") || key.startsWith("t:")

    // The shows carried over in this run of the app: each is looked at once.
    private val carried = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Gives [show] ([ShowKeys.of]'s key) the choices kept for it under [older] keys
     * ([ShowKeys.older]): an older Woodruff kept them under its title's own key ("backyard science
     * (2019)"), and a TV that didn't know the show's library entry under its name. So does a key whose
     * show is called [name], if only one show is (not when a remake and its original, or a film of the
     * same name, both are). Each kind (Skip intro, audio, subtitles) only while [show] has none of its
     * own: the newest one, with when it was made, so the family's other TVs still take whichever is
     * newest. Once per show in a run of the app.
     */
    fun carryOver(c: Context, show: String, older: List<String>, name: String?) {
        if (show.isBlank() || show in carried) return
        synchronized(this) {
            val all = p(c).all
            val edit = p(c).edit()
            var changed = false
            // Looked for only when the show is missing a kind.
            val byName by lazy { name?.let { n -> byName(all.keys, n, setOf(show) + older) } }
            for (kind in listOf("i:", "a:", "t:")) {
                if ("$kind$show" in all || "@$kind$show" in all) continue
                val kept = older.filter { "$kind$it" in all || "@$kind$it" in all } + listOfNotNull(byName?.takeIf { "$kind$it" in all })
                // The newest; the likeliest first when they're as old (an older Woodruff's, from before choices had a time).
                val from = kept.maxByOrNull { all["@$kind$it"] as? Long ?: 0L } ?: continue
                val value = all["$kind$from"]
                val at = all["@$kind$from"] as? Long ?: 0L
                when (value) {
                    is Long -> edit.putLong("$kind$show", value)
                    is String -> edit.putString("$kind$show", value)
                    // Taken off there last: off here too.
                    else -> {}
                }
                edit.putLong("@$kind$show", at)
                changed = true
            }
            if (changed) edit.apply()
            carried += show
        }
    }

    /** The one show whose kept choices' keys name [name] ("backyard science (2019)" for "backyard science"), [known] aside; null for none, or more than one. */
    private fun byName(keys: Set<String>, name: String, known: Set<String>): String? =
        keys.asSequence().filter { isChoice(it) }.map { it.substring(2) }
            .filter { it !in known && !it.startsWith("show:") && com.woodruff.tvexplorer.cloud.Library.showMatchKey(it) == name }
            .toSet().singleOrNull()

    /** For tests: carries over again, as a new run of the app would. */
    internal fun forgetCarried() = carried.clear()

    /** For [FamilySync]: every choice with when it was made; one taken off has no value. */
    internal fun stamps(c: Context): Map<String, Stamped> {
        val all = p(c).all
        val out = HashMap<String, Stamped>()
        for ((k, v) in all) {
            if (k.startsWith("@")) {
                val key = k.substring(1)
                if (v is Long && isChoice(key) && key !in all) out[key] = Stamped(null, v)
            } else if (isChoice(k)) {
                val value = when (v) {
                    is Long -> v.toString()
                    is String -> v
                    else -> null
                } ?: continue
                out[k] = Stamped(value, all["@$k"] as? Long ?: 0L)
            }
        }
        return out
    }

    /**
     * For [FamilySync]: [merge] turns the choices into the merged ones, in one locked step. Not
     * sent on again (they came from the other TVs). True when anything changed.
     */
    internal fun syncWith(c: Context, merge: (Map<String, Stamped>) -> Map<String, Stamped>): Boolean = synchronized(this) {
        val before = stamps(c)
        val edit = p(c).edit()
        var changed = false
        for ((k, s) in merge(before)) {
            if (before[k] == s || !isChoice(k)) continue
            val value = s.value
            when {
                value == null -> edit.remove(k)
                // Kept as a number, as setIntroEnd keeps it.
                k.startsWith("i:") -> edit.putLong(k, value.toLongOrNull() ?: continue)
                else -> edit.putString(k, value)
            }
            edit.putLong("@$k", s.at)
            changed = true
        }
        if (changed) edit.apply()
        changed
    }
}
