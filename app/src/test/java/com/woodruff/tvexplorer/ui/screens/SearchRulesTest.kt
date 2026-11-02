package com.woodruff.tvexplorer.ui.screens

import android.view.KeyEvent
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.cloud.SignInStep
import com.woodruff.tvexplorer.cloud.TitleSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Search's rules that need no screen: typing, what the page shows, its words (search spec §3–§5). */
class SearchRulesTest {
    @Test fun aSpaceIsNeverFirstOrTwiceInARow() {
        assertEquals("", typedInto("", ' '))
        assertEquals("a", typedInto("", 'a'))
        assertEquals("the ", typedInto("the", ' '))
        assertEquals("the ", typedInto("the ", ' '))
        assertEquals("the l", typedInto("the ", 'l'))
    }

    @Test fun theQueryStopsAtSixtyCharacters() {
        val full = "a".repeat(TitleSearch.QUERY_MAX)
        assertEquals(full, typedInto(full, 'b'))
        assertEquals(60, phoneQuery("x".repeat(80)).length)
        assertEquals("midnight harbor", spokenQuery("  midnight harbor "))
        assertEquals(60, spokenQuery(" " + "y".repeat(90)).length)
    }

    @Test fun aRealKeyboardsKeys() {
        assertEquals('a', keyChar(KeyEvent.KEYCODE_A))
        assertEquals('z', keyChar(KeyEvent.KEYCODE_Z))
        assertEquals('0', keyChar(KeyEvent.KEYCODE_0))
        assertEquals('7', keyChar(KeyEvent.KEYCODE_NUMPAD_7))
        assertEquals(' ', keyChar(KeyEvent.KEYCODE_SPACE))
        // The D-pad, OK, Back and the media keys are never typed.
        for (k in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DEL)) {
            assertNull(keyChar(k))
        }
    }

    @Test fun fastInputReplacesTheResultsOnce() {
        // A single key (the last one a while ago) answers at once; keys closer than 150 ms wait for the last.
        assertEquals(0L, resultsWait(400))
        assertEquals(0L, resultsWait(RESULTS_CALM_MS))
        assertEquals(RESULTS_CALM_MS, resultsWait(40))
        assertEquals(RESULTS_CALM_MS, resultsWait(0))
    }

    @Test fun whatStandsInTheWay() {
        assertEquals(SearchBlock.NEEDS_DRIVE, searchBlock(SignInStep.SETUP, ended = false, refused = false))
        assertEquals(SearchBlock.NEEDS_DRIVE, searchBlock(SignInStep.SIGN_IN, ended = false, refused = false))
        assertEquals(SearchBlock.SIGN_IN_ENDED, searchBlock(SignInStep.SIGN_IN, ended = true, refused = false))
        assertEquals(SearchBlock.KEYS, searchBlock(SignInStep.CONNECTED, ended = false, refused = true))
        assertEquals(SearchBlock.NONE, searchBlock(SignInStep.CONNECTED, ended = false, refused = false))
    }

    @Test fun thePagesWords() {
        val drive = searchBlockWords(SearchBlock.NEEDS_DRIVE)
        assertEquals("Search needs Google Drive", drive.title)
        assertEquals("Movies and TV Shows come from your Google Drive. Set it up once on this TV, then search from anywhere.", drive.body)
        assertEquals("Set up Google Drive", drive.action)
        val ended = searchBlockWords(SearchBlock.SIGN_IN_ENDED)
        assertEquals("Sign in to Google Drive again", ended.title)
        assertEquals("Google ended this TV’s sign-in. Sign in again and search from anywhere.", ended.body)
        assertEquals("Sign in", ended.action)
        val keys = searchBlockWords(SearchBlock.KEYS)
        assertEquals("Check the Google keys", keys.title)
        assertEquals("Google doesn’t accept the Client ID or secret saved on this TV. Fix them, then search from anywhere.", keys.body)
        assertEquals("Open Keys", keys.action)
    }

    @Test fun theOfflineLine() {
        assertNull(searchOfflineWords(networkDown = false, status = DriveStatus.Online))
        // Within the grace, nothing is said.
        assertNull(searchOfflineWords(networkDown = false, status = DriveStatus.Offline(since = 0, google = true, shown = false)))
        assertEquals("Offline · searching your saved library", searchOfflineWords(networkDown = true, status = DriveStatus.Online))
        assertEquals(
            "Can’t reach Google · searching your saved library",
            searchOfflineWords(networkDown = false, status = DriveStatus.Offline(since = 0, google = true, shown = true)),
        )
        assertEquals(
            "Offline · searching your saved library",
            searchOfflineWords(networkDown = false, status = DriveStatus.Reconnecting(DriveStatus.Offline(since = 0, google = false, shown = true))),
        )
        // No network wins over what Google last said.
        assertEquals(
            "Offline · searching your saved library",
            searchOfflineWords(networkDown = true, status = DriveStatus.Offline(since = 0, google = true, shown = true)),
        )
    }

    @Test fun whatTheRightSideShows() {
        val film = DriveFile("m1", "Midnight Harbor.mkv", "video/x-matroska", 0, 0, null, info = MediaInfo("Midnight Harbor", 2024))
        val found = TitleSearch.find("harbor", mapOf(DrivePlace.Movies to listOf(film)))
        assertEquals(SearchShows.Waiting("Movies and TV Shows are still loading…"), searchShows("", TitleSearch.Found.NONE, listed = false, down = false, recent = 0, voice = true))
        assertEquals(SearchShows.Waiting("Search will work once the TV is back online."), searchShows("har", TitleSearch.Found.NONE, listed = false, down = true, recent = 0, voice = true))
        assertEquals(SearchShows.Recent, searchShows("", TitleSearch.Found.NONE, listed = true, down = false, recent = 4, voice = true))
        assertEquals(SearchShows.Hint("Type a title, or press the mic and say one."), searchShows(" ", TitleSearch.Found.NONE, listed = true, down = false, recent = 0, voice = true))
        assertEquals(SearchShows.Hint("Type a title."), searchShows("", TitleSearch.Found.NONE, listed = true, down = false, recent = 0, voice = false))
        assertEquals(SearchShows.Results, searchShows("harbor", found, listed = true, down = false, recent = 4, voice = true))
        assertEquals(SearchShows.NoMatches("midnite"), searchShows("midnite ", TitleSearch.Found.NONE, listed = true, down = true, recent = 4, voice = true))
    }
}
