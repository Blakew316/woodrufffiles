package com.woodruff.tvexplorer.player

/**
 * A title split the way tvOS shows what's playing: a small line above for the show and episode
 * ("Backyard Science · S2, E5") and the episode's own name big under it ("The
 * Volcano"). Films and home videos just keep their title, with nothing above it.
 */
internal data class EpisodeLabel(val eyebrow: String?, val title: String) {
    companion object {
        // Show name, then S02E05 / S2 E5 / 2x05 ("_" may touch it: "S01E13_Pilot"), then the episode's name (if any).
        private val PATTERN = Regex("""(?i)^(.*?)[\s._\-·:]*(?<![a-z0-9])(?:s(\d{1,2})\s*[ex]\s*(\d{1,3})|(\d{1,2})x(\d{2}))(?![0-9])[\s._\-·:]*(.*)$""")
        private val TIDY = Regex("""[._]+""")
        private val SPACES = Regex("""\s+""")

        fun of(title: String): EpisodeLabel {
            val m = PATTERN.find(title.trim()) ?: return EpisodeLabel(null, title.trim())
            val g = m.groupValues
            val season = (g[2].ifEmpty { g[4] }).toIntOrNull() ?: return EpisodeLabel(null, title.trim())
            val episode = (g[3].ifEmpty { g[5] }).toIntOrNull() ?: return EpisodeLabel(null, title.trim())
            fun tidy(s: String) = s.replace(TIDY, " ").replace(SPACES, " ").trim(' ', '-', '·', ':')
            val show = tidy(g[1])
            val name = tidy(g[6])
            return if (name.isNotEmpty()) {
                EpisodeLabel(listOf(show, "S$season, E$episode").filter { it.isNotEmpty() }.joinToString(" · "), name)
            } else {
                EpisodeLabel(listOf(show, "Season $season").filter { it.isNotEmpty() }.joinToString(" · "), "Episode $episode")
            }
        }
    }
}
