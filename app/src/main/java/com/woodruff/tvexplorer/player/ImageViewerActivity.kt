package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.io.File

/**
 * Full-screen photo viewer for local and Google Drive photos. Left/Right: previous/next. Up: rotate. OK: start/stop slideshow.
 */
class ImageViewerActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); com.woodruff.tvexplorer.remote.TvScreen.attach(this) }
    override fun onStop() { com.woodruff.tvexplorer.remote.TvScreen.detach(this); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val start = paths.indexOf(intent.getStringExtra(EXTRA_START)).coerceAtLeast(0)
        if (paths.isEmpty()) { finish(); return }
        val needsDrive = paths.any { MediaRef.parse(it).needsDriveAuth }

        setContent {
            var index by remember { mutableIntStateOf(start) }
            var rotation by remember { mutableFloatStateOf(0f) }
            var slideshow by remember { mutableIntStateOf(0) } // 0 = off, otherwise a run token
            val focus = remember { FocusRequester() }
            val context = LocalContext.current
            val driveToken by produceState<String?>(null) { if (needsDrive) value = GoogleAuth.token(context) }
            val ref = MediaRef.parse(paths[index])
            val title = titles.getOrNull(index) ?: File(paths[index]).name
            fun go(delta: Int) { index = (index + delta + paths.size) % paths.size; rotation = 0f }

            LaunchedEffect(slideshow) {
                while (slideshow != 0) { delay(5000); go(1) }
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
                    modifier = Modifier.fillMaxSize().rotate(rotation),
                )
                Text(
                    "$title   ${index + 1} / ${paths.size}" + if (slideshow != 0) "   ▶ Slideshow" else "",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }

    companion object {
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
