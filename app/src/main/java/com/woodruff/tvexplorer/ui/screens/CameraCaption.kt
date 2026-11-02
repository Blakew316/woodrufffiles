package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.ui.Screen
import java.text.DateFormat
import java.util.Date

/**
 * The words over a camera full screen (doorbell.md §3.1), 28 dp from the left and top: why it opened
 * ([eyebrow], "RANG AT 7:42 PM", only on a camera that rang), its name, and what the keys do. Always
 * white: camera views are dark in both themes, and over a live picture they sit on [CameraScrim].
 */
@Composable
internal fun CameraCaption(eyebrow: String?, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 28.dp, top = 28.dp, end = 28.dp)) {
        if (eyebrow != null) Text(
            eyebrow, maxLines = 1, color = Color.White.copy(alpha = 0.75f),
            // A time that changes in place (another ring) keeps its width.
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The soft black behind [CameraCaption], across the top of a live picture: 65 % from the top to
 * 100 dp, then fading out by 180 dp. Held under the whole caption, so its white words still read over
 * a sunlit white porch (the eyebrow 4.8:1, the hint 5.2:1). Drawn by the window over the picture (a
 * SurfaceView shows under its window), like the words. Not over the placeholders (no picture).
 */
@Composable
internal fun CameraScrim(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(180.dp).background(ScrimBrush))
}

private val ScrimBrush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.65f),
    (100f / 180f) to Color.Black.copy(alpha = 0.65f),
    1f to Color.Transparent,
)

/** "RANG AT 7:42 PM": when a doorbell rang, in the TV's own 12- or 24-hour [clock]. */
internal fun rangAt(at: Long, clock: DateFormat): String = "RANG AT " + clock.format(Date(at)).uppercase()

/** What the keys do on a camera full screen: Back's [back], after Left / Right when there are [cameras] to switch between. */
internal fun cameraHint(back: String, cameras: Int): String = if (cameras > 1) "◀ ▶ switch camera  ·  $back" else back

/**
 * The name of the Woodruff [screen] a doorbell camera opened over, for its hint ("Back to Home"):
 * as the side menu and the screens' titles say. [library]: Streaming's library ("Movies", "TV
 * Shows"); [inMemories]: the browser is in Family Memories.
 */
internal fun screenName(screen: Screen, library: String?, inMemories: Boolean): String = when (screen) {
    Screen.HOME -> "Home"
    Screen.DRIVE -> library ?: "Streaming"
    Screen.BROWSER -> if (inMemories) "Family Memories" else "Files"
    Screen.MAIL -> "Mail"
    Screen.NOTIFICATIONS -> "Notifications"
    Screen.NOTES -> "Notes"
    Screen.CAMERAS -> "the Command Center"
    Screen.APPS -> "Apps"
    Screen.TRANSFER -> "Woodruff"
    Screen.SETTINGS -> "Settings"
}
