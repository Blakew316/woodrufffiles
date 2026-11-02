package com.woodruff.tvexplorer.tvs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A TV keeps who it is through a Keystore that loses its key (finding 55), and a pinned message
 * shows once on each TV however often its hub sends it (finding 50). Its id, name and link key while
 * the Keystore is still waking after a power cut: TvLinksKeystoreTest.
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
