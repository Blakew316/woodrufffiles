package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.woodruff.tvexplorer.cloud.GoogleAuth

/**
 * Built-in video/music player for local files and Google Drive. Many Android TV devices ship
 * without a general-purpose player, so media opens here by default; "Open with…" still offers
 * VLC, Kodi, etc. The rest of the folder is queued so Next/Previous move through it, and the
 * position is saved so playback resumes and shows up under "Continue watching".
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var recent: RecentStore
    private var savedIndex: Int? = null
    private var savedPosition: Long? = null

    // Drive requests need a bearer token. Play services caches it, but asking costs an IPC,
    // so reuse it for a while; tokens live for about an hour.
    @Volatile private var driveToken: String? = null
    @Volatile private var driveTokenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recent = RecentStore(this)
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
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        if (keys.isEmpty()) { finish(); return }
        val start = savedIndex ?: keys.indexOf(intent.getStringExtra(EXTRA_START)).coerceAtLeast(0)
        val startPos = savedPosition ?: recent.resumePosition(keys[start])

        val items = keys.mapIndexed { i, key ->
            val ref = MediaRef.parse(key)
            MediaItem.Builder()
                .setMediaId(key)
                .setUri(ref.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrNull(i) ?: ref.uri.lastPathSegment).build())
                .build()
        }

        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(dataSources())).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Unsupported codec etc.: say so and move on rather than sitting on a black screen.
                    Toast.makeText(this@PlayerActivity, "Can't play this file (${error.errorCodeName}). Try Open with…", Toast.LENGTH_LONG).show()
                    if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        // Finished the previous one: drop it from Continue watching.
                        val prev = p.previousMediaItemIndex.takeIf { it >= 0 }?.let { p.getMediaItemAt(it) }
                        prev?.mediaId?.let(recent::remove)
                    }
                }
            })
            p.setMediaItems(items, start, startPos)
            p.playWhenReady = true
            p.prepare()
            view.player = p
        }
    }

    override fun onStop() {
        player?.let { p ->
            savedIndex = p.currentMediaItemIndex
            savedPosition = p.currentPosition
            p.currentMediaItem?.let { item ->
                recent.save(item.mediaId, item.mediaMetadata.title?.toString() ?: "", p.currentPosition, p.duration.coerceAtLeast(0))
            }
            p.release()
        }
        player = null
        super.onStop()
    }

    /** Local files pass straight through; Drive URLs get an Authorization header per request. */
    private fun dataSources(): DefaultDataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val authed = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.host?.endsWith("googleapis.com") == true) {
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${token()}"))
            } else spec
        }
        return DefaultDataSource.Factory(this, authed)
    }

    /** Runs on ExoPlayer's loading thread, so blocking here is fine. */
    private fun token(): String {
        val now = System.currentTimeMillis()
        driveToken?.takeIf { now - driveTokenAt < 30 * 60_000 }?.let { return it }
        val t = GoogleAuth.blockingToken(applicationContext) ?: ""
        driveToken = t
        driveTokenAt = now
        return t
    }

    /** OK shows the controls; everything else (media keys, D-pad seeking) PlayerView handles. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!view.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            view.showController()
            return true
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_START = "start"

        /** [keys] are [MediaRef] keys; [titles] line up with them. */
        fun intent(context: Context, keys: List<String>, titles: List<String>, start: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys.ifEmpty { listOf(start) }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_START, start)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
