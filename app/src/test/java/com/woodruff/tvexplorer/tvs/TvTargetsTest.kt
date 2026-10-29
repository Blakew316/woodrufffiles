package com.woodruff.tvexplorer.tvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which TVs an announcement or a Shortcut message goes to ("tvs"). */
class TvTargetsTest {
    private val kids = LinkedTv("kids-id", "Kids Room", "http://192.168.1.21:8080", "key1")
    private val bedroom = LinkedTv("bed-id", "Bedroom", "http://192.168.1.22:8080", "key2")
    private val linked = listOf(kids, bedroom)

    private fun pick(vararg asked: String, default: TvTargets = TvTargets.HERE) =
        pickTargets(asked.toList(), default, "me-id", "Living Room", linked)

    @Test fun nothingAskedKeepsTheDefault() {
        assertEquals(TvTargets.ALL, pickTargets(null, TvTargets.ALL, "me-id", "Living Room", linked))
        assertEquals(TvTargets.NONE, pickTargets(emptyList(), TvTargets.NONE, "me-id", "Living Room", linked))
        assertEquals(TvTargets.HERE, pick(" ", ""))
    }

    @Test fun allMeansEveryTv() {
        assertEquals(TvTargets.ALL, pick("all"))
        assertEquals(TvTargets.ALL, pick("ALL", default = TvTargets.NONE))
        assertEquals(TvTargets.ALL, pick("this", "all"))
    }

    @Test fun thisMeansOnlyThisTv() {
        assertEquals(TvTargets(true, emptySet()), pick("this", default = TvTargets.ALL))
        // Its own id or name counts too.
        assertEquals(TvTargets(true, emptySet()), pick("me-id"))
        assertEquals(TvTargets(true, emptySet()), pick("living room"))
    }

    @Test fun linkedTvsByIdOrName() {
        assertEquals(TvTargets(false, setOf("kids-id")), pick("kids-id"))
        assertEquals(TvTargets(false, setOf("kids-id", "bed-id")), pick(" kids room ", "BEDROOM"))
        assertEquals(TvTargets(true, setOf("bed-id")), pick("this", "Bedroom"))
    }

    @Test fun unknownTvsAreLeftOut() {
        assertEquals(TvTargets(false, setOf("bed-id")), pick("Garage", "Bedroom"))
        // Naming only TVs there aren't is a mistake to report, not a silent nothing.
        assertNull(pick("Garage"))
    }
}
