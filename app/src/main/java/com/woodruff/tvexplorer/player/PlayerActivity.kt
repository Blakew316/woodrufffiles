package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.net.Http
import java.util.concurrent.TimeUnit

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
    /** The queue as it was when the player last stopped (it can grow: see [fillQueueFromFolder]). */
    private var savedQueue: Pair<List<String>, List<String>>? = null
    /** One retry with a fresh Google token after a sign-in error, per item. */
    private var retriedKey: String? = null
    /** Waiting for the connection (or Google) to come back, on this item: retries without skipping it. */
    private val retry = android.os.Handler(android.os.Looper.getMainLooper())
    private var stalledKey: String? = null
    /** The item playing a moment ago and where it was, saved when Next / Previous moves on from it. */
    private var lastSeen: RecentItem? = null
    /** The key whose OK / Back press was handled here: its release is ours too (PlayerView would pop the controls up). */
    private var swallowUp: Int? = null
    /** How "Hey Google, pause", Alexa and Bluetooth buttons reach the player; one per player. */
    private var session: MediaSession? = null
    /** Playing or paused when the player last stopped, so it comes back the same way. */
    private var savedPlayWhenReady: Boolean? = null
    /** What a picture is awaited for (the start, a seek) and since when, for the log. */
    private var waitingForPicture: Pair<String, Long>? = null

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
            player?.let { p ->
                updateSkip(p)
                matchFrameRate(p)
                queueNextSeason(p)
                p.currentMediaItem?.let { item ->
                    lastSeen = RecentItem(item.mediaId, item.mediaMetadata.title?.toString() ?: "", p.currentPosition, p.duration.coerceAtLeast(0), 0)
                }
            }
            updateScreenOn()
            if (leader && ++syncTicks % 10 == 0) sendSync()
            reporter.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recent = RecentStore(this)
        com.woodruff.tvexplorer.cloud.EpisodeShows.load(this) // which show name-less episodes belong to
        view = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowNextButton(true)
            setShowPreviousButton(true)
            // Subtitles button, and the settings (gear) menu with audio track and speed.
            setShowSubtitleButton(true)
        }
        // Our own button next to them: sleep timer and "Skip intro" setup.
        runCatching {
            val bar = view.findViewById<android.view.ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls)
            val px = (60 * resources.displayMetrics.density).toInt()
            val pad = (14 * resources.displayMetrics.density).toInt()
            bar?.addView(android.widget.ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_recent_history)
                // Same look and focus ring as the player's own buttons.
                setBackgroundResource(com.woodruff.tvexplorer.R.drawable.player_button_bg)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                contentDescription = "Sleep timer and intro"
                setOnClickListener { showOptions() }
            }, 0, android.widget.LinearLayout.LayoutParams(px, px).apply { setMargins(pad / 2, 0, pad / 2, 0) })
        }
        skip = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            text = "Skip intro  ▸  OK"
            setPadding(44, 26, 44, 26)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xE6101820.toInt()); cornerRadius = 36f }
            visibility = android.view.View.GONE
        }
        // "Next episode" during the end credits, like Netflix: OK skips them.
        nextEp = android.widget.TextView(this).apply {
            setTextColor(0xFF0B1220.toInt())
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "Next episode  ▸  OK"
            setPadding(48, 26, 48, 26)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = 36f }
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
        // "Someone's at the door" when the doorbell rings, in the same corner.
        doorbell = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setPadding(48, 32, 48, 32)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6101820.toInt()); cornerRadius = 36f
            }
            visibility = android.view.View.GONE
        }
        // What's playing, big at the top whenever the controls are up (they had no title at all).
        titleBar = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(88, 56, 88, 120)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000),
            )
            visibility = android.view.View.GONE
        }
        view.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { v ->
            titleBar.text = player?.currentMediaItem?.mediaMetadata?.title ?: ""
            titleBar.visibility = v
            // The corner buttons would cover the controls' own (and OK goes to the controls then).
            if (v == android.view.View.VISIBLE) { skip.visibility = android.view.View.GONE; nextEp.visibility = android.view.View.GONE }
            else player?.let { updateSkip(it) }
            // The doorbell's card waits behind them too.
            doorbell.visibility = if (ringing && v != android.view.View.VISIBLE) android.view.View.VISIBLE else android.view.View.GONE
        })
        val root = android.widget.FrameLayout(this)
        root.addView(view)
        root.addView(titleBar, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP,
        ))
        // The doorbell's card sits above Up next's when both are up.
        root.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            addView(doorbell, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 24 })
            addView(upNext, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply { setMargins(0, 0, 72, 72) })
        root.addView(nextEp, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply { setMargins(0, 0, 72, 72) })
        root.addView(skip, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply { setMargins(0, 0, 72, 72) })
        // Scrubbing preview: a frame from that point of the film, above the progress bar.
        val d = resources.displayMetrics.density
        previewImage = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 8 * d) }
            }
        }
        previewTime = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        previewBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (4 * d).toInt()
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6101820.toInt()); cornerRadius = 11 * d; setStroke((2 * d).toInt(), android.graphics.Color.WHITE)
            }
            addView(previewImage, android.widget.LinearLayout.LayoutParams((PREVIEW_W * d).toInt(), (PREVIEW_H * d).toInt()))
            addView(previewTime, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = android.view.View.GONE
        }
        root.addView(previewBox, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.START,
        ))
        rootView = root
        setContentView(root)
        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) = showPreview(timeBar as android.view.View, position, starting = true)
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) = showPreview(timeBar as android.view.View, position, starting = false)
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                preview?.cancel()
                previewBox.visibility = android.view.View.GONE
            }
        })
    }

    /**
     * Watched to the end: out of Continue watching, marked ✓ on its poster / episode row, and the
     * show's next episode offered there instead.
     */
    private fun finished(key: String) {
        recent.remove(key)
        Watched.mark(this, key)
        upNextAfter(key)
        // The family's other TVs hear at once (as the player closes, onStop sends it instead).
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) com.woodruff.tvexplorer.cloud.FamilySync.pushNow(this)
    }

    /** The episode queued after [key], when it's the same show's: "Next" on Home and the show's page. */
    private fun upNextAfter(key: String) {
        val p = player ?: return
        val queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
        val i = UpNext.indexAfter(queue.map { it.mediaId }, key) ?: return
        val title = queue[i - 1].mediaMetadata.title?.toString().orEmpty()
        val next = queue[i]
        val nextTitle = next.mediaMetadata.title?.toString().orEmpty()
        if (ShowPrefs.isEpisode(title) && showFor(next.mediaId, nextTitle) == showFor(key, title)) recent.saveUpNext(next.mediaId, nextTitle)
    }

    // ---- Scrubbing preview ----------------------------------------------------------------------

    private lateinit var rootView: android.widget.FrameLayout
    private lateinit var previewBox: android.widget.LinearLayout
    private lateinit var previewImage: android.widget.ImageView
    private lateinit var previewTime: android.widget.TextView
    private var preview: ScrubPreview? = null

    /** Where the preview frames come from: the same file the player streams (Drive with its token). Off the main thread. */
    private fun previewSource(key: String): Pair<String, Map<String, String>>? = when (val ref = MediaRef.parse(key)) {
        is MediaRef.Drive -> token().takeIf { it.isNotEmpty() }?.let { com.woodruff.tvexplorer.cloud.DriveApi.mediaUrl(ref.id) to mapOf("Authorization" to "Bearer $it") }
        is MediaRef.Local -> ref.path to emptyMap()
        is MediaRef.Remote -> ref.url to emptyMap()
    }

    private fun showPreview(timeBar: android.view.View, position: Long, starting: Boolean) {
        val p = player ?: return
        val key = p.currentMediaItem?.mediaId ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val pv = preview ?: return
        previewTime.text = com.woodruff.tvexplorer.util.Format.duration(position)
        val cached = pv.cached(key, position)
        if (cached != null) previewImage.setImageBitmap(cached) else if (starting) previewImage.setImageDrawable(null)
        pv.request(key, position) { previewImage.setImageBitmap(it) }
        // Centered over the scrub point, kept on screen, just above the bar.
        val d = resources.displayMetrics.density
        val boxW = (PREVIEW_W + 10) * d
        val boxH = (PREVIEW_H + 10 + 26) * d
        val bar = IntArray(2).also(timeBar::getLocationInWindow)
        val rootPos = IntArray(2).also(rootView::getLocationInWindow)
        val x = bar[0] - rootPos[0] + timeBar.width * (position.toFloat() / duration) - boxW / 2
        previewBox.translationX = x.coerceIn(24 * d, (rootView.width - boxW - 24 * d).coerceAtLeast(24 * d))
        previewBox.translationY = (bar[1] - rootPos[1] - boxH - 18 * d).coerceAtLeast(24 * d)
        previewBox.visibility = android.view.View.VISIBLE
    }

    // ---- Sleep timer, skip intro, remembered tracks ----------------------------------------

    private lateinit var skip: android.widget.TextView
    private lateinit var titleBar: android.widget.TextView
    private lateinit var nextEp: android.widget.TextView
    /** The episode whose "Next episode" button was waved away with Back (don't show it again for it). */
    private var nextDismissedFor: String? = null

    // ---- Watch together ----------------------------------------------------------------------
    /** This TV started a watch-together: it leads, the linked TVs follow. */
    private val leader get() = intent.getBooleanExtra(EXTRA_TOGETHER, false)
    private var syncTicks = 0
    private var followJob: kotlinx.coroutines.Job? = null
    /** When the leader last said where it is (this TV follows one): the screen stays on meanwhile. */
    private var syncedAt: Long? = null

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
        syncedAt = android.os.SystemClock.elapsedRealtime()
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
            showCache = id to showFor(id, player?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "")
            introEnd = ShowPrefs.introEnd(this, showCache.second)
        }
        return showCache.second
    }

    /**
     * Which show an item belongs to: from its name ("Show S01E02"), else, when the name only has
     * the episode ("S01E013_Pilot"), the show it was found under ([EpisodeShows]).
     */
    private fun showFor(key: String?, title: String): String =
        if (ShowPrefs.namesShow(title)) ShowPrefs.showKey(title)
        else key?.let { com.woodruff.tvexplorer.cloud.EpisodeShows.map.value[it] }?.let { "show:$it" } ?: ShowPrefs.showKey(title)

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
        val free = upNext.visibility != android.view.View.VISIBLE && !view.isControllerFullyVisible && !ringing
        skip.visibility = if (end > 0 && pos in 1_000 until end - 2_000 && free) android.view.View.VISIBLE else android.view.View.GONE
        // The last 40 s of an episode with the next one of the same show queued: the credits,
        // roughly. Not for films or home videos (it said "Next episode" over the end of a movie).
        val left = p.duration - pos
        val key = p.currentMediaItem?.mediaId
        val title = p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        val next = if (p.hasNextMediaItem()) p.getMediaItemAt(p.nextMediaItemIndex) else null
        val nextTitle = next?.mediaMetadata?.title?.toString().orEmpty()
        nextEp.visibility = if (
            p.hasNextMediaItem() && p.duration > 60_000 && left in 1..40_000 && p.pauseAtEndOfMediaItems && free &&
            key != nextDismissedFor && ShowPrefs.isEpisode(title) && showFor(next?.mediaId, nextTitle) == show()
        ) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** This show's audio / subtitle choice, applied when an episode starts. */
    private fun applyShowTracks(p: ExoPlayer) {
        val s = show()
        val audio = ShowPrefs.audio(this, s)
        val text = ShowPrefs.text(this, s)
        val textType = androidx.media3.common.C.TRACK_TYPE_TEXT
        setOwnParams(p, p.trackSelectionParameters.buildUpon().apply {
            // A new episode starts from the show's choices, not the last one's picks.
            clearOverridesOfType(textType)
            setTrackTypeDisabled(textType, false)
            if (audio != null) setPreferredAudioLanguage(audio)
            when (text) {
                null -> setIgnoredTextSelectionFlags(0).setPreferredTextLanguage(null)
                // Like the menu's own "Off": no subtitles, but signs-only ("forced") ones still show.
                ShowPrefs.OFF -> setIgnoredTextSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_FORCED.inv()).setPreferredTextLanguage(null)
                else -> setIgnoredTextSelectionFlags(0).setPreferredTextLanguage(text)
            }
        }.build())
    }

    /** Applies track settings as the player's own (not the viewer's choice), and notes them. */
    private fun setOwnParams(p: ExoPlayer, params: androidx.media3.common.TrackSelectionParameters) {
        ownParams = params
        p.trackSelectionParameters = params
        lastParams = p.trackSelectionParameters
    }

    /** English subtitles were switched on here (not by the viewer) for the current item. */
    private var autoSubsOn = false

    /**
     * Subtitles follow the speech while the show has no subtitle choice of its own: English ones
     * come on (without asking) when it isn't English, and go off again if the speech is switched
     * to English. A choice made in the subtitles menu (including Off) is kept for the show and wins.
     */
    private fun englishSubtitlesIfForeign(p: ExoPlayer, tracks: androidx.media3.common.Tracks) {
        if (ShowPrefs.text(this, show()) != null) return
        val text = androidx.media3.common.C.TRACK_TYPE_TEXT
        val audio = tracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        if (!ShowPrefs.isForeign(audio)) {
            if (autoSubsOn && ShowPrefs.isEnglish(audio)) {
                autoSubsOn = false
                setOwnParams(p, p.trackSelectionParameters.buildUpon().clearOverridesOfType(text).build())
            }
            return
        }
        // English subtitles in the file: full ones before "forced" (signs-only) ones.
        val choices = tracks.groups.filter { it.type == text }.flatMap { g -> (0 until g.length).map { g to it } }
            .filter { (g, i) -> g.isTrackSupported(i) && ShowPrefs.isEnglish(g.getTrackFormat(i).language) }
        val (group, index) = choices.firstOrNull { (g, i) -> g.getTrackFormat(i).selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED == 0 }
            ?: choices.firstOrNull() ?: return
        if (group.isTrackSelected(index)) return
        autoSubsOn = true
        setOwnParams(
            p,
            p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(text, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
                .build(),
        )
    }

    /** The track settings this activity applied itself; anything else came from the viewer's menus. */
    private var ownParams: androidx.media3.common.TrackSelectionParameters? = null
    /** The settings before the latest change, to tell what the viewer changed. */
    private var lastParams: androidx.media3.common.TrackSelectionParameters? = null
    /** Track types (audio, text) the viewer changed in the menus, not yet remembered. */
    private var userChanged: Set<Int> = emptySet()

    /** The audio or subtitle types that differ between two sets of track settings. */
    private fun changedTypes(a: androidx.media3.common.TrackSelectionParameters, b: androidx.media3.common.TrackSelectionParameters): Set<Int> {
        fun of(p: androidx.media3.common.TrackSelectionParameters, type: Int): List<Any?> = listOf(
            p.overrides.values.filter { it.type == type },
            type in p.disabledTrackTypes,
            if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) p.ignoredTextSelectionFlags else 0,
            if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) p.preferredTextLanguages else p.preferredAudioLanguages,
        )
        return setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO, androidx.media3.common.C.TRACK_TYPE_TEXT).filter { of(a, it) != of(b, it) }.toSet()
    }

    /**
     * Remember what the viewer picked in the menus for the rest of the show — only what they
     * changed. (Switching just the audio used to save "subtitles off" too, which then kept English
     * subtitles away from every later episode.)
     */
    private fun rememberTracks(p: ExoPlayer, tracks: androidx.media3.common.Tracks, changed: Set<Int>) {
        val audioType = androidx.media3.common.C.TRACK_TYPE_AUDIO
        val textType = androidx.media3.common.C.TRACK_TYPE_TEXT
        fun selectedLanguage(type: Int): String? = tracks.groups.filter { it.type == type && it.isSelected }
            .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        val audio = if (audioType in changed) selectedLanguage(audioType) else null
        val text = when {
            textType !in changed -> null
            tracks.groups.none { it.type == textType } -> null // nothing to choose
            else -> {
                val params = p.trackSelectionParameters
                // The menu's "Off" (only signs-only subtitles allowed), or text switched off outright.
                val off = textType in params.disabledTrackTypes ||
                    (params.overrides.values.none { it.type == textType } &&
                        (params.ignoredTextSelectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED.inv()) != 0)
                if (off) ShowPrefs.OFF else selectedLanguage(textType)
            }
        }
        if (textType in changed) autoSubsOn = false // the viewer's choice now, not ours
        if (audio != null || text != null) ShowPrefs.setLanguages(this, show(), audio, text)
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
        nextEp.visibility = android.view.View.GONE
        skip.visibility = android.view.View.GONE
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
        // Finished: drop it from Continue watching, then on to the next one. (Not "left part-way":
        // Next episode can be pressed with the credits still running.)
        p.currentMediaItem?.mediaId?.let(::finished)
        lastSeen = null
        if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.play() }
    }

    // ---- Screen on -----------------------------------------------------------------------------

    private var screenOn: Boolean? = null

    /**
     * The screen stays on while it plays or is about to (a stall, a voice question that takes the
     * sound for a moment, the TV switching its refresh rate), and for the whole of a Watch together.
     * Paused or ended, the TV's own screensaver can come on.
     */
    private fun updateScreenOn() {
        val p = player
        val together = leader || syncedAt?.let { android.os.SystemClock.elapsedRealtime() - it < 60_000 } == true
        val on = together || (p != null && (p.playWhenReady || heldForRate) &&
            (p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_READY))
        if (on == screenOn) return
        screenOn = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- Match frame rate ----------------------------------------------------------------------

    /** Settings → Appearance → Match frame rate, as it was when this player started. */
    private var matchRate = false
    private var rateSwitch: FrameRateMatch.Switch? = null
    /** The item whose frame rate was seen to (once each). */
    private var rateMatchedFor: String? = null
    /** Playback waits while the TV switches its refresh rate (it was playing). */
    private var heldForRate = false

    /**
     * Once an item's frame rate and length are known, the TV switches to a refresh rate that suits
     * it; playback waits for that (a few seconds at most).
     */
    private fun matchFrameRate(p: ExoPlayer) {
        if (!matchRate) return
        val key = p.currentMediaItem?.mediaId ?: return
        if (rateMatchedFor == key) return
        val fps = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).frameRate } } ?: return
        if (p.duration == C.TIME_UNSET) return
        rateMatchedFor = key
        if (p.duration < FrameRateMatch.MIN_LENGTH_MS) return
        val switch = rateSwitch ?: FrameRateMatch.Switch(this).also { rateSwitch = it }
        val wasHeld = heldForRate
        if (switch.start(fps) { if (player === p && heldForRate) { heldForRate = false; p.playWhenReady = true } }) {
            if (p.playWhenReady) { heldForRate = true; p.playWhenReady = false }
        } else if (wasHeld) {
            // The previous item's switch was dropped for this one, which needs none: play on.
            heldForRate = false
            p.playWhenReady = true
        }
    }

    // ---- Next season ---------------------------------------------------------------------------

    /** The episode whose next season was looked for (once each). */
    private var nextSeasonFor: String? = null

    /**
     * The last episode in the queue is in its final minutes: find the first episode of the show's
     * next season and queue it, so Up next carries on into it. Dropped if the player closes first.
     */
    private fun queueNextSeason(p: ExoPlayer) {
        val item = p.currentMediaItem ?: return
        val key = item.mediaId
        if (nextSeasonFor == key || p.hasNextMediaItem() || p.duration <= 0 || p.duration - p.currentPosition > NEXT_SEASON_LEAD_MS) return
        nextSeasonFor = key
        val ref = MediaRef.parse(key) as? MediaRef.Drive ?: return
        val name = item.mediaMetadata.title?.toString().orEmpty()
        val show = com.woodruff.tvexplorer.cloud.EpisodeShows.map.value[key]
        if (show == null || !ShowPrefs.isEpisode(name)) return
        val app = applicationContext
        lifecycleScope.launch {
            val next = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val t = token().ifEmpty { return@withContext null }
                    val showFolder = com.woodruff.tvexplorer.cloud.DriveFile(show, "", com.woodruff.tvexplorer.cloud.DriveFile.FOLDER_MIME, 0, 0, null)
                    val inShow = com.woodruff.tvexplorer.cloud.FolderCache.list(app, t, showFolder) ?: return@withContext null
                    val season = UpNext.seasonOf(inShow, com.woodruff.tvexplorer.cloud.DriveApi.parentOf(t, ref.id), name) ?: return@withContext null
                    val folder = UpNext.nextSeason(inShow, season) ?: return@withContext null
                    com.woodruff.tvexplorer.cloud.FolderCache.list(app, t, folder)
                        ?.let { UpNext.firstEpisode(it, com.woodruff.tvexplorer.tmdb.Tmdb.seasonNumber(folder.name)) }
                }
            }.getOrNull() ?: return@launch
            if (player !== p || p.hasNextMediaItem() || p.currentMediaItem?.mediaId != key) return@launch
            val nextRef = MediaRef.Drive(next.id)
            // Known as this show's whatever it's called (Continue watching's poster, the same-show checks).
            com.woodruff.tvexplorer.cloud.EpisodeShows.remember(app, mapOf(nextRef.key to show))
            p.addMediaItem(
                MediaItem.Builder().setMediaId(nextRef.key).setUri(nextRef.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(com.woodruff.tvexplorer.util.Format.title(next.name)).build()).build(),
            )
        }
    }

    // ---- Doorbell ------------------------------------------------------------------------------

    private lateinit var doorbell: android.widget.TextView
    /** The doorbell's card is up (for 20 s, or until Back). */
    private var ringing = false
    /** The camera that rang, for OK; null when this TV's cameras aren't signed in (a linked TV's ring). */
    private var ringingCamera: String? = null
    private var doorbellJob: kotlinx.coroutines.Job? = null
    private val hideDoorbell = Runnable { dismissDoorbell() }

    /**
     * The doorbell rang: a card over the film for 20 s. OK opens that camera live; the player keeps
     * its place while it's covered, and Back there returns to the film.
     */
    private fun showDoorbell(camera: String) {
        val nest = com.woodruff.tvexplorer.nest.Nest.state.value
        val canLook = nest.step == com.woodruff.tvexplorer.nest.NestStep.CONNECTED
        val label = nest.cameras.firstOrNull { it.name == camera }?.label
        ringingCamera = camera.takeIf { canLook }
        val line = listOfNotNull(label, "OK to look".takeIf { canLook }).joinToString("  ·  ")
        doorbell.text = if (line.isEmpty()) "Someone's at the door" else "Someone's at the door\n$line"
        ringing = true
        view.hideController()
        doorbell.visibility = android.view.View.VISIBLE
        player?.let { updateSkip(it) } // Skip intro / Next episode make way
        countdown.removeCallbacks(hideDoorbell)
        countdown.postDelayed(hideDoorbell, 20_000)
    }

    private fun dismissDoorbell() {
        countdown.removeCallbacks(hideDoorbell)
        ringing = false
        ringingCamera = null
        if (::doorbell.isInitialized) doorbell.visibility = android.view.View.GONE
    }

    override fun onStart() {
        super.onStart()
        // A press handled just before another screen covered this one (OK on the doorbell's card):
        // its release went there, so don't swallow the next one here.
        swallowUp = null
        com.woodruff.tvexplorer.util.FrameStats.screen = "Player"
        com.woodruff.tvexplorer.util.FrameStats.attach(this)
        preview = ScrubPreview(::previewSource)
        com.woodruff.tvexplorer.remote.TvScreen.attach(this)
        var keys: List<String> = savedQueue?.first ?: intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        var titles: List<String> = savedQueue?.second ?: intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        // The item chosen must be in the queue: if a caller's list left it out, it goes first (the
        // player used to start the list's first item instead — a different film).
        val chosen = intent.getStringExtra(EXTRA_START)
        if (savedQueue == null && chosen != null && chosen !in keys) { keys = listOf(chosen) + keys; titles = listOf("") + titles }
        if (keys.isEmpty()) { finish(); return }
        val start = savedIndex ?: keys.indexOf(chosen).coerceAtLeast(0)
        // A new player: no subtitles switched on by this activity yet, nothing stalled. The refresh
        // rate and the next season are seen to again (the TV may have switched back meanwhile).
        autoSubsOn = false
        stalledKey = null
        rateMatchedFor = null
        nextSeasonFor = null
        matchRate = FrameRateMatch.enabled(this)
        val startPos = savedPosition ?: intent.getLongExtra(EXTRA_POSITION, -1L).takeIf { it >= 0 } ?: recent.resumePosition(keys[start])

        val items = keys.mapIndexed { i, key ->
            val ref = MediaRef.parse(key)
            MediaItem.Builder()
                .setMediaId(key)
                .setUri(ref.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle((titles.getOrNull(i)?.ifBlank { null } ?: ref.uri.lastPathSegment)?.let(com.woodruff.tvexplorer.util.Format::title)).build())
                .build()
        }

        // The buffer is Java memory in Woodruff's own heap. Where that's small (192 MB or less), it
        // and the video OkHttp has already fetched ahead get at most half of it (see bufferCapBytes):
        // a Blu-ray remux would fill Media3's ~140 MB otherwise.
        val heapMb = getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 256
        val smallHeap = heapMb <= SMALL_HEAP_MB
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(dataSources()))
            // Start as soon as 1 s is buffered (2 s after a stall) instead of the default 2.5 / 5 s,
            // so Play feels immediate; it keeps filling up to 50 s ahead while it plays. The last 10 s
            // (5 on a small heap) stay too, so a short rewind is instant instead of a new download.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
                    .setBackBuffer(if (smallHeap) 5_000 else 10_000, /* retainBackBufferFromKeyframe = */ true)
                    .apply { bufferCapBytes(heapMb)?.let { setTargetBufferBytes(it) } }
                    .build(),
            )
            // Other apps go quiet while it plays, a voice question pauses it for a moment, and it
            // pauses when headphones are unplugged.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            // Match frame rate switches the TV itself: Media3's own switching only asks for seamless
            // changes, which TV boxes can't make.
            .apply { if (matchRate) setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF) }
            .build().also { p ->
            // Known before its first events: the show's track choices are looked up through it (a
            // null player here applied show ""'s, so the first episode ignored the saved choice).
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val key = p.currentMediaItem?.mediaId
                    val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                    // The connection or Google, not this file: every other item would fail the same way.
                    val passing = http == 401 || http == 403 || http == 429 || (http ?: 0) in 500..599 ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
                    // Google Drive turned the request down (sign-in ran out, a hiccup): try this episode
                    // again at once, from where it was, with a current token.
                    if (key != null && key.startsWith("drive:") && retriedKey != key && passing) {
                        retriedKey = key
                        p.prepare()
                        return
                    }
                    if (passing) {
                        // Stay on it and try again shortly (it used to skip ahead, item after item, until
                        // the Wi-Fi came back many episodes later). Said once, not on every try.
                        if (stalledKey != key) {
                            stalledKey = key
                            val msg = if (http == 401 || http == 403) "Google Drive turned it down. Trying again… (if this keeps happening, sign in to Google Drive again in Settings)"
                            else "Lost the connection. Trying again…"
                            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
                        }
                        retry.removeCallbacksAndMessages(null)
                        retry.postDelayed({ if (player === p) p.prepare() }, 10_000)
                        return
                    }
                    // Unsupported codec etc.: say so and move on rather than sitting on a black screen.
                    // The whole cause goes to the log: the TV running out of memory partway through a
                    // big remux otherwise looks just like a bad file.
                    android.util.Log.w("Player", "Can't play $key: ${error.errorCodeName}", error)
                    val why = when {
                        http == 401 || http == 403 -> "Google Drive refused it (sign in to Google Drive again in Settings)"
                        http == 404 -> "it's no longer in Google Drive"
                        http != null -> "Google Drive error $http"
                        generateSequence<Throwable>(error) { it.cause }.take(10).any { it is OutOfMemoryError } -> "this TV ran out of memory"
                        else -> error.errorCodeName
                    }
                    Toast.makeText(this@PlayerActivity, "Can't play ${p.currentMediaItem?.mediaMetadata?.title ?: "this file"}: $why", Toast.LENGTH_LONG).show()
                    if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) { retriedKey = null; stalledKey = null }
                    if (leader) sendSync()
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                    waitingForPicture = "Seek" to android.os.SystemClock.elapsedRealtime()
                    if (leader) sendSync()
                }

                override fun onRenderedFirstFrame() {
                    // How long Play or a seek took to show a picture (adb logcat -s Player), to
                    // compare builds on a real TV.
                    waitingForPicture?.let { (what, since) ->
                        android.util.Log.i("Player", "$what: picture after ${android.os.SystemClock.elapsedRealtime() - since} ms")
                    }
                    waitingForPicture = null
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (tracks.isEmpty) return
                    // Music runs straight on; only video pauses at the end for the Up next countdown.
                    p.pauseAtEndOfMediaItems = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    // Remember tracks only when the viewer picked them, not the player's automatic choice.
                    val changed = userChanged
                    userChanged = emptySet()
                    if (changed.isNotEmpty()) rememberTracks(p, tracks, changed)
                    // Unless they chose the subtitles themselves, those follow the speech.
                    if (androidx.media3.common.C.TRACK_TYPE_TEXT !in changed) englishSubtitlesIfForeign(p, tracks)
                    // Known before the first frame plays: the TV can switch while nothing's lost.
                    matchFrameRate(p)
                }

                override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                    // A change of ours made inside another callback is reported only after the rest of
                    // that batch of events, when a newer one of ours may already be in place: out of
                    // date, and not the viewer's (at an episode change it saved the automatic English
                    // subtitles as the show's choice).
                    if (parameters != p.trackSelectionParameters) return
                    val before = lastParams
                    lastParams = parameters
                    if (parameters != ownParams && before != null) userChanged = userChanged + changedTypes(before, parameters)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    updateScreenOn()
                    // Last item of a music queue (no pause at the end): leave like a video does.
                    if (state == Player.STATE_ENDED) { p.currentMediaItem?.mediaId?.let(::finished); finish() }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateScreenOn()
                    if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
                    if (sleepAtEnd) { sleepNow(); return }
                    if (p.hasNextMediaItem()) startUpNext()
                    else { p.currentMediaItem?.mediaId?.let(::finished); finish() }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    cancelUpNext()
                    autoSubsOn = false
                    applyShowTracks(p)
                    titleBar.text = mediaItem?.mediaMetadata?.title ?: ""
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        // Finished the previous one: drop it from Continue watching.
                        val prev = p.previousMediaItemIndex.takeIf { it >= 0 }?.let { p.getMediaItemAt(it) }
                        prev?.mediaId?.let(::finished)
                    } else {
                        // Next / Previous (or a skip past a bad file) from part-way through a video: keep
                        // where that one was, as stopping would. Not songs, nor anything only glimpsed
                        // while skipping (Continue watching's own 10 s): they pushed real progress out of
                        // the 30 kept. (pauseAtEndOfMediaItems is still the one left's: the new item's
                        // tracks aren't known yet.)
                        lastSeen?.takeIf { it.key != mediaItem?.mediaId && it.positionMs > 10_000 && p.pauseAtEndOfMediaItems }
                            ?.let(::keepPlace)
                    }
                    lastSeen = null
                }
            })
            // Pause at the end of each item so the "Up next" countdown can run (and be cancelled).
            p.pauseAtEndOfMediaItems = true
            p.setMediaItems(items, start, startPos)
            applyShowTracks(p)
            // Back to it (from the doorbell, the screensaver): as it was, so paused stays paused. A
            // new film plays.
            p.playWhenReady = savedPlayWhenReady ?: true
            waitingForPicture = "Start" to android.os.SystemClock.elapsedRealtime()
            p.prepare()
            view.player = p
            // Voice ("Hey Google, pause", Alexa) and Bluetooth buttons. The remote's own media keys
            // still go to PlayerView first and only unhandled ones reach the session, so none acts
            // twice. A new id each time: Watch together and Play on TV open a second player before
            // the first one stops, and Media3 refuses a duplicate.
            session = runCatching { MediaSession.Builder(this, p).setId("player-${++sessionIds}").build() }.getOrNull()
            if (items.size == 1) fillQueueFromFolder(p)
        }
        updateScreenOn()
        reporter.post(report)
        if (!leader) followJob = lifecycleScope.launch { WatchSync.updates.collect { follow(it) } }
        doorbellJob = lifecycleScope.launch { com.woodruff.tvexplorer.nest.Doorbell.rings.collect { showDoorbell(it) } }
        keepDriveTokenFresh(keys)
    }

    override fun onStop() {
        com.woodruff.tvexplorer.util.FrameStats.detach(this)
        preview?.release(); preview = null
        if (::previewBox.isInitialized) previewBox.visibility = android.view.View.GONE
        com.woodruff.tvexplorer.remote.TvScreen.detach(this)
        reporter.removeCallbacks(report)
        retry.removeCallbacksAndMessages(null)
        followJob?.cancel(); followJob = null
        doorbellJob?.cancel(); doorbellJob = null
        tokenJob?.cancel(); tokenJob = null
        dismissDoorbell()
        cancelUpNext()
        countdown.removeCallbacks(sleep)
        rateSwitch?.cancel()
        com.woodruff.tvexplorer.nowplaying.NowPlaying.ownPlayer = null
        // Before the player it speaks for.
        session?.release(); session = null
        player?.let { p ->
            savedIndex = p.currentMediaItemIndex
            savedPosition = p.currentPosition
            // Waiting for the TV's refresh rate isn't a pause.
            savedPlayWhenReady = p.playWhenReady || heldForRate
            savedQueue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }.let { items ->
                items.map { it.mediaId } to items.map { it.mediaMetadata.title?.toString() ?: "" }
            }
            p.currentMediaItem?.let { item ->
                keepPlace(RecentItem(item.mediaId, item.mediaMetadata.title?.toString() ?: "", p.currentPosition, p.duration.coerceAtLeast(0), 0))
            }
            // The family's other TVs get where it stopped now, before this TV may go to sleep.
            com.woodruff.tvexplorer.cloud.FamilySync.pushNow(this)
            p.release()
        }
        heldForRate = false
        player = null
        super.onStop()
    }

    /**
     * Where [item] was left: saved for Continue watching, or — watched into the credits (the last
     * 5 %, or the last few seconds) — finished, so it's marked ✓ instead of offered again from there.
     */
    private fun keepPlace(item: RecentItem) {
        val pos = item.positionMs
        // Left before its length was known (Back while it was still opening): keep the length saved
        // before, or it was saved as 0 % watched and dropped from the show's "Resume".
        val dur = item.durationMs.takeIf { it > 0 } ?: recent.all().firstOrNull { it.key == item.key }?.durationMs ?: 0L
        if (dur > 0 && (dur - pos < 5_000 || pos >= dur * 0.95)) finished(item.key)
        else if (pos > 0) recent.save(item.key, item.title, pos, dur)
    }

    /**
     * Started on its own (Continue watching, Recently added, search): queue the other episodes in
     * its Google Drive folder around it, so Next / Previous and "Up next" work. Playback carries on
     * while they're added.
     */
    private fun fillQueueFromFolder(p: ExoPlayer) {
        val key = p.currentMediaItem?.mediaId ?: return
        val ref = MediaRef.parse(key) as? MediaRef.Drive ?: return
        if (!ShowPrefs.isEpisode(p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty())) return
        lifecycleScope.launch {
            val folder = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val t = token().ifEmpty { return@withContext null }
                    com.woodruff.tvexplorer.cloud.DriveApi.siblings(t, ref.id)
                }
            }.getOrNull() ?: return@launch
            if (player !== p || p.mediaItemCount != 1 || p.currentMediaItem?.mediaId != key) return@launch
            // Episodes only: a movie in the Movies folder shouldn't roll on into another movie.
            val media = folder.filter { it.kind == com.woodruff.tvexplorer.data.FileKind.VIDEO && ShowPrefs.isEpisode(it.name) }
            val at = media.indexOfFirst { it.id == ref.id }.takeIf { it >= 0 } ?: return@launch
            fun item(f: com.woodruff.tvexplorer.cloud.DriveFile) = MediaItem.Builder()
                .setMediaId(MediaRef.Drive(f.id).key).setUri(MediaRef.Drive(f.id).uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(com.woodruff.tvexplorer.util.Format.title(f.name)).build()).build()
            p.addMediaItems(media.drop(at + 1).take(300).map(::item))
            p.addMediaItems(0, media.take(at).takeLast(300).map(::item))
        }
    }

    /** Local files pass straight through; Drive URLs get an Authorization header per request. */
    private fun dataSources(): DefaultDataSource.Factory {
        // Over OkHttp, on the app's shared connections: one kept-open (HTTP/2) connection to Drive
        // carries every range request, so a start (3-4 requests for an MKV) and each seek no longer
        // wait for a new connection and handshake. Redirects between http and https are still followed.
        val http = OkHttpDataSource.Factory(streamClient)
        val authed = ResolvingDataSource.Factory(http) { spec ->
            // Only Drive's own file URLs get the token (see carriesDriveToken). A redirect can't take
            // it anywhere else: OkHttp drops the header when a redirect changes host, port or scheme
            // (the old data source sent it on to any host a redirect named).
            val uri = spec.uri
            if (carriesDriveToken(uri.scheme, uri.host, uri.path)) {
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${token()}"))
            } else spec
        }
        return DefaultDataSource.Factory(this, authed)
    }

    /**
     * Runs on ExoPlayer's loading thread, so blocking here is fine. GoogleAuth keeps the token in
     * memory until a minute before it runs out and then fetches a new one, so it's always current
     * (a copy kept here for 30 minutes could outlive it, and kept "" after a failed refresh).
     */
    private fun token(): String = GoogleAuth.blockingToken(applicationContext).orEmpty()

    // ---- Drive token ---------------------------------------------------------------------------

    private var tokenJob: kotlinx.coroutines.Job? = null

    /**
     * Renews the Drive token in the background just before it runs out, for as long as the player
     * is up. Google's tokens last an hour, and GoogleAuth renews one only in its last minute, when
     * it's next asked for: during a film that was the first seek or retry after the hour, which then
     * waited on Google's sign-in server.
     */
    private fun keepDriveTokenFresh(keys: List<String>) {
        if (keys.none { MediaRef.parse(it) is MediaRef.Drive }) return
        val app = applicationContext
        tokenJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val secrets = com.woodruff.tvexplorer.settings.SecretStore.get(app)
            while (GoogleAuth.isConnected(app)) {
                // When the current token runs out, as GoogleAuth keeps it.
                val expires = secrets.get(GoogleAuth.Grant.DRIVE.expiresKey)?.toLongOrNull() ?: 0L
                kotlinx.coroutines.delay(tokenRenewDelayMs(expires, System.currentTimeMillis()))
                runCatching { GoogleAuth.token(app) }
                // At most one try a minute: a renewal that failed (no connection) is tried again then.
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    /** OK shows the controls; everything else (media keys, D-pad seeking) PlayerView handles. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The release of a press handled below: PlayerView would take it as a press of its own and
        // pop the controls up over what just happened (after Next episode or Skip intro).
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) { swallowUp = null; return true }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val handled = handleKey(event)
            if (handled) { swallowUp = event.keyCode; return true }
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /** The presses this activity acts on itself (before PlayerView sees them). */
    private fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
            showOptions(); return true
        }
        // The doorbell's card first: OK looks at the door, Back closes the card.
        if (doorbell.visibility == android.view.View.VISIBLE) {
            val camera = ringingCamera
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (camera != null) {
                    dismissDoorbell(); startActivity(DoorbellActivity.intent(this, camera)); return true
                }
                KeyEvent.KEYCODE_BACK -> { dismissDoorbell(); return true }
            }
        }
        if (nextEp.visibility == android.view.View.VISIBLE && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { nextEp.visibility = android.view.View.GONE; playNext(); return true }
                KeyEvent.KEYCODE_BACK -> { nextDismissedFor = player?.currentMediaItem?.mediaId; nextEp.visibility = android.view.View.GONE; return true }
            }
        }
        if (skip.visibility == android.view.View.VISIBLE && !view.isControllerFullyVisible &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            show(); player?.seekTo(introEnd); skip.visibility = android.view.View.GONE; return true
        }
        if (upNext.visibility == android.view.View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playNext(); return true }
                KeyEvent.KEYCODE_BACK -> {
                    // Stop: this one is finished, so leave rather than sit paused on its last frame.
                    cancelUpNext(); player?.currentMediaItem?.mediaId?.let(::finished); finish(); return true
                }
            }
        }
        if (!view.isControllerFullyVisible &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            view.showController()
            return true
        }
        return false
    }

    companion object {
        // Half the first size: big enough to recognise a scene, small enough not to cover the film.
        private const val PREVIEW_W = 144
        private const val PREVIEW_H = 81
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_START = "start"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_TOGETHER = "together"
        /** How near the end of a season's last episode the next season is looked for. */
        private const val NEXT_SEASON_LEAD_MS = 3 * 60_000L
        /** Media session ids must differ, and two players can overlap for a moment. */
        private var sessionIds = 0

        /**
         * The app's HTTP client (and its connections) with Media3's own timeouts for video, as
         * before: 8 s to connect, and 8 s without data before a stalled stream is tried again.
         * The app's own are longer, for everything else.
         */
        private val streamClient by lazy {
            Http.client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        }

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

/**
 * Whether a request may carry the Google token: Drive's own file URLs only — https, Google's API
 * host, the Drive path. Not any other googleapis.com address (a link sent from the phone, say).
 */
internal fun carriesDriveToken(scheme: String?, host: String?, path: String?): Boolean =
    scheme == "https" && host == "www.googleapis.com" && path.orEmpty().startsWith("/drive/")

/**
 * How long before the Drive token runs out the player renews it: just inside its last minute, the
 * only time GoogleAuth renews a token rather than hand back the one it has.
 */
internal const val TOKEN_RENEW_LEAD_MS = 50_000L

/** How long to wait before renewing a token that runs out at [expiresAt]; 0 when that's due already. */
internal fun tokenRenewDelayMs(expiresAt: Long, now: Long): Long = (expiresAt - TOKEN_RENEW_LEAD_MS - now).coerceAtLeast(0)

/** Heaps of this many MB or fewer get a smaller player buffer. */
internal const val SMALL_HEAP_MB = 192
private const val MB = 1024 * 1024

/**
 * Video OkHttp holds beyond the player's buffer: its HTTP/2 window (16 MB in OkHttp 4.12), what
 * Drive may send ahead of what the player has read, kept in memory while the buffer is full.
 */
internal const val HTTP2_WINDOW_BYTES = 16 * MB

/** Media3's own smallest buffer (DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE). */
private const val MIN_BUFFER_BYTES = 200 * 64 * 1024

/**
 * The player's buffer cap in bytes on a TV whose app heap is [heapMb] MB; null keeps Media3's own
 * (about 140 MB). On a small heap the buffer and OkHttp's window get at most half of it between
 * them: the same memory and the same video fetched ahead as before OkHttp.
 */
internal fun bufferCapBytes(heapMb: Int): Int? =
    if (heapMb > SMALL_HEAP_MB) null else (heapMb * MB / 2 - HTTP2_WINDOW_BYTES).coerceAtLeast(MIN_BUFFER_BYTES)
