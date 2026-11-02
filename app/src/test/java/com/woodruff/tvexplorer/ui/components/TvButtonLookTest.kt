package com.woodruff.tvexplorer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A dialog's filled button (Unlink, Delete, Save) reads at rest in both themes: its words stand out
 * from its pill. tv-material's own colours made it an 80 % white pill with near-white words in the dark
 * theme (1.3:1), a blank shape until it was focused.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvButtonLookTest {
    @get:Rule val compose = createComposeRule()

    @After fun dark() { Palette.light = false }

    @Test fun aFilledButtonsWordsStandOutFromItsPillInBothThemes() {
        compose.setContent {
            TvExplorerTheme {
                Box(Modifier.background(Palette.background).padding(24.dp)) {
                    TvButton(onClick = {}, modifier = Modifier.testTag("button")) { Text("Unlink") }
                }
            }
        }
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light }
            compose.waitForIdle()
            val ratio = wordsOnPill(compose.onNodeWithTag("button").captureToImage().asAndroidBitmap())
            val theme = if (light) "light" else "dark"
            assertTrue("The button's words are ${"%.2f".format(ratio)}:1 on its pill in the $theme theme", ratio >= 4.5)
        }
    }

    /**
     * The contrast of a button's words on its pill: inside the pill's middle (clear of its rounded ends and
     * its rim), the most common colour is the pill, and the colour furthest from it the words.
     */
    private fun wordsOnPill(b: Bitmap): Double {
        val pixels = buildList {
            for (y in (b.height * 0.3).toInt() until (b.height * 0.7).toInt()) {
                for (x in (b.width * 0.15).toInt() until (b.width * 0.85).toInt()) add(b.getPixel(x, y) or 0xFF000000.toInt())
            }
        }
        val pill = pixels.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        return pixels.maxOf { contrast(it, pill) }
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun channel(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel((c shr 16) and 0xFF) + 0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)
    }
}
