package com.woodruff.tvexplorer.nest

import com.woodruff.tvexplorer.cloud.GoogleAuth
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Opening the Command Center's cameras while their sign-in waits for Google's answer leaves it to finish: this TV's
 * code on show, or one the phone started. Only with none waiting does it make a new code (a new one refuses the old).
 */
class CameraSignInWaitingTest {
    private val tvCode = GoogleAuth.Attempt("tv", null, "https://nestservices.google.com/partnerconnections/p/auth?state=tv", GoogleAuth.Grant.NEST)
    private val phone = GoogleAuth.Attempt("phone", null, "https://nestservices.google.com/partnerconnections/p/auth?state=phone", GoogleAuth.Grant.NEST, fromPhone = true)

    @Test fun theCodeOnShowStaysWhileItsAnswerStillComesHere() {
        assertSame(tvCode, cameraSignInWaiting(listOf(tvCode), tvCode.authUrl, answeredHere = { true }, phoneWaiting = { false }))
    }

    @Test fun aCodeForAnAddressThisTvNoLongerHasIsMadeAgain() {
        assertNull(cameraSignInWaiting(listOf(tvCode), tvCode.authUrl, answeredHere = { false }, phoneWaiting = { false }))
    }

    @Test fun aSignInThePhoneStartedIsLeftToFinish() {
        assertSame(phone, cameraSignInWaiting(listOf(phone), null, answeredHere = { true }, phoneWaiting = { it === phone }))
        // Even with an older code of this TV's on show: the phone's replaced it.
        assertSame(phone, cameraSignInWaiting(listOf(phone), tvCode.authUrl, answeredHere = { true }, phoneWaiting = { it === phone }))
    }

    @Test fun withNothingWaitingANewCodeIsMade() {
        assertNull(cameraSignInWaiting(emptyList(), null, answeredHere = { true }, phoneWaiting = { true }))
        // The phone stopped waiting (out of time): the TV shows its own code.
        assertNull(cameraSignInWaiting(listOf(phone), null, answeredHere = { true }, phoneWaiting = { false }))
        // Nothing on show yet (this TV's code was never put up): a new one.
        assertNull(cameraSignInWaiting(listOf(tvCode), null, answeredHere = { true }, phoneWaiting = { false }))
    }
}
