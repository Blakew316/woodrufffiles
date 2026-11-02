package com.woodruff.tvexplorer.tvs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A TV keeps who it is through a Keystore that loses its key (finding 55), and a pinned message
 * shows once on each TV however often its hub sends it (finding 50).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TvIdentityTest {
    private val c: Context = ApplicationProvider.getApplicationContext()

    @Before fun fresh() {
        listOf("tvs", "secrets", "board").forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun aTvsIdIsKeptInPlainSettings() {
        val id = TvLinks.id(c)
        assertEquals(id, TvLinks.id(c))
        assertEquals(id, c.getSharedPreferences("tvs", Context.MODE_PRIVATE).getString("id", null))
    }

    @Test fun anIdTheKeystoreCantReadYetIsNeverReplacedForGood() {
        // Saved by an earlier version, encrypted, and the Keystore isn't ready (as on some TVs at power-on).
        c.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString("tvs.id", "c2VjcmV0").commit()
        val interim = TvLinks.id(c)
        assertNull(c.getSharedPreferences("tvs", Context.MODE_PRIVATE).getString("id", null))
        assertEquals(interim, TvLinks.id(c)) // the same for the whole run
    }

    @Test fun aLinkKeyTheKeystoreCantReadYetIsNeverReplaced() {
        // Saved, encrypted, and the Keystore isn't ready: no hub's key is taken (it goes on to the pairing-key check,
        // without an error), and no new key is made, which would turn down every hub for good ("Link again").
        val secrets = c.getSharedPreferences("secrets", Context.MODE_PRIVATE)
        secrets.edit().putString("tvs.linkkey", "c2VjcmV0").commit()
        assertFalse(TvLinks.isLinkKey(c, "c2VjcmV0"))
        assertFalse(TvLinks.isLinkKey(c, null))
        assertEquals("c2VjcmV0", secrets.getString("tvs.linkkey", null))
    }

    @Test fun aCodeIsntUsedUpWhileTheLinkKeyCantBeRead() {
        c.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString("tvs.linkkey", "c2VjcmV0").commit()
        val code = TvLinks.newCode()
        try {
            // The hub hears it didn't work; the code shown on screen still does once the key can be read.
            assertThrows(java.io.IOException::class.java) { TvLinks.redeem(c, code) }
            assertThrows(java.io.IOException::class.java) { TvLinks.redeem(c, code) } // not "wrong or expired" (null)
        } finally {
            TvLinks.cancelCode()
        }
        assertNull(TvLinks.redeem(c, code))
    }

    @Test fun aTvsNameIsKeptInPlainSettingsToo() {
        assertNull(TvLinks.givenName(c))
        TvLinks.setName(c, "  Den  ")
        assertEquals("Den", TvLinks.givenName(c))
        assertEquals("Den", TvLinks.name(c))
        TvLinks.setName(c, " ")
        assertNull(TvLinks.givenName(c))
        assertTrue(TvLinks.name(c).endsWith(" TV"))
    }

    @Test fun aPinnedMessageShowsOnceWhateverHowOftenItComes() {
        assertEquals(0L, Board.seenAt(c))
        assertTrue(Board.receive(c, "Chores before screens", "", "Living Room", at = 1_000L))
        assertEquals(1_000L, Board.seenAt(c))
        assertEquals(1_000L, Board.pin.value!!.at)
        // Dismissed here: the hub sending it again (it was told, or an older time) brings nothing back.
        Board.dismiss(c)
        assertFalse(Board.receive(c, "Chores before screens", "", "Living Room", at = 1_000L))
        assertNull(Board.pin.value)
        assertFalse(Board.receive(c, "Older", "", null, at = 500L))
        // A newer one shows.
        assertTrue(Board.receive(c, "Movie night", "Friday", null, at = 2_000L))
        assertEquals("Movie night", Board.pin.value!!.title)
        assertNotEquals(0L, Board.seenAt(c))
    }

    @Test fun aPinFromAPhoneIsTheHubsToCatchUp() {
        Board.pin(c, "Dinner at 6", "", "Living Room", relay = false)
        val shown = Board.pin.value!!
        assertEquals(shown.at, Board.seenAt(c))
        // Relayed by a hub after: older than what this TV has shown, so not shown again.
        assertFalse(Board.receive(c, "Dinner at 6", "", null, at = shown.at - 10))
    }
}
