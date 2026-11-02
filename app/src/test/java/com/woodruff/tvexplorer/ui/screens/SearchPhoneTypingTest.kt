package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.server.CompanionRoutes
import com.woodruff.tvexplorer.server.answerFor
import com.woodruff.tvexplorer.server.bodyText
import com.woodruff.tvexplorer.server.phoneRequest
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The phone's keyboard types into Search through the TV's own route (`/api/type`), by the id the phone
 * was given for its box. The route turns down text for a box that isn't open any more (so it never
 * lands in another one), and Search keeps one id for as long as it's open: a title page opened over
 * a result and closed again never makes the phone's next text bounce.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchPhoneTypingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)

    private val films = listOf(
        DriveFile("m1", "Midnight Harbor (2024).mkv", "video/x-matroska", 0, 0, null, info = MediaInfo("Midnight Harbor", 2024, rating = 7.8)),
        DriveFile("m2", "Orbit (2021).mkv", "video/x-matroska", 0, 0, null, info = MediaInfo("Orbit", 2021, rating = 7.6)),
    )

    /** The box the TV says the phone types into now (GET /api/type), or null when there's none. */
    private fun field(): JSONObject? = JSONObject(routes.answerFor(phoneRequest("/api/type")).bodyText()).optJSONObject("field")

    private fun type(id: Long, text: String) =
        routes.answerFor(phoneRequest("/api/type", Method.POST, body = JSONObject().put("id", id).put("text", text))).status

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun thePhoneTypesOnIntoSearchOnceAPageOverItCloses() {
        // A title page (or a title menu) opened over Search covers it.
        val covered = mutableStateOf(false)
        compose.setContent {
            TvExplorerTheme {
                SearchScreen(
                    request = SearchRequest(SearchFrom.MENU, null, listen = false, id = 1), block = SearchBlock.NONE,
                    libraries = { mapOf(DrivePlace.Movies to films, DrivePlace.TvShows to emptyList()) }, listings = 1,
                    recent = emptyList(), news = emptyMap(), offline = null, down = false, token = null, voice = false,
                    covered = covered.value, marks = remember { mutableIntStateOf(0) },
                    onListen = {}, heard = emptyFlow(), listenAgain = emptyFlow(),
                    onOpen = {}, onMenu = {}, onPlay = {}, onFix = {},
                )
            }
        }
        compose.waitForIdle()
        val box = field()!!
        assertEquals(SEARCH_BOX, box.getString("label"))
        val id = box.getLong("id")
        assertEquals(Status.OK, type(id, "harbor"))
        compose.waitUntil(5_000) { shows("Midnight Harbor (2024)") }

        // Covered: nothing of Search's to type into, so the phone's text is turned down and the query stays.
        compose.runOnIdle { covered.value = true }
        compose.waitForIdle()
        assertTrue(field()?.optLong("id") != id)
        assertEquals(Status.BAD_REQUEST, type(id, "orbit"))
        compose.waitForIdle()
        assertTrue(shows("Midnight Harbor (2024)"))

        // The page closes: the same box under the same id, with what was typed, and the phone types on.
        compose.runOnIdle { covered.value = false }
        compose.waitForIdle()
        assertEquals(id, field()?.getLong("id"))
        assertEquals("harbor", field()?.getString("text"))
        assertEquals(Status.OK, type(id, "orbit"))
        compose.waitUntil(5_000) { shows("Orbit (2021)") }
    }
}
