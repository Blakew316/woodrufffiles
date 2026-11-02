package com.woodruff.tvexplorer.remote

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.woodruff.tvexplorer.ui.motion.DialogWindows
import com.woodruff.tvexplorer.ui.motion.DpadGate
import com.woodruff.tvexplorer.util.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The phone's TV Screen and remote: a live picture of what Woodruff Family shows on the TV, and the
 * remote's buttons pressed where the TV's own remote would press them.
 *
 * Woodruff's screens are activities (the main window, the player, the photo viewer, the slideshow, the
 * doorbell's camera), and each of its pop-ups (a title page, a menu, a confirmation, the filters,
 * search) is a window of its own over the screen that opened it ([DialogWindows]). A key goes to the
 * window with the focus, as the TV's remote's does, so Back closes a pop-up and OK presses Play on a
 * title page. It carries the time it came and, for an arrow held on the phone, how many times it has
 * repeated: held arrows are gated as the remote's are (never queued, at the phone's own pace), and
 * Settings → Smoothness counts the moves.
 *
 * The picture is the screen with its pop-ups drawn over it, the screen dimmed behind them as on the
 * TV. Only this app's windows can be copied (Android needs a permission prompt on the TV for anything
 * else), and video surfaces show black. Each window is copied straight at the size asked for (at most
 * [WIDTH] px wide; the hub's thumbnail asks for less), and a picture is shared for 500 ms, so several
 * phones watching don't multiply the work on the TV. What pictures are copied into is let go
 * [KEEP_MS] after the last one.
 */
object TvScreen {
    /** The widest picture handed out (the phone's TV Screen). */
    const val WIDTH = 960
    /** The narrowest asked for. */
    private const val MIN_WIDTH = 120
    /** A picture made this recently is handed out again. */
    private const val SHARE_MS = 500L
    /** What pictures are copied into is let go this long after the last picture. */
    const val KEEP_MS = 15_000L
    /** The longest a picture waits for the main thread. */
    private const val WAIT_MS = 2_000L
    /** A held arrow's next press, coming within this, carries on the same hold. */
    private const val HOLD_GAP_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- The screen on top --------------------------------------------------------------------------

    /** Woodruff's screens that are up, the top one last (a screen opened over another doesn't hide it for good). */
    private val screens = ArrayList<WeakReference<Activity>>()

    /** Call from onStart / onStop of every Woodruff screen (main app, player, photo viewer, slideshow, doorbell). */
    fun attach(a: Activity) {
        synchronized(screens) {
            screens.removeAll { val s = it.get(); s == null || s === a }
            screens += WeakReference(a)
        }
    }

    fun detach(a: Activity) {
        synchronized(screens) { screens.removeAll { val s = it.get(); s == null || s === a } }
    }

    private fun top(): Activity? = synchronized(screens) {
        screens.asReversed().firstNotNullOfOrNull { ref -> ref.get()?.takeIf { !it.isFinishing } }
    }

    val onScreen: Boolean get() = top() != null

    // ---- The remote's buttons -----------------------------------------------------------------------

    private val holdLock = Any()
    /** The last press's button, when its hold began and when it came (uptime ms). */
    private var holdCode = 0
    private var holdDown = 0L
    private var holdLast = 0L

    /**
     * Presses a remote button inside Woodruff Family, in the window with the focus (the class comment
     * says which). [repeat]: how many times an arrow held on the phone has repeated (0 for a press).
     * Returns false when Woodruff isn't on screen. Any thread.
     */
    fun press(key: String, repeat: Int = 0): Boolean {
        val code = keyCode(key) ?: return false
        if (top() == null) return false
        // Someone's at the phone's remote: as much use as the TV's own (before the key even arrives,
        // so an update deciding meanwhile already sees it).
        Presence.pressed()
        val (down, up) = events(code, repeat, SystemClock.uptimeMillis())
        main.post {
            val target = keyWindow() ?: return@post
            // A held arrow's repeats keep the phone's own pace through the gate (DpadGate.fromPhone).
            DpadGate.fromPhone(down) { target.dispatchKeyEvent(down) }
            target.dispatchKeyEvent(up)
        }
        pressedAt = SystemClock.elapsedRealtime() // the next picture should show what it did
        return true
    }

    /** The button the phone's remote sends as [key]; null for one it doesn't press here. */
    internal fun keyCode(key: String): Int? = when (key) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
        "back" -> KeyEvent.KEYCODE_BACK
        "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "menu" -> KeyEvent.KEYCODE_MENU
        else -> null
    }

    /**
     * The press and release a phone press of [code] makes, at [now] (uptime ms, as the TV's own keys
     * carry). An arrow held on the phone comes as presses with [repeat] 1, 2, 3…: each carries on the
     * hold its first press began (the same down time), as a held button's repeats do on the remote.
     */
    internal fun events(code: Int, repeat: Int, now: Long): Pair<KeyEvent, KeyEvent> {
        val n = repeat.coerceIn(0, 10_000)
        val downAt = synchronized(holdLock) {
            if (n == 0 || code != holdCode || now - holdLast !in 0..HOLD_GAP_MS) holdDown = now
            holdCode = code
            holdLast = now
            holdDown
        }
        val source = if (code in DPAD) InputDevice.SOURCE_DPAD else InputDevice.SOURCE_KEYBOARD
        return KeyEvent(downAt, now, KeyEvent.ACTION_DOWN, code, n, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source) to
            KeyEvent(downAt, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source)
    }

    private val DPAD = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
    )

    /**
     * Where a key goes now: of the top screen's windows (its own and its pop-ups over it), the one with
     * the focus; when none has it (one of Android's own windows is over them), its top pop-up, else the
     * screen itself. Its window's callback is what Android hands keys to (for a pop-up, the dialog:
     * Back closes it). Main thread.
     */
    private fun keyWindow(): Window.Callback? {
        val a = top() ?: return null
        val windows = listOf(a.window) + DialogWindows.of(a)
        val w = windows.lastOrNull { it.peekDecorView()?.hasWindowFocus() == true } ?: windows.last()
        return w.callback ?: a
    }

    // ---- The picture --------------------------------------------------------------------------------

    private val pictureLock = Any()
    private class Made(val jpeg: ByteArray, val at: Long)
    /** Pictures made lately, by width, and when (elapsed ms). */
    private val made = HashMap<Int, Made>()
    /** What pictures of each width are copied into, one bitmap per window, kept for the next picture. */
    private val spare = HashMap<Int, ArrayList<Bitmap>>()
    /** When those go if no picture comes first (elapsed ms), and the job that lets them go. */
    private var keepUntil = 0L
    private var letGo: Job? = null
    /** The last remote press (elapsed ms): a picture made before it doesn't show what it did. */
    @Volatile private var pressedAt = 0L
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * A JPEG of what Woodruff Family shows now, at most [maxWidth] px wide (at most [WIDTH]), or null
     * when it isn't on screen. Server threads only: it waits for the main thread.
     */
    fun frame(maxWidth: Int = WIDTH): ByteArray? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val width = maxWidth.coerceIn(MIN_WIDTH, WIDTH)
        synchronized(pictureLock) {
            val now = SystemClock.elapsedRealtime()
            made[width]?.let { if (now - it.at < SHARE_MS && it.at >= pressedAt) return it.jpeg }
            val a = top() ?: return null
            keepUntil = now + KEEP_MS
            if (letGo == null) letGo = scope.launch { letGoWhenDue() }
            val picture = picture(a, width) ?: return null
            val out = ByteArrayOutputStream(if (width >= WIDTH) 96 * 1024 else 16 * 1024)
            picture.compress(Bitmap.CompressFormat.JPEG, 72, out)
            return out.toByteArray().also { made[width] = Made(it, now) }
        }
    }

    /** Lets go of what pictures are copied into now (Woodruff has been off screen a while). Any thread. */
    fun release() {
        scope.launch { synchronized(pictureLock) { letGoNow() } }
    }

    /** Whether anything is kept for pictures (for tests). */
    internal val holdsBitmaps: Boolean get() = synchronized(pictureLock) { spare.isNotEmpty() }

    private suspend fun letGoWhenDue() {
        while (true) {
            val left = synchronized(pictureLock) {
                (keepUntil - SystemClock.elapsedRealtime()).also { if (it <= 0) letGoNow() }
            }
            if (left <= 0) return
            delay(left)
        }
    }

    /** Under [pictureLock]. */
    private fun letGoNow() {
        spare.values.forEach { list -> list.forEach { it.recycle() } }
        spare.clear()
        made.clear()
        letGo = null
    }

    /** One window in the picture: where it sits on the screen (px) and how much it dims what's under it. */
    private class Layer(val window: Window, val x: Int, val y: Int, val w: Int, val h: Int, val dim: Float)

    /** [a] with its pop-ups over it, [width] px wide at most; null if it couldn't be copied. Under [pictureLock]. */
    private fun picture(a: Activity, width: Int): Bitmap? {
        val layers = onMain { layers(a) }?.takeIf { it.isNotEmpty() } ?: return null
        val screen = layers[0]
        val outW = minOf(width, screen.w)
        val scale = outW.toFloat() / screen.w
        val sizes = layers.map { l -> Pair(sized(l.w, scale), sized(l.h, scale)) }
        val into = bitmaps(width, sizes)
        val copied = copy(layers, into)
        if (copied == null) {
            // The main thread didn't answer in time: a copy may still be on its way into these, so
            // they're left to the garbage collector rather than used again or recycled.
            spare.remove(width)
            return null
        }
        if (!copied[0]) return null
        val canvas = Canvas(into[0])
        for (i in 1 until layers.size) {
            if (!copied[i]) continue
            val l = layers[i]
            // Dimmed behind it, as the TV dims what's under a pop-up.
            if (l.dim > 0f) canvas.drawColor(Color.argb((l.dim.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0))
            canvas.drawBitmap(into[i], null, RectF(l.x * scale, l.y * scale, (l.x + l.w) * scale, (l.y + l.h) * scale), smooth)
        }
        return into[0]
    }

    private fun sized(px: Int, scale: Float) = (px * scale).roundToInt().coerceAtLeast(1)

    /** One bitmap per window, [sizes] big, the ones from the last picture of this width where they fit. */
    private fun bitmaps(width: Int, sizes: List<Pair<Int, Int>>): List<Bitmap> {
        val list = spare.getOrPut(width) { ArrayList() }
        sizes.forEachIndexed { i, (w, h) ->
            val old = list.getOrNull(i)
            if (old == null || old.isRecycled || old.width != w || old.height != h) {
                old?.recycle()
                val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                if (i < list.size) list[i] = fresh else list += fresh
            }
        }
        while (list.size > sizes.size) list.removeAt(list.size - 1).recycle()
        return list.toList()
    }

    /**
     * What's on screen of [a], lowest first: its window, then each of its pop-ups that's up, with where
     * it sits and how much it dims what's under it. Main thread.
     */
    private fun layers(a: Activity): List<Layer> {
        val base = a.window ?: return emptyList()
        val decor = base.peekDecorView() ?: return emptyList()
        if (decor.width <= 0 || decor.height <= 0) return emptyList()
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        val at = IntArray(2)
        val layers = arrayListOf(Layer(base, 0, 0, decor.width, decor.height, 0f))
        for (w in DialogWindows.of(a)) {
            val d = w.peekDecorView() ?: continue
            if (d.width <= 0 || d.height <= 0 || d.windowVisibility != View.VISIBLE) continue
            d.getLocationOnScreen(at)
            val attrs = w.attributes
            val dim = if (attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) attrs.dimAmount else 0f
            layers += Layer(w, at[0] - origin[0], at[1] - origin[1], d.width, d.height, dim)
        }
        return layers
    }

    /**
     * Copies each of [layers]' windows into the bitmap of [into] at its place, scaled to fit it; which
     * ones were copied, or null when the main thread didn't answer in time.
     */
    private fun copy(layers: List<Layer>, into: List<Bitmap>): BooleanArray? {
        val ok = BooleanArray(layers.size)
        val done = CountDownLatch(layers.size)
        main.post {
            layers.forEachIndexed { i, l ->
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        // PixelCopy reads what's really on screen (hardware-rendered Compose included),
                        // scaled to the bitmap it's copied into.
                        PixelCopy.request(l.window, into[i], { result -> ok[i] = result == PixelCopy.SUCCESS; done.countDown() }, main)
                    } else {
                        ok[i] = draw(l, into[i])
                        done.countDown()
                    }
                } catch (e: Exception) {
                    // A pop-up closing just then (its window has gone): the picture goes without it.
                    done.countDown()
                }
            }
        }
        return if (done.await(WAIT_MS, TimeUnit.MILLISECONDS)) ok else null
    }

    /** Before Android 8: [l]'s window drawn into [into], scaled to fit it. Main thread. */
    private fun draw(l: Layer, into: Bitmap): Boolean {
        val decor = l.window.peekDecorView() ?: return false
        into.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(into)
        canvas.scale(into.width.toFloat() / l.w, into.height.toFloat() / l.h)
        decor.draw(canvas)
        return true
    }

    private fun <T : Any> onMain(block: () -> T): T? {
        val done = CountDownLatch(1)
        var result: T? = null
        main.post {
            try {
                result = block()
            } finally {
                done.countDown()
            }
        }
        return if (done.await(WAIT_MS, TimeUnit.MILLISECONDS)) result else null
    }

    // ---- Another TV's picture -------------------------------------------------------------------------

    /**
     * Another TV's picture ([jpeg]) decoded to at least [width] px wide where it's wider, sampled down
     * (a TV with an older Woodruff sends its full 960 px whatever was asked); null if it isn't a picture.
     */
    fun thumbnail(jpeg: ByteArray, width: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, width) }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    /** The largest power of two that keeps a picture [actual] px wide at least [wanted] px wide. */
    internal fun sampleFor(actual: Int, wanted: Int): Int {
        var sample = 1
        while (wanted > 0 && actual / (sample * 2) >= wanted) sample *= 2
        return sample
    }
}
