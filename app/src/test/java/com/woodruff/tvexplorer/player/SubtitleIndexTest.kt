package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The subtitle files beside Drive's videos, learned from folder listings and kept on the TV, and the
 * ones beside a USB drive's; and the library leaving them out of its titles.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubtitleIndexTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val saved get() = File(context.cacheDir, "subtitles.json")

    @Before fun fresh() {
        SubtitleIndex.resetForTest()
        // Learned and saved at once, in order, for the test to see.
        SubtitleIndex.worker = java.util.concurrent.Executor { it.run() }
        saved.delete()
    }

    @After fun done() = SubtitleIndex.resetForTest()

    private fun video(id: String, name: String) = DriveFile(id, name, "video/x-matroska", 1_000_000, 0, null)
    private fun file(id: String, name: String, mime: String = "application/x-subrip") = DriveFile(id, name, mime, 1_000, 0, null)
    private fun folder(id: String, name: String) = DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null)

    @Test fun aFoldersSubtitlesAreFoundForEachVideo() {
        val season = listOf(
            video("e1", "Show.S01E01.mkv"), file("s1", "Show.S01E01.en.srt"), file("s1f", "Show.S01E01.eng.forced.srt"),
            video("e2", "Show.S01E02.mkv"), file("s2", "Show.S01E02.es.vtt", "text/vtt"),
            video("e3", "Show.S01E03.mkv"),
            file("n", "Show.nfo", "text/plain"), file("x", "._Show.S01E03.en.srt"),
        )
        val found = SubtitleIndex.inFolder(season)
        assertEquals(setOf("drive:e1", "drive:e2", "drive:e3"), found.keys)
        assertEquals(
            listOf(
                Sidecar(DriveApi.mediaUrl("s1"), "application/x-subrip", "en"),
                Sidecar(DriveApi.mediaUrl("s1f"), "application/x-subrip", "en", forced = true),
            ),
            found["drive:e1"],
        )
        assertEquals(listOf(Sidecar(DriveApi.mediaUrl("s2"), "text/vtt", "es")), found["drive:e2"])
        // A Mac's "._" shadow file is no subtitle.
        assertEquals(emptyList<Sidecar>(), found["drive:e3"])
    }

    @Test fun aFilmsOwnFolderGivesItEverySubtitle() {
        val film = listOf(video("f", "Heat.1995.1080p.mkv"), file("a", "English.srt"), file("b", "spa.srt"), folder("subs", "Subs"))
        assertEquals(listOf("en", "es"), SubtitleIndex.inFolder(film)["drive:f"]?.map { it.language })
        // A folder of several films (or with other folders in it, like a library's top) isn't any one film's.
        val library = listOf(video("f", "Heat.1995.1080p.mkv"), file("a", "English.srt"), folder("x", "Ronin (1998)"))
        assertEquals(emptyList<Sidecar>(), SubtitleIndex.inFolder(library)["drive:f"])
    }

    @Test fun aSubtitleFileDriveCallsAVideoIsNoVideo() {
        // VobSub's .sub can come back from Drive as an MPEG video.
        val found = SubtitleIndex.inFolder(listOf(video("v", "Heat.mkv"), DriveFile("s", "Heat.sub", "video/mpeg", 1, 0, null)))
        assertEquals(setOf("drive:v"), found.keys)
    }

    @Test fun whatsLearnedIsKnownAndAFileTakenAwayIsForgotten() {
        SubtitleIndex.learnNow(listOf(video("v", "Heat.mkv"), file("s", "Heat.en.srt")))
        assertEquals(listOf("en"), SubtitleIndex.known("drive:v")?.map { it.language })
        // Listed again without it: gone.
        SubtitleIndex.learnNow(listOf(video("v", "Heat.mkv")))
        assertNull(SubtitleIndex.known("drive:v"))
    }

    @Test fun whatsFoundIsKeptForTheNextStart() {
        SubtitleIndex.attach(context)
        SubtitleIndex.learnNow(listOf(video("v", "Heat.mkv"), file("s", "Heat.en.sdh.srt"), video("w", "Ronin.mkv")))
        assertTrue(saved.exists())
        // Only videos that have some are kept.
        assertEquals(setOf("drive:v"), SubtitleIndex.read(saved).keys)

        // Woodruff starts again: read back.
        SubtitleIndex.resetForTest()
        SubtitleIndex.worker = java.util.concurrent.Executor { it.run() }
        assertFalse(SubtitleIndex.loaded)
        SubtitleIndex.load(context)
        assertTrue(SubtitleIndex.loaded)
        assertEquals(listOf(Sidecar(DriveApi.mediaUrl("s"), "application/x-subrip", "en", sdh = true)), SubtitleIndex.known("drive:v"))
    }

    @Test fun whatsLearnedSinceStartingWinsOverWhatWasSaved() {
        SubtitleIndex.write(saved, mapOf(
            "drive:v" to listOf(Sidecar(DriveApi.mediaUrl("old"), "application/x-subrip", "fr")),
            "drive:w" to listOf(Sidecar(DriveApi.mediaUrl("w1"), "application/x-subrip", "de")),
            "drive:x" to listOf(Sidecar(DriveApi.mediaUrl("x1"), "application/x-subrip", "it")),
        ))
        // Before the saved copy is read: v's subtitle changed, w's was taken away.
        SubtitleIndex.learnNow(listOf(video("v", "Heat.mkv"), file("new", "Heat.en.srt")))
        SubtitleIndex.learnNow(listOf(video("w", "Ronin.mkv")))
        SubtitleIndex.load(context)
        assertEquals(listOf(DriveApi.mediaUrl("new")), SubtitleIndex.known("drive:v")?.map { it.uri })
        assertNull(SubtitleIndex.known("drive:w"))
        assertEquals(listOf("it"), SubtitleIndex.known("drive:x")?.map { it.language })
        // And the copy on the TV says so too.
        assertEquals(setOf("drive:v", "drive:x"), SubtitleIndex.read(saved).keys)
    }

    @Test fun signingOutForgetsThem() {
        SubtitleIndex.attach(context)
        SubtitleIndex.learnNow(listOf(video("v", "Heat.mkv"), file("s", "Heat.en.srt")))
        DriveApi.forgetFolders()
        assertNull(SubtitleIndex.known("drive:v"))
        assertEquals(emptyMap<String, List<Sidecar>>(), SubtitleIndex.read(saved))
    }

    @Test fun aLibraryShowsFilmsAndFoldersNotTheFilesThatComeWithThem() = runBlocking {
        val listing = listOf(
            video("heat", "Heat (1995).mkv"), file("heat-en", "Heat (1995).en.srt"), file("heat-nfo", "Heat (1995).nfo", "text/plain"),
            DriveFile("heat-jpg", "Heat (1995).jpg", "image/jpeg", 1, 0, "https://lh3.googleusercontent.com/heat=s220"),
            DriveFile("fanart", "fanart.jpg", "image/jpeg", 1, 0, null),
            DriveFile("song", "Theme.mp3", "audio/mpeg", 1, 0, null),
            file("readme", "Read me.txt", "text/plain"), file("idx", "Ronin.idx", "application/octet-stream"),
        )
        val shown = DriveApi.withPosters("token", listing)
        assertEquals(listOf("heat", "song"), shown.map { it.id })
        // The film's own picture is its poster.
        assertEquals("https://lh3.googleusercontent.com/heat=s720", shown.first().posterUrl)
        // And its subtitle file is known for the player.
        assertEquals(listOf(DriveApi.mediaUrl("heat-en")), SubtitleIndex.known("drive:heat")?.map { it.uri })
    }

    @Test fun theyAttachWithTheirLanguageFlagsAndAMark() {
        val config = Sidecars.configuration(Sidecar(DriveApi.mediaUrl("s"), "application/x-subrip", "en", forced = true, sdh = true))
        assertTrue(SidecarLoading.isMarked(config.uri))
        assertEquals("application/x-subrip", config.mimeType)
        assertEquals("en", config.language)
        assertEquals(C.SELECTION_FLAG_FORCED, config.selectionFlags)
        assertEquals(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, config.roleFlags)
        // No language in the name: undetermined, so choosing it can still be remembered for the show.
        assertEquals(C.LANGUAGE_UNDETERMINED, Sidecars.configuration(Sidecar(DriveApi.mediaUrl("t"), "text/vtt")).language)
    }

    @Test fun aUsbFoldersSubtitlesAreFoundBesideAndInSubs() {
        val dir = tmp.newFolder("Films")
        fun touch(path: String) = File(dir, path).apply { parentFile?.mkdirs(); writeText("x") }
        touch("Heat (1995).mkv"); touch("Heat (1995).en.srt"); touch("Heat (1995).fr.forced.ass"); touch("._Heat (1995).en.srt")
        touch("Ronin (1998).mp4"); touch("Subs/Ronin (1998).es.srt"); touch("Subs/Heat (1995)/2_English.srt")
        touch("Notes.txt")
        val found = LocalSidecars.inFolder(dir)
        val heat = File(dir, "Heat (1995).mkv").absolutePath
        val ronin = File(dir, "Ronin (1998).mp4").absolutePath
        assertEquals(setOf(heat, ronin), found.keys)
        assertEquals(
            listOf(
                Sidecar(Uri.fromFile(File(dir, "Heat (1995).en.srt")).toString(), "application/x-subrip", "en"),
                Sidecar(Uri.fromFile(File(dir, "Heat (1995).fr.forced.ass")).toString(), "text/x-ssa", "fr", forced = true),
                Sidecar(Uri.fromFile(File(dir, "Subs/Heat (1995)/2_English.srt")).toString(), "application/x-subrip", "en"),
            ),
            found[heat],
        )
        assertEquals(listOf(Sidecar(Uri.fromFile(File(dir, "Subs/Ronin (1998).es.srt")).toString(), "application/x-subrip", "es")), found[ronin])
    }

    @Test fun aUsbFolderWithoutVideosHasNothing() {
        val dir = tmp.newFolder("Music")
        File(dir, "a.srt").writeText("x")
        assertEquals(emptyMap<String, List<Sidecar>>(), LocalSidecars.inFolder(dir))
        assertEquals(emptyMap<String, List<Sidecar>>(), LocalSidecars.inFolder(File(dir, "missing")))
    }
}
