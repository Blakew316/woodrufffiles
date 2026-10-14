package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Built-in video/music player. Many Android TV devices ship without a general-purpose media
 * player, so files open here by default; "Open with…" still offers VLC, Kodi, etc.
 * The rest of the folder is queued so Next/Previous (and media keys) move through it.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowNextButton(true)
            setShowPreviousButton(true)
            keepScreenOn = true
        }
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val start = savedIndex ?: paths.indexOf(intent.getStringExtra(EXTRA_START)).coerceAtLeast(0)
        val items = paths.map { path ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(path)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(File(path).name).build())
                .build()
        }
        player = ExoPlayer.Builder(this).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Unsupported codec etc.: say so and move on rather than sitting on a black screen.
                    Toast.makeText(this@PlayerActivity, "Can't play this file (${error.errorCodeName}). Try Open with…", Toast.LENGTH_LONG).show()
                    if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() }
                }
            })
            p.setMediaItems(items, start, savedPosition)
            p.playWhenReady = true
            p.prepare()
            view.player = p
        }
    }

    override fun onStop() {
        // Remember where we were so returning from the home screen resumes the same file and spot.
        player?.let { savedIndex = it.currentMediaItemIndex; savedPosition = it.currentPosition; it.release() }
        player = null
        super.onStop()
    }

    /** Remote shortcuts: OK toggles the controls; media keys are handled by PlayerView already. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!view.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            view.showController()
            return true
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private var savedIndex: Int? = null
    private var savedPosition = 0L

    companion object {
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_START = "start"

        fun intent(context: Context, paths: List<String>, start: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths.ifEmpty { listOf(start) }))
                .putExtra(EXTRA_START, start)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
