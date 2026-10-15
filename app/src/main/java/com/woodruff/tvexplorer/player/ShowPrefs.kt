package com.woodruff.tvexplorer.player

import android.content.Context

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
        title.replace(Regex("""\.[A-Za-z0-9]{2,4}$"""), "") // file extension
            .replace(Regex("""[._]+"""), " ")
            .replace(Regex("""(?i)[\s\-]*(s\d{1,2}\s*[ex]\s*\d{1,3}|\d{1,2}x\d{2}|season\s*\d+|episode\s*\d+).*$"""), "")
            .replace(Regex("""\s+"""), " ").trim().lowercase().ifBlank { title.lowercase() }

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun audio(c: Context, show: String): String? = p(c).getString("a:$show", null)
    fun text(c: Context, show: String): String? = p(c).getString("t:$show", null)
    fun setLanguages(c: Context, show: String, audio: String?, text: String?) {
        p(c).edit().apply {
            if (audio != null) putString("a:$show", audio)
            if (text != null) putString("t:$show", text)
        }.apply()
    }

    fun introEnd(c: Context, show: String): Long = p(c).getLong("i:$show", 0L)
    fun setIntroEnd(c: Context, show: String, ms: Long) {
        p(c).edit().apply { if (ms > 0) putLong("i:$show", ms) else remove("i:$show") }.apply()
    }
}
