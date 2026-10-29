package com.woodruff.tvexplorer.screensaver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Full-screen photo slideshow with a clock, shared by the system screensaver ([SlideshowDream])
 * and the in-app one ([SlideshowActivity]). Photos keep their own aspect ratio (no cropping) and
 * cross-fade. Plain Views rather than Compose, because a DreamService isn't a lifecycle owner.
 */
@SuppressLint("ViewConstructor")
class SlideshowView(context: Context) : FrameLayout(context) {
    // The app's image loader and one shared thread: a new loader (with its own memory cache) and
    // a new thread on every slideshow start added up on a TV that's left on for days.
    private val loader = coil.Coil.imageLoader(context)
    private val main = Handler(Looper.getMainLooper())
    private val io = sharedIo
    private var front = image()
    private var back = image()
    private val clock = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 34f
        setShadowLayer(12f, 0f, 2f, Color.BLACK)
        setPadding(64, 0, 0, 48)
    }
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
        setPadding(96, 0, 96, 0)
    }
    private var photos: List<File> = emptyList()
    private var index = 0
    private var request: Disposable? = null
    /** The next photo, decoded while the current one shows, so it fades in right on its turn. */
    private var ready: Pair<File, Drawable>? = null
    /** Its turn came before it was decoded: it shows the moment it is. */
    private var waiting = false
    /** The photo on screen. */
    private var shown: File? = null
    /** Photos in a row that couldn't be read. */
    private var misses = 0
    private var running = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(back, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        back.alpha = 0f
    }

    private fun image() = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("h:mm  ·  EEEE, MMMM d", Locale.getDefault()).format(Date())
            main.postDelayed(this, 15_000)
        }
    }

    private val advance = object : Runnable {
        override fun run() {
            showNext()
            main.postDelayed(this, Appearance.seconds * 1000L)
        }
    }

    // Decodes the next photo unless one is ready or on its way. After a fade it waits for the fade
    // to finish, so the two don't compete for the TV's CPU.
    private val preloadNext = Runnable { if (ready == null && request == null) preload() }

    /** Show only today's "On this day" photos. */
    var memoriesOnly = false

    fun start() {
        if (running) return
        running = true
        misses = 0
        Appearance.load(context)
        main.post(tick)
        message.text = "Loading photos…"
        // Walking a large photo folder can take a moment; don't block the UI thread.
        io.execute {
            val all = Appearance.photos(context)
            // "On this day" photos first (or only them, when opened from Home's Memories).
            val memories = runCatching { com.woodruff.tvexplorer.photos.Memories.forToday(context) { all } }.getOrDefault(emptyList())
            val found = if (memoriesOnly) memories else memories + (all - memories.toSet())
            main.post {
                if (!running) return@post
                photos = found
                message.text = if (found.isEmpty()) {
                    "No photos yet.\n\nSend photos from your phone (Woodruff → Files), then pick their folder in Settings → Screensaver."
                } else ""
                if (found.isNotEmpty()) main.post(advance)
            }
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        request?.dispose()
        request = null
        ready = null
        waiting = false
    }

    private fun showNext() {
        if (photos.isEmpty() || single()) return
        val next = ready
        if (next == null) {
            waiting = true
            if (request == null) preload()
            return
        }
        ready = null
        fadeIn(next.first, next.second)
    }

    /** Only one photo, and it's up: it just stays (nothing to change to, nothing to decode again). */
    private fun single() = photos.size == 1 && shown == photos[0]

    private fun preload() {
        if (!running || photos.isEmpty() || single()) return
        val file = photos[index % photos.size]
        index++
        request?.dispose()
        request = loader.enqueue(
            ImageRequest.Builder(context)
                .data(file)
                .size(1920, 1080)
                // Kept out of the app's image cache: each photo shows once a round, and there it only
                // pushed out the Movies and TV Shows posters, which then had to be decoded again.
                // The next photo waits in [ready] instead.
                .memoryCachePolicy(CachePolicy.DISABLED)
                .target(
                    onSuccess = { d: Drawable -> loaded(file, d) },
                    onError = { _ -> failed() },
                )
                .build(),
        )
    }

    private fun loaded(file: File, d: Drawable) {
        request = null
        misses = 0
        if (!running) return
        if (waiting) {
            waiting = false
            fadeIn(file, d)
        } else {
            ready = file to d
        }
    }

    private fun failed() {
        request = null
        // Skip a photo that can't be read; after a whole round of them, wait for the next turn.
        if (running && ++misses < photos.size) main.post(preloadNext)
    }

    private fun fadeIn(file: File, d: Drawable) {
        shown = file
        back.setImageDrawable(d)
        back.animate().alpha(1f).setDuration(FADE_MS).start()
        val old = front
        // Once hidden, the old photo lets go of its picture: only the one on screen and the next are held.
        old.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (old.alpha == 0f) old.setImageDrawable(null) }.start()
        front = back
        back = old
        front.bringToFront()
        message.bringToFront()
        clock.bringToFront()
        main.removeCallbacks(preloadNext)
        main.postDelayed(preloadNext, FADE_MS)
    }
}

private const val FADE_MS = 1200L

/** The TV's own screensaver ("Daydream"), when chosen in the TV's settings or with adb. */
class SlideshowDream : DreamService() {
    private var view: SlideshowView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        view = SlideshowView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }
}

/** In-app slideshow: started from Settings or after the idle timeout. Any button closes it. */
class SlideshowActivity : Activity() {
    private lateinit var view: SlideshowView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = SlideshowView(this).apply { memoriesOnly = intent.getBooleanExtra(EXTRA_MEMORIES, false) }
        setContentView(view)
    }

    override fun onStart() { super.onStart(); view.start(); com.woodruff.tvexplorer.remote.TvScreen.attach(this) }
    override fun onStop() { com.woodruff.tvexplorer.remote.TvScreen.detach(this); view.stop(); super.onStop() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_MEMORIES = "memories"
        fun start(context: Context, memories: Boolean = false) = context.startActivity(
            Intent(context, SlideshowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_MEMORIES, memories),
        )
    }
}

/** Folder scans for every slideshow run on this one background thread. */
private val sharedIo = Executors.newSingleThreadExecutor()
