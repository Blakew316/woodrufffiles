package com.woodruff.tvexplorer.nest

import kotlin.math.abs
import kotlin.math.roundToInt

// A camera alert's picture, kept apart from Android so its rules can be unit-tested: which alert a
// picture from the family's Mac is for, how many are kept and for how long, and its size on a card.

/**
 * A camera alert, as its picture is matched to it: the camera's resource name, what was seen ([kind]:
 * "doorbell", "person", "motion"…), the visit and the event it belongs to (Nest's eventSessionId and
 * eventId, null when not known) and when it happened ([at], ms since 1970: Google's time when the
 * message says it, else this TV's when the alert came).
 */
data class CameraAlert(val camera: String, val kind: String, val session: String?, val event: String?, val at: Long)

/** A [CameraAlert] from what another device sent: a blank visit or event is none (an event's id is cleaned as a visit's). */
internal fun cameraAlert(camera: String, kind: String, session: String?, event: String?, at: Long): CameraAlert =
    CameraAlert(camera.trim(), kind.trim(), cleanSession(session), cleanSession(event), at)

/** How long a picture waits for its alert's card, and how many wait at most (the oldest go first). */
internal const val PICTURE_KEEP_MS = 3 * 60_000L
internal const val PICTURE_MAX = 10

/** An alert that names neither its visit nor its event takes a picture of its camera from this close to it. */
internal const val PICTURE_NEAR_MS = 2 * 60_000L

/**
 * Whether the picture the Mac took for [picture] (the alert it was taken for) is [alert]'s: one of the
 * same visit, else of the same event. An alert that names its visit or its event takes no other (another
 * visit on the same camera is someone else); one that names neither (passed on by a TV too old to say)
 * takes its camera's picture from within 2 minutes of it.
 */
internal fun pictureFits(picture: CameraAlert, alert: CameraAlert): Boolean {
    val visit = alert.session
    val event = alert.event
    if (visit != null && visit == picture.session) return true
    if (event != null && event == picture.event) return true
    if (visit != null || event != null) return false
    return picture.camera == alert.camera && abs(picture.at - alert.at) <= PICTURE_NEAR_MS
}

/**
 * A picture waiting for its alert: the alert it was taken for, whether it's cut to who or what was seen
 * ([cropped]), when it came ([got], ms by this TV's clock) and the picture itself.
 */
internal class Shelved<T>(val alert: CameraAlert, val cropped: Boolean, val got: Long, val picture: T)

/**
 * Of [pictures], the one [alert]'s card shows: of those that fit it ([pictureFits]), the one taken for its
 * own event, then one of its kind, a close-up, the nearest in time, the newest. Null when none fits.
 */
internal fun <T> bestPicture(alert: CameraAlert, pictures: Collection<Shelved<T>>): Shelved<T>? =
    pictures.filter { pictureFits(it.alert, alert) }.maxWithOrNull(
        compareBy<Shelved<T>> { alert.event != null && it.alert.event == alert.event }
            .thenBy { it.alert.kind == alert.kind }
            .thenBy { it.cropped }
            .thenByDescending { abs(it.alert.at - alert.at) }
            .thenBy { it.got },
    )

/**
 * The last pictures from the family's Mac, each waiting [keepMs] (3 minutes) for its alert's card, [max]
 * (10) at most: the oldest goes first. Pictures come on the server's threads and cards ask on others.
 */
internal class PictureShelf<T>(private val max: Int = PICTURE_MAX, private val keepMs: Long = PICTURE_KEEP_MS) {
    private val items = ArrayList<Shelved<T>>()

    /** Keeps [item]; [now] is this TV's clock, as [Shelved.got] is. */
    @Synchronized
    fun add(item: Shelved<T>, now: Long) {
        prune(now)
        items += item
        while (items.size > max) items.removeAt(0)
    }

    /** The picture [alert]'s card shows at [now] ([bestPicture]), if one came. */
    @Synchronized
    fun find(alert: CameraAlert, now: Long): Shelved<T>? {
        prune(now)
        return bestPicture(alert, items)
    }

    /** How many wait at [now]. */
    @Synchronized
    fun count(now: Long): Int {
        prune(now)
        return items.size
    }

    private fun prune(now: Long) {
        items.removeAll { now - it.got !in 0..keepMs }
    }
}

// ---- Its size on a card -------------------------------------------------------------------------

/** A size in pixels: a card's picture, or the part of a picture it shows. */
internal data class PictureSize(val width: Int, val height: Int)

/** The narrowest and widest a card's picture is: square (a close-up is square to 4:3) to 16:9 (a camera's whole picture). */
internal const val PICTURE_MIN_ASPECT = 1f
internal const val PICTURE_MAX_ASPECT = 16f / 9f

/** A [width] × [height] picture's box on a card, [boxHeight] high: as wide as the picture is at that height, square to 16:9. */
internal fun pictureBox(width: Int, height: Int, boxHeight: Int): PictureSize {
    val aspect = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(PICTURE_MIN_ASPECT, PICTURE_MAX_ASPECT) else PICTURE_MAX_ASPECT
    return PictureSize((boxHeight * aspect).roundToInt(), boxHeight)
}

/** The part of a [width] × [height] picture its box shows: all of it, unless it's narrower than square or wider than 16:9 (then its middle). */
internal fun pictureCrop(width: Int, height: Int): PictureSize {
    if (width <= 0 || height <= 0) return PictureSize(width, height)
    val aspect = width.toFloat() / height
    return when {
        aspect > PICTURE_MAX_ASPECT -> PictureSize((height * PICTURE_MAX_ASPECT).roundToInt(), height)
        aspect < PICTURE_MIN_ASPECT -> PictureSize(width, (width / PICTURE_MIN_ASPECT).roundToInt())
        else -> PictureSize(width, height)
    }
}

/** How much a picture [height] high is shrunk as it's decoded for a box [boxHeight] high: by halves, never below the box. */
internal fun pictureSample(height: Int, boxHeight: Int): Int {
    var sample = 1
    while (boxHeight > 0 && height / (sample * 2) >= boxHeight) sample *= 2
    return sample
}
