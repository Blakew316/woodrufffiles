package com.woodruff.tvexplorer.ui.screens

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.cloud.filmPlayTitle
import com.woodruff.tvexplorer.player.RecentItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A film reaches the player under its title ("Midnight Harbor"), the words the controls, Continue
 * watching and the phone's now playing then show, never its Drive file name
 * ("Midnight.Harbor.2024.2160p.WEB-DL"): from its page or its menu, Play/Pause on its poster, a film kept
 * in its own folder, and Watch together.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FilmTitlesTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    /** A film as Drive names it, with no TMDB match (its title read from the name). */
    private val loose = DriveFile("m9", "Midnight.Harbor.2024.2160p.WEB-DL.mkv", "video/x-matroska", 4_000, 0, null)

    /** A film kept in its own folder, as TMDB knows the folder. */
    private val box = DriveFile("f9", "Midnight Harbor (2024)", DriveFile.FOLDER_MIME, 0, 0, null, info = MediaInfo("Midnight Harbor", 2024))

    private val run = TitleRun(
        activity, CoroutineScope(Dispatchers.Unconfined), resume = { it() }, drive = null,
        openPage = { _, _, _ -> }, beforePlay = {}, changed = {}, removing = { _, _ -> }, playTile = null,
    )

    /** The titles the player was last started with. */
    private fun playerTitles(): List<String>? = shadowOf(activity).nextStartedActivity?.getStringArrayListExtra("titles")

    @Test fun aFilmOfItsOwnPlaysUnderItsTitle() {
        run.run(TitleMenuModel("Midnight Harbor (2024)", null, emptyList(), DrivePlace.Movies, loose), TitleAction.Play(MainPlay.Film(loose, 0)))
        assertEquals(listOf("Midnight Harbor"), playerTitles())
        // From its page's Play, too.
        playTitle(activity, loose, fromStart = true)
        assertEquals(listOf("Midnight Harbor"), playerTitles())
    }

    @Test fun aFilmKeptInItsFolderPlaysUnderTheFoldersTitle() {
        run.run(TitleMenuModel("Midnight Harbor (2024)", null, emptyList(), DrivePlace.Movies, box), TitleAction.Restart(MainPlay.Film(loose, 0, folder = box)))
        assertEquals(listOf("Midnight Harbor"), playerTitles())
    }

    @Test fun theTitleIsTheLibrarysNeverTheFilesName() {
        assertEquals("Midnight Harbor", filmPlayTitle(loose))
        // TMDB's title when it matched the file; the folder's for a film kept in one.
        assertEquals("The Harbour Pilot", filmPlayTitle(loose.copy(info = MediaInfo("The Harbour Pilot", 2024))))
        assertEquals("Midnight Harbor", filmPlayTitle(DriveFile("v1", "movie.mkv", "video/x-matroska", 0, 0, null), folder = box))
        // Nothing to call it by: its file's name, as before.
        assertEquals("x.mkv", filmPlayTitle(DriveFile("v2", "x.mkv", "video/x-matroska", 0, 0, null, info = MediaInfo(" ", null))))
    }

    @Test fun watchTogetherSendsTheFilmsTitle() {
        val recent = listOf(RecentItem("drive:m9", loose.name, 3_900_000, 8_640_000, 0))
        fun together(target: DriveFile, inside: List<DriveFile>?) = titleMenu(
            TitleFacts(MenuFor(DrivePlace.Movies, target, MenuPlace.LIBRARY), target, DrivePlace.Movies, recent, emptyMap(), inside, { null }, { false }, null, 0L, true),
        ).rows.filterIsInstance<TitleAction.Together>().single()
        assertEquals("Midnight Harbor", together(loose, inside = null).title)
        assertEquals("Midnight Harbor", together(box, inside = listOf(loose)).title)
    }
}
