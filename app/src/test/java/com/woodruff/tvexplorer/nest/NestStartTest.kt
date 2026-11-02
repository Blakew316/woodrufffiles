package com.woodruff.tvexplorer.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the cameras show at start-up, from the list saved at the last listing, before the sign-in is
 * read from the Keystore: Home's tiles straight away, never "aren't connected" for a moment.
 */
class NestStartTest {
    private val saved = listOf(
        Camera("enterprises/p/devices/1", "Front Door", "Entryway", webRtc = true, doorbell = true),
        Camera("enterprises/p/devices/2", "Backyard", null, webRtc = true, doorbell = false),
    )

    @Test fun aSavedListShowsAtOnceAsConnected() {
        val (shown, guessed) = startingWith(NestState(), saved)
        assertEquals(saved, shown.cameras)
        assertEquals(NestStep.CONNECTED, shown.step)
        assertTrue(guessed)
    }

    @Test fun withNothingSavedNothingIsGuessed() {
        val (shown, guessed) = startingWith(NestState(), emptyList())
        assertEquals(NestState(), shown)
        assertFalse(guessed)
    }

    @Test fun whatSomethingElseAlreadySaidStands() {
        // The Command Center opened first and found no sign-in: its words stay, with the saved names.
        val (signIn, guessed) = startingWith(NestState(step = NestStep.SIGN_IN, qrUrl = "https://accounts.example/qr"), saved)
        assertEquals(NestStep.SIGN_IN, signIn.step)
        assertEquals("https://accounts.example/qr", signIn.qrUrl)
        assertEquals(saved, signIn.cameras)
        assertFalse(guessed)
        // Listed already: the newer list stays.
        val newer = listOf(saved[0].copy(label = "Front Porch"))
        val (listed, _) = startingWith(NestState(step = NestStep.CONNECTED, cameras = newer), saved)
        assertEquals(newer, listed.cameras)
    }

    @Test fun theSignInOnceReadPutsAGuessRight() {
        val (guess, guessed) = startingWith(NestState(), saved)
        // Still connected: nothing changes.
        assertEquals(NestStep.CONNECTED, signInRead(guess, NestStep.CONNECTED, guessed).step)
        // Signed out elsewhere meanwhile (Google's keys reset, say): it says so.
        assertEquals(NestStep.SIGN_IN, signInRead(guess, NestStep.SIGN_IN, guessed).step)
        assertEquals(NestStep.NEED_GOOGLE, signInRead(guess, NestStep.NEED_GOOGLE, guessed).step)
    }

    @Test fun theSignInReadAtStartDoesntUndoWhatHappenedMeanwhile() {
        // Nothing saved, and a sign-in finished on the phone before the start-up read came back.
        assertEquals(NestStep.CONNECTED, signInRead(NestState(step = NestStep.CONNECTED), NestStep.SIGN_IN, guessed = false).step)
        // The Command Center showing its QR code: it stays.
        assertEquals(NestStep.SIGN_IN, signInRead(NestState(step = NestStep.SIGN_IN), NestStep.CONNECTED, guessed = false).step)
        // Nothing said yet: the read is what shows.
        assertEquals(NestStep.NEED_PROJECT, signInRead(NestState(), NestStep.NEED_PROJECT, guessed = false).step)
    }
}
