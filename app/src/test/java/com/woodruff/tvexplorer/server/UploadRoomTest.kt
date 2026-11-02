package com.woodruff.tvexplorer.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.data.StorageRoot
import com.woodruff.tvexplorer.photos.FamilyMemories
import com.woodruff.tvexplorer.tvs.TvLinks
import fi.iki.elonen.NanoHTTPD.Method
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * Big iPhone videos (finding 44, step 1): the TV says how much room a file from the phone has where it goes (less what
 * it keeps for itself on its own storage), and turns down one that won't fit, in plain words, before writing a byte.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UploadRoomTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val mb = 1024L * 1024
    private val dir = File(ctx.cacheDir, "usb-drive").apply { mkdirs() }
    private val token = "abcdefghjkmn"
    private val huge = 1L shl 50   // a petabyte: more than any test machine has free

    @After fun clean() { dir.deleteRecursively() }

    private fun server(removable: Boolean) = TransferServer(0, token, "<html></html>",
        { listOf(StorageRoot(if (removable) "USB drive" else "Internal storage", dir, removable, 0, 0)) }, {}, homeName = { "Living Room" })

    @Test fun theWordsSayHowBigItIsAndWhatsFree() {
        Locale.setDefault(Locale.US)
        assertEquals("This video is 2.1\u00A0GB; Living Room has 800\u00A0MB free.", UploadRoom.words("IMG_0042.MOV", 2150 * mb, 800 * mb, "Living Room"))
        assertEquals("This photo is 3.2\u00A0MB; Den has 120\u00A0MB free.", UploadRoom.words("IMG_0043.HEIC", (3.2 * mb).toLong(), 120 * mb, "Den"))
        assertEquals("This file is 12.0\u00A0MB; USB drive has 1.5\u00A0MB free.", UploadRoom.words("notes.zip", 12 * mb, (1.5 * mb).toLong(), "USB drive"))
        assertEquals("The TV is full.", UploadRoom.words("IMG_0042.MOV", 2150 * mb, 0, "the TV"))
    }

    @Test fun onlyWhatWontFitIsTurnedDown() {
        UploadRoom.check("a.mov", 100 * mb, 100 * mb, "Living Room")
        UploadRoom.check("a.mov", null, 1, "Living Room")       // no size said: the TV can't tell
        UploadRoom.check("a.mov", huge, null, "Living Room")    // storage doesn't say
        val e = assertThrows(NoRoom::class.java) { UploadRoom.check("a.mov", 100 * mb + 1, 100 * mb, "Living Room") }
        assertTrue(e.message!!.startsWith("This video is 100\u00A0MB; Living Room has 100\u00A0MB free"))
    }

    @Test fun theTvKeepsSomeOfItsOwnStorageForItself() {
        val free = dir.usableSpace
        assertEquals(free, UploadRoom.of(dir, onTv = false))
        assertEquals((free - UploadRoom.KEEP).coerceAtLeast(0), UploadRoom.of(dir, onTv = true))
        // A folder that isn't there yet: the room where it would be.
        assertEquals(free, UploadRoom.of(File(dir, "not/yet"), onTv = false))
    }

    @Test fun photosAndFilesSaysTheRoomAndTurnsDownWhatWontFit() {
        val s = server(removable = true)
        val roots = JSONArray(s.serve(phoneRequest("/api/roots", params = mapOf("k" to token))).bodyText())
        assertTrue(roots.getJSONObject(0).getBoolean("removable"))
        assertEquals(dir.usableSpace, roots.getJSONObject(0).getLong("free"))
        val list = JSONObject(s.serve(phoneRequest("/api/list", params = mapOf("k" to token, "path" to dir.path))).bodyText())
        assertEquals(dir.usableSpace, list.getLong("free"))

        val r = s.serve(phoneRequest("/api/upload", Method.POST, params = mapOf("k" to token, "path" to dir.path, "name" to "Big video.mov"), length = huge))
        assertEquals(507, r.status.requestStatus)
        val words = r.bodyText()
        assertTrue(words, words.startsWith("This video is 1024\u00A0TB; USB drive has ") && words.endsWith(" free."))
        assertTrue(r.isCloseConnection)
        assertTrue("nothing written", dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun onTheTvsOwnStorageItsNamed() {
        val r = server(removable = false).serve(phoneRequest("/api/upload", Method.POST, params = mapOf("k" to token, "path" to dir.path, "name" to "Big video.mov"), length = huge))
        assertEquals(507, r.status.requestStatus)
        val words = r.bodyText()
        assertTrue(words, words.contains("; Living Room has "))
    }

    @Test fun familyMemoriesSaysTheRoomAndTurnsDownWhatWontFit() {
        val routes = CompanionRoutes(ctx)
        val listing = JSONObject(routes.answerFor(phoneRequest("/api/memories")).bodyText())
        assertTrue(listing.has("free"))
        val s = TransferServer(0, token, "<html></html>", { emptyList() }, {}, extra = routes::handle)
        val r = s.serve(phoneRequest("/api/memories/add", Method.POST, params = mapOf("k" to token, "name" to "IMG_0042.MOV"), length = huge))
        assertEquals(507, r.status.requestStatus)
        val words = r.bodyText()
        assertTrue(words, words.startsWith("This video is 1024\u00A0TB; ${TvLinks.name(ctx)} has "))
        assertFalse("no empty file left behind", File(FamilyMemories.videos, "IMG_0042.MOV").exists())
        assertNull(FamilyMemories.videos.listFiles()?.firstOrNull { it.name.startsWith("IMG_0042") })
    }
}
