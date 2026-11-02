package com.woodruff.tvexplorer.tvs

import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.SignIn
import com.woodruff.tvexplorer.cloud.needsAttention
import com.woodruff.tvexplorer.cloud.signInWords
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One mapping words a sign-in everywhere ([signInWords]): Settings → Accounts, the phone's Connections and the TV
 * health report on both apps. They differ only where the spec says (health-tv.md §3.1): Accounts' "Connect" for
 * Photos and Calendar, where OK connects them, and the report alone saying how a signed-in TV is doing just now.
 */
class SignInWordsTest {
    @Test fun theReportsWordsAreTheOneMappings() {
        for (s in SignInState.entries) for (g in HealthGrant.entries) {
            assertEquals("$s $g", signInWords(g.auth, s.signIn, report = true), s.words(g))
        }
        // Every state of GoogleAuth's has its JSON name, and the names stay what TVs and phones already send.
        for (s in SignIn.entries) assertEquals(s, SignInState.of(s).signIn)
        assertEquals(
            listOf("signedIn", "offline", "unreachable", "expired", "keysRejected", "notSignedIn", "notSetUp"),
            SignInState.entries.map { it.json },
        )
    }

    @Test fun accountsAndTheReportDifferOnlyWherePhotosAndCalendarConnect() {
        for (g in Grant.entries) for (s in SignIn.entries) {
            val accounts = signInWords(g, s)
            val report = signInWords(g, s, report = true)
            val connects = (g == Grant.PHOTOS || g == Grant.CALENDAR) && (s == SignIn.NOT_SIGNED_IN || s == SignIn.NOT_SET_UP)
            if (connects) {
                assertEquals("Connect", accounts)
                assertEquals("Not connected", report)
            } else {
                assertEquals("$g $s", accounts, report)
            }
        }
    }

    @Test fun eachSignInsWords() {
        assertEquals(
            listOf("Signed in", "Sign-in ended", "Keys not accepted", "Not signed in", "Not set up", "Offline", "Can’t reach Google"),
            SignIn.entries.map { signInWords(Grant.DRIVE, it, report = true) },
        )
        assertEquals(
            listOf("Connected", "Sign-in ended", "Keys not accepted", "Not connected", "Not connected", "Offline", "Can’t reach Google"),
            SignIn.entries.map { signInWords(Grant.NEST, it, report = true) },
        )
        assertEquals(
            listOf("Connected", "Sign-in ended", "Keys not accepted", "Connect", "Connect", "Offline", "Can’t reach Google"),
            SignIn.entries.map { signInWords(Grant.PHOTOS, it) },
        )
    }

    @Test fun onlyAnEndingOrRefusedKeysNeedSomeone() {
        for (s in SignInState.entries) assertEquals("$s", s.signIn.needsAttention, s.needsAttention)
        assertEquals(setOf(SignInState.EXPIRED, SignInState.KEYS_REJECTED), SignInState.entries.filter { it.needsAttention }.toSet())
    }
}
