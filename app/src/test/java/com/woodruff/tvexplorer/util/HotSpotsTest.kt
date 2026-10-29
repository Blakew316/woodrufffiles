package com.woodruff.tvexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the Smoothness Report's recording adds up main-thread stack samples and long messages. */
class HotSpotsTest {
    private fun f(cls: String, method: String, file: String?, line: Int) = StackTraceElement(cls, method, file, line)

    // A main-thread stack, top first: Compose measuring a row of Woodruff's Home screen.
    private val measuring = arrayOf(
        f("androidx.compose.ui.node.LayoutNode", "remeasure", "LayoutNode.kt", 1123),
        f("androidx.compose.ui.node.MeasurePassDelegate", "measure", "LayoutNodeLayoutDelegate.kt", 504),
        f("androidx.compose.runtime.ComposerImpl", "doCompose", "Composer.kt", 3000),
        f("com.woodruff.tvexplorer.ui.screens.HomeScreenKt", "PosterRow", "HomeScreen.kt", 812),
        f("com.woodruff.tvexplorer.ui.AppRootKt", "AppRoot", "AppRoot.kt", 50),
        f("android.os.Looper", "loop", "Looper.java", 200),
    )

    private fun texts(s: HotSpots.Spot) = s.frames.map(::frameText)

    @Test fun ourFirstFrameComesWithTheTwoCallsAboveIt() {
        val h = HotSpots().apply { add(measuring) }
        val spot = h.topOurs(30).single()
        assertEquals(1, spot.samples)
        assertEquals(
            listOf("MeasurePassDelegate.measure (LayoutNodeLayoutDelegate.kt:504)", "ComposerImpl.doCompose (Composer.kt:3000)", "HomeScreenKt.PosterRow:812"),
            texts(spot),
        )
    }

    @Test fun theTopOfTheStackIsItsThreeTopFrames() {
        val spot = HotSpots().apply { add(measuring) }.topOfStack(30).single()
        assertEquals(listOf("LayoutNode.remeasure:1123", "MeasurePassDelegate.measure (LayoutNodeLayoutDelegate.kt:504)", "ComposerImpl.doCompose (Composer.kt:3000)"), texts(spot))
    }

    @Test fun ourFrameOnTopStandsAlone() {
        val stack = arrayOf(f("com.woodruff.tvexplorer.photos.Memories", "pick", "Memories.kt", 40), f("android.os.Handler", "dispatchMessage", "Handler.java", 99))
        assertEquals(listOf("Memories.pick:40"), texts(HotSpots().apply { add(stack) }.topOurs(1).single()))
    }

    @Test fun samplesWithoutOurCodeAreCountedAsSuch() {
        val gc = arrayOf(f("dalvik.system.VMRuntime", "trackExternalAllocation", null, -2), f("android.os.Looper", "loop", "Looper.java", 200))
        val spot = HotSpots().apply { add(gc) }.topOurs(1).single()
        assertTrue(spot.frames.isEmpty())
        assertEquals("VMRuntime.trackExternalAllocation (native)", frameText(gc[0]))
    }

    @Test fun theSameSignatureAddsUpAndTheBusiestComesFirst() {
        val other = arrayOf(f("com.woodruff.tvexplorer.ui.screens.SettingsScreenKt", "SettingsList", "SettingsScreen.kt", 9))
        val h = HotSpots()
        repeat(3) { h.add(measuring) }
        h.add(other)
        assertEquals(4, h.samples)
        assertEquals(listOf(3, 1), h.topOurs(30).map { it.samples })
        assertEquals(1, h.topOurs(1).size)
        h.clear()
        assertEquals(0, h.samples)
        assertTrue(h.topOurs(30).isEmpty())
    }

    @Test fun newSignaturesStopAtTheCap() {
        val h = HotSpots(maxKinds = 2)
        for (i in 1..5) h.add(arrayOf(f("com.woodruff.A", "m$i", "A.kt", i)))
        assertEquals(5, h.samples)
        assertEquals(2, h.topOurs(30).size)
    }

    @Test fun choreographerFramesAreOneKind() {
        val a = messageKind(">>>>> Dispatching to Handler (android.view.Choreographer\$FrameHandler) {8b4e8d1} android.view.Choreographer\$FrameDisplayEventReceiver@2bb6f36: 0")
        val b = messageKind(">>>>> Dispatching to Handler (android.view.Choreographer\$FrameHandler) {11aa22} android.view.Choreographer\$FrameDisplayEventReceiver@99ff00: 0")
        assertEquals("Choreographer\$FrameHandler: Choreographer\$FrameDisplayEventReceiver", a)
        assertEquals(a, b)
    }

    @Test fun aMessageWithoutCallbackKeepsItsNumber() {
        assertEquals("ViewRootImpl\$ViewRootHandler #6", messageKind(">>>>> Dispatching to Handler (android.view.ViewRootImpl\$ViewRootHandler) {1a2b3c} null: 6"))
    }

    @Test fun coroutinesSayWhereTheyResume() {
        assertEquals(
            "Handler: DispatchedContinuation[Dispatchers.Main, Continuation at HomeScreenKt\$HomeScreen\$1\$1.invokeSuspend(HomeScreen.kt:120)]",
            messageKind(">>>>> Dispatching to Handler (android.os.Handler) {f00d} DispatchedContinuation[Dispatchers.Main, Continuation at com.woodruff.tvexplorer.ui.screens.HomeScreenKt\$HomeScreen\$1\$1.invokeSuspend(HomeScreen.kt:120)@5f1a2b]: 0"),
        )
    }

    @Test fun aFrameNamesItsFileOnlyWhenItIsNotTheClasses() {
        assertEquals("Choreographer.doFrame:1301", frameText(f("android.view.Choreographer", "doFrame", "Choreographer.java", 1301)))
        assertEquals("AndroidParagraph.<init>:140", frameText(f("androidx.compose.ui.text.AndroidParagraph", "<init>", "AndroidParagraph.android.kt", 140)))
        assertEquals("HomeScreenKt\$HomeScreen\$1.invoke:77", frameText(f("com.woodruff.tvexplorer.ui.screens.HomeScreenKt\$HomeScreen\$1", "invoke", "HomeScreen.kt", 77)))
        assertEquals("ComposerImpl.doCompose (Composer.kt:3000)", frameText(f("androidx.compose.runtime.ComposerImpl", "doCompose", "Composer.kt", 3000)))
    }

    @Test fun framesWithoutLineNumbersStillRead() {
        assertEquals("B.c (?)", frameText(f("a.B", "c", null, -1)))
        assertEquals("B.c (Other.kt)", frameText(f("a.B", "c", "Other.kt", -1)))
        assertEquals("B.c", frameText(f("a.B", "c", "B.kt", -1)))
    }

    @Test fun longWorkAddsUpByKindMostTimeFirst() {
        val w = LongWork()
        w.add("A", 20_000_000)
        w.add("A", 15_000_000)
        w.add("B", 50_000_000)
        val top = w.top(10)
        assertEquals(listOf("B", "A"), top.map { it.name })
        assertEquals(2, top[1].times)
        assertEquals(35_000_000L, top[1].totalNs)
        assertEquals(20_000_000L, top[1].maxNs)
        w.clear()
        assertTrue(w.top(10).isEmpty())
    }
}
