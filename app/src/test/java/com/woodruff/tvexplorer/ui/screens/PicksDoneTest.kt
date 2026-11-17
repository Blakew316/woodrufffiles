package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.photos.GooglePhotos.Step
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Files and Family Memories list their folder again when a Google Photos pick finishes while they're up, once; never
 * for a pick that finished before they opened (its "done" stays), which listed every folder twice on every visit.
 */
class PicksDoneTest {
    @Test fun onlyAPickFinishingFromNowOnCounts() = runBlocking {
        val steps = MutableStateFlow(Step.DONE)
        var done = 0
        val watching = launch(start = CoroutineStart.UNDISPATCHED) { steps.picksDone().collect { done++ } }
        yield()
        // Done already, from an earlier pick: nothing.
        assertEquals(0, done)
        for (step in listOf(Step.READY, Step.PICKING, Step.DOWNLOADING)) { steps.value = step; yield() }
        assertEquals(0, done)
        // This pick's done: once.
        steps.value = Step.DONE
        yield()
        assertEquals(1, done)
        // The next pick's too.
        for (step in listOf(Step.READY, Step.PICKING, Step.DOWNLOADING, Step.DONE)) { steps.value = step; yield() }
        assertEquals(2, done)
        watching.cancel()
    }
}
