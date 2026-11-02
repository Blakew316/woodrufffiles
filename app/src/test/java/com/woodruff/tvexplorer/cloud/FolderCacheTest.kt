package com.woodruff.tvexplorer.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A folder's listing as it's kept on disk, so a title page opens complete after a restart (org.json needs Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderCacheTest {
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

    @Test fun folderIdsAreSafeFileNames() {
        assertEquals("1aB-c_D.json", FolderCache.fileName("1aB-c_D"))
        assertEquals("a_b_c.json", FolderCache.fileName("a/b.c"))
    }
}
