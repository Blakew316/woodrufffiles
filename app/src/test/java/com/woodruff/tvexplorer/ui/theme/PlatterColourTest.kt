package com.woodruff.tvexplorer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The highlight that glides behind a menu's rows (the title menu, the TV-link and Notifications lists)
 * is the app's own white (dark theme) or navy (light theme) platter, the one Settings, Search and TV
 * health use, with words in the page's colour on it. Material's baseline greys (a pinkish #E6E1E5, a
 * warm #313033) showed through before.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlatterColourTest {
    @get:Rule val compose = createComposeRule()

    @After fun dark() { Palette.light = false }

    @Test fun menusHighlightInTheAppsPlatterColoursInBothThemes() {
        var platter = Color.Unspecified
        var words = Color.Unspecified
        compose.setContent {
            TvExplorerTheme {
                platter = MaterialTheme.colorScheme.inverseSurface
                words = MaterialTheme.colorScheme.inverseOnSurface
            }
        }
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light }
            compose.waitForIdle()
            val theme = if (light) "light" else "dark"
            assertEquals("the platter in the $theme theme", Palette.text, platter)
            assertEquals("the words on it in the $theme theme", Palette.background, words)
        }
    }
}
