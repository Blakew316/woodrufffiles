package com.woodruff.tvexplorer.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Typing from the phone counts as someone using Woodruff (util/Presence), which reads Android's clock. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [34])
class RemoteInputTest {
    private val ids = mutableListOf<Long>()

    private fun field(label: String, text: String = ""): Long =
        RemoteInput.newId().also { RemoteInput.register(it, label, false, false, text); ids += it }

    @After fun tearDown() = ids.forEach(RemoteInput::unregister)

    @Test fun newestFieldIsTheTarget() {
        field("Notes")
        val dialog = field("Rename")
        assertEquals(dialog, RemoteInput.target?.id)
    }

    @Test fun focusMovesTheTarget() {
        val title = field("Title")
        field("Body")
        RemoteInput.bringToFront(title)
        assertEquals(title, RemoteInput.target?.id)
    }

    @Test fun closingADialogFallsBackToTheFieldBehindIt() {
        val notes = field("Notes")
        val dialog = field("Rename")
        RemoteInput.unregister(dialog)
        assertEquals(notes, RemoteInput.target?.id)
    }

    @Test fun typingUpdatesTheSharedText() {
        val id = field("Search", "old")
        com.woodruff.tvexplorer.notify.AppVisibility.lastInput = 0
        assertTrue(RemoteInput.type(null, "new text", submit = false))
        assertTrue(com.woodruff.tvexplorer.notify.AppVisibility.lastInput > 0)
        assertEquals("new text", RemoteInput.target?.text)
        assertEquals(id, RemoteInput.target?.id)
    }

    @Test fun typingWithNothingOpenFails() {
        assertNull(RemoteInput.target)
        assertFalse(RemoteInput.type(null, "hello", submit = true))
    }
}
