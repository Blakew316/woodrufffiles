package com.woodruff.tvexplorer.remote

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "TV" tab on the phone: a live picture of Woodruff Family's own screen, plus remote keys.
 *
 * Only this app's windows can be captured (Android needs a permission prompt on the TV for
 * anything else), and video surfaces show black. Frames are scaled to 960 px wide JPEGs and
 * shared for 500 ms, so several phones watching don't multiply the work on the TV.
 */
object TvScreen {
    @Volatile private var current: WeakReference<Activity>? = null
    private val main = Handler(Looper.getMainLooper())
    private var full: Bitmap? = null
    private var lastAt = 0L
    private var last: ByteArray? = null

    /** Call from onStart / onStop of every Woodruff screen (main app, player, photo viewer). */
    fun attach(a: Activity) { current = WeakReference(a) }
    fun detach(a: Activity) { if (current?.get() === a) current = null }

    val onScreen: Boolean get() = current?.get()?.let { !it.isFinishing } == true

    /** A JPEG of what Woodruff Family shows now, or null when it isn't on screen. Server threads only. */
    @Synchronized
    fun frame(): ByteArray? {
        val a = current?.get()?.takeIf { !it.isFinishing } ?: return null
        val now = System.currentTimeMillis()
        last?.let { if (now - lastAt < 500) return it }
        val view = a.window.decorView
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val bmp = full?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { full?.recycle(); full = it }
        val done = CountDownLatch(1)
        var ok = false
        main.post {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    // PixelCopy reads what's really on screen (hardware-rendered Compose included).
                    PixelCopy.request(a.window, bmp, { result -> ok = result == PixelCopy.SUCCESS; done.countDown() }, main)
                } else {
                    view.draw(Canvas(bmp)); ok = true; done.countDown()
                }
            } catch (e: Exception) {
                done.countDown()
            }
        }
        if (!done.await(2, TimeUnit.SECONDS) || !ok) return null
        val scale = minOf(1f, 960f / w)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream(96 * 1024)
        small.compress(Bitmap.CompressFormat.JPEG, 72, out)
        if (small !== bmp) small.recycle()
        return out.toByteArray().also { last = it; lastAt = now }
    }

    /** Presses a remote button inside Woodruff Family. Returns false when it isn't on screen. */
    fun press(key: String): Boolean {
        val a = current?.get()?.takeIf { !it.isFinishing } ?: return false
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "menu" -> KeyEvent.KEYCODE_MENU
            else -> return false
        }
        main.post {
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        last = null // the next frame should show the result
        return true
    }
}
