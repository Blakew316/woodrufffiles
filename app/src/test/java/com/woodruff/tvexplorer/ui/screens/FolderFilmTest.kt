package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.cloud.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderFilmTest {
    private val gb = 1L shl 30
    private fun video(name: String, size: Long) = DriveFile(name, name, "video/x-matroska", size, 0, null)
    private val extras = DriveFile("x", "Extras", DriveFile.FOLDER_MIME, 0, 0, null)

    @Test fun theOnlyVideoIsTheFilm() {
        val heat = video("Heat (1995).mkv", 9 * gb)
        // Only videos count, however big the rest.
        assertEquals(heat, folderFilm(listOf(extras, DriveFile("p", "poster.jpg", "image/jpeg", 20 * gb, 0, null), heat)))
    }

    @Test fun aTrailerOrSampleBesideTheFilmDoesNotCount() {
        val heat = video("Heat (1995).mkv", 9 * gb)
        assertEquals(heat, folderFilm(listOf(video("Heat (1995)-trailer.mkv", 90_000_000), heat, video("sample.mkv", 60_000_000))))
    }

    @Test fun aCollectionOpensInstead() = assertNull(folderFilm(listOf(
        video("Back to the Future (1985).mkv", 8 * gb),
        video("Back to the Future Part II (1989).mkv", 9 * gb),
        video("Back to the Future Part III (1990).mkv", 8 * gb),
    )))

    @Test fun aFilmInPartsOpensInstead() =
        assertNull(folderFilm(listOf(video("Heat CD1.mkv", 700_000_000), video("Heat CD2.mkv", 690_000_000))))

    @Test fun noVideoNoFilm() {
        assertNull(folderFilm(emptyList()))
        assertNull(folderFilm(listOf(extras)))
    }
}
