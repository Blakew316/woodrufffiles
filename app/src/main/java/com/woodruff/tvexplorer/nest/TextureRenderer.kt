package com.woodruff.tvexplorer.nest

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import com.woodruff.tvexplorer.notify.AppVisibility
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.EglThread
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.Executors

/**
 * A live camera view drawn into a TextureView, for small tiles: unlike a SurfaceView (which draws
 * in its own layer, outside the app's clipping) it takes the tile's rounded corners, and frames
 * are cropped to the view's shape so the picture fills the tile instead of sitting letterboxed
 * inside it. The full-screen camera keeps the SurfaceView (cheaper, and shows the whole frame).
 *
 * Each frame a TextureView shows redraws the app's window on the main thread, so a tile shows few:
 * 15 a second when it has the focus, 5 otherwise, and none just after a remote press (the picture
 * holds, so moving the focus gets the main thread). The tiles without the focus all draw on one
 * shared beat ([BeatGate]), so their redraws tend to land together rather than each at its own
 * moment. Its GL work runs on the tiles' shared GL thread ([CameraGl]): nothing here waits for GL
 * on the main thread.
 */
class TextureRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val egl = EglRenderer("camera-tile")
    /** Let go of: set up after that (in the background), it would never be released. */
    @Volatile private var released = false
    /** The tile has the focus: it plays smoothest, on the renderer's own clock. */
    @Volatile private var focused = false
    /** Without the focus, its frames go through on the beat every tile shares. */
    private val beat = BeatGate()

    init {
        // Without the focus to start with: the shared beat sets its pace, not the renderer.
        egl.disableFpsReduction()
        surfaceTextureListener = this
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Set up in the background, on the shared GL thread; frames that come before then are skipped.
        CameraGl.later { if (!released) CameraGl.attach(egl) }
    }

    /** The tile with the focus plays smoothest; the others at a third of that, together on the shared beat. */
    fun setFocused(focused: Boolean) {
        this.focused = focused
        if (focused) egl.setFpsReduction(tileFps(focused = true)) else egl.disableFpsReduction()
    }

    override fun onFrame(frame: VideoFrame) {
        if (released || holdsPicture(System.currentTimeMillis(), AppVisibility.lastInput)) return
        if (!focused && !beat.pass(SystemClock.uptimeMillis())) return
        egl.onFrame(frame)
    }

    /** A small copy of the next frame (for the still shown when the tile isn't streaming). */
    fun addFrameListener(listener: EglRenderer.FrameListener, scale: Float) = egl.addFrameListener(listener, scale)

    /**
     * [drawn] runs (on the GL thread) once this view next draws a frame into its picture: one it skips
     * (held after a press, off the shared beat) or can't draw yet (no surface) doesn't count, and no
     * copy of it is made. Asked for on the background thread after its GL side is set up there (in
     * order), as a renderer not yet set up would drop the request.
     */
    fun afterNextFrame(drawn: () -> Unit) = CameraGl.later {
        if (!released) egl.addFrameListener({ drawn() }, 0f, null, true)
    }

    /** Clears the picture. */
    fun clearImage() = egl.clearImage()

    /**
     * Lets go of the GL side, in the background (it waits for the GL thread to clean up). Detach the
     * view from its stream first. Main thread.
     */
    fun release() {
        if (released) return
        released = true
        CameraGl.later { egl.release() }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        fill(width, height)
        egl.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fill(width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Not waited for here: the GL thread lets go of the surface first, then it's released in the
        // background (false: by this, not the view), after any clean-up of this view queued there.
        egl.releaseEglSurface { CameraGl.later { surface.release() } }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    /** Frames are cropped to the view's shape: the picture fills it, nothing stretched. */
    private fun fill(width: Int, height: Int) = egl.setLayoutAspectRatio(if (width > 0 && height > 0) width.toFloat() / height else 0f)
}

/** Frames a second a camera tile shows: each one redraws the window on the main thread. */
internal fun tileFps(focused: Boolean): Float = if (focused) 15f else 5f

/** The beat the tiles without the focus share: 5 a second, as [tileFps] says. */
internal const val TILE_BEAT_MS = 200L

/**
 * Lets a tile's frames through on the beat all tiles share (the uptime clock in [beatMs] steps): the
 * first frame in each beat, no other. Each tile limiting its frames on a clock of its own drew at
 * moments unrelated to the others', so four tiles meant up to 20 separate redraws of the whole window
 * a second; on one beat they land close together and mostly share a redraw (finding 32). It only ever
 * lets fewer frames through, never delays one: no frame is held back, so nothing can show late or out
 * of order. One decoder thread feeds a tile at a time.
 */
internal class BeatGate(private val beatMs: Long = TILE_BEAT_MS) {
    @Volatile private var last = Long.MIN_VALUE

    /** Whether the frame arriving at [uptimeMs] goes through. */
    fun pass(uptimeMs: Long): Boolean {
        val b = Math.floorDiv(uptimeMs, beatMs)
        if (b == last) return false
        last = b
        return true
    }
}

/** How long after a remote press the tiles hold their picture. */
internal const val HOLD_AFTER_PRESS_MS = 350L

/** Whether a tile skips the frame arriving [now], the remote last pressed at [lastInput] (both ms). */
internal fun holdsPicture(now: Long, lastInput: Long): Boolean = now - lastInput in 0 until HOLD_AFTER_PRESS_MS

/**
 * The camera views' GL work that held up the main thread. Each tile had a GL thread of its own:
 * making one waits for it to start and set up GL, and letting one go waits for its GL clean-up,
 * four times over on every visit to Home or the Command center. Now every tile draws on one shared
 * GL thread, made once in the background (as soon as cameras are connected and one of Woodruff's
 * screens is up) and kept while it's used; once Woodruff has been off screen a while with no tile
 * left on it, it's let go ([trim], util/LeanMemory) and made again with the next tile. The views are
 * set up and let go on a background thread, in the order asked.
 */
internal object CameraGl {
    private const val TAG = "CameraGl"

    // As WebRTC's plain config, and able to make the small off-screen surface ([keeper]) as well as
    // the tiles' window ones.
    private val CONFIG = intArrayOf(
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_NONE,
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-views").apply { isDaemon = true } }

    // Only touched on [worker].
    private var thread: EglThread? = null
    private var keeper: EglBase? = null
    private var keep: Keep? = null
    private var failed = false
    /** Tiles drawing on the shared thread now: counted up as each starts on it, back down as each lets go ([Keep]). */
    private val tiles = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Cameras are connected: the shared thread is made now, in the background, before a tile needs it.
     * Only with one of Woodruff's screens up: at a start with none (the TV turning on, an update) it
     * would sit there for nobody. A tile makes it anyway if it isn't there.
     */
    fun warm() {
        if (com.woodruff.tvexplorer.util.LeanMemory.onScreen) later { shared() }
    }

    /**
     * Woodruff has been off screen a while: the shared thread and its GL context go, unless a tile
     * still draws on it. The next tile makes them again. In the background, after what was asked before.
     */
    fun trim() = later {
        val t = thread ?: return@later
        if (tiles.get() > 0) return@later
        val off = keeper
        thread = null
        keeper = null
        // Its small off-screen surface goes on the thread it was made on, then the thread itself.
        runCatching { ThreadUtils.invokeAtFrontUninterruptibly(t.handler, Runnable { off?.release() }) }
            .onFailure { Log.w(TAG, "keeper", it) }
        keep?.forever = false
        keep = null
        t.release()
    }

    /** Runs [task] on the background thread, after everything asked before it. */
    fun later(task: () -> Unit) = worker.execute {
        try { task() } catch (e: Exception) { Log.w(TAG, "camera view", e) }
    }

    /** On the background thread: starts [egl] on the shared thread (or one of its own, on a TV that can't share). */
    fun attach(egl: EglRenderer) {
        val shared = shared()
        val keep = keeper
        if (shared != null && keep != null) {
            // Counted before it starts: whatever happens as it does, its release counts it back off
            // ([Keep]), so the thread is never let go under a tile (at worst it's kept).
            tiles.incrementAndGet()
            egl.init(shared, TileDrawer(keep), false)
        } else {
            egl.init(Rtc.egl.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        }
    }

    /** The shared thread, made now if need be; null if this TV can't make it (tiles then get one each, as before). */
    private fun shared(): EglThread? {
        thread?.let { return it }
        if (failed) return null
        val keep = Keep()
        var made: EglThread? = null
        var off: EglBase? = null
        return try {
            val t = EglThread.create(keep, Rtc.egl.eglBaseContext, CONFIG).also { made = it }
            val k = t.createEglBaseWithSharedConnection().also { off = it }
            ThreadUtils.invokeAtFrontUninterruptibly(t.handler, Runnable { k.createDummyPbufferSurface() })
            keep.forever = true
            this.keep = keep
            keeper = k
            thread = t
            t
        } catch (e: RuntimeException) {
            Log.w(TAG, "No shared GL thread for the camera tiles", e)
            failed = true
            off?.release()
            made?.release()
            null
        }
    }

    /**
     * Keeps the shared thread once it's set up: every tile's release asks to end it, and is counted off
     * [tiles] instead. Only [trim] (or a thread that couldn't be set up) ends it.
     */
    private class Keep : EglThread.ReleaseMonitor {
        @Volatile var forever = false
        override fun onRelease(eglThread: EglThread): Boolean {
            if (!forever) return true
            tiles.updateAndGet { (it - 1).coerceAtLeast(0) }
            return false
        }
    }

    /**
     * A tile's drawer on the shared thread. A tile's GL clean-up starts with its drawer's, which here
     * first makes the shared context current on [keeper]'s small off-screen surface: by then the
     * tile's own surface is usually gone, and GL clean-up with no context current does nothing, so
     * the context (kept for good) would collect every tile's leftovers.
     */
    private class TileDrawer(private val keeper: EglBase, private val drawer: GlRectDrawer = GlRectDrawer()) :
        RendererCommon.GlDrawer by drawer {
        override fun release() {
            runCatching {
                keeper.makeCurrent()
                GLES20.glUseProgram(0)
            }.onFailure { Log.w(TAG, "keeper", it) }
            drawer.release()
        }
    }
}
