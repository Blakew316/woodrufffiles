package com.woodruff.tvexplorer.nest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.woodruff.tvexplorer.notify.NoticeLook
import com.woodruff.tvexplorer.notify.Notices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Camera alerts' pictures. The family's Mac sends every Woodruff TV its close-up of who or what each
 * camera alert is about (POST /api/camera-picture: the camera, the alert's kind, its event and visit,
 * when it happened, and a JPEG of at most 720 px), at about the time Google tells the TVs of the alert.
 * Each is decoded and cut to its card's size once, off the main thread, and waits here 3 minutes for its
 * alert (at most 10 wait: AlertPictureRules.kt). An alert's card shows its picture beside its words, and
 * a card already up when its picture comes takes it in place: [Notices.pictureCame] for the cards in
 * Woodruff and over other apps, [arrivals] for the player's doorbell card. Without one, a card is as it was.
 */
object AlertPictures {
    private val shelf = PictureShelf<Bitmap>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _arrivals = MutableSharedFlow<Shelved<Bitmap>>(extraBufferCapacity = 8)
    /** Each picture once it's ready (and kept): a card already up takes it if it's its own ([pictureFits]). */
    internal val arrivals: SharedFlow<Shelved<Bitmap>> = _arrivals.asSharedFlow()

    /** What a POST /api/camera-picture says: the alert it was taken for, whether it's a close-up, and the JPEG. */
    internal class Request(val alert: CameraAlert, val cropped: Boolean, val jpeg: ByteArray)

    /** [body] as a picture for an alert ([now]: when one that doesn't say when it happened is taken to have); IllegalArgumentException (a 400) when it isn't one. */
    internal fun request(body: JSONObject, now: Long = System.currentTimeMillis()): Request {
        fun text(key: String) = if (body.isNull(key)) "" else body.optString(key)
        val camera = text("camera").trim()
        require(camera.isNotEmpty()) { "Which camera is it from?" }
        val jpeg = jpeg(text("picture")) ?: throw IllegalArgumentException("The picture isn’t a JPEG")
        val at = body.optLong("at").takeIf { it > 0 } ?: now
        return Request(cameraAlert(camera, text("kind"), text("session"), text("event"), at), body.optBoolean("cropped"), jpeg)
    }

    /** A picture from the family's Mac ([body]): taken now and decoded after, in the background. IllegalArgumentException when it isn't one. */
    fun receive(c: Context, body: JSONObject) {
        val r = request(body)
        val app = c.applicationContext
        scope.launch {
            val bitmap = runCatching { decode(app, r.jpeg) }.onFailure { Log.w("AlertPictures", "couldn’t decode a picture: $it") }.getOrNull()
                ?: return@launch
            val got = SystemClock.elapsedRealtime()
            val item = Shelved(r.alert, r.cropped, got, bitmap)
            // Kept first, then given to the cards up: a card that comes up meanwhile finds it here.
            shelf.add(item, got)
            Notices.pictureCame(app, item)
            _arrivals.tryEmit(item)
        }
    }

    /** The picture [alert]'s card shows, if its came in the last 3 minutes (decoded, sized and rounded: drawn as it is). */
    fun forAlert(alert: CameraAlert): Bitmap? = shelf.find(alert, SystemClock.elapsedRealtime())?.picture

    /** [text]'s JPEG: base64 (a data address's prefix allowed); null when it isn't one. */
    private fun jpeg(text: String): ByteArray? {
        val b64 = text.substringAfter("base64,", text).trim()
        if (b64.isEmpty()) return null
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        // A JPEG starts FF D8 FF.
        return bytes.takeIf { it.size > 3 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() && it[2] == 0xFF.toByte() }
    }

    /**
     * [jpeg] as cards show it, made once: [NoticeLook.PICTURE_HEIGHT_DP] high, as wide as the picture is at
     * that height (square to 16:9: its middle when it's narrower or wider), its corners rounded. It's decoded
     * at no more than twice that size, so a 720 px picture takes no more memory than it needs. Null when it
     * can't be read.
     */
    private fun decode(c: Context, jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val density = c.resources.displayMetrics.density
        val boxHeight = (NoticeLook.PICTURE_HEIGHT_DP * density).roundToInt()
        val sample = pictureSample(pictureCrop(bounds.outWidth, bounds.outHeight).height, boxHeight)
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            val box = pictureBox(src.width, src.height, boxHeight)
            val part = pictureCrop(src.width, src.height)
            val left = (src.width - part.width) / 2f
            val top = (src.height - part.height) / 2f
            val area = RectF(0f, 0f, box.width.toFloat(), box.height.toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setRectToRect(RectF(left, top, left + part.width, top + part.height), area, Matrix.ScaleToFit.FILL) })
                }
            }
            val out = Bitmap.createBitmap(box.width, box.height, Bitmap.Config.ARGB_8888)
            val radius = NoticeLook.PICTURE_CORNER_DP * density
            Canvas(out).drawRoundRect(area, radius, radius, paint)
            return out
        } finally {
            src.recycle()
        }
    }
}
