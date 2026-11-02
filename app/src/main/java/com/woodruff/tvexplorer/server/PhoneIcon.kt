package com.woodruff.tvexplorer.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.tvs.TvLinks
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * This TV's web app on a phone's Home Screen is named after the TV (Settings → TVs & phones → This
 * TV's name, e.g. "Guest Room"), in the label under the icon and on the icon's picture itself, where
 * "WOODRUFF FAMILY" is written: "GUEST" large and white, "ROOM" small and blue under it, over the
 * same emblem and background. A TV that hasn't been given a name keeps the app's name and picture.
 * Nothing else changes: the app inside, its own logo and pictures (icon-*.png), and Woodruff on the TV.
 *
 * iOS takes the label from the page's apple-mobile-web-app-title and the picture from its
 * apple-touch-icon, Android both from the manifest, when the icon is added (an icon added before
 * keeps its label and picture until it's added again).
 */
object PhoneIcon {
    const val APP_NAME = "Woodruff Family"

    fun label(c: Context): String = TvLinks.givenName(c) ?: APP_NAME

    /** The web app's page for this TV: its pairing key and Home Screen label filled in. */
    fun render(template: String, token: String, label: String): String = template
        .replace("__TVX_KEY__", token)
        .replace("__TVX_HOME_NAME__", escapeHtml(label))
        .replace("__TVX_HOME_TAG__", tag(label))

    /** The Home Screen app's manifest ([template], from assets) named [label], with its pictures ([PICTURES]). */
    fun manifest(template: String, label: String): String {
        val o = JSONObject(template).put("name", label).put("short_name", label)
        val icons = o.optJSONArray("icons")
        if (icons != null) for (i in 0 until icons.length()) {
            val icon = icons.getJSONObject(i)
            icon.put("src", homeIconSrc(icon.getString("src"), label))
        }
        return o.toString()
    }

    /** "/icon-192.png?v=2" → "/home-icon-192.png?v=2&n=…": the picture with this TV's name, at an address that changes with it. */
    internal fun homeIconSrc(src: String, label: String): String =
        src.replace("/icon-", "/home-icon-") + (if ('?' in src) "&" else "?") + "n=" + tag(label)

    /** The Home Screen icon's pictures, by address and size. The app's own icon-*.png (shown inside it) stay as they are. */
    val PICTURES = mapOf("/home-icon-180.png" to 180, "/home-icon-192.png" to 192, "/home-icon-512.png" to 512)

    /**
     * How [label] is written on the icon, as "WOODRUFF FAMILY" is: the first words large, the last
     * word small under them; a single word on one line. "Guest Room" → ("GUEST", "ROOM"); an
     * apostrophe is written curly, "Den's TV" → ("DEN’S", "TV").
     */
    fun wording(label: String): Pair<String, String?> {
        val words = label.trim().uppercase(Locale.US).replace('\'', '\u2019').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "WOODRUFF" to "FAMILY"
        if (words.size == 1) return words[0] to null
        return words.dropLast(1).joinToString(" ") to words.last()
    }

    /** Pictures drawn so far, by size and label: each is drawn once. */
    private val pictures = ConcurrentHashMap<String, ByteArray>()

    /** This TV's Home Screen icon picture at [size] (180, 192 or 512), as a PNG, with [label] on it. */
    fun picture(c: Context, size: Int, label: String): ByteArray = pictures.getOrPut("$size/$label") {
        // No name: the app's own picture, exactly.
        if (label == APP_NAME) return@getOrPut c.assets.open("web/icon-$size.png").use { it.readBytes() }
        // The same picture with "WOODRUFF FAMILY" taken out (made from it, at each size), then the name written in.
        val bitmap = c.assets.open("web/icon-base-$size.png").use { BitmapFactory.decodeStream(it) }.copy(Bitmap.Config.ARGB_8888, true)
        draw(c, Canvas(bitmap), size, label)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }

    // Where and how "WOODRUFF FAMILY" is written on the 512-px picture, measured from it: Inter Display
    // Bold 60 px in white on a baseline at 426, then Inter SemiBold 25 px, tracked 0.44 em, in blue at 462.
    private const val BIG_PX = 60f
    private const val BIG_BASELINE = 426f
    private const val SMALL_PX = 25f
    private const val SMALL_BASELINE = 462f
    private const val SMALL_TRACKING = 0.44f
    private const val SMALL_COLOR = 0xFF5AC9FB.toInt()
    /** A one-word name: one line, in the middle of where the two lines were. */
    private const val ONE_LINE_BASELINE = 444f
    /** The widest a line may be, as a share of the picture (WOODRUFF takes 0.67); longer lines are made smaller. */
    private const val MAX_WIDTH = 0.82f

    /** Writes [label] onto a [size]-px picture whose wording was taken out. */
    internal fun draw(c: Context, canvas: Canvas, size: Int, label: String) {
        val s = size / 512f
        val (first, second) = wording(label)
        val big = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = ResourcesCompat.getFont(c, R.font.interdisplay_bold)
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = BIG_PX * s
        }
        fit(big, first, MAX_WIDTH * size)
        if (second == null) {
            canvas.drawText(first, size / 2f, ONE_LINE_BASELINE * s, big)
            return
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = ResourcesCompat.getFont(c, R.font.inter_semibold)
            color = SMALL_COLOR
            textAlign = Paint.Align.CENTER
            textSize = SMALL_PX * s
            letterSpacing = SMALL_TRACKING
        }
        fit(small, second, MAX_WIDTH * size)
        canvas.drawText(first, size / 2f, BIG_BASELINE * s, big)
        canvas.drawText(second, size / 2f, SMALL_BASELINE * s, small)
    }

    /** Makes [paint]'s text smaller until [text] fits in [width]. */
    private fun fit(paint: Paint, text: String, width: Float) {
        val w = paint.measureText(text)
        if (w > width) paint.textSize *= width / w
    }

    /**
     * A short stand-in for [label] in the manifest's address: phones keep the manifest a week, so a
     * renamed TV gives them a new address to fetch rather than the old name.
     */
    fun tag(label: String): String =
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    /** [s] made safe inside an HTML attribute. */
    fun escapeHtml(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
