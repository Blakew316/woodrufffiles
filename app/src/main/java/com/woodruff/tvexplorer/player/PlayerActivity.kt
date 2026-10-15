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
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
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
            player?.let { updateSkip(it) }
            if (leader && ++syncTicks % 10 == 0) sendSync()
            reporter.postDelayed(this, 1_000)
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
            // Subtitles button, and the settings (gear) menu with audio track and speed.
            setShowSubtitleButton(true)
            keepScreenOn = true
        }
        // Our own button next to them: sleep timer and "Skip intro" setup.
        runCatching {
            val bar = view.findViewById<android.view.ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls)
            bar?.addView(android.widget.ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_recent_history)
                background = null
                contentDescription = "Sleep timer and intro"
                setOnClickListener { showOptions() }
            }, 0)
        }
        skip = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            text = "Skip intro  ▸  OK"
            setPadding(44, 26, 44, 26)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xE6101820.toInt()); cornerRadius = 36f }
            visibility = android.view.View.GONE
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
        root.addView(skip, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply { setMargins(0, 0, 72, 72) })
        setContentView(root)
    }

    // ---- Sleep timer, skip intro, remembered tracks ----------------------------------------

    private lateinit var skip: android.widget.TextView

    // ---- Watch together ----------------------------------------------------------------------
    /** This TV started a watch-together: it leads, the linked TVs follow. */
    private val leader get() = intent.getBooleanExtra(EXTRA_TOGETHER, false)
    private var syncTicks = 0
    private var followJob: kotlinx.coroutines.Job? = null

    private fun sendSync() {
        val p = player ?: return
        val key = p.currentMediaItem?.mediaId ?: return
        val pos = p.currentPosition
        val playing = p.playWhenReady
        val app = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.woodruff.tvexplorer.tvs.TvLinks.forEachLinked(app) { com.woodruff.tvexplorer.tvs.TvLinks.sync(it, key, pos, playing) }
        }
    }

    private fun follow(u: WatchSync.Update) {
        val p = player ?: return
        if (leader) return
        // Where the leader is now: its position plus the time the message took (clocks roughly agree on a LAN).
        val lag = (System.currentTimeMillis() - u.sentAt).coerceIn(0, 2_000)
        val target = u.positionMs + if (u.playing) lag else 0
        if (p.currentMediaItem?.mediaId != u.key) {
            // The leader moved on to another episode: follow it there if it's in this queue.
            val i = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == u.key } ?: return
            cancelUpNext()
            p.seekTo(i, target)
        } else if (kotlin.math.abs(p.currentPosition - target) > if (u.playing) 2_000 else 300) {
            // Playing: only correct real drift. Paused: line up exactly on the leader's frame.
            p.seekTo(target)
        }
        if (u.playing && upNext.visibility == android.view.View.VISIBLE) cancelUpNext()
        p.playWhenReady = u.playing
    }
    private var sleepAtEnd = false
    private val sleep = Runnable { sleepNow() }

    /** The current episode's show and its intro end, worked out when the episode starts (not every second). */
    private var showCache: Pair<String?, String> = null to ""
    private var introEnd = 0L
    private fun show(): String {
        val id = player?.currentMediaItem?.mediaId
        if (showCache.first != id) {
            showCache = id to ShowPrefs.showKey(player?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "")
            introEnd = ShowPrefs.introEnd(this, showCache.second)
        }
        return showCache.second
    }

    /** Stop for the night: pause, leave the player, and let the slideshow take over the screen. */
    private fun sleepNow() {
        player?.pause()
        runCatching { com.woodruff.tvexplorer.screensaver.SlideshowActivity.start(this) }
        finish()
    }

    private fun showOptions() {
        val p = player ?: return
        val intro = ShowPrefs.introEnd(this, show())
        val here = p.currentPosition
        val items = arrayOf(
            "Sleep in 30 minutes", "Sleep in 60 minutes", "Sleep in 90 minutes", "Sleep at the end of this episode", "Sleep timer off",
            "Intro ends here (${com.woodruff.tvexplorer.util.Format.duration(here)}): use for this show",
            if (intro > 0) "Forget this show's intro" else null,
        ).filterNotNull().toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Player options")
            .setItems(items) { _, which ->
                countdown.removeCallbacks(sleep); sleepAtEnd = false
                when (which) {
                    0, 1, 2 -> { val min = 30L * (which + 1); countdown.postDelayed(sleep, min * 60_000L); Toast.makeText(this, "Sleeping in $min minutes", Toast.LENGTH_SHORT).show() }
                    3 -> { sleepAtEnd = true; Toast.makeText(this, "Sleeping after this episode", Toast.LENGTH_SHORT).show() }
                    4 -> Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
                    5 -> { ShowPrefs.setIntroEnd(this, show(), here); introEnd = here; Toast.makeText(this, "Skip intro will jump here", Toast.LENGTH_SHORT).show() }
                    6 -> { ShowPrefs.setIntroEnd(this, show(), 0); introEnd = 0 }
                }
            }
            .show()
    }

    /** "Skip intro" while inside this show's intro. */
    private fun updateSkip(p: ExoPlayer) {
        show() // refreshes introEnd when the episode changed
        val end = introEnd
        val pos = p.currentPosition
        skip.visibility = if (end > 0 && pos in 1_000 until end - 2_000 && upNext.visibility != android.view.View.VISIBLE) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** This show's audio / subtitle choice, applied when an episode starts. */
    private fun applyShowTracks(p: ExoPlayer) {
        val s = show()
        val audio = ShowPrefs.audio(this, s)
        val text = ShowPrefs.text(this, s)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            if (audio != null) setPreferredAudioLanguage(audio)
            when (text) {
                null -> setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                ShowPrefs.OFF -> setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                else -> setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(text)
            }
        }.build().also { ownParams = it }
    }

    /** The track settings this activity applied itself; anything else came from the viewer's menus. */
    private var ownParams: androidx.media3.common.TrackSelectionParameters? = null
    private var userPickedTracks = false

    /** Remember what's playing (picked in the settings / subtitles menus) for the rest of the show. */
    private fun rememberTracks(tracks: androidx.media3.common.Tracks) {
        var audio: String? = null
        var text: String? = ShowPrefs.OFF
        for (g in tracks.groups) {
            if (!g.isSelected) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                val lang = g.getTrackFormat(i).language ?: continue
                if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) audio = lang
                if (g.type == androidx.media3.common.C.TRACK_TYPE_TEXT) text = lang
            }
        }
        if (tracks.groups.none { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }) text = null // nothing to choose
        ShowPrefs.setLanguages(this, show(), audio, text)
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

                override fun onIsPlayingChanged(isPlaying: Boolean) { if (leader) sendSync() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (leader && reason == Player.DISCONTINUITY_REASON_SEEK) sendSync()
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (tracks.isEmpty) return
                    // Music runs straight on; only video pauses at the end for the Up next countdown.
                    p.pauseAtEndOfMediaItems = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    // Remember tracks only when the viewer picked them, not the player's automatic choice.
                    if (userPickedTracks) { userPickedTracks = false; rememberTracks(tracks) }
                }

                override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                    if (parameters != ownParams) userPickedTracks = true
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // Last item of a music queue (no pause at the end): leave like a video does.
                    if (state == Player.STATE_ENDED) { p.currentMediaItem?.mediaId?.let(recent::remove); finish() }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
                    if (sleepAtEnd) { sleepNow(); return }
                    if (p.hasNextMediaItem()) startUpNext()
                    else { p.currentMediaItem?.mediaId?.let(recent::remove); finish() }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    cancelUpNext()
                    applyShowTracks(p)
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
            applyShowTracks(p)
            p.playWhenReady = true
            p.prepare()
            view.player = p
        }
        reporter.post(report)
        if (!leader) followJob = lifecycleScope.launch { WatchSync.updates.collect { follow(it) } }
    }

    override fun onStop() {
        com.woodruff.tvexplorer.remote.TvScreen.detach(this)
        reporter.removeCallbacks(report)
        followJob?.cancel(); followJob = null
        cancelUpNext()
        countdown.removeCallbacks(sleep)
        com.woodruff.tvexplorer.nowplaying.NowPlaying.ownPlayer = null
        player?.let { p ->
            savedIndex = p.currentMediaItemIndex
            savedPosition = p.currentPosition
            p.currentMediaItem?.let { item ->
                // Watched to the end: it stays out of Continue watching instead of coming back at its last second.
                if (p.duration > 0 && p.duration - p.currentPosition < 5_000) recent.remove(item.mediaId)
                else recent.save(item.mediaId, item.mediaMetadata.title?.toString() ?: "", p.currentPosition, p.duration.coerceAtLeast(0))
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
            // Only Google's own hosts over https get the token: never a look-alike or a plain-http redirect.
            val host = spec.uri.host.orEmpty()
            if (spec.uri.scheme == "https" && (host == "googleapis.com" || host.endsWith(".googleapis.com"))) {
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
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO)) {
            showOptions(); return true
        }
        if (skip.visibility == android.view.View.VISIBLE && !view.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            show(); player?.seekTo(introEnd); skip.visibility = android.view.View.GONE; return true
        }
        if (upNext.visibility == android.view.View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playNext(); return true }
                KeyEvent.KEYCODE_BACK -> {
                    // Stop: this one is finished, so leave rather than sit paused on its last frame.
                    cancelUpNext(); player?.currentMediaItem?.mediaId?.let(recent::remove); finish(); return true
                }
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
        private const val EXTRA_TOGETHER = "together"

        /** [keys] are [MediaRef] keys; [titles] line up with them. */
        fun intent(context: Context, keys: List<String>, titles: List<String>, start: String, positionMs: Long? = null, together: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_TOGETHER, together)
                .apply { if (positionMs != null) putExtra(EXTRA_POSITION, positionMs) }
                .putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys.ifEmpty { listOf(start) }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_START, start)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
