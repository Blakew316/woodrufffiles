package com.woodruff.tvexplorer.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.cloud.TitleSearch
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Search's page (search spec): typing on its keys, a real keyboard and the phone, Back, and its whole-page states. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchScreenTest {
    // The rule's second version: an effect goes on on the main thread after its work elsewhere (the search runs
    // off it), as on a TV. SearchResultsThreadTest keeps the first, to catch work done from that other thread.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun film(id: String, title: String, year: Int, rating: Double) =
        DriveFile(id, "$title ($year).mkv", "video/x-matroska", 0, 0, null, info = MediaInfo(title, year, rating = rating))

    private fun show(id: String, title: String, year: Int, rating: Double) =
        DriveFile(id, title, DriveFile.FOLDER_MIME, 0, 0, null, info = MediaInfo(title, year, rating = rating, tv = true))

    private val films = listOf(film("m1", "The Last Light", 2023, 7.1), film("m2", "Midnight Harbor", 2024, 7.8), film("m3", "Orbit", 2021, 7.6))
    private val shows = listOf(show("t1", "The Lighthouse", 2019, 7.9), show("t2", "Kitchen Rules", 2018, 7.4))

    private var opened: TitleSearch.Hit? = null
    private var fixed: SearchBlock? = null
    private var listened = 0
    private val heard = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private fun open(block: SearchBlock = SearchBlock.NONE, voice: Boolean = true, recent: List<Pair<DrivePlace, DriveFile>> = emptyList()) {
        compose.setContent {
            TvExplorerTheme {
                SearchScreen(
                    request = SearchRequest(SearchFrom.MENU, null, listen = false, id = 1), block = block,
                    libraries = { mapOf(DrivePlace.Movies to films, DrivePlace.TvShows to shows) }, listings = 1,
                    recent = recent, news = emptyMap(), offline = null, down = false, token = null, voice = voice, covered = false,
                    marks = remember { mutableIntStateOf(0) },
                    onListen = { listened++ }, heard = heard, listenAgain = emptyFlow(),
                    onOpen = { opened = it }, onMenu = {}, onPlay = {}, onFix = { fixed = it },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun key(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun typingOnTheKeysFindsBothLibrariesAsPosters() {
        open()
        key("a").assertIsFocused()
        key("l").performSemanticsAction(SemanticsActions.OnClick)
        key("i").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("2 results")
        compose.onNode(hasText("The Lighthouse (2019)") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("t1", opened?.file?.id)
        assertEquals(DrivePlace.TvShows, opened?.place)
    }

    @Test fun backFromAResultGoesToTheKeyLastOnThenTheQueryStays() {
        open()
        key("o").performSemanticsAction(SemanticsActions.OnClick)
        // The last key typed on, focused (a click by its semantics doesn't move the focus).
        key("r").requestFocus()
        key("r").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Orbit (2021)")
        compose.onNode(hasText("Orbit (2021)") and hasClickAction()).requestFocus()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        key("r").assertIsFocused()
        compose.onNode(hasText("or")).assertExists()
    }

    @Test fun aRealKeyboardTypesWithoutMovingTheFocus() {
        open()
        compose.onRoot().performKeyInput { pressKey(Key.K); pressKey(Key.I); pressKey(Key.T) }
        waitForText("Kitchen Rules (2018)")
        key("a").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Backspace); pressKey(Key.Backspace); pressKey(Key.Backspace) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Kitchen Rules (2018)")).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun thePhoneTypesAndEnterGoesToTheFirstResult() {
        open()
        val target = RemoteInput.target
        assertEquals(SEARCH_BOX, target?.label)
        RemoteInput.type(null, "harbor", submit = true)
        waitForText("Midnight Harbor (2024)")
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasText("Midnight Harbor (2024)") and hasClickAction()).assertIsFocused() }.isSuccess
        }
    }

    @Test fun aFocusedResultThatGoesHandsTheFocusToTheFirst() {
        open()
        RemoteInput.type(null, "the", submit = false)
        waitForText("The Lighthouse (2019)")
        compose.onNode(hasText("The Lighthouse (2019)") and hasClickAction()).requestFocus()
        // The phone types on: The Lighthouse no longer matches, The Last Light does.
        RemoteInput.type(null, "the last", submit = false)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("The Lighthouse (2019)")).fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasText("The Last Light (2023)") and hasClickAction()).assertIsFocused() }.isSuccess
        }
    }

    @Test fun whatTheVoiceHeardReplacesTheQuery() {
        open()
        key("z").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasContentDescription("Say a title")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, listened)
        heard.tryEmit("  orbit ")
        waitForText("Orbit (2021)")
    }

    @Test fun holdingOkOnDeleteClears() {
        open()
        key("o").performSemanticsAction(SemanticsActions.OnClick)
        key("r").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Orbit (2021)")
        compose.onNode(hasContentDescription("Delete a letter")).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Movies & TV Shows")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun everyRowSpansTheSameWidthWithOrWithoutTheMic() {
        fun width(label: String) = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot.width
        open(voice = true)
        val density = compose.density.density
        val key = (300f - 4 * 5) / 6
        assertEquals((key * 2 + 4) * density, width("Space"), 1.5f)
        assertEquals((key * 2 + 4) * density, width("Clear"), 1.5f)
        assertEquals(key * density, compose.onNode(hasContentDescription("Say a title")).fetchSemanticsNode().boundsInRoot.width, 1.5f)
        // A key at rest ("a" has the focus, and grows with it).
        assertEquals(key * density, width("b"), 1.5f)
    }

    @Test fun withoutAMicClearTakesItsRoom() {
        open(voice = false)
        assertTrue(compose.onAllNodes(hasContentDescription("Say a title")).fetchSemanticsNodes().isEmpty())
        val density = compose.density.density
        val key = (300f - 4 * 5) / 6
        assertEquals((key * 3 + 8) * density, compose.onNode(hasText("Clear") and hasClickAction()).fetchSemanticsNode().boundsInRoot.width, 1.5f)
        compose.onNode(hasText("Type a title.")).assertExists()
    }

    @Test fun anEmptyQueryShowsRecentlyAdded() {
        open(recent = listOf(DrivePlace.Movies to films[1], DrivePlace.TvShows to shows[0]))
        compose.onNode(hasText("Recently added")).assertExists()
        compose.onNode(hasText("Midnight Harbor (2024)")).assertExists()
    }

    @Test fun noMatchesSaysSo() {
        open()
        key("z").performSemanticsAction(SemanticsActions.OnClick)
        key("z").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("No matches for “zz”")
        compose.onNode(hasText("Check the spelling, or try fewer letters.")).assertExists()
    }

    @Test fun googleDriveNotReadyIsAPageWithOneButton() {
        open(block = SearchBlock.NEEDS_DRIVE)
        compose.onNode(hasText("Search needs Google Drive")).assertExists()
        val button = compose.onNode(hasText("Set up Google Drive") and hasClickAction())
        compose.waitUntil(5_000) { runCatching { button.assertIsFocused() }.isSuccess }
        button.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(SearchBlock.NEEDS_DRIVE, fixed)
    }
}
