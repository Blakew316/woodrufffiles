package com.woodruff.tvexplorer.util

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.profileinstaller.ProfileVerifier
import com.woodruff.tvexplorer.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The smoothness report the phone app shows (Settings › TVs › Smoothness Report), to find out why
 * moving around isn't smooth on a particular TV: the TV and the app, each screen's frames while
 * navigating and while idle, how long remote keys take, and from the last recording the main
 * thread's hot spots, its long work by kind and garbage collection. Plain text in short lines, so
 * it reads on a phone and pastes into a message.
 */
object SmoothnessReport {
    /** Hot spots listed in each list, and kinds of long work. */
    private const val TOP_SPOTS = 30
    private const val TOP_WORK = 20

    /** Everything the report says, gathered; [format] words it (kept apart from Android, for tests). */
    class Data(
        val made: String,
        val facts: List<String>,
        val recording: Recording,
        val frames: CountedFrames,
        /** The display's refresh interval (ns): the deadline before Android 12. */
        val frameIntervalNs: Long,
        /** Android 12 and up: each frame carries its own deadline. */
        val deadlinePerFrame: Boolean,
        val hotOurs: List<HotSpots.Spot>,
        val hotTop: List<HotSpots.Spot>,
        val samples: Int,
        val longWork: List<LongWork.Kind>,
        /** Collections, their ms, blocking ones, their ms; null where Android doesn't say. */
        val gc: LongArray?,
        val gcDuringRecording: Boolean,
    )

    /** The report as it stands. Off the main thread: it waits briefly for Android's compile status. */
    fun build(c: Context): String {
        val now = SystemClock.elapsedRealtime()
        val (gc, gcDuring) = MainThreadWatch.gc()
        return format(
            Data(
                made = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date()),
                facts = facts(c),
                recording = MainThreadWatch.state(now),
                frames = FrameStats.counted(),
                frameIntervalNs = FrameStats.frameIntervalNs,
                deadlinePerFrame = Build.VERSION.SDK_INT >= 31,
                hotOurs = MainThreadWatch.hotSpots.topOurs(TOP_SPOTS),
                hotTop = MainThreadWatch.hotSpots.topOfStack(TOP_SPOTS),
                samples = MainThreadWatch.hotSpots.samples,
                longWork = MainThreadWatch.longWork.top(TOP_WORK),
                gc = gc,
                gcDuringRecording = gcDuring,
            ),
        )
    }

    internal fun format(d: Data): String = buildString {
        fun line(s: String = "") = append(s).append('\n')
        line("Woodruff Family · smoothness report")
        line("Made ${d.made}")
        d.facts.forEach { line(it) }

        line()
        val r = d.recording
        line(
            when {
                r.running -> "Recording: running, ${clock(r.leftMs)} left"
                r.finished -> "Recording: finished ${since(r.agoMs)} ago (${clock(r.lengthMs)} long)"
                else -> "Recording: none yet. Press Record (or OK on the TV's Smoothness row) to record 3 minutes in detail."
            },
        )
        val f = d.frames
        line(
            when {
                f.fromRecording -> "Frames below: during that recording."
                r.running -> "Frames below: since it started (${clock(f.countedMs)})."
                else -> "Frames below: counted for ${since(f.countedMs)}."
            },
        )
        line("Navigating: drawn within 1 s of a remote key.")
        line("Idle: the rest (video, animations alone).")
        line(
            if (d.deadlinePerFrame) "Late: past its deadline (set by Android)."
            else "Late: over one refresh (${ms1(d.frameIntervalNs / 1e6)} ms)."
        )
        line("Slow: over $SLOW_MS ms. Stutter: over $STUTTER_MS ms.")
        line("Late frames, average ms in each phase:")
        line(" wait   waiting to start (main thread busy)")
        line(" input  input batched into the frame")
        line(" anim   animation and composition")
        line(" layout measure and layout")
        line(" draw   recording the drawing")
        line(" sync   uploading pictures")
        line(" gfx    issuing commands, swapping buffers")
        line(" RT     queued behind the render thread")
        if (d.deadlinePerFrame) line(" GPU    the graphics chip's own time")

        val screens = f.screens.sortedWith(
            compareByDescending<ScreenFrames> { it.navigating.late }.thenByDescending { it.navigating.frames }.thenByDescending { it.idle.frames },
        )
        if (screens.isEmpty()) {
            line()
            line("No frames yet: open Woodruff on the TV and move around.")
        }
        for (s in screens) {
            line()
            line("== ${s.name} ==")
            tally(this, "Navigating", s.navigating)
            tally(this, "Idle", s.idle)
        }

        line()
        line("== Remote keys ==")
        val k = f.keys
        if (k.count == 0) line("None handled yet.")
        else {
            line("${count(k.count)} presses (and held repeats) handled")
            line(" ms p50 ${k.percentileMs(0.5)} · p90 ${k.percentileMs(0.9)} · p99 ${k.percentileMs(0.99)} · max ${k.maxNs / 1_000_000}")
            line(" over 16 ms: ${count(k.over(16))} · over 50 ms: ${count(k.over(50))}")
            line(" (A key's time shows as \"wait\" in the next frame.)")
        }

        line()
        line("== Hot spots ==")
        when {
            r.state == Recording.NONE -> line("Not recorded yet: press Record.")
            d.samples == 0 -> line(if (r.running) "None yet." else "None: the main thread never stayed busy over ${MainThreadWatch.LONG_MS} ms.")
            else -> {
                line("Main thread busy over ${MainThreadWatch.LONG_MS} ms, sampled every")
                line("${MainThreadWatch.SAMPLE_MS} ms: ${count(d.samples)} samples ≈ ${sampleMs(d.samples)}.")
                line("By Woodruff code (with the 2 calls above it):")
                spots(this, d.hotOurs)
                line("By top of stack:")
                spots(this, d.hotTop)
            }
        }

        line()
        line("== Long main-thread work (over ${MainThreadWatch.LONG_MS} ms) ==")
        when {
            r.state == Recording.NONE -> line("Not recorded yet: press Record.")
            d.longWork.isEmpty() -> line(if (r.running) "None yet." else "None.")
            else -> d.longWork.forEachIndexed { i, w ->
                line("${i + 1}. ${count(w.times)} ${if (w.times == 1) "time" else "times"}, ${count(w.totalNs / 1_000_000)} ms, longest ${w.maxNs / 1_000_000} ms")
                line("   ${w.name}")
            }
        }

        line()
        line("== Garbage collection (${if (d.gcDuringRecording) "during the recording" else "since the app started"}) ==")
        val gc = d.gc
        if (gc == null) line("Not available on this Android.")
        else {
            line("${count(gc[0])} collections, ${count(gc[1])} ms")
            line("${count(gc[2])} blocking, ${count(gc[3])} ms")
        }
    }

    private fun tally(out: StringBuilder, kind: String, t: FrameTally) {
        if (t.frames == 0) {
            out.append("$kind: none\n")
            return
        }
        val stutters = when (t.stutters) { 0 -> "no stutters"; 1 -> "1 stutter"; else -> "${count(t.stutters)} stutters" }
        out.append("$kind: ${count(t.frames)} frames\n")
        out.append(" late ${t.late * 100 / t.frames}% · slow ${t.slow * 100 / t.frames}% · $stutters\n")
        val d = t.totals
        out.append(" ms p50 ${d.percentileMs(0.5)} · p90 ${d.percentileMs(0.9)} · p99 ${d.percentileMs(0.99)} · max ${d.maxNs / 1_000_000}\n")
        if (t.late == 0) {
            out.append(" late: none\n")
            return
        }
        val parts = (0 until Phase.COUNT).mapNotNull { p ->
            t.lateAverageMs(p).takeIf { !it.isNaN() }?.let { "${Phase.SHORT[p]} ${ms1(it)}" }
        }
        parts.chunked(3).forEachIndexed { i, row -> out.append(if (i == 0) " late: " else "  ").append(row.joinToString(" · ")).append('\n') }
    }

    private fun spots(out: StringBuilder, list: List<HotSpots.Spot>) {
        list.forEachIndexed { i, s ->
            out.append("${i + 1}. ${count(s.samples)} ${if (s.samples == 1) "sample" else "samples"} ≈ ${sampleMs(s.samples)}\n")
            if (s.frames.isEmpty()) out.append("   (no Woodruff code on the stack)\n")
            s.frames.forEach { out.append("   ").append(frameText(it)).append('\n') }
        }
    }

    private fun sampleMs(samples: Int): String {
        val ms = samples * MainThreadWatch.SAMPLE_MS
        return if (ms < 10_000) "${count(ms)} ms" else "${ms1(ms / 1000.0)} s"
    }

    private fun count(n: Number): String = String.format(Locale.US, "%,d", n.toLong())
    private fun ms1(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** "6 min", "under a minute", "2 h 5 min". */
    internal fun since(ms: Long): String = when {
        ms < 60_000 -> "under a minute"
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "${ms / 3_600_000} h ${ms / 60_000 % 60} min"
    }

    // ---- This TV and this app --------------------------------------------------------------------

    private fun facts(c: Context): List<String> = buildList {
        val up = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        add("App ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · up ${since(up)}")
        add("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("CPU: " + safe { cpu() })
        add(safe { memory(c) })
        add(safe { heap() })
        addAll(runCatching { display(c) }.getOrElse { listOf("Display: unknown") })
        add("Animation scales: " + safe { scales(c) })
        add("Graphics: " + safe { graphics() })
        add("App code: " + safe { appCode() })
    }

    private fun safe(read: () -> String): String = runCatching(read).getOrElse { "unknown (${it.javaClass.simpleName})" }

    /** "4 cores, 4 × 1.80 GHz · MediaTek MT5895": its cores and their top speeds, if Android lets us read them. */
    private fun cpu(): String {
        val online = Runtime.getRuntime().availableProcessors()
        val all = File("/sys/devices/system/cpu").list()?.count { it.matches(CPU_DIR) } ?: 0
        val cores = maxOf(all, online)
        val khz = (0 until cores).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val speeds = khz.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }
            .joinToString(" + ") { (k, n) -> "$n × " + String.format(Locale.US, "%.2f GHz", k / 1e6) }
        val chip = (if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL) else listOf(Build.HARDWARE))
            .filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
        return "$cores cores" + (if (online < cores) " ($online online)" else "") +
            (if (speeds.isNotEmpty()) ", $speeds" else ", top speed unreadable") + (if (chip.isNotEmpty()) " · $chip" else "")
    }

    private val CPU_DIR = Regex("cpu[0-9]+")

    private fun memory(c: Context): String {
        val am = c.getSystemService(ActivityManager::class.java) ?: return "RAM: unknown"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return "RAM: ${gb(mi.totalMem)}, ${gb(mi.availMem)} free · low-RAM: ${if (am.isLowRamDevice) "yes" else "no"}"
    }

    private fun heap(): String {
        val rt = Runtime.getRuntime()
        return "Java heap: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB used of ${rt.maxMemory() / 1_048_576} MB"
    }

    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)

    /**
     * "Display: 3840×2160 @ 60 Hz (app draws 1920×1080)", then the other modes it supports, by resolution.
     * Many TVs draw apps at 1080p on a 4K panel; what the app draws at is what the graphics chip fills.
     */
    private fun display(c: Context): List<String> {
        val d = c.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return listOf("Display: unknown")
        val mode = d.mode
        val dm = c.resources.displayMetrics
        val draws = maxOf(dm.widthPixels, dm.heightPixels) to minOf(dm.widthPixels, dm.heightPixels)
        val drawn = if (draws.first == mode.physicalWidth && draws.second == mode.physicalHeight) "" else " (app draws ${draws.first}×${draws.second})"
        val lines = mutableListOf("Display: ${mode.physicalWidth}×${mode.physicalHeight} @ ${num(mode.refreshRate)} Hz$drawn")
        d.supportedModes.filter { it.modeId != mode.modeId }
            .groupBy { it.physicalWidth to it.physicalHeight }.entries
            .sortedByDescending { it.key.first * it.key.second }
            .forEachIndexed { i, (size, modes) ->
                val rates = modes.map { it.refreshRate }.distinct().sortedDescending().joinToString(", ") { num(it) }
                lines += (if (i == 0) " also: " else "       ") + "${size.first}×${size.second} @ $rates Hz"
            }
        return lines
    }

    private fun scales(c: Context): String {
        fun scale(name: String): String {
            val v = Settings.Global.getFloat(c.contentResolver, name, 1f)
            return if (v == 0f) "off" else num(v) + "×"
        }
        return "window ${scale(Settings.Global.WINDOW_ANIMATION_SCALE)}, transition ${scale(Settings.Global.TRANSITION_ANIMATION_SCALE)}, " +
            "animator ${scale(Settings.Global.ANIMATOR_DURATION_SCALE)}"
    }

    /**
     * "ARM · repaints only what changed": who made the graphics driver, and whether it lets Android repaint
     * just the part of the screen that changed (EGL_EXT_buffer_age or EGL_KHR_partial_update). Without that,
     * every frame repaints the whole screen however little moved, and a glide costs as much as a new page.
     * The display is the one Android already draws with: it's only asked, never let go of.
     */
    private fun graphics(): String {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return "unknown"
        val vendor = EGL14.eglQueryString(display, EGL14.EGL_VENDOR)?.trim().orEmpty().ifEmpty { "unknown driver" }
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty().split(' ').toSet()
        val partial = "EGL_EXT_buffer_age" in extensions || "EGL_KHR_partial_update" in extensions
        return "$vendor · " + if (partial) "repaints only what changed" else "repaints the whole screen every frame"
    }

    /** 60, 59.94, 0.5: no more decimals than it has (two at most). */
    private fun num(v: Float): String =
        if (abs(v - v.roundToInt()) < 0.005f) v.roundToInt().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /**
     * Whether Android has compiled the app's code (its speed profile), as Settings › Advanced › App code
     * says: from profileinstaller's own check, waited for briefly.
     */
    private fun appCode(): String {
        if (Build.VERSION.SDK_INT < 28) return "not supported (Android 9 and up)"
        val status = runCatching { ProfileVerifier.getCompilationStatusAsync().get(3, TimeUnit.SECONDS) }.getOrNull() ?: return "not known yet"
        return when (val code = status.profileInstallResultCode) {
            ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE,
            ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING -> "compiled for speed"
            ProfileVerifier.CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION -> "waiting for the TV to compile it"
            ProfileVerifier.CompilationStatus.RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION -> "not supported"
            else -> "not installed (code $code)"
        }
    }
}
