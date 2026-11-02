package com.woodruff.tvexplorer.phones

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.server.CompanionRoutes
import com.woodruff.tvexplorer.server.answerFor
import com.woodruff.tvexplorer.server.bodyText
import com.woodruff.tvexplorer.server.phoneRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Messages to phones never vanish (finding 43): numbered in turn, read without being taken, kept through a restart, at
 * most the last 20 of the last week per phone, and never shown twice to a phone that asks after the last one it has.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneInboxTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_761_700_000_000L
    private val day = 24 * 3_600_000L

    @After fun clean() { PhoneInbox.forgetLoaded(); File(ctx.filesDir, "phone-inbox.json").delete(); PhoneInbox.forgetLoaded() }

    private fun msg(title: String, at: Long = now) = JSONObject().put("title", title).put("body", "").put("from", "Den").put("at", at)
    private fun titles(a: PhoneInbox.Book.Answer) = a.messages.map { it.getString("title") }

    @Test fun messagesAreNumberedAndReadWithoutBeingTaken() {
        val b = PhoneInbox.Book("e1")
        assertEquals(1L, b.add("p-1", msg("Dinner’s ready"), now))
        assertEquals(2L, b.add("p-1", msg("Movie night at 8"), now))
        val first = b.since("p-1", 0, "e1", now)
        assertEquals(listOf("Dinner’s ready", "Movie night at 8"), titles(first))
        assertEquals(2L, first.seq)
        // The reply never reached the phone: it asks again with the same number and gets the same.
        assertEquals(listOf("Dinner’s ready", "Movie night at 8"), titles(b.since("p-1", 0, "e1", now)))
        // It had them: only what's newer.
        b.add("p-1", msg("Bring the dog in"), now)
        assertEquals(listOf("Bring the dog in"), titles(b.since("p-1", 2, "e1", now)))
        assertEquals(emptyList<String>(), titles(b.since("p-1", 3, "e1", now)))
    }

    @Test fun eachPhoneHasItsOwnAndTheNumbersNeverGoBack() {
        val b = PhoneInbox.Book("e1")
        b.add("p-1", msg("For one"), now)
        b.add("p-2", msg("For two"), now)
        assertEquals(listOf("For one"), titles(b.since("p-1", 0, "e1", now)))
        assertEquals(listOf("For two"), titles(b.since("p-2", 0, "e1", now)))
        // Forgotten and named again: a number it has seen is never given again.
        b.remove("p-1")
        assertEquals(emptyList<String>(), titles(b.since("p-1", 1, "e1", now)))
        b.add("p-1", msg("Welcome back"), now)
        assertEquals(listOf("Welcome back"), titles(b.since("p-1", 1, "e1", now)))
    }

    @Test fun thePhoneAppFromBeforeNumbersGetsEachMessageOnce() {
        val b = PhoneInbox.Book("e1")
        b.add("p-1", msg("Dinner’s ready"), now)
        assertEquals(listOf("Dinner’s ready"), titles(b.since("p-1", null, null, now)))
        assertTrue(b.since("p-1", null, null, now).messages.isEmpty())
        b.add("p-1", msg("Movie night at 8"), now)
        assertEquals(listOf("Movie night at 8"), titles(b.since("p-1", null, null, now)))
        // This version asking for the first time (no inbox name yet) starts there too: nothing comes twice.
        val first = b.since("p-1", 0, "", now)
        assertTrue(first.messages.isEmpty())
        assertEquals(2L, first.seq)
    }

    @Test fun aNumberFromAnotherInboxStartsAgainFromTheFirst() {
        val b = PhoneInbox.Book("new")
        b.add("p-1", msg("After the reset"), now)
        // The phone kept 57 from the TV's inbox before it was reinstalled.
        assertEquals(listOf("After the reset"), titles(b.since("p-1", 57, "old", now)))
    }

    @Test fun aPhoneKeepsTheLastTwentyOfTheLastWeek() {
        val b = PhoneInbox.Book("e1")
        b.add("p-1", msg("Last month", now - 30 * day), now - 30 * day)
        b.add("p-1", msg("Eight days ago", now - 8 * day), now - 8 * day)
        (1..25).forEach { b.add("p-1", msg("Message $it"), now) }
        val all = titles(b.since("p-1", 0, "e1", now))
        assertEquals(20, all.size)
        assertEquals("Message 6", all.first())
        assertEquals("Message 25", all.last())
        // A week later, they've gone too.
        assertTrue(b.since("p-1", 0, "e1", now + 8 * day).messages.isEmpty())
    }

    @Test fun veryLongWordsAreCut() {
        val b = PhoneInbox.Book("e1")
        b.add("p-1", msg("x".repeat(5_000)), now)
        val t = b.since("p-1", 0, "e1", now).messages.single().getString("title")
        assertEquals(PhoneInbox.MAX_TEXT, t.length)
        assertTrue(t.endsWith("…"))
    }

    @Test fun theBookComesBackAsItWas() {
        val b = PhoneInbox.Book("e1")
        b.add("p-1", msg("Dinner’s ready").put("id", "x1").put("link", "#settings/tvs/this"), now)
        b.since("p-1", null, null, now)
        val again = PhoneInbox.Book.fromJson(JSONObject(b.toJson().toString()))
        assertEquals("e1", again.epoch)
        val m = again.since("p-1", 0, "e1", now).messages.single()
        assertEquals("x1", m.getString("id"))
        assertEquals("#settings/tvs/this", m.getString("link"))
        assertEquals(2L, again.add("p-1", msg("Next"), now))
        assertTrue("an old phone app had it", again.since("p-1", null, null, now).messages.single().getString("title") == "Next")
    }

    @Test fun messagesOutliveARestart() {
        PhoneInbox.add(ctx, listOf("p-kitchen-1234"), msg("Dinner’s ready", System.currentTimeMillis()))
        PhoneInbox.settle()
        val before = PhoneInbox.since(ctx, "p-kitchen-1234", 0, null)
        PhoneInbox.forgetLoaded()   // as an update restarts the TV app
        val after = PhoneInbox.since(ctx, "p-kitchen-1234", 0, before.getString("epoch"))
        assertEquals(before.getString("epoch"), after.getString("epoch"))
        assertEquals("Dinner’s ready", after.getJSONArray("messages").getJSONObject(0).getString("title"))
        assertEquals(1L, after.getJSONArray("messages").getJSONObject(0).getLong("seq"))
    }

    @Test fun thePhonesInboxRoute() {
        val routes = CompanionRoutes(ctx)
        PhoneInbox.add(ctx, listOf("p-kitchen-1234"), msg("Dinner’s ready", System.currentTimeMillis()))
        PhoneInbox.settle()
        val first = JSONObject(routes.answerFor(phoneRequest("/api/inbox", params = mapOf("id" to "p-kitchen-1234", "after" to "0", "epoch" to ""))).bodyText())
        assertEquals(1, first.getJSONArray("messages").length())
        val epoch = first.getString("epoch")
        assertNotEquals("", epoch)
        val seq = first.getLong("seq")
        // Asked again after it: nothing new, and nothing was taken.
        val again = JSONObject(routes.answerFor(phoneRequest("/api/inbox", params = mapOf("id" to "p-kitchen-1234", "after" to "$seq", "epoch" to epoch))).bodyText())
        assertEquals(0, again.getJSONArray("messages").length())
        val replay = JSONObject(routes.answerFor(phoneRequest("/api/inbox", params = mapOf("id" to "p-kitchen-1234", "after" to "0", "epoch" to epoch))).bodyText())
        assertEquals(1, replay.getJSONArray("messages").length())
        // A phone that isn't known: nothing, and no error.
        val stranger = JSONObject(routes.answerFor(phoneRequest("/api/inbox", params = mapOf("id" to "p-unknown-1"))).bodyText())
        assertFalse(stranger.getJSONArray("messages").length() > 0)
    }
}
