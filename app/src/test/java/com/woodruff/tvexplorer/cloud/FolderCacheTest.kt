package com.woodruff.tvexplorer.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

    @Test fun folderIdsAreSafeFileNames() {
        assertEquals("1aB-c_D.json", FolderCache.fileName("1aB-c_D"))
        assertEquals("a_b_c.json", FolderCache.fileName("a/b.c"))
    }
}
