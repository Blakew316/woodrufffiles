package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.useAppMotion
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.io.File

/**
 * Full-screen photo viewer for local and Google Drive photos. Left/Right: previous/next. Up: rotate. OK: start/stop slideshow.
 * One photo cross-fades into the next. Opened again while it's up (a phone showing another photo from Family
 * Memories), it changes the photo it shows instead of another viewer opening over it.
 */
class ImageViewerActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); com.woodruff.tvexplorer.remote.TvScreen.attach(this) }
    override fun onStop() { com.woodruff.tvexplorer.remote.TvScreen.detach(this); super.onStop() }

    /** The photos (MediaRef keys), their titles, the one shown and how far it's turned: set by each intent. */
    private var paths by mutableStateOf<List<String>>(emptyList())
    private var titles by mutableStateOf<List<String>>(emptyList())
    private var index by mutableIntStateOf(0)
    private var rotation by mutableFloatStateOf(0f)

    /** Takes what [intent] asks to show; false when it names no photo. */
    private fun take(intent: Intent): Boolean {
        val p = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        if (p.isEmpty()) return false
        paths = p
        titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        index = p.indexOf(intent.getStringExtra(EXTRA_START)).coerceAtLeast(0)
        rotation = 0f
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!take(intent)) { finish(); return }
        // The app's motion plays at its own speed, even on a TV with Android's animations turned off.
        window.decorView.useAppMotion(lifecycle)

        setContent {
            var slideshow by remember { mutableIntStateOf(0) } // 0 = off, otherwise a run token
            val focus = remember { FocusRequester() }
            val context = LocalContext.current
            val needsDrive = remember(paths) { paths.any { MediaRef.parse(it).needsDriveAuth } }
            val driveToken by produceState<String?>(null, needsDrive) { if (needsDrive) value = GoogleAuth.token(context) }
            val path = paths[index.coerceIn(0, paths.size - 1)]
            val title = titles.getOrNull(index) ?: File(path).name
            fun go(delta: Int) { index = (index + delta + paths.size) % paths.size; rotation = 0f }

            LaunchedEffect(slideshow) {
                while (slideshow != 0) { delay(5000); go(1) }
            }
            // The photos either side, decoded ahead (local ones), so the next one is ready to fade in.
            LaunchedEffect(path) {
                val size = context.resources.displayMetrics
                val loader = coil.Coil.imageLoader(context)
                for (d in listOf(1, -1)) {
                    val ref = MediaRef.parse(paths[(index + d + paths.size) % paths.size])
                    if (ref is MediaRef.Local) {
                        loader.enqueue(ImageRequest.Builder(context).data(File(ref.path)).size(size.widthPixels, size.heightPixels).build())
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionRight, Key.MediaNext -> { go(1); true }
                            Key.DirectionLeft, Key.MediaPrevious -> { go(-1); true }
                            Key.DirectionUp -> { rotation = (rotation + 90f) % 360f; true }
                            Key.DirectionDown -> { rotation = (rotation + 270f) % 360f; true }
                            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                                slideshow = if (slideshow == 0) (1..Int.MAX_VALUE).random() else 0; true
                            }
                            else -> false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = Shown(path, rotation),
                    animationSpec = tween(CrossfadeMs, easing = TvMotion.GlideEase),
                    label = "photo",
                ) { shown ->
                    val ref = MediaRef.parse(shown.path)
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(
                                when {
                                    ref is MediaRef.Local -> File(ref.path)
                                    ref.needsDriveAuth && driveToken == null -> null // wait for the token rather than fire a request that 401s
                                    else -> ref.uri.toString()
                                },
                            )
                            .apply { if (ref.needsDriveAuth && driveToken != null) addHeader("Authorization", "Bearer $driveToken") }
                            .build(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().rotate(shown.rotation),
                    )
                }
                Text(
                    "$title   ${index + 1} / ${paths.size}" + if (slideshow != 0) "   ▶ Slideshow" else "",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }

    /** What's on screen: a photo, turned so far. */
    private data class Shown(val path: String, val rotation: Float)

    companion object {
        /** One photo fading into the next. */
        private const val CrossfadeMs = 450
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_START = "start"
        private const val EXTRA_TITLES = "titles"

        /** [paths] are [MediaRef] keys (local paths or "drive:<id>"); [titles] line up with them. */
        fun intent(context: Context, paths: List<String>, start: String, titles: List<String> = emptyList()): Intent =
            Intent(context, ImageViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths.ifEmpty { listOf(start) }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_START, start)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
