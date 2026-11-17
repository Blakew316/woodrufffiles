package com.woodruff.tvexplorer.nest

import com.woodruff.tvexplorer.cloud.GoogleAuth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening the Command Center's cameras while their sign-in waits for Google's answer leaves it to finish: this TV's
 * code on show, or one the phone started. Only with none waiting does it make a new code (a new one refuses the old).
 */
class CameraSignInWaitingTest {
    private val tvCode = GoogleAuth.Attempt("tv", null, "https://nestservices.google.com/partnerconnections/p/auth?state=tv", GoogleAuth.Grant.NEST)
    private val phone = GoogleAuth.Attempt("phone", null, "https://nestservices.google.com/partnerconnections/p/auth?state=phone", GoogleAuth.Grant.NEST, fromPhone = true)

    @Test fun theCodeOnShowStaysWhileItsAnswerStillComesHere() {
        assertSame(tvCode, cameraSignInWaiting(listOf(tvCode), tvCode.authUrl, stillGood = { true }, phoneWaiting = { false }))
    }

    @Test fun aCodeThatNoLongerWorksIsMadeAgain() {
        // For an address this TV no longer has, or made with other Google keys or another cameras project.
        assertNull(cameraSignInWaiting(listOf(tvCode), tvCode.authUrl, stillGood = { false }, phoneWaiting = { false }))
    }

    @Test fun aSignInThePhoneStartedIsLeftToFinish() {
        assertSame(phone, cameraSignInWaiting(listOf(phone), null, stillGood = { true }, phoneWaiting = { it === phone }))
        // Even with an older code of this TV's on show: the phone's replaced it.
        assertSame(phone, cameraSignInWaiting(listOf(phone), tvCode.authUrl, stillGood = { true }, phoneWaiting = { it === phone }))
    }

    @Test fun aCodeWorksOnlyWithTheKeysAndProjectItWasMadeWith() {
        val url = "https://nestservices.google.com/partnerconnections/1a2b-3c4d/auth?client_id=123-abc.apps.googleusercontent.com" +
            "&redirect_uri=https%3A%2F%2Fexample.github.io%2Frelay&response_type=code&state=eyJy"
        assertTrue(cameraSignInMadeWith(url, "123-abc.apps.googleusercontent.com", "1a2b-3c4d"))
        // Other Google keys, or another cameras project, saved since: a new code.
        assertFalse(cameraSignInMadeWith(url, "456-def.apps.googleusercontent.com", "1a2b-3c4d"))
        assertFalse(cameraSignInMadeWith(url, "123-abc.apps.googleusercontent.com", "9z8y-7x6w"))
        // Keys or a project that can't be read just now: a new code (it says what's missing).
        assertFalse(cameraSignInMadeWith(url, null, "1a2b-3c4d"))
        assertFalse(cameraSignInMadeWith(url, "123-abc.apps.googleusercontent.com", null))
        assertFalse(cameraSignInMadeWith("not a url", "123-abc.apps.googleusercontent.com", "1a2b-3c4d"))
    }

    @Test fun withNothingWaitingANewCodeIsMade() {
        assertNull(cameraSignInWaiting(emptyList(), null, stillGood = { true }, phoneWaiting = { true }))
        // The phone stopped waiting (out of time): the TV shows its own code.
        assertNull(cameraSignInWaiting(listOf(phone), null, stillGood = { true }, phoneWaiting = { false }))
        // Nothing on show yet (this TV's code was never put up): a new one.
        assertNull(cameraSignInWaiting(listOf(tvCode), null, stillGood = { true }, phoneWaiting = { false }))
    }
}
