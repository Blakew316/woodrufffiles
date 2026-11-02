package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Drive's error answers say on screen: never a code or Google's own words, and a setting to fix
 * (the Drive API off for the keys, Google Drive not allowed) says what to do rather than "can't reach",
 * which would have the family wait for something that never comes.
 */
class DriveAnswersTest {
    @Test fun aFileThatsGoneSaysSo() {
        assertEquals("That file is no longer in Google Drive.", DriveApi.answerWords(404, "notFound"))
    }

    @Test fun busyOrFailingIsCantReach() {
        assertEquals(DriveApi.CANT_REACH, DriveApi.answerWords(503, null))
        assertEquals(DriveApi.CANT_REACH, DriveApi.answerWords(500, "backendError"))
        assertEquals(DriveApi.CANT_REACH, DriveApi.answerWords(429, "rateLimitExceeded"))
        assertEquals(DriveApi.CANT_REACH, DriveApi.answerWords(403, "userRateLimitExceeded"))
        assertEquals(DriveApi.CANT_REACH, DriveApi.answerWords(401, "authError"))
        // A refusal Google gave no reason for: as before.
        assertEquals(DriveApi.CANT_REACH, DriveApi.answerWords(403, null))
    }

    @Test fun theDriveApiTurnedOffSaysWhereToTurnItOn() {
        val words = DriveApi.answerWords(403, "accessNotConfigured")
        assertNotEquals(DriveApi.CANT_REACH, words)
        assertTrue(words.contains("Google Drive API"))
        assertTrue(words.contains("Google Cloud"))
        assertEquals(words, DriveApi.answerWords(403, "SERVICE_DISABLED"))
    }

    @Test fun googleDriveNotAllowedSaysToSignInAgain() {
        val words = DriveApi.answerWords(403, "insufficientPermissions")
        assertNotEquals(DriveApi.CANT_REACH, words)
        assertTrue(words.contains("sign in again"))
        assertEquals(words, DriveApi.answerWords(403, "ACCESS_TOKEN_SCOPE_INSUFFICIENT"))
    }

    @Test fun noAnswerUsesCodesOrBannedWords() {
        val banned = listOf("error", "failed", "invalid", "unavailable", "retry", "please", "403", "401", "404")
        val answers = listOf(404 to "notFound", 403 to "accessNotConfigured", 403 to "insufficientPermissions", 503 to null)
            .map { (code, reason) -> DriveApi.answerWords(code, reason) }
        for (w in answers) for (b in banned) assertFalse("\"$w\" says \"$b\"", w.lowercase().contains(b))
    }

    @Test fun onlyBusyOrFailingAnswersAreTriedAgainByThemselves() {
        // A setting to fix isn't "unreachable": it shows its words instead of retrying for ever.
        assertFalse(DriveHttpException(403, DriveApi.answerWords(403, "accessNotConfigured")).unreachable)
        assertTrue(DriveHttpException(403, DriveApi.CANT_REACH, busy = true).unreachable)
        assertTrue(DriveHttpException(503, DriveApi.CANT_REACH).unreachable)
    }
}
