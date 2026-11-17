package com.woodruff.tvexplorer.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nest.CameraAlert
import com.woodruff.tvexplorer.nowplaying.AppleTvScreen
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A doorbell ring whose camera opens is kept in Notifications, but no card covers the camera; and no camera alert or
 * mail of this TV's is drawn over the Apple TV, but everything is over Woodruff's own screens. Woodruff's main screen
 * is in front here: its banner ([Notices.latest]) is the card.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoticesPopUpTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val over = Notices.overAppleTv
    private val own = Notices.ownScreenUp

    private fun alert(kind: String = "person") = CameraAlert("enterprises/p/devices/backyard", kind, "visit-${System.nanoTime()}", null, System.currentTimeMillis())

    @Before fun woodruffInFront() {
        Notices.load(app)
        Notices.dismissLatest()
        AppVisibility.foreground = true
    }

    @After fun asItWas() {
        Notices.overAppleTv = over
        Notices.ownScreenUp = own
        AppleTvScreen.forget()
        AppVisibility.foreground = false
        Notices.dismissLatest()
    }

    @Test fun aNoticeWithoutACardIsKeptButNotShown() {
        val n = Notices.post(app, NoticeSource.SYSTEM, "Someone's at the door", "Front Door", chime = Chime.Sound.DOORBELL, popUp = false, alert = alert("doorbell"))
        assertSame(n, Notices.items.value.first())
        assertNull(Notices.latest.value)
        // The camera couldn't open after all: its card, late.
        Notices.popUp(app, n)
        assertSame(n, Notices.latest.value)
    }

    @Test fun noCameraAlertOrMailIsDrawnOverTheAppleTvButWhatPeopleSendIs() {
        Notices.overAppleTv = { true }
        Notices.ownScreenUp = { false }
        // A camera's news: kept, unread, and no card.
        val seen = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard", alert = alert())
        assertSame(seen, Notices.items.value.first())
        assertEquals(false, seen.read)
        assertNull(Notices.latest.value)
        // The doorbell too, and without its chime; and mail.
        Notices.post(app, NoticeSource.SYSTEM, "Someone's at the door", "Front Door", chime = Chime.Sound.DOORBELL, alert = alert("doorbell"))
        Notices.post(app, NoticeSource.MAIL, "Your order has shipped", "Arriving Thursday")
        assertNull(Notices.latest.value)
        // What a person sends this TV still shows: from a phone, and an announcement typed on another TV.
        val sent = Notices.post(app, NoticeSource.PHONE, "Dinner", "In ten minutes", chime = Chime.Sound.ANNOUNCE)
        assertSame(sent, Notices.latest.value)
        Notices.dismissLatest()
        val announced = Notices.post(app, NoticeSource.SYSTEM, "Dinner’s ready!", "", chime = Chime.Sound.ANNOUNCE)
        assertSame(announced, Notices.latest.value)
    }

    @Test fun nothingIsDrawnWhileWoodruffOnTheAppleTvSaysItsOnScreen() {
        Notices.ownScreenUp = { false }
        NowPlaying.ownPlayer = null
        // It says so (POST /api/appletv); what plays on this TV can't be seen (no Notification access in tests).
        NowPlaying.appleTvSaid(app, true, 25, from = "apple-tv-1")
        val seen = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard", alert = alert())
        assertSame(seen, Notices.items.value.first())
        assertEquals(false, seen.read)
        assertNull(Notices.latest.value)
        // What a person sends this TV still shows.
        val sent = Notices.post(app, NoticeSource.PHONE, "Dinner", "In ten minutes", chime = Chime.Sound.ANNOUNCE)
        assertSame(sent, Notices.latest.value)
        Notices.dismissLatest()
        // It left the screen: cards show again.
        NowPlaying.appleTvSaid(app, false, from = "apple-tv-1")
        val next = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Driveway", alert = alert())
        assertSame(next, Notices.latest.value)
    }

    @Test fun overWoodruffsOwnScreensAndItsScreensaverEverythingShowsAsBefore() {
        // The Apple TV says it's on its screen, but this TV shows Woodruff (its photo viewer, slideshow, screensaver…).
        Notices.overAppleTv = { true }
        Notices.ownScreenUp = { true }
        val seen = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard", alert = alert())
        assertSame(seen, Notices.latest.value)
        assertEquals(false, Notices.onAppleTvNow(app))
    }

    @Test fun anOrdinaryNoticeShowsItsCard() {
        val n = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard", alert = alert())
        assertEquals("Person seen", Notices.latest.value?.title)
        assertSame(n, Notices.latest.value)
    }
}
