package com.woodruff.tvexplorer.ui.theme

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** The backdrop is painted once into a small picture that each frame stretches over the screen. */
class BackdropTest {
    @Test fun thePictureIsAQuarterOfTheScreenEachWay() {
        assertEquals(IntSize(480, 270), backdropPictureSize(1920f, 1080f))
        assertEquals(IntSize(320, 180), backdropPictureSize(1280f, 720f))
    }

    @Test fun itRoundsUpSoItAlwaysCoversTheScreen() {
        assertEquals(IntSize(241, 136), backdropPictureSize(961f, 541f))
    }

    @Test fun itIsNeverEmpty() {
        assertEquals(IntSize(1, 1), backdropPictureSize(0f, 0f))
        assertEquals(IntSize(1, 1), backdropPictureSize(3f, 2f))
    }
}
