package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.cloud.Library

/**
 * Which show an episode is, with one answer however it was started: from its title page, which
 * names it from TMDB ("Backyard Science S02E05 The Volcano"), or by its file's name from Continue
 * watching, its folder, the next season, the phone or an older Woodruff on another TV ("Backyard
 * Science (2019) - S02E06 - The Lava"). Skip intro and the audio and subtitle choices ([ShowPrefs]),
 * the "Next episode" card, Up next on Home and Continue watching's one tile per show all ask it.
 *
 * The library entry an episode is known to sit under comes first ([shows]: media key to entry id, as
 * [com.woodruff.tvexplorer.cloud.EpisodeShows] keeps it): a remake and its original stay apart, and a
 * show whose files are called something else than its folder stays one show. Else its show's name
 * without its year or punctuation ([Library.showMatchKey]), which every way of naming it shares.
 */
object ShowKeys {
    /**
     * "backyard science": the show an episode's title names, whatever its year or punctuation. Null
     * for a film, a clip, or an episode whose name doesn't say its show ("S01E13_Pilot").
     */
    fun name(title: String): String? =
        if (ShowPrefs.isEpisode(title) && ShowPrefs.namesShow(title)) Library.showMatchKey(title).ifBlank { null } else null

    /**
     * The key [ShowPrefs] keeps a show's choices under: "show:<entry id>" when the entry [key] sits
     * under is known, or is known for another episode of that name ([named]); else the show's
     * [name]. A film keeps its title's own key, as before (every way of starting one gives its file's
     * name), and so does an episode whose name doesn't say its show and whose entry isn't known.
     */
    fun of(key: String?, title: String, shows: Map<String, String>, named: Map<String, String> = emptyMap()): String {
        val id = key?.let { shows[it] }
        val name = name(title)
        return when {
            name != null -> (id ?: named[name])?.let { "show:$it" } ?: name
            ShowPrefs.namesShow(title) -> ShowPrefs.showKey(title)
            else -> id?.let { "show:$it" } ?: ShowPrefs.showKey(title)
        }
    }

    /** Whether two items are episodes of one show: their entries decide when both are known, else their shows' names. */
    fun same(aKey: String?, aTitle: String, bKey: String?, bTitle: String, shows: Map<String, String>): Boolean {
        val a = aKey?.let { shows[it] }
        val b = bKey?.let { shows[it] }
        if (a != null && b != null) return a == b
        val aName = name(aTitle)
        val bName = name(bTitle)
        if (aName != null && bName != null) return aName == bName
        return of(aKey, aTitle, shows) == of(bKey, bTitle, shows)
    }

    /**
     * Each show name among [items] to the entry its episodes there are known to sit under, when it's
     * just one: an episode whose entry isn't known yet (saved by another TV, or off a USB drive) is
     * then still that show's. A name two entries share (a remake and its original) is left out.
     */
    fun named(items: List<RecentItem>, shows: Map<String, String>): Map<String, String> = named(items, items.map { name(it.title) }, shows)

    /** [named], with each item's [name] worked out already ([names], in [items]' order). */
    private fun named(items: List<RecentItem>, names: List<String?>, shows: Map<String, String>): Map<String, String> {
        val found = HashMap<String, String?>()
        items.forEachIndexed { i, r ->
            val id = shows[r.key] ?: return@forEachIndexed
            val name = names[i] ?: return@forEachIndexed
            found[name] = if (found.containsKey(name) && found[name] != id) null else id
        }
        return found.mapNotNull { (name, id) -> id?.let { name to it } }.toMap()
    }

    /**
     * The show each of [items] belongs to, for Continue watching's one tile per show: an episode's
     * entry ([of]'s, or one named like it in [items]), else its show's name. A film, a clip, or an
     * episode nothing says the show of is its own.
     */
    fun groups(items: List<RecentItem>, shows: Map<String, String>): List<String> {
        val names = items.map { name(it.title) }
        val named = named(items, names, shows)
        return items.mapIndexed { i, r ->
            val name = names[i]
            val id = shows[r.key] ?: name?.let { named[it] }
            when {
                !ShowPrefs.isEpisode(r.title) -> r.key
                id != null -> "show:$id"
                name != null -> "name:$name"
                else -> r.key
            }
        }
    }

    /**
     * Keys this show's choices may still be kept under, for [ShowPrefs.carryOver]: its name (where a
     * TV that didn't know its entry kept them), then the key its title had before this release (with
     * the year: "backyard science (2019)"), then the keys of the titles its episodes in [recent] were
     * saved with. [show] itself ([of]'s key) is left out.
     */
    fun older(key: String?, title: String, show: String, recent: List<RecentItem>, shows: Map<String, String>): List<String> {
        val id = key?.let { shows[it] }
        val name = name(title)
        val mine = recent.filter { r -> if (id != null) shows[r.key] == id else name != null && name(r.title) == name }
        return (listOfNotNull(name, ShowPrefs.showKey(title)) + mine.map { ShowPrefs.showKey(it.title) })
            .distinct().filter { it.isNotBlank() && it != show }
    }
}
