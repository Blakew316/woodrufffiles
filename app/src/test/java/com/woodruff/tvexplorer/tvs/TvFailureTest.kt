package com.woodruff.tvexplorer.tvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A linked TV that answers "no" passes on its reason, and isn't mistaken for a TV that moved. */
class TvFailureTest {
    @Test fun aReasonFromTheTvIsPassedOn() {
        val e = failure(400, "Netflix isn't installed on this TV\n")
        assertTrue(e is TvLinks.Refused)
        assertEquals("Netflix isn't installed on this TV", e.message)
    }

    @Test fun aBareBadRequestIsJustUnreachable() {
        assertFalse(failure(400, null) is TvLinks.Refused)
        assertFalse(failure(400, "  ") is TvLinks.Refused)
    }

    @Test fun otherAnswersMayBeAnotherDevice() {
        // 403: a wrong link key, likely another device now at that address. 404/500: maybe not Woodruff at all.
        for (code in listOf(401, 403, 404, 500)) {
            val e = failure(code, "Forbidden")
            assertFalse(e is TvLinks.Refused)
            assertEquals("HTTP $code", e.message)
        }
    }

    @Test fun longReasonsAreCut() {
        assertEquals(200, failure(400, "x".repeat(5000)).message!!.length)
    }
}
