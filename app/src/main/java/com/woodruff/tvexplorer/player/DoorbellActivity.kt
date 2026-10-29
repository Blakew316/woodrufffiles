package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.ui.screens.LiveVideo
import com.woodruff.tvexplorer.ui.theme.ThemeSetting
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme

/**
 * The doorbell's camera, live with its sound, opened with OK on the player's "Someone's at the
 * door" card. The film waits underneath (the player keeps its place while it's covered) and Back
 * returns to it. Left / Right: the other cameras.
 */
class DoorbellActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); com.woodruff.tvexplorer.remote.TvScreen.attach(this) }
    override fun onStop() { com.woodruff.tvexplorer.remote.TvScreen.detach(this); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSetting.load(this)
        Nest.warm() // lists the cameras if this TV hasn't yet
        val camera = intent.getStringExtra(EXTRA_CAMERA)

        setContent {
            TvExplorerTheme {
                val state by Nest.state.collectAsStateWithLifecycle()
                val cams = state.cameras
                // The camera that rang first, then Left / Right go through the rest.
                var index by remember { mutableIntStateOf(-1) }
                LaunchedEffect(cams) {
                    if (index < 0 && cams.isNotEmpty()) index = cams.indexOfFirst { it.name == camera }.coerceAtLeast(0)
                }
                val cam = cams.getOrNull(index)
                val focus = remember { FocusRequester() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .focusRequester(focus)
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || cams.size < 2 || index < 0) return@onKeyEvent false
                            when (e.key) {
                                Key.DirectionRight -> { index = (index + 1) % cams.size; true }
                                Key.DirectionLeft -> { index = (index - 1 + cams.size) % cams.size; true }
                                else -> false
                            }
                        }
                        .focusable(),
                ) {
                    if (cam != null && cam.webRtc) key(cam.name) { LiveVideo(cam, audio = true) }
                    else Text(
                        when {
                            cam != null -> "This camera model doesn't support live view here"
                            state.step != NestStep.CONNECTED -> "Connect your cameras in the Command center first"
                            else -> state.error ?: "Connecting…"
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                    Column(Modifier.align(Alignment.TopStart).padding(28.dp)) {
                        Text(cam?.label ?: "Doorbell", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(
                            if (cams.size > 1) "◀ ▶ switch camera  ·  Back to keep watching" else "Back to keep watching",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }

    companion object {
        private const val EXTRA_CAMERA = "camera"

        /** [camera]: the resource name of the camera that rang (as [com.woodruff.tvexplorer.nest.Doorbell.rings] gives it). */
        fun intent(context: Context, camera: String): Intent =
            Intent(context, DoorbellActivity::class.java).putExtra(EXTRA_CAMERA, camera)
    }
}
