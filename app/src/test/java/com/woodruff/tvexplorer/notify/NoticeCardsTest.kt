package com.woodruff.tvexplorer.notify

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nest.CameraAlert
import com.woodruff.tvexplorer.nest.Shelved
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/**
 * Where a notice's card goes, and when none does: Woodruff's own banner only while its main screen is in front; a
 * doorbell ring's card keeps its time, covered or cut short by nothing but another ring; nothing on a dark screen;
 * and a card over other apps goes when something else is the notice now (the player's own doorbell card, the TV
 * switched to the Apple TV, the ring's camera on screen, the screen going off). What isn't shown is kept, unread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoticeCardsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val over = Notices.overAppleTv
    private val own = Notices.ownScreenUp
    private val screen = Notices.screenOn
    private val camera = NoticeOverlay.cameraUp

    private fun alert(kind: String = "person") = CameraAlert("enterprises/p/devices/front-door", kind, "visit-${System.nanoTime()}", null, System.currentTimeMillis())
    private fun ring() = Notices.post(app, NoticeSource.SYSTEM, "Someone’s at the door", "Front Door", chime = Chime.Sound.DOORBELL, alert = alert("doorbell"))
    private fun person() = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Driveway", alert = alert())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun trayTitles() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .map { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() }

    @Before fun freshStart() {
        Notices.load(app)
        Notices.dismissLatest()
        AppVisibility.foreground = false
        ShadowSettings.setCanDrawOverlays(false)
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @After fun asItWas() {
        Notices.overAppleTv = over
        Notices.ownScreenUp = own
        Notices.screenOn = screen
        NoticeOverlay.cameraUp = camera
        Notices.playerDoorbellCard(false)
        NoticeOverlay.hide()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        ShadowSettings.setCanDrawOverlays(false)
        AppVisibility.foreground = false
        Notices.dismissLatest()
    }

    @Test fun woodruffsOwnBannerShowsOnlyWhileItsMainScreenIsInFront() {
        // Over another app (no pop-ups allowed here: the TV's notification instead), the banner is left alone, so it
        // doesn't come up again later when Woodruff's main screen does, beside or long after the card over the app.
        val n = person()
        assertNull(Notices.latest.value)
        assertTrue("Person seen" in trayTitles())
        // In Woodruff: its banner.
        AppVisibility.foreground = true
        Notices.popUp(app, n)
        assertSame(n, Notices.latest.value)
    }

    @Test fun aRingsCardIsntCutShortByAnotherAlertOnlyByAnotherRing() {
        AppVisibility.foreground = true
        val door = ring()
        assertSame(door, Notices.latest.value)
        // The driveway camera sees the same visitor 3 s later, and mail comes: kept, unread, no card.
        val seen = person()
        val mail = Notices.post(app, NoticeSource.MAIL, "Your order has shipped", "Arriving Thursday")
        assertSame(door, Notices.latest.value)
        assertEquals(listOf(mail.id, seen.id, door.id), Notices.items.value.take(3).map { it.id })
        assertTrue(Notices.items.value.take(3).none { it.read })
        // Another ring takes its place.
        val again = ring()
        assertSame(again, Notices.latest.value)
        // Once the ring's card has gone, cards show again.
        Notices.dismissLatest()
        val next = person()
        assertSame(next, Notices.latest.value)
    }

    @Test fun overTheFilmThePlayersDoorbellCardIsTheOnlyOne() {
        ShadowSettings.setCanDrawOverlays(true)
        // A person seen over the film: its card over the player.
        val seen = person()
        idle()
        assertSame(seen, NoticeOverlay.up)
        // The doorbell: the player's own card comes up, and the one over it goes.
        Notices.playerDoorbellCard(true)
        idle()
        assertNull(NoticeOverlay.up)
        // While it's up, nothing else covers it (kept in Notifications); another ring is the player's to show.
        val mail = Notices.post(app, NoticeSource.MAIL, "Your order has shipped", "Arriving Thursday")
        person()
        idle()
        assertNull(NoticeOverlay.up)
        assertEquals(false, Notices.items.value.first { it.id == mail.id }.read)
        // It went: cards come again.
        Notices.playerDoorbellCard(false)
        val later = person()
        idle()
        assertSame(later, NoticeOverlay.up)
    }

    @Test fun overOtherAppsARingsCardKeepsItsTime() {
        ShadowSettings.setCanDrawOverlays(true)
        val door = ring()
        idle()
        assertSame(door, NoticeOverlay.up)
        person()
        Notices.post(app, NoticeSource.PHONE, "Dinner", "In ten minutes", chime = Chime.Sound.ANNOUNCE)
        idle()
        assertSame(door, NoticeOverlay.up)
        // Its 25 s up, it goes, and the next card shows.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(26))
        assertNull(NoticeOverlay.up)
        val next = person()
        idle()
        assertSame(next, NoticeOverlay.up)
    }

    @Test fun nothingIsDrawnOrChimedOnADarkScreen() {
        Notices.screenOn = { false }
        AppVisibility.foreground = true
        val door = ring()
        val sent = Notices.post(app, NoticeSource.PHONE, "Dinner", "In ten minutes", chime = Chime.Sound.ANNOUNCE)
        assertNull(Notices.latest.value)
        assertTrue(trayTitles().isEmpty())
        assertTrue(Notices.items.value.take(2).map { it.id } == listOf(sent.id, door.id))
        assertTrue(Notices.items.value.take(2).none { it.read })
    }

    @Test fun theScreenGoingOffTakesTheCardsAway() {
        ShadowSettings.setCanDrawOverlays(true)
        person()
        idle()
        assertTrue(NoticeOverlay.up != null)
        AppVisibility.foreground = true
        person()
        Notices.screenWentOff()
        idle()
        assertNull(NoticeOverlay.up)
        assertNull(Notices.latest.value)
    }

    @Test fun aCardUpOverAnotherAppGoesWhenTheTvSwitchesToTheAppleTv() {
        ShadowSettings.setCanDrawOverlays(true)
        Notices.ownScreenUp = { false }
        val door = ring()
        idle()
        assertSame(door, NoticeOverlay.up)
        // Not yet on the Apple TV: it stays.
        Notices.overAppleTv = { false }
        Notices.appleTvOnScreen(app)
        idle()
        assertSame(door, NoticeOverlay.up)
        // Switched to it: it goes (the Apple TV shows the ring itself).
        Notices.overAppleTv = { true }
        Notices.appleTvOnScreen(app)
        idle()
        assertNull(NoticeOverlay.up)
    }

    @Test fun anAnnouncementsCardStaysOverTheAppleTv() {
        ShadowSettings.setCanDrawOverlays(true)
        Notices.ownScreenUp = { false }
        val sent = Notices.post(app, NoticeSource.SYSTEM, "Dinner’s ready!", "", chime = Chime.Sound.ANNOUNCE)
        idle()
        Notices.overAppleTv = { true }
        Notices.appleTvOnScreen(app)
        idle()
        assertSame(sent, NoticeOverlay.up)
    }

    @Test fun aRingsLateCardStaysOffItsCamera() {
        ShadowSettings.setCanDrawOverlays(true)
        // The ring's camera came on screen as the late card came: no card over it.
        NoticeOverlay.cameraUp = { true }
        ring()
        idle()
        assertNull(NoticeOverlay.up)
        // Without a camera up, the card.
        NoticeOverlay.cameraUp = { false }
        val door = ring()
        idle()
        assertSame(door, NoticeOverlay.up)
    }

    @Test fun aDismissedCardStaysDismissedWhenItsPictureComes() {
        AppVisibility.foreground = true
        val a = alert()
        Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Driveway", alert = a)
        Notices.dismissLatest()
        val picture = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Notices.pictureCame(app, Shelved(a, cropped = true, got = System.currentTimeMillis(), picture = picture))
        assertNull(Notices.latest.value)
    }
}
