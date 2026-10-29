package com.woodruff.tvexplorer.nest

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.VideoSink

/**
 * The cameras' live streams, one per camera, shared by every view of it: Home's tiles, the camera
 * grid, full screen and the doorbell over a film. Moving between them hands the running stream over
 * instead of stopping it and asking Google for a new one, so the picture is there at once and Google
 * (which allows only a few camera requests a minute) sees far fewer.
 *
 * A stream no view shows stays up [LINGER_MS] in case the next screen shows it, then closes. It
 * closes at once when Woodruff leaves the screen (a film, another app, the screensaver). Main thread
 * only. Every WebRTC call stays in [CameraStream]; this only decides when a stream starts and stops.
 */
object CameraStreams {
    /** How long a stream no view shows stays up for the next screen. */
    const val LINGER_MS = 10_000L

    /** One view showing a camera; give it back with [release]. */
    class Hold internal constructor(internal val camera: String, val stream: CameraStream, internal val sink: VideoSink, internal var audio: Boolean) {
        internal var released = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = StreamPool<CameraStream>(
        close = { s -> runCatching { s.close() }.onFailure { Log.w("CameraStreams", "close", it) } },
        later = { ms, action ->
            val r = Runnable { action() }
            main.postDelayed(r, ms)
            val cancel: () -> Unit = { main.removeCallbacks(r) }
            cancel
        },
    )
    private var watching = false

    /** Shows [camera] in [sink]: joins its running stream, or starts one. [audio]: play its sound. */
    fun acquire(context: Context, camera: Camera, sink: VideoSink, audio: Boolean): Hold {
        watchScreens(context)
        val app = context.applicationContext
        val stream = pool.acquire(camera.name) { CameraStream(app, camera).also { it.start() } }
        stream.attach(sink, audio)
        return Hold(camera.name, stream, sink, audio)
    }

    /**
     * The view is done with its camera. [linger]: it's off to another screen, so the stream stays up a
     * moment in case that one shows the camera too; otherwise it closes now unless another view shows it.
     */
    fun release(hold: Hold, linger: Boolean) {
        if (hold.released) return
        hold.released = true
        // Detached first: once that returns nothing draws into the view, which is released next.
        hold.stream.detach(hold.sink)
        pool.release(hold.camera, hold.stream, if (linger) LINGER_MS else 0L)
    }

    /** Sound on or off for this view (the stream plays it while any view wants it). */
    fun setAudio(hold: Hold, audio: Boolean) {
        if (hold.released || hold.audio == audio) return
        hold.audio = audio
        hold.stream.attach(hold.sink, audio)
    }

    /** Closes every stream no view shows right now ("one at a time" moving on, Woodruff leaving the screen). */
    fun closeIdle() = pool.closeIdle()

    /**
     * Any of Woodruff's screens stopping (a film starting, another app, the TV going to sleep) closes
     * what's lingering at once. Views on that screen let go themselves, as it stops.
     */
    private fun watchScreens(context: Context) {
        if (watching) return
        val app = context.applicationContext as? Application ?: return
        watching = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) { closeIdle() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/**
 * Who uses which stream, and when one nobody uses closes: [CameraStreams]' bookkeeping, apart from
 * Android and WebRTC so it can be tested. [later] runs an action after a delay and returns how to
 * cancel it. Not thread-safe: one thread (the main thread) uses it.
 */
internal class StreamPool<S : Any>(
    private val close: (S) -> Unit,
    private val later: (ms: Long, action: () -> Unit) -> (() -> Unit),
) {
    private class Entry<T>(val stream: T) {
        var users = 0
        var cancelClose: (() -> Unit)? = null
    }

    private val entries = HashMap<String, Entry<S>>()

    /** The running stream for [key] (no longer closing), or a new one from [open]. */
    fun acquire(key: String, open: () -> S): S {
        val e = entries.getOrPut(key) { Entry(open()) }
        e.cancelClose?.invoke()
        e.cancelClose = null
        e.users++
        return e.stream
    }

    /**
     * One user of [stream] is done. After the last, it closes in [lingerMs] unless used again by then
     * (at once for 0). A stream that has already closed (or been replaced) is left alone.
     */
    fun release(key: String, stream: S, lingerMs: Long) {
        val e = entries[key]?.takeIf { it.stream === stream && it.users > 0 } ?: return
        if (--e.users > 0) return
        e.cancelClose?.invoke()
        e.cancelClose = null
        if (lingerMs <= 0) shut(key, e)
        else e.cancelClose = later(lingerMs) { if (entries[key] === e && e.users == 0) shut(key, e) }
    }

    /** Closes every stream nobody uses right now. */
    fun closeIdle() {
        entries.filterValues { it.users == 0 }.forEach { (key, e) -> shut(key, e) }
    }

    /** The streams running now, by key. */
    fun running(): Set<String> = entries.keys.toSet()

    private fun shut(key: String, e: Entry<S>) {
        e.cancelClose?.invoke()
        e.cancelClose = null
        if (entries[key] === e) entries.remove(key)
        close(e.stream)
    }
}
