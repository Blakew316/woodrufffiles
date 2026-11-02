package com.woodruff.tvexplorer.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The notice that Google really ended a sign-in: posted once per ending, quietly (Woodruff's own
 * banner only while its main screen is in front, never over the player or other apps, never a
 * sound), with the words of the offline-states spec.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SignInNoticeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val t0 = 1_800_000_000_000L

    @Before fun clean() {
        Notices.load(app)
        Notices.clear(app)
        Notices.dismissLatest()
        Notices.prefs(app).edit().clear().commit()
    }

    @After fun back() { AppVisibility.foreground = false }

    @Test fun eachSignInSaysWhereToSignInAgain() {
        assertEquals(
            "Sign in to Google Drive again" to "Google ended this TV’s sign-in. Open Streaming and scan the code with your phone. Your library and progress are kept.",
            SignInNotice.words(Grant.DRIVE),
        )
        assertEquals("Connect Google Photos again" to "Google ended this TV’s sign-in. Settings → Accounts → Google Photos.", SignInNotice.words(Grant.PHOTOS))
        assertEquals("Connect Google Calendar again" to "Google ended this TV’s sign-in. Settings → Accounts → Google Calendar.", SignInNotice.words(Grant.CALENDAR))
        assertEquals(
            "Connect your cameras again" to "Google ended this TV’s sign-in. Open the Command Center and scan the code with your phone.",
            SignInNotice.words(Grant.NEST),
        )
        // Within the notice card's lines (about 38 characters a line).
        for (g in Grant.entries) assertTrue("$g", SignInNotice.words(g).second.length <= NoticeLook.BODY_LINES * 38)
    }

    @Test fun eachEndingIsToldOnceAndANewOneAgain() {
        assertFalse(SignInNotice.wasTold(null, Grant.DRIVE, t0))
        val told = SignInNotice.withTold(null, Grant.DRIVE, t0)
        assertTrue(SignInNotice.wasTold(told, Grant.DRIVE, t0))
        // Another sign-in's ending, or the same one ending again later, is news.
        assertFalse(SignInNotice.wasTold(told, Grant.NEST, t0))
        assertFalse(SignInNotice.wasTold(told, Grant.DRIVE, t0 + 7 * 86_400_000L))
        // Both kept side by side; a later ending replaces its sign-in's earlier one.
        val both = SignInNotice.withTold(SignInNotice.withTold(told, Grant.NEST, t0 + 5), Grant.DRIVE, t0 + 9)
        assertTrue(SignInNotice.wasTold(both, Grant.NEST, t0 + 5))
        assertTrue(SignInNotice.wasTold(both, Grant.DRIVE, t0 + 9))
        assertFalse(SignInNotice.wasTold(both, Grant.DRIVE, t0))
        // Whatever was stored before can't break it.
        assertFalse(SignInNotice.wasTold("garbage,,DRIVE:x", Grant.DRIVE, t0))
    }

    @Test fun postedOnceQuietlyWhileWoodruffIsAway() {
        AppVisibility.foreground = false
        SignInNotice.ended(app, Grant.DRIVE, t0)
        SignInNotice.ended(app, Grant.DRIVE, t0) // the player and a screen both found out
        val items = Notices.items.value
        assertEquals(1, items.size)
        assertEquals("Sign in to Google Drive again", items[0].title)
        assertEquals(NoticeSource.SYSTEM, items[0].source)
        assertFalse(items[0].read)
        // Nothing pops up: it waits, unread, in Notifications and Home's At a glance.
        assertNull(Notices.latest.value)
        assertNull(items[0].chime)
    }

    @Test fun inWoodruffItShowsInItsOwnBannerWithoutASound() {
        AppVisibility.foreground = true
        SignInNotice.ended(app, Grant.CALENDAR, t0)
        val shown = Notices.latest.value
        assertEquals("Connect Google Calendar again", shown?.title)
        assertNull(shown?.chime)
        // A later network failure doesn't post it again; a later ending does.
        SignInNotice.ended(app, Grant.CALENDAR, t0)
        assertEquals(1, Notices.items.value.size)
        SignInNotice.ended(app, Grant.CALENDAR, t0 + 86_400_000L)
        assertEquals(2, Notices.items.value.size)
    }

    @Test fun aQuietNoticeNeverChimes() {
        AppVisibility.foreground = true
        Notices.post(app, NoticeSource.SYSTEM, "Quiet", "", chime = Chime.Sound.ANNOUNCE, quiet = true)
        assertNull(Notices.items.value.first().chime)
    }
}
