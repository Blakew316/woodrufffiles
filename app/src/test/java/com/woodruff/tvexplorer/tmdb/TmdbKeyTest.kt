package com.woodruff.tvexplorer.tmdb

import android.util.JsonReader
import android.util.JsonWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.StringReader
import java.io.StringWriter

/**
 * The TMDB key changes only when it really changes (the same key entered again, or sent back by a
 * linked TV, isn't a change), and what TMDB answered is kept, read and written as a stream.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TmdbKeyTest {
    @Test fun onlyADifferentKeyIsAChange() {
        assertFalse(Tmdb.keyChanged("abc123", "abc123"))
        assertFalse(Tmdb.keyChanged(null, null))
        assertTrue(Tmdb.keyChanged(null, "abc123"))
        assertTrue(Tmdb.keyChanged("abc123", null))
        assertTrue(Tmdb.keyChanged("abc123", "eyJhbGciOi.new"))
    }

    @Test fun aKeyBeingEnteredIsJudgedByTmdbsAnswer() {
        // TMDB answered with the key: it works.
        assertEquals(Tmdb.KeyCheck.ACCEPTED, Tmdb.keyCheckOf(200))
        // TMDB turned it down: not saved, and the person is told.
        assertEquals(Tmdb.KeyCheck.REFUSED, Tmdb.keyCheckOf(401))
        // Busy, failing or anything else: saved, and checked as posters next load.
        for (code in listOf(429, 500, 502, 503, 404, 403)) assertEquals("$code", Tmdb.KeyCheck.UNCHECKED, Tmdb.keyCheckOf(code))
    }

    @Test fun anEmptyKeyIsRefusedWithoutAsking() = kotlinx.coroutines.runBlocking {
        assertEquals(Tmdb.KeyCheck.REFUSED, Tmdb.check("   "))
    }

    @Test fun whatThePersonIsTold() {
        assertEquals("TMDB didn’t accept that key. Copy the API Key or the Read Access Token again from themoviedb.org → Settings → API.", Tmdb.REFUSED_WORDS)
        assertEquals("Saved. TMDB couldn’t be reached just now, so the key will be checked when posters next load.", Tmdb.UNCHECKED_WORDS)
    }

    @Test fun theCacheReadsBackAsItWasSaved() {
        val hit = Tmdb.Hit("https://image.tmdb.org/t/p/w342/p.jpg", 2024, listOf("Drama"), 7.1, "A \"quiet\" night.\nAnd a / slash.", null, 42, tv = false)
        val entries = mapOf("m:Midnight Harbor:2024" to hit.toJson(), "t:Nothing Here:null" to "", "s:Backyard Science:null:2" to "{\"p\":\"x\"}")
        val out = StringWriter()
        JsonWriter(out).use { Tmdb.writeCache(it, entries) }
        val read = HashMap<String, String>()
        JsonReader(StringReader(out.toString())).use { Tmdb.readCache(it, read) }
        assertEquals(entries, read)
        assertEquals(hit, Tmdb.Hit.fromJson(read.getValue("m:Midnight Harbor:2024")))
    }

    @Test fun aCacheSavedByTheVersionBeforeStillReads() {
        // Earlier versions wrote it with org.json (which escapes slashes).
        val old = JSONObject(mapOf("m:Orbit:2021" to "{\"p\":\"https://image.tmdb.org/t/p/w342/o.jpg\",\"y\":2021}", "t:Nobody:null" to "")).toString()
        val read = HashMap<String, String>()
        JsonReader(StringReader(old)).use { Tmdb.readCache(it, read) }
        assertEquals(setOf("m:Orbit:2021", "t:Nobody:null"), read.keys)
        assertEquals("", read["t:Nobody:null"])
        assertEquals("https://image.tmdb.org/t/p/w342/o.jpg", Tmdb.Hit.fromJson(read.getValue("m:Orbit:2021"))?.poster)
    }

    @Test fun somethingThatIsntAnAnswerIsLeftOut() {
        val read = HashMap<String, String>()
        JsonReader(StringReader("{\"a\": \"1\", \"b\": 2, \"c\": {\"x\": 1}, \"d\": \"\"}")).use { Tmdb.readCache(it, read) }
        assertEquals(mapOf("a" to "1", "d" to ""), read)
    }
}
