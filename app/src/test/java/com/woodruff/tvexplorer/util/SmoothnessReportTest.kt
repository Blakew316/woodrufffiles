package com.woodruff.tvexplorer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wording of the phone's Smoothness Report, from known numbers. */
class SmoothnessReportTest {
    private val ms = 1_000_000L
    private val deadline = 16_666_667L

    private fun phases(wait: Long = 0, anim: Long = 0, draw: Long = 0, gpu: Long = -1) = LongArray(Phase.COUNT).also {
        it[Phase.WAITING] = wait
        it[Phase.ANIMATION] = anim
        it[Phase.DRAW] = draw
        it[Phase.GPU] = gpu
    }

    /** 100 navigating frames: 70 on time, 20 late, 9 slow, 1 stutter; every late one 5 ms waiting, 4 animating, 2 drawing, 3 on the GPU. */
    private fun home() = ScreenFrames("Home").apply {
        val late = phases(wait = 5 * ms, anim = 4 * ms, draw = 2 * ms, gpu = 3 * ms)
        repeat(70) { navigating.add(10 * ms, deadline, phases(gpu = 1 * ms)) }
        repeat(20) { navigating.add(20 * ms, deadline, late) }
        repeat(9) { navigating.add(30 * ms, deadline, late) }
        navigating.add(60 * ms, deadline, late)
    }

    /** Navigating frames, none late; and video frames by themselves. */
    private fun cameras() = ScreenFrames("Cameras").apply {
        repeat(80) { navigating.add(8 * ms, deadline, phases()) }
        repeat(200) { idle.add(12 * ms, deadline, phases()) }
    }

    private fun keys() = Durations().apply {
        repeat(80) { add(3 * ms) }
        repeat(15) { add(18 * ms) }
        repeat(5) { add(74 * ms) }
    }

    private val ours = HotSpots.Spot(
        listOf(
            StackTraceElement("androidx.compose.ui.node.LayoutNode", "remeasure", "LayoutNode.kt", 1123),
            StackTraceElement("androidx.compose.runtime.ComposerImpl", "doCompose", "Composer.kt", 3000),
            StackTraceElement("com.woodruff.tvexplorer.ui.screens.HomeScreenKt", "PosterRow", "HomeScreen.kt", 812),
        ),
        52,
    )

    private fun data(
        recording: Recording,
        screens: List<ScreenFrames> = listOf(cameras(), home()),
        keys: Durations = keys(),
        fromRecording: Boolean = false,
        perFrame: Boolean = true,
        gc: LongArray? = longArrayOf(14, 212, 2, 61),
        during: Boolean = true,
        samples: Int = 341,
    ) = SmoothnessReport.Data(
        made = "Oct 29, 2:05 PM",
        facts = listOf("App 2.0.143 (build 143) · up 38 min", "Android 12 (API 31)"),
        recording = recording,
        frames = CountedFrames(screens, keys, countedMs = 19_000, fromRecording = fromRecording),
        frameIntervalNs = deadline,
        deadlinePerFrame = perFrame,
        hotOurs = if (samples > 0) listOf(ours) else emptyList(),
        hotTop = if (samples > 0) listOf(HotSpots.Spot(emptyList(), 7)) else emptyList(),
        samples = samples,
        longWork = listOf(LongWork.Kind("Choreographer\$FrameHandler: Choreographer\$FrameDisplayEventReceiver", 41, 1_230 * ms, 96 * ms)),
        gc = gc,
        gcDuringRecording = during,
    )

    private fun assertLines(report: String, vararg lines: String) {
        val all = report.lines()
        for (l in lines) assertTrue("missing line: \"$l\"\nin:\n$report", l in all)
    }

    @Test fun aRunningRecordingShowsEverythingSoFar() {
        val r = SmoothnessReport.format(data(Recording(Recording.RUNNING, leftMs = 161_000, agoMs = 0, lengthMs = 19_000)))
        assertTrue(r.startsWith("Woodruff Family · smoothness report\nMade Oct 29, 2:05 PM\nApp 2.0.143 (build 143) · up 38 min\nAndroid 12 (API 31)\n"))
        assertLines(
            r,
            "Recording: running, 2:41 left",
            "Frames below: since it started (0:19).",
            "Late: past its deadline (set by Android).",
            " GPU    the graphics chip's own time",
            // Home first: it has the late navigating frames.
            "== Home ==",
            "Navigating: 100 frames",
            " late 30% · slow 10% · 1 stutter",
            " ms p50 10 · p90 20 · p99 30 · max 60",
            " late: wait 5.0 · input 0.0 · anim 4.0",
            "  layout 0.0 · draw 2.0 · sync 0.0",
            "  gfx 0.0 · RT 0.0 · GPU 3.0",
            "Idle: none",
            "== Cameras ==",
            "Navigating: 80 frames",
            " late 0% · slow 0% · no stutters",
            " late: none",
            "Idle: 200 frames",
            "100 presses (and held repeats) handled",
            " ms p50 3 · p90 18 · p99 74 · max 74",
            " over 16 ms: 20 · over 50 ms: 5",
            "5 ms: 341 samples ≈ 1,705 ms.",
            "1. 52 samples ≈ 260 ms",
            "   LayoutNode.remeasure:1123",
            "   HomeScreenKt.PosterRow:812",
            "   (no Woodruff code on the stack)",
            "1. 41 times, 1,230 ms, longest 96 ms",
            "   Choreographer\$FrameHandler: Choreographer\$FrameDisplayEventReceiver",
            "== Garbage collection (during the recording) ==",
            "14 collections, 212 ms",
            "2 blocking, 61 ms",
        )
        assertTrue(r.indexOf("== Home ==") < r.indexOf("== Cameras =="))
    }

    @Test fun withoutARecordingItSaysHowToMakeOne() {
        val r = SmoothnessReport.format(
            data(Recording(Recording.NONE, 0, 0, 0), screens = emptyList(), keys = Durations(), during = false, samples = 0).let {
                SmoothnessReport.Data(it.made, it.facts, it.recording, CountedFrames(emptyList(), Durations(), 12 * 60_000L, false),
                    it.frameIntervalNs, it.deadlinePerFrame, emptyList(), emptyList(), 0, emptyList(), it.gc, false)
            },
        )
        assertLines(
            r,
            "Recording: none yet. Press Record (or OK on the TV's Smoothness row) to record 3 minutes in detail.",
            "Frames below: counted for 12 min.",
            "No frames yet: open Woodruff on the TV and move around.",
            "None handled yet.",
            "Not recorded yet: press Record.",
            "== Garbage collection (since the app started) ==",
        )
        assertFalse(r.contains("samples"))
    }

    @Test fun aFinishedRecordingOnOlderAndroid() {
        val r = SmoothnessReport.format(
            data(Recording(Recording.FINISHED, leftMs = 0, agoMs = 6 * 60_000L + 5_000, lengthMs = 180_000), fromRecording = true, perFrame = false, gc = null),
        )
        assertLines(
            r,
            "Recording: finished 6 min ago (3:00 long)",
            "Frames below: during that recording.",
            "Late: over one refresh (16.7 ms).",
            "Not available on this Android.",
        )
        assertFalse(r.contains("GPU    the graphics chip"))
    }

    @Test fun aRunningRecordingWithNothingLongYet() {
        val r = SmoothnessReport.format(data(Recording(Recording.RUNNING, 170_000, 0, 10_000), samples = 0).let {
            SmoothnessReport.Data(it.made, it.facts, it.recording, it.frames, it.frameIntervalNs, it.deadlinePerFrame, emptyList(), emptyList(), 0, emptyList(), it.gc, true)
        })
        assertTrue(r.contains("== Hot spots ==\nNone yet.\n"))
        assertTrue(r.contains("(over 12 ms) ==\nNone yet.\n"))
    }

    @Test fun durationsReadNaturally() {
        assertTrue(SmoothnessReport.since(30_000) == "under a minute")
        assertTrue(SmoothnessReport.since(6 * 60_000L) == "6 min")
        assertTrue(SmoothnessReport.since(125 * 60_000L) == "2 h 5 min")
    }
}
