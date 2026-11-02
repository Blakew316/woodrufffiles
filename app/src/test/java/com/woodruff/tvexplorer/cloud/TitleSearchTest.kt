package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSearchTest {
    private fun film(id: String, title: String, year: Int? = null, rating: Double? = null) =
        DriveFile(id, "$title.mkv", "video/x-matroska", 0, 0, null, info = MediaInfo(title, year, rating = rating))

    private fun show(id: String, title: String, year: Int? = null, rating: Double? = null) =
        DriveFile(id, title, DriveFile.FOLDER_MIME, 0, 0, null, info = MediaInfo(title, year, rating = rating, tv = true))

    private val lastLight = film("m1", "The Last Light", 2023, 7.1)
    private val harbor = film("m2", "Midnight Harbor", 2024, 7.8)
    private val neon = film("m3", "Neon Alley", 2024, 6.9)
    private val amelie = film("m4", "Amélie", 2001, 7.9)
    private val lighthouse = show("t1", "The Lighthouse", 2019, 7.9)
    private val starlight = show("t2", "Starlight Kitchen", 2020, 6.2)
    private val libraries = mapOf(
        DrivePlace.Movies to listOf(lastLight, harbor, neon, amelie),
        DrivePlace.TvShows to listOf(lighthouse, starlight),
    )

    private fun ids(found: TitleSearch.Found) = found.hits.map { it.file.id }

    @Test fun bothLibrariesInOneRankedList() {
        // "li": The Lighthouse and The Last Light have a word starting with it (the better rated first),
        // Amélie and Starlight Kitchen only have it somewhere.
        val found = TitleSearch.find("li", libraries)
        assertEquals(listOf("t1", "m1", "m4", "t2"), ids(found))
        assertEquals(DrivePlace.TvShows, found.hits.first().place)
        assertEquals(4, found.total)
        assertFalse(found.capped)
    }

    @Test fun aTitleStartingWithWhatsTypedComesFirstArticlesIgnored() {
        assertEquals(1, TitleSearch.rank(Library.queryWords("last li"), lastLight.info!!))
        assertEquals(1, TitleSearch.rank(Library.queryWords("the last"), lastLight.info!!))
        assertEquals(1, TitleSearch.rank(Library.queryWords("the"), lastLight.info!!))
        assertEquals(2, TitleSearch.rank(Library.queryWords("harb"), harbor.info!!))
        assertEquals(2, TitleSearch.rank(Library.queryWords("light"), lastLight.info!!))
        assertEquals(3, TitleSearch.rank(Library.queryWords("ight"), lastLight.info!!))
        // Starts with it (an article ignored), then a word starting with it, then anywhere; the rating
        // only orders titles that answer equally well.
        val keepers = film("k1", "Lighthouse Keepers", rating = 5.0)
        val under = film("k2", "Under the Light", rating = 9.0)
        val star = show("k3", "Starlight Kitchen", rating = 9.5)
        val found = TitleSearch.find("light", mapOf(DrivePlace.Movies to listOf(star, under, keepers), DrivePlace.TvShows to listOf(lighthouse)))
        assertEquals(listOf("t1", "k1", "k2", "k3"), ids(found))
    }

    @Test fun accentsCaseAndWordOrderDontMatter() {
        assertEquals(listOf("m4"), ids(TitleSearch.find("AMELIE", libraries)))
        assertEquals(listOf("m2"), ids(TitleSearch.find("harbor midnight", libraries)))
        assertTrue(TitleSearch.find("titanic", libraries).hits.isEmpty())
        assertTrue(TitleSearch.find("   ", libraries).hits.isEmpty())
    }

    @Test fun aFourDigitWordAlsoFindsTheReleaseYear() {
        // Neither title has 2024 in its name; both came out in 2024. The better rated first.
        assertEquals(listOf("m2", "m3"), ids(TitleSearch.find("2024", libraries)))
        // A year with words: the words rank it as they would alone.
        assertEquals(listOf("m2"), ids(TitleSearch.find("harbor 2024", libraries)))
        assertTrue(TitleSearch.find("harbor 2023", libraries).hits.isEmpty())
        // Only four digits make a year: "202" is just letters in a title, and none has them.
        assertTrue(TitleSearch.find("202", libraries).hits.isEmpty())
        // A year in the title itself still matches as words do.
        val olympics = film("m9", "2024 Summer Games", 2025)
        assertEquals(listOf("m9"), ids(TitleSearch.find("2024", mapOf(DrivePlace.Movies to listOf(olympics)))))
    }

    @Test fun theLibrarySearchWasOpenedFromWinsATie() {
        val a = film("ma", "Copper Valley", rating = 7.0)
        val b = show("tb", "Copper Ridge", rating = 7.0)
        val both = mapOf(DrivePlace.Movies to listOf(a), DrivePlace.TvShows to listOf(b))
        // Equal rank and rating: A–Z ("ridge" before "valley")…
        assertEquals(listOf("tb", "ma"), ids(TitleSearch.find("copper", both)))
        // …unless Search was opened from Movies' search field.
        assertEquals(listOf("ma", "tb"), ids(TitleSearch.find("copper", both, prefer = DrivePlace.Movies)))
        // The library only breaks ties: "The Lighthouse" starts with "the li", "The Last Light" only has
        // the words somewhere, so the show comes first even from Movies.
        val pair = mapOf(DrivePlace.Movies to listOf(lastLight), DrivePlace.TvShows to listOf(lighthouse))
        assertEquals(listOf("t1", "m1"), ids(TitleSearch.find("the li", pair, prefer = DrivePlace.Movies)))
    }

    @Test fun atMostAHundredAndItSaysThereAreMore() {
        val many = (1..140).map { film("f$it", "Copper Valley $it") }
        val found = TitleSearch.find("copper", mapOf(DrivePlace.Movies to many))
        assertEquals(100, found.hits.size)
        assertEquals(140, found.total)
        assertTrue(found.capped)
        assertEquals("100+ results", TitleSearch.countWords(found))
    }

    @Test fun theCountAtTheFieldsEnd() {
        assertNull(TitleSearch.countWords(TitleSearch.Found.NONE))
        assertEquals("1 result", TitleSearch.countWords(TitleSearch.find("amelie", libraries)))
        assertEquals("2 results", TitleSearch.countWords(TitleSearch.find("2024", libraries)))
    }

    @Test fun onlyFilmsAndShowsNeverOtherFiles() {
        val subtitles = DriveFile("s1", "The Last Light.srt", "application/x-subrip", 0, 0, null)
        val poster = DriveFile("p1", "The Last Light.jpg", "image/jpeg", 0, 0, null)
        val found = TitleSearch.find("last light", mapOf(DrivePlace.Movies to listOf(subtitles, poster, lastLight)))
        assertEquals(listOf("m1"), ids(found))
    }

    @Test fun aTitleInBothLibrariesShowsOnce() {
        val found = TitleSearch.find("harbor", mapOf(DrivePlace.Movies to listOf(harbor), DrivePlace.TvShows to listOf(harbor)))
        assertEquals(listOf("m2"), ids(found))
        assertEquals(DrivePlace.Movies, found.hits.single().place)
    }

    @Test fun streamingsFilterUsesTheSameYearMatch() {
        val files = listOf(lastLight, harbor, neon, amelie)
        assertEquals(listOf("Midnight Harbor", "Neon Alley"), Library.apply(files, LibraryFilter(query = "2024")).map { Library.info(it).title })
        assertTrue(Library.matches(Library.queryWords(""), amelie.info!!))
        assertFalse(Library.matches(Library.queryWords("2001 harbor"), amelie.info!!))
        assertTrue(Library.matches(Library.queryWords("2001 amel"), amelie.info!!))
    }
}
