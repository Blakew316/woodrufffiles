package com.woodruff.tvexplorer.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A doorbell ring whose camera opens is kept in Notifications, but no card covers the camera. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoticesPopUpTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun aNoticeWithoutACardIsKeptButNotShown() {
        Notices.load(app)
        Notices.dismissLatest()
        val n = Notices.post(app, NoticeSource.SYSTEM, "Someone's at the door", "Front Door", chime = Chime.Sound.DOORBELL, popUp = false)
        assertSame(n, Notices.items.value.first())
        assertNull(Notices.latest.value)
        // The camera couldn't open after all: its card, late.
        Notices.popUp(app, n)
        assertSame(n, Notices.latest.value)
        Notices.dismissLatest()
    }

    @Test fun nothingIsDrawnOverTheAppleTvButAMessageFromAPhone() {
        Notices.load(app)
        Notices.dismissLatest()
        val before = Notices.overAppleTv
        Notices.overAppleTv = { true }
        try {
            // A camera's news: kept, unread, and no card.
            val seen = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard")
            assertSame(seen, Notices.items.value.first())
            assertEquals(false, seen.read)
            assertNull(Notices.latest.value)
            // The doorbell too, and without its chime.
            Notices.post(app, NoticeSource.SYSTEM, "Someone's at the door", "Front Door", chime = Chime.Sound.DOORBELL)
            assertNull(Notices.latest.value)
            // What a person sends this TV still shows.
            val sent = Notices.post(app, NoticeSource.PHONE, "Dinner", "In ten minutes", chime = Chime.Sound.ANNOUNCE)
            assertSame(sent, Notices.latest.value)
        } finally {
            Notices.overAppleTv = before
            Notices.dismissLatest()
        }
    }

    @Test fun anOrdinaryNoticeShowsItsCard() {
        Notices.load(app)
        val n = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard")
        assertEquals("Person seen", Notices.latest.value?.title)
        assertSame(n, Notices.latest.value)
        Notices.dismissLatest()
    }
}
