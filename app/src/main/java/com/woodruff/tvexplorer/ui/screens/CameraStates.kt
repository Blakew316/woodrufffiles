package com.woodruff.tvexplorer.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.nest.CAMERA_TROUBLE_MS
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.CameraStream
import com.woodruff.tvexplorer.nest.troubleSince
import kotlinx.coroutines.delay

// What a camera view shows besides its live picture (doorbell.md §5, offline-states.md §3.6): the
// words while it connects or reconnects, and the veil over a picture that has stopped. A picture is
// live only while its frames come (CameraStream.Status.Live): nothing here claims otherwise.

/** What a camera view shows, for what its host draws around it: the scrim under its words, the LIVE badge. */
internal enum class CameraPicture {
    /** No picture: words in its place (connecting, or nothing to hold while it reconnects). */
    NONE,
    /** The live picture: its frames are coming. */
    LIVE,
    /** The last picture, held under a veil while it reconnects. */
    HELD,
}

/**
 * What a view showing [status] shows; [held]: it has drawn a frame, so it holds a picture. A [tile]
 * counts as live only once it has drawn its own first frame, so its LIVE badge comes with its picture
 * (joining a stream that's already running, it's live a moment before it has drawn anything). A
 * full-screen view counts as live with its stream: its caption's scrim stays put through a switch of
 * camera, over the black before the next one's first frame.
 */
internal fun cameraPicture(status: CameraStream.Status, held: Boolean, tile: Boolean = false): CameraPicture = when {
    status == CameraStream.Status.Live -> if (tile && !held) CameraPicture.NONE else CameraPicture.LIVE
    held && troubleSince(status) > 0 -> CameraPicture.HELD
    else -> CameraPicture.NONE
}

/**
 * The words over a camera view: none while it's live; "Connecting…" until its first frames;
 * "Reconnecting…" once its frames stopped or its connection dropped, and "Can’t reach this camera
 * right now" when that has gone on for 30 s ([long]). Never Google's own words or codes.
 */
internal fun streamWords(status: CameraStream.Status, long: Boolean): String? = when (status) {
    CameraStream.Status.Live -> null
    CameraStream.Status.Connecting -> "Connecting…"
    else -> if (long) "Can’t reach this camera right now" else "Reconnecting…"
}

/** Whether the trouble that began at [since] (elapsedRealtime; 0: none) has gone on for [CAMERA_TROUBLE_MS]. */
@Composable
internal fun rememberLongTrouble(since: Long): Boolean {
    var long by remember(since) { mutableStateOf(since > 0 && SystemClock.elapsedRealtime() - since >= CAMERA_TROUBLE_MS) }
    LaunchedEffect(since) {
        if (since <= 0 || long) return@LaunchedEffect
        delay(since + CAMERA_TROUBLE_MS - SystemClock.elapsedRealtime())
        long = true
    }
    return long
}

/** What [CameraOverlay] draws over a camera view ([cameraOverlay]). */
internal enum class Overlay {
    /** Nothing: the live picture shows. */
    NONE,
    /** A tile's last picture, without words: it's live, but hasn't drawn its own first frame yet. */
    STILL,
    /** The picture the view holds (its last frame) under the veil, with the words. */
    VEIL,
    /** A tile's last picture under the veil, with the words. */
    STILL_VEIL,
    /** The words on black (full screen). */
    WORDS,
    /** The camera's symbol and the words, on the camera views' dark. */
    PLACEHOLDER,
}

/**
 * What goes over a view showing [status]: nothing while it's live; the picture it [held] (its last
 * frame), or a tile's last still ([hasStill]), under a veil that says it isn't live; or the words in
 * its place when there's no picture. A full-screen view ([big]) that's connecting says so on black
 * instead: a minutes-old picture of the porch could be taken for the visitor. A tile joining a stream
 * that's already live shows its last still until it has drawn a frame of its own, rather than black
 * for a moment. A tile never looks live before its frames come, with or without a LIVE badge.
 */
internal fun cameraOverlay(status: CameraStream.Status, held: Boolean, hasStill: Boolean, big: Boolean): Overlay = when {
    status == CameraStream.Status.Live -> if (!big && !held && hasStill) Overlay.STILL else Overlay.NONE
    held && !(big && status == CameraStream.Status.Connecting) -> Overlay.VEIL
    big -> Overlay.WORDS
    hasStill -> Overlay.STILL_VEIL
    else -> Overlay.PLACEHOLDER
}

/** Drawn over a camera view's picture, as [cameraOverlay] says, with its words ([streamWords]). */
@Composable
internal fun CameraOverlay(status: CameraStream.Status, longTrouble: Boolean, held: Boolean, still: ImageBitmap?, big: Boolean) {
    val words = streamWords(status, longTrouble).orEmpty()
    when (cameraOverlay(status, held, hasStill = still != null, big)) {
        Overlay.NONE -> Unit
        Overlay.STILL -> if (still != null) StillPicture(still)
        Overlay.VEIL -> StallVeil(words, big)
        Overlay.STILL_VEIL -> if (still != null) {
            StillPicture(still)
            StallVeil(words, big = false)
        }
        Overlay.WORDS -> CameraWords(words)
        Overlay.PLACEHOLDER -> Placeholder(words)
    }
}

/**
 * Words in place of a full-screen camera picture ("Connecting…", "Offline · reconnecting…"): centred,
 * white 75 %, bodyLarge, on black (doorbell.md §5). Camera views are dark in both themes.
 */
@Composable
internal fun CameraWords(words: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(
            words, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/**
 * Over a picture that isn't live (it stopped, or isn't moving yet): black 60 % across it, so it plainly
 * isn't, and the words in white 90 % at its centre (5:1 even over a sunlit white wall). Over a
 * full-screen view the caption's scrim is drawn on top, so its words still read.
 */
@Composable
private fun StallVeil(words: String, big: Boolean) {
    Box(Modifier.fillMaxSize().background(StallVeilColor), contentAlignment = Alignment.Center) {
        Text(
            words, color = Color.White.copy(alpha = 0.9f),
            style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = if (big) 32.dp else 12.dp),
        )
    }
}

private val StallVeilColor = Color.Black.copy(alpha = 0.6f)

/** A tile's last picture (from [com.woodruff.tvexplorer.nest.CameraStills]), cropped to fill it as the live one does. */
@Composable
private fun StillPicture(still: ImageBitmap) {
    Image(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/**
 * Sample camera views for the UI gallery, which has no WebRTC: a state and a picture for each camera,
 * by name. [LiveVideo] draws a sample with the same words and veil as a real view. Never set in the app.
 */
internal object CameraSamples {
    /** [status] as a view shows it, over [picture] (the live one, or the one it held), after [longTrouble]. */
    class Sample(val status: CameraStream.Status, val picture: ImageBitmap? = null, val longTrouble: Boolean = false)

    var views: Map<String, Sample>? = null

    /** A camera for one of Home's sample tiles (the gallery's are names), when it has a sample view. */
    fun camera(name: String): Camera? = views?.takeIf { name in it }?.let { Camera(name, name, null, webRtc = true, doorbell = false) }
}

/** A gallery sample in place of a live view ([CameraSamples]): its picture, the same [CameraOverlay] over it. */
@Composable
internal fun SampleVideo(sample: CameraSamples.Sample, fill: Boolean, onPicture: (CameraPicture) -> Unit) {
    val picture = sample.picture
    val held = picture != null && sample.status != CameraStream.Status.Connecting
    val shows = cameraPicture(sample.status, held, tile = fill)
    LaunchedEffect(shows) { onPicture(shows) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (picture != null && shows != CameraPicture.NONE) {
            Image(picture, contentDescription = null, contentScale = if (fill) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        CameraOverlay(
            sample.status, sample.longTrouble, held,
            still = picture.takeIf { fill && sample.status == CameraStream.Status.Connecting }, big = !fill,
        )
    }
}
