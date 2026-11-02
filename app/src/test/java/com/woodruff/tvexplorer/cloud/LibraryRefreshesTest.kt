package com.woodruff.tvexplorer.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * One refresh per library at a time, shared by whoever asks: Home's warm-up, the screen and the
 * other tab no longer list the same library twice at once, and leaving it doesn't cancel the listing.
 */
class LibraryRefreshesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshes = LibraryRefreshes<String>(scope)

    @After fun stop() = scope.cancel()

    @Test fun askingWhileOneIsUnderWayJoinsIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val first = refreshes.listing("Movies") { runs.incrementAndGet(); gate.await(); Fetch.Done }
        val second = refreshes.listing("Movies") { runs.incrementAndGet(); Fetch.Done }
        assertSame(first, second)
        gate.complete(Unit)
        assertEquals(Fetch.Done, withTimeout(5_000) { second.await() })
        assertEquals(1, runs.get())
    }

    @Test fun eachLibraryHasItsOwn() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val movies = refreshes.listing("Movies") { gate.await(); Fetch.Done }
        val shows = refreshes.listing("TV Shows") { Fetch.Offline }
        assertNotSame(movies, shows)
        assertEquals(Fetch.Offline, withTimeout(5_000) { shows.await() })
        assertTrue(movies.isActive)
        gate.complete(Unit)
        assertEquals(Fetch.Done, withTimeout(5_000) { movies.await() })
    }

    @Test fun onceDoneTheNextAskListsAgain() = runBlocking {
        val first = refreshes.listing("Movies") { Fetch.Offline }
        assertEquals(Fetch.Offline, withTimeout(5_000) { first.await() })
        val second = refreshes.listing("Movies") { Fetch.Done }
        assertNotSame(first, second)
        assertEquals(Fetch.Done, withTimeout(5_000) { second.await() })
    }

    @Test fun theOneWhoAskedLeavingDoesNotStopIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val refresh = refreshes.listing("Movies") { gate.await(); Fetch.Done }
        // The screen waiting on it goes away (another tab chosen, a show opened).
        val screen = launch { refresh.await() }
        screen.cancel()
        gate.complete(Unit)
        assertEquals(Fetch.Done, withTimeout(5_000) { refresh.await() })
    }

    @Test fun aTmdbPassWaitsForTheListingUnderWayThenRuns() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(ArrayList<String>())
        val listing = refreshes.listing("Movies") { gate.await(); order += "listing"; Fetch.Done }
        val pass = refreshes.tmdbPass("Movies") { order += "tmdb"; Fetch.Done }
        assertNotSame(listing, pass)
        assertTrue(order.isEmpty())
        gate.complete(Unit)
        withTimeout(5_000) { pass.await() }
        assertEquals(listOf("listing", "tmdb"), order)
    }

    @Test fun aListingAskedForDuringATmdbPassRunsAfterIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(ArrayList<String>())
        val pass = refreshes.tmdbPass("Movies") { gate.await(); order += "tmdb"; Fetch.Done }
        val listing = refreshes.listing("Movies") { order += "listing"; Fetch.Done }
        assertNotSame(pass, listing)
        // And a third asker joins that listing rather than a third run.
        assertSame(listing, refreshes.listing("Movies") { order += "again"; Fetch.Done })
        gate.complete(Unit)
        withTimeout(5_000) { listing.await() }
        assertEquals(listOf("tmdb", "listing"), order)
    }

    @Test fun signingOutStopsThemAll() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val movies = refreshes.listing("Movies") { never.await(); Fetch.Done }
        val shows = refreshes.listing("TV Shows") { never.await(); Fetch.Done }
        refreshes.cancelAll()
        withTimeout(5_000) { movies.join(); shows.join() }
        assertTrue(movies.isCancelled && shows.isCancelled)
        // A new account's listing is a new one.
        val next = refreshes.listing("Movies") { Fetch.Done }
        assertFalse(next.isCancelled)
        assertEquals(Fetch.Done, withTimeout(5_000) { next.await() })
    }

    @Test fun freshForFiveMinutes() {
        val listed = 1_000_000_000L
        assertTrue(isFresh(listed, listed))
        assertTrue(isFresh(listed, listed + FRESH_MS - 1))
        assertFalse(isFresh(listed, listed + FRESH_MS))
        assertFalse(isFresh(null, listed))
        // The TV's clock went back (set after a power cut): stale, not fresh for ever.
        assertFalse(isFresh(listed, listed - 60_000))
    }
}
