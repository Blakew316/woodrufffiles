package com.woodruff.tvexplorer.photos

import com.woodruff.tvexplorer.data.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's Family Memories: what counts as a memory, the sizes of its pictures, and photos turned upright. */
class FamilyMemoriesTest {
    @Test fun photosAndVideosOnly() {
        assertEquals(FileKind.IMAGE, FamilyMemories.kindOf("IMG_2104.JPG"))
        assertEquals(FileKind.IMAGE, FamilyMemories.kindOf("Beach.heic"))
        assertEquals(FileKind.VIDEO, FamilyMemories.kindOf("Birthday.MOV"))
        assertNull(FamilyMemories.kindOf("Recipes.pdf"))
        assertNull(FamilyMemories.kindOf("notes"))
    }

    @Test fun gridTilesCoverTheirSquare() {
        // The shorter side is the tile's, so a square crop of it fills the tile.
        assertEquals(560 to 420, FamilyMemories.scaled(4032, 3024, FamilyMemories.Size.GRID, 420))
        assertEquals(420 to 747, FamilyMemories.scaled(1080, 1920, FamilyMemories.Size.GRID, 420))
        // Never larger than the photo.
        assertEquals(400 to 300, FamilyMemories.scaled(400, 300, FamilyMemories.Size.GRID, 420))
    }

    @Test fun theViewerFitsTheLongerSide() {
        assertEquals(2048 to 1536, FamilyMemories.scaled(4032, 3024, FamilyMemories.Size.VIEW, 2048))
        assertEquals(1536 to 2048, FamilyMemories.scaled(3024, 4032, FamilyMemories.Size.VIEW, 2048))
        // A TV-size copy is shown as it is.
        assertEquals(1920 to 1080, FamilyMemories.scaled(1920, 1080, FamilyMemories.Size.VIEW, 2048))
    }

    @Test fun decodedJustLargeEnough() {
        assertEquals(4, FamilyMemories.sampleFor(3024, 420))   // 756 ≥ 420, 378 isn't
        assertEquals(1, FamilyMemories.sampleFor(1080, 1080))
        assertEquals(1, FamilyMemories.sampleFor(300, 420))     // smaller than asked: as it is
        assertEquals(2, FamilyMemories.sampleFor(3024, 1075))
    }

    @Test fun exifOrientations() {
        assertTrue(FamilyMemories.Turn.of(1).isNone)
        assertTrue(FamilyMemories.Turn.of(0).isNone)   // missing or unknown: as it is
        assertTrue(FamilyMemories.Turn.of(9).isNone)
        assertEquals(FamilyMemories.Turn.R90, FamilyMemories.Turn.of(6))
        assertEquals(FamilyMemories.Turn.R270, FamilyMemories.Turn.of(8))
        assertEquals(FamilyMemories.Turn.R180, FamilyMemories.Turn.of(3))
        // Transpose and transverse: flipped, then turned 270° and 90°.
        assertEquals(FamilyMemories.Turn.FLIP270, FamilyMemories.Turn.of(5))
        assertEquals(FamilyMemories.Turn.FLIP90, FamilyMemories.Turn.of(7))
        assertTrue(FamilyMemories.Turn.of(6).swapsSides)
        assertTrue(FamilyMemories.Turn.of(5).swapsSides)
        assertFalse(FamilyMemories.Turn.of(4).swapsSides)
    }
}
