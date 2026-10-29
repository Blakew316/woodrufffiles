package com.woodruff.tvexplorer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The background photo is dimmed as it's drawn (a colour matrix) instead of under a see-through
 * layer of the background colour: the pixels must come out the same.
 */
class BackgroundPhotoTest {
    private val navy = Color(0xFF060A12)
    private val white = Color(0xFFF2F6FA)

    /** A pixel (0-255 channels) through the matrix, as Android's colour filter applies it. */
    private fun filter(m: FloatArray, rgba: FloatArray) = FloatArray(4) { row ->
        (0 until 4).fold(m[row * 5 + 4]) { sum, col -> sum + m[row * 5 + col] * rgba[col] }.coerceIn(0f, 255f)
    }

    /** [top] at [alpha] drawn over [under]. */
    private fun over(top: Float, alpha: Float, under: Float) = top * alpha + under * (1 - alpha)

    private fun channels(c: Color) = floatArrayOf(c.red * 255, c.green * 255, c.blue * 255)

    @Test fun anOpaquePhotoComesOutAsItDidUnderTheScrim() {
        for (scrim in listOf(navy, white)) {
            val m = scrimMatrix(scrim, 0.78f)
            val s = channels(scrim)
            for (pixel in listOf(floatArrayOf(255f, 255f, 255f), floatArrayOf(0f, 0f, 0f), floatArrayOf(200f, 120f, 40f))) {
                val out = filter(m, pixel + 255f)
                for (i in 0 until 3) assertEquals(over(s[i], 0.78f, pixel[i]), out[i], 0.01f)
                assertEquals(255f, out[3], 0f)
            }
        }
    }

    @Test fun aSeeThroughPhotoOverTheBackgroundComesOutTheSameToo() {
        // Half see-through white over the navy background: the old way blended the photo onto the
        // background and then the scrim over both; now the filtered photo is blended onto it.
        val m = scrimMatrix(navy, 0.78f)
        val bg = channels(navy)
        val out = filter(m, floatArrayOf(255f, 255f, 255f, 127.5f))
        for (i in 0 until 3) {
            val before = over(bg[i], 0.78f, over(255f, 0.5f, bg[i]))
            assertEquals(before, over(out[i], 0.5f, bg[i]), 0.01f)
        }
        assertEquals(127.5f, out[3], 0f)
    }
}
