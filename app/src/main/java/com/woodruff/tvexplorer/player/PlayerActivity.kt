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
    private lateinit var upNext: android.widget.TextView
    private val countdown = android.os.Handler(android.os.Looper.getMainLooper())
    private var secondsLeft = 0
    private lateinit var recent: RecentStore
    private var savedIndex: Int? = null
    private var savedPosition: Long? = null

    // Drive requests need a bearer token. Play services caches it, but asking costs an IPC,
    // so reuse it for a while; tokens live for about an hour.
    @Volatile private var driveToken: String? = null
    @Volatile private var driveTokenAt = 0L

    /** Shares title / play state / position with other TVs and the phone (see NowPlaying). */
    private val reporter = android.os.Handler(android.os.Looper.getMainLooper())
    private val report = object : Runnable {
        override fun run() {
            player?.let { p ->
                com.woodruff.tvexplorer.nowplaying.NowPlaying.ownPlayer = com.woodruff.tvexplorer.nowplaying.Playing(
                    title = p.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Video",
                    subtitle = null, app = "Woodruff Family", playing = p.isPlaying,
                    positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.coerceAtLeast(0),
                )
            }
            reporter.postDelayed(this, 2_000)
        }
    }

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
        // "Up next" card, bottom right, shown when an episode ends and there's another queued.
        upNext = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setPadding(48, 32, 48, 32)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6101820.toInt()); cornerRadius = 36f
            }
            visibility = android.view.View.GONE
        }
        val root = android.widget.FrameLayout(this)
        root.addView(view)
        root.addView(upNext, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply { setMargins(0, 0, 72, 72) })
        setContentView(root)
    }

    /** End of an episode with another one queued: count down 10 s, then play it. OK plays now, Back stops. */
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (secondsLeft <= 0) { playNext(); return }
            val next = p.getMediaItemAt(p.nextMediaItemIndex).mediaMetadata.title ?: "Next"
            upNext.text = "Up next: $next\nPlaying in $secondsLeft  ·  OK to play now  ·  Back to stop"
            secondsLeft--
            countdown.postDelayed(this, 1_000)
        }
    }

    private fun startUpNext() {
        secondsLeft = 10
        upNext.visibility = android.view.View.VISIBLE
        countdown.removeCallbacks(tick)
        countdown.post(tick)
    }

    private fun cancelUpNext() {
        countdown.removeCallbacks(tick)
        upNext.visibility = android.view.View.GONE
    }

    private fun playNext() {
        cancelUpNext()
        val p = player ?: return
        // Finished: drop it from Continue watching, then on to the next one.
        p.currentMediaItem?.mediaId?.let(recent::remove)
        if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.play() }
    }

    override fun onStart() {
        super.onStart()
        com.woodruff.tvexplorer.remote.TvScreen.attach(this)
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        if (keys.isEmpty()) { finish(); return }
        val start = savedIndex ?: keys.indexOf(intent.getStringExtra(EXTRA_START)).coerceAtLeast(0)
        val startPos = savedPosition ?: intent.getLongExtra(EXTRA_POSITION, -1L).takeIf { it >= 0 } ?: recent.resumePosition(keys[start])

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

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
                    if (p.hasNextMediaItem()) startUpNext()
                    else { p.currentMediaItem?.mediaId?.let(recent::remove); finish() }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    cancelUpNext()
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        // Finished the previous one: drop it from Continue watching.
                        val prev = p.previousMediaItemIndex.takeIf { it >= 0 }?.let { p.getMediaItemAt(it) }
                        prev?.mediaId?.let(recent::remove)
                    }
                }
            })
            // Pause at the end of each item so the "Up next" countdown can run (and be cancelled).
            p.pauseAtEndOfMediaItems = true
            p.setMediaItems(items, start, startPos)
            p.playWhenReady = true
            p.prepare()
            view.player = p
        }
        reporter.post(report)
    }

    override fun onStop() {
        com.woodruff.tvexplorer.remote.TvScreen.detach(this)
        reporter.removeCallbacks(report)
        cancelUpNext()
        com.woodruff.tvexplorer.nowplaying.NowPlaying.ownPlayer = null
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
        if (upNext.visibility == android.view.View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playNext(); return true }
                KeyEvent.KEYCODE_BACK -> { cancelUpNext(); return true }
            }
        }
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
        private const val EXTRA_POSITION = "position"

        /** [keys] are [MediaRef] keys; [titles] line up with them. */
        fun intent(context: Context, keys: List<String>, titles: List<String>, start: String, positionMs: Long? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .apply { if (positionMs != null) putExtra(EXTRA_POSITION, positionMs) }
                .putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys.ifEmpty { listOf(start) }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_START, start)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
