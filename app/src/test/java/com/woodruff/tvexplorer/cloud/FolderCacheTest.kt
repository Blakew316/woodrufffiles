package com.woodruff.tvexplorer.cloud

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A folder's listing as it's kept on disk, so a title page opens complete after a restart (org.json needs Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderCacheTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val season = DriveFile("season1", "Season 1", DriveFile.FOLDER_MIME, 0, 0, null)
    private var now = 1_800_000_000_000L

    private fun episode(link: String, name: String = "Show S01E01.mkv") =
        DriveFile("ep1", name, "video/x-matroska", 1, 5, DriveArt.tag(link, "ep1", 5))

    /** A Drive for [fetch]: each token's answer, every listing counted. */
    private class FakeDrive(var answer: (String) -> List<DriveFile>) {
        var listings = 0
        var forgotten = 0
        var tokens = ArrayDeque(listOf("good"))
        fun token(): GoogleAuth.TokenResult = GoogleAuth.TokenResult.Ok(tokens.removeFirstOrNull() ?: "good")
    }

    private fun fetch(drive: FakeDrive) = runBlocking {
        FolderCache.fetchWith(context, null, season, tokenFor = { drive.token() }, forget = { drive.forgotten++ }) { t -> drive.listings++; drive.answer(t) }
    }

    private val saved get() = File(context.filesDir, "folders/season1.json")

    @After fun reset() {
        FolderCache.clock = System::currentTimeMillis
        FolderCache.clear(context)
    }

    @Test fun aFolderListedMomentsAgoIsntListedAgain() {
        FolderCache.clock = { now }
        val drive = FakeDrive { listOf(episode("https://lh3.googleusercontent.com/a=s220")) }
        assertTrue(fetch(drive) is FolderCache.Listing.Listed)
        // The title page opened again, its other season tab, a folder Streaming listed as the focus rested on it.
        now += FRESH_MS - 1
        assertEquals(FolderCache.Listing.Listed(listOf(episode("https://lh3.googleusercontent.com/a=s220"))), fetch(drive))
        assertEquals(1, drive.listings)
        // Five minutes on, it's listed again.
        now += 1
        fetch(drive)
        assertEquals(2, drive.listings)
    }

    @Test fun aTokenDriveWontTakeIsRenewedOnceNotTakenForGoogleNotAnswering() {
        val drive = FakeDrive { t -> if (t == "revoked") throw DriveHttpException(401, "no") else listOf(episode("https://lh3.googleusercontent.com/a=s220")) }
        drive.tokens = ArrayDeque(listOf("revoked", "renewed"))
        assertTrue(fetch(drive) is FolderCache.Listing.Listed)
        assertEquals(1, drive.forgotten)
        assertEquals(2, drive.listings)
        // Refused again with the token just renewed: Google not answering properly (tried again later), once only.
        FolderCache.clear(context)
        val still = FakeDrive { throw DriveHttpException(401, "no") }
        assertEquals(FolderCache.Listing.Offline, fetch(still))
        assertEquals(1, still.forgotten)
        assertEquals(2, still.listings)
    }

    @Test fun onlyNewLinksDontSaveTheFolderAgain() {
        FolderCache.clock = { now }
        var link = "https://lh3.googleusercontent.com/monday=s220"
        var name = "Show S01E01.mkv"
        val drive = FakeDrive { listOf(episode(link, name)) }
        fetch(drive)
        // Kept with the app's files, where Android never empties it to free storage.
        assertTrue(saved.exists())
        val first = saved.readText()
        // Drive's new link for the same picture: in memory (a picture not fetched yet needs a link that works), not saved again.
        now += FRESH_MS
        link = "https://lh3.googleusercontent.com/tuesday=s220"
        fetch(drive)
        assertEquals(first, saved.readText())
        assertEquals(DriveArt.tag(link, "ep1", 5), FolderCache.inMemory("season1")!!.single().thumbnailLink)
        // Once the saved copy's links are an hour old they're saved again, new ones and all (the phone's episode
        // pictures are fetched with them).
        saved.setLastModified(System.currentTimeMillis() - DriveArt.SAVED_LINKS_MS)
        now += FRESH_MS
        link = "https://lh3.googleusercontent.com/wednesday=s220"
        fetch(drive)
        assertTrue(saved.readText().contains("wednesday"))
        val renewed = saved.readText()
        // Anything else changed: saved.
        now += FRESH_MS
        name = "Show S01E01 - Pilot.mkv"
        fetch(drive)
        assertFalse(renewed == saved.readText())
    }

    @Test fun aListingKeptInTheCacheFolderByAnEarlierVersionMovesWithTheAppsFiles() {
        val old = File(context.cacheDir, "folders").apply { mkdirs() }
        File(old, "season1.json").writeText(FolderCache.toJson(listOf(episode("https://lh3.googleusercontent.com/a=s220"))))
        assertEquals(listOf(episode("https://lh3.googleusercontent.com/a=s220")), FolderCache.peek(context, "season1"))
        assertTrue(saved.exists())
        assertFalse(old.exists())
    }
    @Test fun aSavedListingReadsBackAsListed() {
        val files = listOf(
            DriveFile("1aB-c_D", "Season 1", DriveFile.FOLDER_MIME, 0, 1_700_000_000_000, null, created = 1_600_000_000_000),
            DriveFile("2xY", "Show - S01E01 - \"Pilot\".mkv", "video/x-matroska", 4_294_967_296, 1_700_000_000_001, "https://lh3.googleusercontent.com/abc=s220"),
        )
        assertEquals(files, FolderCache.fromJson(FolderCache.toJson(files)))
        assertEquals(emptyList<DriveFile>(), FolderCache.fromJson(FolderCache.toJson(emptyList())))
    }

    @Test fun aListingThatCantGetThroughIsOfflineNotEmpty() {
        assertEquals(FolderCache.Listing.Offline, FolderCache.listingFailure(java.net.UnknownHostException("www.googleapis.com")))
        assertEquals(FolderCache.Listing.Offline, FolderCache.listingFailure(java.net.SocketTimeoutException()))
        assertEquals(FolderCache.Listing.Offline, FolderCache.listingFailure(DriveHttpException(503, "busy")))
        assertEquals(FolderCache.Listing.Offline, FolderCache.listingFailure(DriveHttpException(403, "busy", busy = true)))
        assertEquals(FolderCache.Listing.Offline, FolderCache.listingFailure(org.json.JSONException("cut off")))
        // Google answered: the folder is gone, or it may not be listed.
        assertEquals(FolderCache.Listing.Failed, FolderCache.listingFailure(DriveHttpException(404, "gone")))
        assertEquals(FolderCache.Listing.Failed, FolderCache.listingFailure(DriveHttpException(403, "no")))
        assertEquals(FolderCache.Listing.Failed, FolderCache.listingFailure(IllegalStateException("x")))
    }

    @Test fun aSignInGoogleEndedNeverListsWithTheScreensOldToken() {
        // Renewed: the fresh token.
        assertEquals("fresh", FolderCache.listingToken(GoogleAuth.TokenResult.Ok("fresh"), "old"))
        // Couldn't renew (offline), or Google refused the keys: the screen's token may have a while left yet.
        assertEquals("old", FolderCache.listingToken(GoogleAuth.TokenResult.Offline(java.io.IOException()), "old"))
        assertEquals("old", FolderCache.listingToken(GoogleAuth.TokenResult.Rejected("keys"), "old"))
        // Google ended the sign-in: its old token is refused (a 401 that would read as "offline" for ever).
        assertNull(FolderCache.listingToken(GoogleAuth.TokenResult.SignedOut, "old"))
        assertNull(FolderCache.listingToken(GoogleAuth.TokenResult.Offline(java.io.IOException()), null))
    }

    @Test fun aFileIsFoundInTheListingsInMemoryNewestFirst() {
        // Up next's picture of the next episode: from the season a show's page just listed, nothing fetched.
        val old = DriveFile("ep2-up-next", "Show S01E02.mkv", "video/x-matroska", 1, 1, "https://lh3.googleusercontent.com/old=s220")
        val now = old.copy(thumbnailLink = "https://lh3.googleusercontent.com/new=s220")
        FolderCache.seed("season-a-up-next", listOf(old))
        FolderCache.seed("season-b-up-next", listOf(DriveFile("other", "Other.mkv", "video/x-matroska", 1, 1, null), now))
        assertEquals(now, FolderCache.fileInMemory("ep2-up-next"))
        assertNull(FolderCache.fileInMemory("never-listed"))
    }

    @Test fun folderIdsAreSafeFileNames() {
        assertEquals("1aB-c_D.json", FolderCache.fileName("1aB-c_D"))
        assertEquals("a_b_c.json", FolderCache.fileName("a/b.c"))
    }
}
