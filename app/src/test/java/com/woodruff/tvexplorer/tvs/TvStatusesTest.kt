package com.woodruff.tvexplorer.tvs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** The phone's TV list: quick answers, the last known status for slow TVs, and "off" for the unknown. */
class TvStatusesTest {
    private val kids = LinkedTv("kids-id", "Kids Room", "http://192.168.1.21:8080", "key1")
    private val bedroom = LinkedTv("bed-id", "Bedroom", "http://192.168.1.22:8080", "key2")
    private val jobs = mutableListOf<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun pending(): Job = Job().also { jobs += it }
    private fun answered(): Job = Job().also { it.complete() }

    @After fun tearDown() {
        jobs.forEach { it.cancel() }
        scope.cancel()
    }

    private fun elapsed(block: suspend () -> Unit): Long = runBlocking {
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / 1_000_000
    }

    /** A linked TV on the network: answers [next] after [delayMs], or fails (asleep) when it's null. */
    private class FakeTv {
        @Volatile var next: TvStatus? = null
        @Volatile var delayMs = 0L
        val asked = AtomicInteger()

        fun ask(tv: LinkedTv): TvStatus {
            asked.incrementAndGet()
            Thread.sleep(delayMs)
            return next ?: throw IOException("No answer")
        }
    }

    @Test fun aTvHeardFromShowsItsLastStatusUnderItsLinkedName() {
        val last = TvStatus("kids-id", "Old name", online = true, onScreen = true, playing = null, version = "2.0.40")
        val list = lastKnown(listOf(kids), mapOf("kids-id" to last))
        assertEquals(listOf(last.copy(name = "Kids Room")), list)
    }

    @Test fun aTvNeverHeardFromIsOff() {
        assertEquals(listOf(TvStatus("bed-id", "Bedroom", online = false, onScreen = false, playing = null)), lastKnown(listOf(bedroom), emptyMap()))
    }

    @Test fun theListFollowsTheLinkedTvs() {
        val known = mapOf(
            "bed-id" to TvStatus("bed-id", "Bedroom", true, false, null),
            "gone-id" to TvStatus("gone-id", "Unlinked", true, false, null),
        )
        assertEquals(listOf("kids-id", "bed-id"), lastKnown(listOf(kids, bedroom), known).map { it.id })
    }

    @Test fun answersInTimeAreNotWaitedOnFurther() {
        val ms = elapsed { awaitAnswers(mapOf("kids-id" to answered(), "bed-id" to answered()), emptySet(), quickMs = 5_000, firstMs = 10_000) }
        assertTrue("took $ms ms", ms < 2_000)
    }

    @Test fun aSlowTvHeardFromBeforeGetsOnlyTheShortWait() {
        val ms = elapsed { awaitAnswers(mapOf("kids-id" to pending(), "bed-id" to answered()), setOf("kids-id"), quickMs = 200, firstMs = 10_000) }
        assertTrue("took $ms ms", ms in 180L until 5_000L)
    }

    @Test fun aTvNeverHeardFromGetsTheLongerWait() {
        val ms = elapsed { awaitAnswers(mapOf("kids-id" to pending()), emptySet(), quickMs = 100, firstMs = 600) }
        assertTrue("took $ms ms", ms in 550L until 5_000L)
    }

    @Test fun theLongerWaitEndsWhenThatTvAnswers() = runBlocking {
        val first = CompletableDeferred<Unit>().also { jobs += it }
        launch { delay(150); first.complete(Unit) }
        val start = System.nanoTime()
        awaitAnswers(mapOf("bed-id" to first), emptySet(), quickMs = 50, firstMs = 10_000)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test fun aTvThatAnswersShowsWhatItSaid() {
        val tv = FakeTv().apply { next = TvStatus("kids-id", "Kids", online = true, onScreen = true, playing = null, version = "2.0.41") }
        val list = runBlocking { StatusChecks(scope, tv::ask).statuses(listOf(kids)) }
        assertEquals(listOf(TvStatus("kids-id", "Kids Room", online = true, onScreen = true, playing = null, version = "2.0.41")), list)
    }

    @Test fun aTvThatFallsAsleepShowsItsLastAnswerUntilItsCheckGivesUp() {
        val tv = FakeTv().apply { next = TvStatus("kids-id", "Kids Room", online = true, onScreen = false, playing = null) }
        val checks = StatusChecks(scope, tv::ask)
        runBlocking { checks.statuses(listOf(kids)) }
        // Asleep: no answer for a second, then the check fails.
        tv.next = null
        tv.delayMs = 1_000
        val ms = elapsed { assertTrue(checks.statuses(listOf(kids), quickMs = 100, firstMs = 5_000).single().online) }
        assertTrue("took $ms ms", ms < 900)
        Thread.sleep(1_300)
        // That check has given up by now: the TV counts as off, still without waiting.
        assertFalse(runBlocking { checks.statuses(listOf(kids), quickMs = 100, firstMs = 5_000) }.single().online)
    }

    @Test fun aTvNeverHeardFromIsOffAfterTheLongerWait() {
        val tv = FakeTv().apply { delayMs = 3_000 }
        val checks = StatusChecks(scope, tv::ask)
        val ms = elapsed { assertFalse(checks.statuses(listOf(bedroom), quickMs = 100, firstMs = 500).single().online) }
        assertTrue("took $ms ms", ms in 450L until 2_500L)
    }

    @Test fun aSecondCallerJoinsTheCheckUnderWay() = runBlocking {
        val tv = FakeTv().apply { next = TvStatus("kids-id", "Kids Room", true, false, null); delayMs = 400 }
        val checks = StatusChecks(scope, tv::ask)
        val a = async { checks.statuses(listOf(kids), quickMs = 50, firstMs = 100) }
        val b = async { checks.statuses(listOf(kids), quickMs = 50, firstMs = 100) }
        a.await(); b.await()
        assertEquals(1, tv.asked.get())
    }
}
