package com.woodruff.tvexplorer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
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
import kotlinx.coroutines.async
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
class PlayerActivity : ComponentActivity(), com.woodruff.tvexplorer.update.Restore.Restorable {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var timeBar: TvTimeBar
    private lateinit var titleView: android.widget.TextView
    private lateinit var eyebrowView: android.widget.TextView
    /** The title and the buttons above the scrub bar (they fade while scrubbing). */
    private lateinit var infoRow: android.view.View
    private lateinit var subtitlesButton: android.widget.ImageButton
    private lateinit var audioButton: android.widget.ImageButton
    private lateinit var moreButton: android.view.View
    private lateinit var menu: TvMenu
    private lateinit var upNextPanel: UpNextPanel
    /** The controls were up at the last visibility change (focus goes to the scrub bar as they come up). */
    private var controlsUp = false
    private val cardMargin get() = (40 * resources.displayMetrics.density).toInt()
    private val countdown = android.os.Handler(android.os.Looper.getMainLooper())
    private var secondsLeft = 0
    private lateinit var recent: RecentStore
    private var savedIndex: Int? = null
    private var savedPosition: Long? = null
    /** The queue as it was when the player last stopped (it can grow: see [fillQueueFromFolder]). */
    private var savedQueue: Pair<List<String>, List<String>>? = null
    /** One retry with a fresh Google token after a sign-in error, per item. */
    private var retriedKey: String? = null
    /**
     * Waiting for the connection (or Google) to come back, on this item: retries without skipping
     * it ([stalled]). [stall] is why, which the loading ring says, and [stalledSince] since when.
     */
    private var stalledKey: String? = null
    private var stall: Stall? = null
    private var stalledSince: Long? = null
    /**
     * The next try for a stalled film: in [RETRY_MS], or the moment the network is back. A try
     * prepares the player again where it was (nothing happens if a try is already under way).
     */
    private val retry by lazy {
        RetryWait(lifecycleScope, com.woodruff.tvexplorer.util.NetworkWatch.comebacks, RETRY_MS) {
            if (stall != null && stall != Stall.SIGNED_OUT) player?.prepare()
        }
    }
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
            player?.let(::tellPhones)
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
        leftByChoice = savedInstanceState?.getBoolean(KEY_LEFT_BY_CHOICE) == true
        // Made again after Android closed Woodruff to free memory: the queue, item and spot it had
        // (as a stop and start in the same process keeps them), not the intent it first opened with.
        PlayerKeep.restore(savedInstanceState)?.let { k ->
            savedQueue = k.keys to k.titles
            savedIndex = k.index
            savedPosition = k.positionMs
            savedPlayWhenReady = k.playing
        }
        recent = RecentStore(this)
        com.woodruff.tvexplorer.cloud.EpisodeShows.load(this) // which show name-less episodes belong to
        // The starts of films kept on this TV, opened ahead of the film's first request (it reads its
        // index from disk): Continue watching then asks Drive for one thing instead of three.
        val app = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { FilmStarts.warm(app) }
        // The subtitle files beside Drive's films, as last seen in their folders: read in now, so
        // the first film has them (it waits for this only if it isn't done by then).
        SubtitleIndex.attach(app)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { SubtitleIndex.load(app) }
        val root = android.widget.FrameLayout(this)
        // The film, under Woodruff's own tvOS-style controls (layout/tv_player_controls): what's
        // playing at the bottom left, round buttons at the bottom right, the scrub bar under them.
        view = layoutInflater.inflate(com.woodruff.tvexplorer.R.layout.tv_player_view, root, false) as PlayerView
        timeBar = view.findViewById(androidx.media3.ui.R.id.exo_progress)
        titleView = view.findViewById(com.woodruff.tvexplorer.R.id.player_title)
        eyebrowView = view.findViewById(com.woodruff.tvexplorer.R.id.player_eyebrow)
        infoRow = view.findViewById(com.woodruff.tvexplorer.R.id.player_info_row)
        subtitlesButton = view.findViewById<android.widget.ImageButton>(com.woodruff.tvexplorer.R.id.player_subtitles).apply { setOnClickListener { showSubtitles(it) } }
        audioButton = view.findViewById<android.widget.ImageButton>(com.woodruff.tvexplorer.R.id.player_audio).apply { setOnClickListener { showAudio(it) } }
        // Speed, the next episode, the sleep timer and "Skip intro" setup.
        moreButton = view.findViewById<android.view.View>(com.woodruff.tvexplorer.R.id.player_more).apply { setOnClickListener { showOptions(it) } }
        // The cards over the film glide up into place and back down (GlidingCard): none of them pops
        // in or out. Skip intro and Next episode are white pills, as tvOS draws the button OK presses.
        skip = PlayerStyle.pill(this, "Skip Intro")
        skipCard = GlidingCard(skip, GlidingCard.Way.Rise)
        // "Next episode" during the end credits, like Netflix: OK skips them.
        nextEp = PlayerStyle.pill(this, "Next Episode", com.woodruff.tvexplorer.R.drawable.tv_ic_play)
        nextCard = GlidingCard(nextEp, GlidingCard.Way.Rise)
        // "Someone’s at the door" when the doorbell rings: a notice at the top right, as tvOS shows them.
        doorbell = PlayerStyle.notice(this)
        doorbellCard = GlidingCard(doorbell, GlidingCard.Way.Drop)
        // The player's own messages ("Sleeping in 30 minutes"), at the bottom in the middle: Android's
        // toasts popped up over the film in the system's own way.
        message = PlayerStyle.message(this)
        messageCard = GlidingCard(message, GlidingCard.Way.Rise)
        // Subtitles step up out of the way of whatever comes up at the bottom (§3.7), and back down.
        subtitlePlace = view.subtitleView?.let { SubtitlePlace(root, it) }
        for (card in listOf(skipCard, nextCard, messageCard)) card.onShownChanged = { subtitlesMakeWay() }
        view.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { v ->
            val up = v == android.view.View.VISIBLE
            if (up && !controlsUp) {
                bindTitle(player?.currentMediaItem)
                // tvOS: the scrub bar has focus as the controls come up (Left / Right scrub at once).
                timeBar.requestFocus()
            }
            controlsUp = up
            // The corner buttons would cover the controls' own (and OK goes to the controls then).
            if (up) { skipCard.hide(); nextCard.hide() }
            else player?.let { updateSkip(it) }
            subtitlesMakeWay()
        })
        // Not clipping its children: a card gliding up from below its place is drawn there too.
        root.clipChildren = false
        root.addView(view)
        // The loading ring, in the middle over the film and its controls (and under everything else):
        // a picture that pauses to load says so rather than looking frozen.
        loading = BufferingIndicator(this, root)
        // At an episode's end: Up next, over the film's last frame.
        upNextPanel = UpNextPanel(this, root)
        root.addView(nextEp, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply { setMargins(0, 0, cardMargin, cardMargin) })
        root.addView(skip, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply { setMargins(0, 0, cardMargin, cardMargin) })
        root.addView(doorbell, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.END,
        ).apply { setMargins(0, cardMargin / 2, cardMargin, 0) })
        root.addView(message, PlayerStyle.messageParams(this))
        // Scrubbing preview: a frame from that point of the film, above the scrub bar.
        val d = resources.displayMetrics.density
        previewImage = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1C1C1E.toInt())
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 10 * d) }
            }
        }
        previewTime = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = PlayerStyle.semibold(this@PlayerActivity)
            fontFeatureSettings = "tnum"
            gravity = android.view.Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
        }
        previewBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (5 * d).toInt()
            setPadding(pad, pad, pad, pad)
            background = PlayerStyle.glass(this@PlayerActivity, 15f)
            elevation = 16 * d
            addView(previewImage, android.widget.LinearLayout.LayoutParams((PREVIEW_W * d).toInt(), (PREVIEW_H * d).toInt()))
            addView(previewTime, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // A pop-up: in from just larger as it fades, and back out (its own shift places it over the bar).
        previewCard = GlidingCard(previewBox, GlidingCard.Way.Zoom)
        root.addView(previewBox, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.START,
        ))
        // The menus (Subtitles, Audio, Speed, the options): over everything. The controls stay up
        // while one is open, and hide on their own again once it closes.
        menu = TvMenu(this, root).apply {
            onShownChanged = { open -> view.controllerShowTimeoutMs = if (open) 0 else CONTROLS_TIMEOUT_MS }
        }
        rootView = root
        setContentView(root)
        // A scrub is aimed from where the film really is, and lands relative to it as the film plays
        // on: one press back is 10 s behind the film, inside the seconds kept for an instant rewind.
        timeBar.livePosition = { player?.contentPosition }
        timeBar.onLanding = { target -> player?.let { aimAt(it, target) } }
        timeBar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                // As on tvOS, the title and buttons make way for the preview while scrubbing.
                infoRow.animate().alpha(0f).setDuration(150).start()
                showPreview(timeBar as android.view.View, position, starting = true)
            }
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) = showPreview(timeBar as android.view.View, position, starting = false)
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                infoRow.animate().alpha(1f).setDuration(200).start()
                preview?.cancel()
                previewCard.hide()
            }
        })
    }

    /** What's playing, in the controls: the show and episode small above, its own name big. */
    private fun bindTitle(item: MediaItem?) {
        val label = EpisodeLabel.of(item?.mediaMetadata?.title?.toString().orEmpty())
        titleView.text = label.title
        eyebrowView.text = label.eyebrow.orEmpty()
        eyebrowView.visibility = if (label.eyebrow == null) android.view.View.GONE else android.view.View.VISIBLE
        // The title row grows or shrinks with the eyebrow: subtitles risen above it look again.
        subtitlesMakeWay()
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
    private lateinit var previewCard: GlidingCard
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
        previewCard.show()
    }

    // ---- Seeks ---------------------------------------------------------------------------------

    /** How much film this player keeps behind the playhead ([backBufferMs]), set as it's built. */
    private var keptBehindMs = 0
    /** The last seek aimed here and whether its spot was in memory, for the "picture after" log. */
    private var aimed: Pair<Long, Boolean>? = null

    /**
     * Gets the player ready for a seek to [targetMs] that the viewer asked for (a scrub, ⏪ / ⏩,
     * Skip intro): to the first clean frame just after the spot, or exactly on it where that's
     * needed (Watch together, [exact]); see [SeekAim]. Applies to the next seek the player is told
     * to make, which follows straight after.
     */
    private fun aimAt(p: ExoPlayer, targetMs: Long, exact: Boolean = leader) {
        val buffered = p.bufferedPosition
        p.setSeekParameters(SeekAim.parametersFor(targetMs, buffered, exact))
        aimed = targetMs to SeekAim.isKept(targetMs, p.currentPosition, buffered, keptBehindMs)
    }

    /**
     * The remote's ⏪ / ⏩ (Media3's controller skips 5 s back or 15 s ahead from where the film is):
     * aimed like a scrub before PlayerView makes the jump.
     */
    private fun aimSkip(keyCode: Int) {
        val p = player ?: return
        val jump = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> -p.seekBackIncrement
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekForwardIncrement
            else -> return
        }
        aimAt(p, (p.currentPosition + jump).coerceAtLeast(0))
    }

    // ---- The phone's controls ------------------------------------------------------------------

    /** Hears the phone's ↺10, 30↻ and Next ([com.woodruff.tvexplorer.nowplaying.NowPlaying.commands]) while this player is started. */
    private var phoneJob: kotlinx.coroutines.Job? = null
    /** Which of the players started so far this one is ([starts] as it started; 0 before then). */
    private var startedAs = 0

    /**
     * This player is the one on screen: the last one started. Watch together, or a film started from
     * the phone over another, opens a second player before the first one stops (the first stays
     * under it, to come back to). While both are started, only the new one answers the phone and says
     * what's playing, and the first one stopping doesn't take that back.
     */
    private val inFront get() = startedAs != 0 && startedAs == starts

    /**
     * From now until [onStop], the phone's controls reach this player ([fromPhone]), and what the
     * phone is told is playing ([tellPhones]) follows every change, not only each second: the phone
     * looks again 0.7 s after a press, and finds what the press did.
     */
    private fun answerPhones(p: ExoPlayer) {
        startedAs = ++starts
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                    )
                ) tellPhones(p)
            }
        })
        phoneJob = lifecycleScope.launch { com.woodruff.tvexplorer.nowplaying.NowPlaying.commands.collect { fromPhone(it) } }
    }

    /**
     * What this player plays, for the phone and the linked TVs (NowPlaying): its title; whether it
     * plays, as the TV's own Play/Pause button has it (loading for a moment after a jump is still
     * playing, so the phone's button pauses, as the TV's would); where it is; and what the phone's
     * controls can do here: ↺10 and 30↻ where it can jump, Next with something queued after it, and
     * its poster found by its Drive key ([phoneKey]). Only the player in front says.
     */
    private fun tellPhones(p: Player) {
        if (!inFront) return
        com.woodruff.tvexplorer.nowplaying.NowPlaying.ownPlayer = com.woodruff.tvexplorer.nowplaying.Playing(
            title = p.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Video",
            subtitle = null, app = "Woodruff Family",
            playing = !androidx.media3.common.util.Util.shouldShowPlayButton(p) || heldForRate,
            positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.coerceAtLeast(0),
            key = phoneKey(p.currentMediaItem?.mediaId),
            hasNext = p.hasNextMediaItem(),
            seekable = p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        )
    }

    /**
     * ↺10, 30↻ or Next from the phone ([phoneStep]), for the player in front. A jump lands as the
     * remote's ⏪ / ⏩ do: aimed, so 10 s back plays from what's kept in memory (and lands exactly on
     * the spot for a Watch together leader, whose TVs follow it), with the controls coming up to show
     * where it went. Next is the player's own Next Episode: this one counts as watched.
     */
    private fun fromPhone(cmd: com.woodruff.tvexplorer.nowplaying.PlayerCommand) {
        val p = player ?: return
        if (!inFront || isFinishing) return
        val seekable = p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        when (val step = phoneStep(cmd, p.currentPosition, p.duration, seekable, p.hasNextMediaItem(), upNextPanel.shown)) {
            PhoneStep.Next -> {
                // Another episode: a menu open over this one was about this one.
                menu.hide()
                nextCard.hide()
                playNext()
            }
            is PhoneStep.Seek -> {
                // The latest press wins over a spot being chosen with the TV's remote.
                timeBar.cancelScrub()
                if (step.playOn) cancelUpNext()
                aimAt(p, step.toMs)
                p.seekTo(step.toMs)
                if (step.playOn) p.play()
                view.showController()
            }
            PhoneStep.Stay -> Unit
        }
    }

    // ---- Sleep timer, skip intro, remembered tracks ----------------------------------------

    private lateinit var skip: android.widget.TextView
    private lateinit var skipCard: GlidingCard
    private lateinit var nextEp: android.widget.TextView
    private lateinit var nextCard: GlidingCard
    /** The episode whose "Next episode" button was waved away with Back (don't show it again for it). */
    private var nextDismissedFor: String? = null

    // ---- Messages ------------------------------------------------------------------------------

    private lateinit var message: android.widget.TextView
    private lateinit var messageCard: GlidingCard
    private val hideMessage = Runnable { messageCard.hide() }

    /**
     * Says something over the film, as Android's toasts did, but gliding in and out like the player's
     * other cards. It stays [ms]: as long as a short toast did unless told otherwise
     * ([NOTICE_MESSAGE_MS]); a newer message replaces it at once.
     */
    private fun say(text: String, ms: Long = MESSAGE_MS) {
        message.text = text
        messageCard.show()
        // A message replacing one already up can be taller (two lines for one): the subtitles above
        // it look again, as they do when a message comes.
        subtitlesMakeWay()
        countdown.removeCallbacks(hideMessage)
        countdown.postDelayed(hideMessage, ms)
    }

    // ---- Loading, and trying again ------------------------------------------------------------

    private lateinit var loading: BufferingIndicator
    /** Since when the player has been meant to play without playing (the ring shows after 500 ms of it). */
    private val waitClock = WaitClock()
    private val updateLoadingLater = Runnable { updateLoading() }

    /**
     * The loading ring, as things stand now ([Buffering]): after half a second of loading while
     * meant to play (a slow start, a seek, a stall), and all the while the player is trying again
     * after the connection or Google failed, with what's going on under it. Gone the moment the film
     * plays, when it's paused (unless it's still trying again), with Up next, and when the player
     * stops. Called on each player event that can change it; one timer covers the next change of
     * words ("Still loading…" after 10 s), so nothing polls.
     */
    private fun updateLoading() {
        if (!::loading.isInitialized) return
        countdown.removeCallbacks(updateLoadingLater)
        val p = player ?: run { loading.hide(animate = false); return }
        val now = android.os.SystemClock.elapsedRealtime()
        waitClock.update(p.playWhenReady, p.playbackState, p.isPlaying, now)
        val waited = waitClock.waitedMs(p.playbackState, now)
        val stalledMs = stalledSince?.let { now - it } ?: 0L
        val look = if (upNextPanel.shown) null else Buffering.look(waited, stall, stalledMs, com.woodruff.tvexplorer.ui.motion.AppMotion.reduced)
        if (look != null) loading.show(look) else loading.hide()
        Buffering.nextChangeMs(waited, stall, stalledMs)?.let { countdown.postDelayed(updateLoadingLater, it) }
    }

    /**
     * The connection or Google failed on [key] (after the one immediate try with a current token):
     * stay on it, say what's going on under the ring, and try again in [RETRY_MS], or the moment the
     * network is back, whichever comes first ([retry]). Drive's refusal is checked against the
     * sign-in: when Google has ended it, trying stops and the ring's card says how to sign in again.
     */
    private fun stalled(p: ExoPlayer, key: String?, kind: Stall) {
        if (stalledKey == null) stalledSince = android.os.SystemClock.elapsedRealtime()
        stalledKey = key
        stall = kind
        // Media3 brings the controls up whenever the player is idle (between tries) and lets them go
        // during the next try: they'd come and go every few seconds over "Reconnecting…". The ring
        // says what's happening; OK or Play/Pause still bring the controls up.
        view.controllerAutoShow = false
        retry.arm()
        if (kind == Stall.REFUSED) checkDriveSignIn(p)
        updateLoading()
    }

    /**
     * Google Drive turned the film down while this TV looked signed in: whether Google has ended the
     * sign-in (read off the main thread: it's in the TV's secret store). If so, nothing from Drive
     * will play until someone signs in again, so the player stops trying and says how.
     */
    private fun checkDriveSignIn(p: ExoPlayer) {
        val app = applicationContext
        lifecycleScope.launch {
            val signedIn = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { GoogleAuth.isConnected(app) }.getOrDefault(true)
            }
            if (signedIn || player !== p || stall != Stall.REFUSED) return@launch
            stall = Stall.SIGNED_OUT
            stopTrying()
            updateLoading()
        }
    }

    /** No more tries. */
    private fun stopTrying() {
        retry.stop()
    }

    /** The film plays (or is loaded and ready) again: the stall is over. */
    private fun clearStall() {
        if (stalledKey == null && stall == null) return
        stalledKey = null
        stall = null
        stalledSince = null
        stopTrying()
        view.controllerAutoShow = true
    }

    /** Films started again from Drive alone after trouble with this TV's copy of their start (once each). */
    private val startedAgainFromDrive = HashSet<String>()

    /**
     * The start of [key] kept on this TV ([FilmStarts]) let it down: it was out of date (the file was
     * replaced in Drive, found as Drive answered), or the film couldn't be parsed after its start
     * came from the TV (a copy cut short by a power cut, say). Once per film, the kept start is
     * thrown away and the film starts again from Drive, from where it was, without a word: the ring
     * stays up meanwhile. False when this isn't that, or it has been tried already.
     */
    private fun startAgainFromDrive(p: ExoPlayer, key: String, error: PlaybackException): Boolean {
        val stale = FilmStarts.isStale(error)
        val unreadable = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        if (!stale && !(unreadable && FilmStarts.readFromCopy(key))) return false
        if (!startedAgainFromDrive.add(key)) return false
        android.util.Log.i("Player", "Starting $key again from Drive alone (${if (stale) "it changed" else "its kept start didn't read"})")
        lifecycleScope.launch {
            // Out of date: already thrown away as it was found. Unreadable: thrown away here (disk).
            if (!stale) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FilmStarts.forget(key) }
            if (player === p && p.playbackState == Player.STATE_IDLE) p.prepare()
        }
        return true
    }

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
            aimAt(p, target, exact = true)
            p.seekTo(i, target)
        } else if (kotlin.math.abs(p.currentPosition - target) > if (u.playing) 2_000 else 300) {
            // Playing: only correct real drift. Paused: line up exactly on the leader's frame (a seek
            // to the next keyframe would leave this TV up to 1.5 s off, and never corrected).
            aimAt(p, target, exact = true)
            p.seekTo(target)
        }
        if (u.playing && upNextPanel.shown) cancelUpNext()
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

    // ---- Menus: options, subtitles, audio, speed -----------------------------------------------

    /** The options (the "…" button, or the menu button): speed, the next episode, sleep timer and Skip intro. */
    private fun showOptions(anchor: android.view.View? = null) {
        val p = player ?: return
        val intro = ShowPrefs.introEnd(this, show())
        val here = p.currentPosition
        val at = anchor?.takeIf { view.isControllerFullyVisible }
        val speed = SPEEDS.firstOrNull { kotlin.math.abs(it.second - p.playbackParameters.speed) < 0.01f }?.first ?: "Normal"
        // Speed and the next episode don't touch the sleep timer.
        val first = listOfNotNull(
            TvMenu.Row("Speed: $speed") { showSpeed(at) },
            if (p.hasNextMediaItem()) TvMenu.Row("Play next") { playNext() } else null,
        )
        val choices = listOfNotNull<Pair<String, () -> Unit>>(
            "Sleep in 30 minutes" to { sleepIn(30) },
            "Sleep in 60 minutes" to { sleepIn(60) },
            "Sleep in 90 minutes" to { sleepIn(90) },
            "Sleep at the end of this episode" to { sleepAtEnd = true; say("Sleeping after this episode") },
            "Sleep timer off" to { say("Sleep timer off") },
            "Intro ends here (${com.woodruff.tvexplorer.util.Format.duration(here)}): use for this show" to {
                ShowPrefs.setIntroEnd(this, show(), here); introEnd = here; say("Skip intro will jump here")
            },
            if (intro > 0) ("Forget this show's intro" to { ShowPrefs.setIntroEnd(this, show(), 0); introEnd = 0 }) else null,
        )
        // Any choice starts the sleep timer afresh, as it always has.
        menu.show("Options", first + choices.map { (label, action) ->
            TvMenu.Row(label) { countdown.removeCallbacks(sleep); sleepAtEnd = false; action() }
        }, at)
    }

    private fun sleepIn(minutes: Long) {
        countdown.postDelayed(sleep, minutes * 60_000L)
        say("Sleeping in $minutes minutes")
    }

    /** The tracks of one type the player can play, each with whether it's the one playing. */
    private fun tracksOf(p: ExoPlayer, type: Int): List<Triple<androidx.media3.common.Tracks.Group, Int, Boolean>> =
        p.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { Triple(g, it, g.isTrackSelected(it)) }
        }

    /** A track chosen in a menu, as Media3's own menus chose it (so it's remembered for the show). */
    private fun choose(p: ExoPlayer, group: androidx.media3.common.Tracks.Group, index: Int) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    /**
     * Off, every subtitle track (the film's own, then the files beside it, by language: never a file
     * name), and last the size (specs/player-feedback.md §3.6, §3.9).
     */
    private fun showSubtitles(anchor: android.view.View) {
        val p = player ?: return
        // Still opening: its tracks aren't known yet, so "No subtitles" wouldn't be true (as with Audio).
        if (p.currentTracks.isEmpty) return
        val choices = SubtitleTracks.of(p.currentTracks)
        if (choices.isEmpty()) { say(SubtitleTracks.NONE); return }
        val rows = SubtitleTracks.menuRows(
            choices.map { it.track }, java.util.Locale.getDefault(), SubtitleLook.size(this), tvCaptions,
            off = {
                // Media3's own "Off": no subtitles, but signs-only ("forced") ones still show.
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                    .build()
            },
            choose = { i -> choose(p, choices[i].group, choices[i].index) },
            openSize = { showSubtitleSize(anchor) },
            explain = { say(it, SIZE_MESSAGE_MS) },
        )
        menu.show("Subtitles", rows, anchor)
    }

    /** Subtitle size, above the same button: OK changes the subtitles on screen at once, and keeps it. */
    private fun showSubtitleSize(anchor: android.view.View) {
        menu.show("Subtitle size", SubtitleTracks.sizeRows(SubtitleLook.size(this)) { size ->
            SubtitleLook.setSize(this, size)
            applySubtitleLook()
            // Saved all the same: it's for the next film whose subtitles are words.
            if (showingPictures()) say(SubtitleTracks.PICTURES_KEEP_SIZE, SIZE_MESSAGE_MS)
        }, anchor)
    }

    /** The subtitles showing are pictures (a Blu-ray's): no size of ours applies to them. */
    private fun showingPictures(): Boolean =
        player?.let { p -> SubtitleTracks.of(p.currentTracks).any { it.track.selected && it.track.pictures } } == true

    // ---- Subtitles: their look, the files beside the film, and stepping out of the way -------------

    /** Where the player's subtitles sit: risen out of the way of what's at the bottom of the screen ([SubtitlePlace]). */
    private var subtitlePlace: SubtitlePlace? = null
    /** A look at where the subtitles go is due before the next frame. */
    private var placePending = false
    /** The TV's own caption settings are on: they choose the look and size. */
    private var tvCaptions = false

    /** Subtitles' look (§3.5): Woodruff's, at the size chosen, unless the TV's own caption settings are on. */
    private fun applySubtitleLook() {
        val sv = view.subtitleView ?: return
        tvCaptions = SubtitleLook.tvCaptionsOn(this)
        SubtitleLook.apply(sv, this, SubtitleLook.size(this), tvCaptions, resources.displayMetrics.heightPixels)
    }

    /**
     * Something came or went at the bottom of the screen (the controls, Skip Intro or Next Episode,
     * a message, Up next): where the subtitles go is worked out just before the next frame, once
     * what came is laid out.
     */
    private fun subtitlesMakeWay() {
        if (placePending || !::rootView.isInitialized) return
        placePending = true
        rootView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // The observer it was added to may since have been merged into the window's.
                rootView.viewTreeObserver.removeOnPreDrawListener(this)
                placePending = false
                placeSubtitles()
                return true
            }
        })
        rootView.invalidate()
    }

    /** The subtitles above whatever is at the bottom of the screen now, or faded for Up next (§3.7). */
    private fun placeSubtitles() {
        subtitlePlace?.update(
            listOfNotNull(
                infoRow.takeIf { controlsUp },
                skip.takeIf { skipCard.shown },
                nextEp.takeIf { nextCard.shown },
                message.takeIf { messageCard.shown },
            ),
            fade = ::upNextPanel.isInitialized && upNextPanel.shown,
        )
    }

    /** Back in Media3's own place, at once (the player stopping, or starting afresh). */
    private fun resetSubtitlePlace() {
        subtitlePlace?.reset()
    }

    /** Subtitle files found beside USB films this time, by the film's path ([LocalSidecars]). */
    private val localSidecars = HashMap<String, List<Sidecar>>()
    /** USB folders already looked in this time. */
    private val localFolders = HashSet<String>()
    private var sidecarJob: kotlinx.coroutines.Job? = null

    /** The subtitle files known to sit beside [key]: a Drive film's from its folder's listing, a USB film's from its folder. */
    private fun knownSidecars(key: String): List<Sidecar>? = when (MediaRef.parse(key)) {
        is MediaRef.Drive -> SubtitleIndex.known(key)
        is MediaRef.Local -> localSidecars[key]
        is MediaRef.Remote -> null
    }

    /** [key]'s item for the queue, called [title], with the subtitle files known to sit beside it attached. */
    private fun mediaItem(key: String, title: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(key)
            .setUri(MediaRef.parse(key).uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(knownSidecars(key).orEmpty().map(Sidecars::configuration))
            .build()

    /** [item] with the subtitle files now known beside it, or itself when that's what it has. */
    private fun withSidecars(item: MediaItem): MediaItem {
        val configs = knownSidecars(item.mediaId).orEmpty().map(Sidecars::configuration)
        val now = item.localConfiguration?.subtitleConfigurations.orEmpty()
        return if (configs.isEmpty() || configs == now) item else item.buildUpon().setSubtitleConfigurations(configs).build()
    }

    /**
     * Prepares the player, its first film with the subtitle files beside it: Media3 takes them only
     * before it starts loading a film. Drive's are known at once (the index is in memory), unless the
     * index is still being read just after Woodruff started; a USB film's folder is looked in first.
     * Either way it waits at most [SIDECAR_WAIT_MS] (a sleeping USB drive), then starts without them.
     * The rest of the queue gets theirs in the background, long before each one plays.
     */
    private fun prepareWithSidecars(p: ExoPlayer) {
        val first = p.currentMediaItem?.mediaId?.let(MediaRef::parse)
        val look = when (first) {
            is MediaRef.Local -> true
            is MediaRef.Drive -> !SubtitleIndex.loaded
            else -> false
        }
        if (!look) {
            p.prepare()
            sidecarJob = lifecycleScope.launch { findQueueSidecars(p) }
            return
        }
        val app = applicationContext
        sidecarJob = lifecycleScope.launch {
            val found = async(kotlinx.coroutines.Dispatchers.IO) {
                if (first is MediaRef.Drive) SubtitleIndex.load(app)
                if (first is MediaRef.Local) java.io.File(first.path).parentFile?.let(::lookInFolder) else null
            }
            kotlinx.coroutines.withTimeoutOrNull(SIDECAR_WAIT_MS) { found.await() }?.let { localSidecars += it }
            if (player !== p) return@launch
            // Nothing has loaded yet, so the queue can still be given them at no cost.
            if (p.playbackState == Player.STATE_IDLE && p.playerError == null) {
                val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
                val given = items.map(::withSidecars)
                if (given.indices.any { given[it] !== items[it] }) p.setMediaItems(given, p.currentMediaItemIndex, p.currentPosition)
                p.prepare()
            }
            // Still looking (a slow drive): the films after this one get them when it's done.
            found.await()?.let { localSidecars += it }
            findQueueSidecars(p)
        }
    }

    /**
     * Gives the films waiting in the queue the subtitle files beside them: USB films' folders are
     * looked in (a few at most: a queue is usually one folder), Drive films' are known. Not the film
     * playing: Media3 would start it again.
     */
    private suspend fun findQueueSidecars(p: ExoPlayer) {
        val folders = (0 until p.mediaItemCount).mapNotNull { (MediaRef.parse(p.getMediaItemAt(it).mediaId) as? MediaRef.Local)?.path }
            .mapNotNull { java.io.File(it).parentFile }.distinctBy { it.path }
            .filter { dir -> synchronized(localFolders) { dir.path !in localFolders } }.take(MAX_SIDECAR_FOLDERS)
        if (folders.isNotEmpty()) {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { folders.map(::lookInFolder) }
            found.forEach { localSidecars += it }
        }
        if (player === p) giveWaitingSidecars(p)
    }

    /** A USB folder's subtitle files, by film ([LocalSidecars]); noted as looked in. Off the main thread. */
    private fun lookInFolder(dir: java.io.File): Map<String, List<Sidecar>> {
        synchronized(localFolders) { localFolders += dir.path }
        return runCatching { LocalSidecars.inFolder(dir) }.getOrDefault(emptyMap())
    }

    /** The queue's films other than the one playing, given the subtitle files now known beside them ([giveWaitingItems]). */
    private fun giveWaitingSidecars(p: ExoPlayer) = giveWaitingItems(p, ::withSidecars)

    /**
     * Every sound track in the file, the one playing ticked (OK on another switches to it). The
     * ones this TV can't play are listed too, dimmed and marked, and say why when chosen: the menu
     * used to leave them out, and with none left it didn't open at all. A video with no sound says so.
     */
    private fun showAudio(anchor: android.view.View) {
        val p = player ?: return
        // Still opening: its tracks aren't known yet, so there's nothing true to say.
        if (p.currentTracks.isEmpty) return
        val sound = SoundTracks.of(p.currentTracks)
        if (sound.isEmpty()) { say(SoundTracks.NO_SOUND); return }
        val rows = SoundTracks.menuRows(
            sound.map { it.track }, java.util.Locale.getDefault(),
            choose = { i -> choose(p, sound[i].group, sound[i].index) },
            explain = { say(it) },
        )
        menu.show("Audio", rows, anchor)
    }

    private fun showSpeed(anchor: android.view.View?) {
        val p = player ?: return
        val now = p.playbackParameters.speed
        menu.show("Speed", SPEEDS.map { (label, speed) ->
            TvMenu.Row(label, checked = kotlin.math.abs(now - speed) < 0.01f) { p.setPlaybackSpeed(speed) }
        }, anchor)
    }

    /**
     * Subtitles' button: filled while they're on, dimmed when the video has none. Audio's dims only
     * when the video has no sound track at all: with one, its menu shows what's playing, and which
     * tracks this TV can't play.
     */
    private fun updateTrackButtons(p: ExoPlayer) {
        val text = tracksOf(p, C.TRACK_TYPE_TEXT)
        subtitlesButton.setImageResource(
            if (text.any { it.third }) com.woodruff.tvexplorer.R.drawable.tv_ic_subtitles_on else com.woodruff.tvexplorer.R.drawable.tv_ic_subtitles_off,
        )
        subtitlesButton.alpha = if (text.isEmpty()) DIM else 1f
        audioButton.alpha = if (p.currentTracks.containsType(C.TRACK_TYPE_AUDIO)) 1f else DIM
    }

    /** "Skip intro" while inside this show's intro. */
    private fun updateSkip(p: ExoPlayer) {
        show() // refreshes introEnd when the episode changed
        val end = introEnd
        val pos = p.currentPosition
        val free = !upNextPanel.shown && !view.isControllerFullyVisible && !ringing
        skipCard.set(end > 0 && pos in 1_000 until end - 2_000 && free)
        // The last 40 s of an episode with the next one of the same show queued: the credits,
        // roughly. Not for films or home videos (it said "Next episode" over the end of a movie).
        val left = p.duration - pos
        val key = p.currentMediaItem?.mediaId
        val title = p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        val next = if (p.hasNextMediaItem()) p.getMediaItemAt(p.nextMediaItemIndex) else null
        val nextTitle = next?.mediaMetadata?.title?.toString().orEmpty()
        nextCard.set(
            p.hasNextMediaItem() && p.duration > 60_000 && left in 1..40_000 && p.pauseAtEndOfMediaItems && free &&
                key != nextDismissedFor && ShowPrefs.isEpisode(title) && showFor(next?.mediaId, nextTitle) == show(),
        )
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
        // English subtitles in the file (or beside it): full ones before SDH, and both before "forced"
        // (signs-only) ones.
        val choices = SubtitleTracks.of(tracks)
        val pick = SubtitleTracks.englishForForeignSpeech(choices.map { it.track })?.let(choices::get) ?: return
        val group = pick.group
        val index = pick.index
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

    // ---- Sound this TV can't play ---------------------------------------------------------------

    /** Items whose sound has been looked at while this player is up: what's found is said once each. */
    private val soundChecked = HashSet<String>()
    private val checkSound = Runnable { checkSound() }

    /** Looks at the current item's sound in a moment ([checkSound]), unless it has been already. */
    private fun scheduleSoundCheck(p: ExoPlayer) {
        val key = p.currentMediaItem?.mediaId ?: return
        if (key in soundChecked) return
        countdown.removeCallbacks(checkSound)
        countdown.postDelayed(checkSound, SOUND_CHECK_DELAY_MS)
    }

    /**
     * A film whose sound this TV can't play says so at once, in plain words, rather than playing
     * silently, or on a commentary track without a word: once per item, a moment after its first
     * picture (a song, a moment after its tracks are known). A song with no sound this TV can play
     * is skipped, like a file it can't open.
     */
    private fun checkSound() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val tracks = p.currentTracks
        if (tracks.isEmpty || !soundChecked.add(item.mediaId)) return
        val sound = SoundTracks.of(tracks).map { it.track }
        val verdict = SoundTracks.verdict(sound, ShowPrefs.audio(this, show())) ?: return
        if (verdict is SoundVerdict.Silent && !tracks.containsType(C.TRACK_TYPE_VIDEO)) {
            say(SoundTracks.songMessage(item.mediaMetadata.title?.toString() ?: "this file", verdict.codec), NOTICE_MESSAGE_MS)
            if (p.hasNextMediaItem()) p.seekToNextMediaItem()
            return
        }
        say(SoundTracks.startMessage(verdict, SoundTracks.labels(sound, java.util.Locale.getDefault())), NOTICE_MESSAGE_MS)
    }

    /**
     * End of an episode with another one queued: Up next counts down [UP_NEXT_SECONDS], then plays
     * it. OK plays it now (or, with Done chosen, stops); Back stops.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (--secondsLeft <= 0) { playNext(); return }
            countdown.postDelayed(this, 1_000)
        }
    }

    private fun startUpNext() {
        val p = player ?: return
        secondsLeft = UP_NEXT_SECONDS
        nextCard.hide()
        skipCard.hide()
        menu.hide()
        countdown.removeCallbacks(tick)
        // The controls make way (they'd come up on their own at the pause): Up next is all there is.
        view.hideController()
        view.useController = false
        val next = p.getMediaItemAt(p.nextMediaItemIndex)
        upNextPanel.show(EpisodeLabel.of(next.mediaMetadata.title?.toString() ?: "Next"), UP_NEXT_SECONDS)
        // A picture from the next episode, a little way in (past any black at its start).
        preview?.let { pv ->
            pv.cached(next.mediaId, UP_NEXT_ART_MS)?.let(upNextPanel::setArt)
                ?: pv.request(next.mediaId, UP_NEXT_ART_MS) { if (upNextPanel.shown) upNextPanel.setArt(it) }
        }
        countdown.postDelayed(tick, 1_000)
        updateLoading() // the ring makes way too
        subtitlesMakeWay() // and the subtitles fade away
    }

    /** [animate] false: gone at once (the player stopping). */
    private fun cancelUpNext(animate: Boolean = true) {
        countdown.removeCallbacks(tick)
        val wasUp = ::upNextPanel.isInitialized && upNextPanel.shown
        if (::upNextPanel.isInitialized) upNextPanel.hide(animate)
        view.useController = true
        if (wasUp) { updateLoading(); subtitlesMakeWay() }
    }

    /** Done on Up next (or Back): this one is finished, so leave rather than sit paused on its last frame. */
    private fun stopWatching() {
        cancelUpNext(); player?.currentMediaItem?.mediaId?.let(::finished); finish()
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
     * Paused or ended, the TV's own screensaver can come on. Checked every second ([report]).
     */
    private fun updateScreenOn() {
        val p = player
        val now = android.os.SystemClock.elapsedRealtime()
        val together = leader || syncedAt?.let { now - it < 60_000 } == true
        val stalledMs = stalledSince?.takeIf { stall != null && stall != Stall.SIGNED_OUT }?.let { now - it }
        val on = together || (p != null && keepScreenOn(p.playWhenReady || heldForRate, p.playbackState, stalledMs))
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
                        // Its subtitle files, known before the episode is queued (Media3 takes them only then).
                        ?.also { SubtitleIndex.learnNow(it) }
                        ?.let { UpNext.firstEpisode(it, com.woodruff.tvexplorer.tmdb.Tmdb.seasonNumber(folder.name)) }
                }
            }.getOrNull() ?: return@launch
            if (player !== p || p.hasNextMediaItem() || p.currentMediaItem?.mediaId != key) return@launch
            val nextRef = MediaRef.Drive(next.id)
            // Known as this show's whatever it's called (Continue watching's poster, the same-show checks).
            com.woodruff.tvexplorer.cloud.EpisodeShows.remember(app, mapOf(nextRef.key to show))
            p.addMediaItem(mediaItem(nextRef.key, com.woodruff.tvexplorer.util.Format.title(next.name)))
        }
    }

    // ---- Doorbell ------------------------------------------------------------------------------

    private lateinit var doorbell: android.widget.TextView
    private lateinit var doorbellCard: GlidingCard
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
        // The news in bold, what to do in grey under it.
        doorbell.text = android.text.SpannableStringBuilder("Someone’s at the door").apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            if (line.isNotEmpty()) {
                append("\n")
                val from = length
                append(line)
                setSpan(android.text.style.ForegroundColorSpan(PlayerStyle.SECONDARY), from, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.85f), from, length, 0)
            }
        }
        ringing = true
        view.hideController()
        doorbellCard.show()
        player?.let { updateSkip(it) } // Skip intro / Next episode make way
        countdown.removeCallbacks(hideDoorbell)
        countdown.postDelayed(hideDoorbell, 20_000)
    }

    /** [animate] false: gone at once (the player stopping). */
    private fun dismissDoorbell(animate: Boolean = true) {
        countdown.removeCallbacks(hideDoorbell)
        ringing = false
        ringingCamera = null
        if (::doorbellCard.isInitialized) doorbellCard.hide(animate)
    }

    /**
     * Left by choice: the TV's Home, another app, or a screen opened over the film on purpose (OK on
     * the doorbell's card). Back to it, the film is paused where it was, with the controls up (they
     * stay up while it's paused), rather than playing on by itself (browsing-motion §3.4). Not the
     * TV's screensaver or standby, which nobody chose: those come back as they were.
     */
    private var leftByChoice = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        leftByChoice = true
    }

    override fun onResume() {
        super.onResume()
        // Only covered for a moment by something see-through (a TV's settings panel): nothing to undo.
        leftByChoice = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // So it still comes back paused if Android closed Woodruff meanwhile, to free memory...
        outState.putBoolean(KEY_LEFT_BY_CHOICE, leftByChoice)
        // ...on the same item, at the same spot (PlayerKeep). Before Android 9 this comes before
        // onStop, with the player still up; from Android 9 on, after onStop has kept where it was.
        val p = player
        if (p != null && p.mediaItemCount > 0) {
            val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
            PlayerKeep.save(
                outState, items.map { it.mediaId }, items.map { it.mediaMetadata.title?.toString() ?: "" },
                p.currentMediaItemIndex, p.currentPosition, playing = p.playWhenReady || heldForRate,
            )
        } else savedQueue?.let { (keys, titles) ->
            PlayerKeep.save(outState, keys, titles, savedIndex ?: 0, savedPosition ?: 0L, playing = savedPlayWhenReady ?: true)
        }
    }

    override fun onStart() {
        super.onStart()
        if (leftByChoice) savedPlayWhenReady = false
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
        stall = null
        stalledSince = null
        view.controllerAutoShow = true
        waitClock.reset()
        startedAgainFromDrive.clear()
        // Knowing when the Wi-Fi comes back, a stalled film tries again that moment (once per process).
        com.woodruff.tvexplorer.util.NetworkWatch.start(applicationContext)
        rateMatchedFor = null
        nextSeasonFor = null
        matchRate = FrameRateMatch.enabled(this)
        val startPos = savedPosition ?: intent.getLongExtra(EXTRA_POSITION, -1L).takeIf { it >= 0 } ?: recent.resumePosition(keys[start])

        // Each with the subtitle files known to sit beside it (the first film's are looked for as it
        // starts, the rest's in the background: prepareWithSidecars).
        val items = keys.mapIndexed { i, key ->
            mediaItem(key, (titles.getOrNull(i)?.ifBlank { null } ?: MediaRef.parse(key).uri.lastPathSegment)?.let(com.woodruff.tvexplorer.util.Format::title))
        }
        // Subtitles as Woodruff draws them (or as the TV's own caption settings say), in their own place.
        applySubtitleLook()
        resetSubtitlePlace()
        // Back above whatever is still at the bottom as the player comes back (the controls left up).
        subtitlesMakeWay()

        // The buffer is Java memory in Woodruff's own heap, so how much it holds (ahead of the film and
        // behind it) depends on how big that is: see playerLoadControl.
        val heapMb = getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 256
        keptBehindMs = backBufferMs(heapMb)
        // The TV's own decoders (and sound passed straight through to a soundbar) first; FFmpeg's
        // for the sound the TV can't play itself, so DTS and TrueHD films aren't silent.
        player = ExoPlayer.Builder(this, playerRenderers(this))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSources()).setLoadErrorHandlingPolicy(FilmStartsErrorPolicy())
                    // Subtitles that don't parse show nothing rather than stopping the film; Windows-encoded ones keep their accents.
                    .setSubtitleParserFactory(SidecarLoading.parserFactory()),
            )
            .setLoadControl(playerLoadControl(heapMb))
            // Seeks land on the first clean frame just after the spot (SeekAim); the viewer's are aimed
            // one by one (aimAt), and this covers the rest (a voice "rewind"). Watch together's leader
            // seeks exactly: the other TVs line up on the spot it asked for.
            .setSeekParameters(if (leader) androidx.media3.exoplayer.SeekParameters.EXACT else SeekAim.SNAP)
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
                    // The start of this film kept on the TV was out of date (a new version in Drive), or
                    // the film couldn't be read from it: once, start it again from Drive alone.
                    if (key != null && startAgainFromDrive(p, key, error)) return
                    val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                    val drive = key?.startsWith("drive:") == true
                    // The connection or Google, not this file: every other item would fail the same way.
                    val kind = PlaybackErrors.stallOf(http, error.errorCode, drive)
                    if (kind != null) {
                        // Google Drive turned the request down (sign-in ran out, a hiccup): try this episode
                        // again at once, from where it was, with a current token.
                        if (drive && retriedKey != key) {
                            retriedKey = key
                            p.prepare()
                            return
                        }
                        // Stay on it and try again (it used to skip ahead, item after item, until the
                        // Wi-Fi came back many episodes later), the ring saying what's going on.
                        stalled(p, key, kind)
                        return
                    }
                    // Unsupported codec etc.: say so in plain words and move on rather than sitting on a
                    // black screen. The whole cause goes to the log: the TV running out of memory partway
                    // through a big remux otherwise looks just like a bad file.
                    android.util.Log.w("Player", "Can't play $key: ${error.errorCodeName}", error)
                    clearStall()
                    waitClock.reset()
                    val why = PlaybackErrors.why(error.errorCode, http, drive, PlaybackErrors.outOfMemory(error))
                    say(PlaybackErrors.cantPlay(p.currentMediaItem?.mediaMetadata?.title?.toString(), why), NOTICE_MESSAGE_MS)
                    if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() }
                    updateLoading()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) { retriedKey = null; clearStall() }
                    updateLoading()
                    if (leader) sendSync()
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                    // "Seek back 10 s, kept: picture after 40 ms": the jump, and whether it was meant
                    // to come from memory, for a seek aimed here (aimAt; ⏪ / ⏩ read the position
                    // again a moment later, so it can differ by a few ms).
                    val what = aimed?.takeIf {
                        kotlin.math.abs(it.first - newPosition.positionMs) <= 250 && oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                    }?.let { "Seek " + SeekAim.describe(oldPosition.positionMs, newPosition.positionMs, it.second) } ?: "Seek"
                    aimed = null
                    waitingForPicture = what to android.os.SystemClock.elapsedRealtime()
                    if (leader) sendSync()
                }

                override fun onRenderedFirstFrame() {
                    // How long Play or a seek took to show a picture (adb logcat -s Player), to
                    // compare builds on a real TV.
                    waitingForPicture?.let { (what, since) ->
                        // A start whose header and index came from this TV's copy (FilmStarts) says so.
                        val copy = what == "Start" && p.currentMediaItem?.mediaId?.let { FilmStarts.readFromCopy(it, since) } == true
                        val label = if (copy) "$what (header and index from this TV)" else what
                        android.util.Log.i("Player", "$label: picture after ${android.os.SystemClock.elapsedRealtime() - since} ms")
                    }
                    waitingForPicture = null
                    // A film's first picture: its sound is looked at a moment later (once).
                    scheduleSoundCheck(p)
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
                    updateTrackButtons(p)
                    // A song has no first picture to wait for: its sound is looked at now.
                    if (!tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)) scheduleSoundCheck(p)
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
                    // Loaded again after a stall, even while paused: the connection is back.
                    if (p.playbackState == Player.STATE_READY) clearStall()
                    updateLoading()
                    // Last item of a music queue (no pause at the end): leave like a video does.
                    if (state == Player.STATE_ENDED) { p.currentMediaItem?.mediaId?.let(::finished); finish() }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateScreenOn()
                    updateLoading()
                    if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
                    if (sleepAtEnd) { sleepNow(); return }
                    if (p.hasNextMediaItem()) startUpNext()
                    else { p.currentMediaItem?.mediaId?.let(::finished); finish() }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // A spot being scrubbed to was chosen in the item that just ended: it would land
                    // somewhere arbitrary in this one.
                    timeBar.cancelScrub()
                    cancelUpNext()
                    // A look at the last item's sound that hadn't happened yet is for an item no longer playing.
                    countdown.removeCallbacks(checkSound)
                    // Moved on while waiting to try again (Next pressed): try this one now, not at the
                    // next try (a sign-in that ended may not matter to it: a film on a USB drive).
                    if (stalledKey != null && p.playbackState == Player.STATE_IDLE) p.prepare()
                    autoSubsOn = false
                    applyShowTracks(p)
                    bindTitle(mediaItem)
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
            // new film plays, unless it's one put back after an update (paused at its spot).
            p.playWhenReady = savedPlayWhenReady ?: !intent.getBooleanExtra(com.woodruff.tvexplorer.update.Restore.EXTRA_PAUSED, false)
            waitingForPicture = "Start" to android.os.SystemClock.elapsedRealtime()
            prepareWithSidecars(p)
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
        // The phone's ↺10, 30↻ and Next, while this player is on screen.
        player?.let(::answerPhones)
        keepDriveTokenFresh(keys)
    }

    override fun onStop() {
        com.woodruff.tvexplorer.util.FrameStats.detach(this)
        preview?.release(); preview = null
        // Off the screen: the preview, the options and any message go at once, as the cards below do.
        if (::previewCard.isInitialized) previewCard.hide(animate = false)
        if (::menu.isInitialized) menu.hide(animate = false)
        countdown.removeCallbacks(hideMessage)
        countdown.removeCallbacks(checkSound)
        if (::messageCard.isInitialized) messageCard.hide(animate = false)
        com.woodruff.tvexplorer.remote.TvScreen.detach(this)
        reporter.removeCallbacks(report)
        stopTrying()
        followJob?.cancel(); followJob = null
        doorbellJob?.cancel(); doorbellJob = null
        tokenJob?.cancel(); tokenJob = null
        sidecarJob?.cancel(); sidecarJob = null
        phoneJob?.cancel(); phoneJob = null
        dismissDoorbell(animate = false)
        cancelUpNext(animate = false)
        countdown.removeCallbacks(sleep)
        rateSwitch?.cancel()
        // Nothing of ours plays now, unless a player started since is in front: it says what it plays.
        if (inFront) com.woodruff.tvexplorer.nowplaying.NowPlaying.ownPlayer = null
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
        // Off the screen: the loading ring goes at once, as the cards above do, and the subtitles are back in their place.
        updateLoading()
        resetSubtitlePlace()
        super.onStop()
    }

    /**
     * What's playing and where, so an update puts it back paused at this spot (update/Restore); and
     * whether it's playing, or about to by itself (Up next counting down, the TV switching its
     * refresh rate), which no update interrupts. Main thread.
     */
    override fun restoreRoute(): com.woodruff.tvexplorer.update.Restore.Route? {
        val p = player
        val items = p?.let { (0 until it.mediaItemCount).map { i -> it.getMediaItemAt(i) } }
        val (keys, titles) = items?.let { l -> l.map { it.mediaId } to l.map { it.mediaMetadata.title?.toString().orEmpty() } } ?: savedQueue ?: return null
        return com.woodruff.tvexplorer.update.Restore.film(
            keys, titles, p?.currentMediaItemIndex ?: savedIndex ?: 0, p?.currentPosition ?: savedPosition ?: 0L,
            playing = p != null && (p.playWhenReady || heldForRate || upNextPanel.shown),
        )
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
                    // The season's subtitle files come with it: the episodes queued below get theirs.
                    com.woodruff.tvexplorer.cloud.DriveApi.siblings(t, ref.id).also { SubtitleIndex.learnNow(it) }
                }
            }.getOrNull() ?: return@launch
            if (player !== p || p.mediaItemCount != 1 || p.currentMediaItem?.mediaId != key) return@launch
            // Episodes only: a movie in the Movies folder shouldn't roll on into another movie.
            val media = folder.filter {
                it.kind == com.woodruff.tvexplorer.data.FileKind.VIDEO && !com.woodruff.tvexplorer.data.FileTypes.isSidecar(it.name) && ShowPrefs.isEpisode(it.name)
            }
            val at = media.indexOfFirst { it.id == ref.id }.takeIf { it >= 0 } ?: return@launch
            fun item(f: com.woodruff.tvexplorer.cloud.DriveFile) = mediaItem(MediaRef.Drive(f.id).key, com.woodruff.tvexplorer.util.Format.title(f.name))
            p.addMediaItems(media.drop(at + 1).take(300).map(::item))
            p.addMediaItems(0, media.take(at).takeLast(300).map(::item))
        }
    }

    /**
     * Local files pass straight through; Drive URLs get an Authorization header per request, and
     * the start of each Drive film (its header and index) is kept on the TV ([FilmStarts]). A
     * subtitle file beside the film that can't be read leaves the film without it ([SidecarLoading]).
     */
    private fun dataSources(): androidx.media3.datasource.DataSource.Factory {
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
        return SidecarLoading.dataSourceFactory(DefaultDataSource.Factory(this, FilmStarts.dataSourceFactory(applicationContext, authed)))
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
            aimSkip(event.keyCode) // ⏪ / ⏩: PlayerView jumps next
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /** The presses this activity acts on itself (before PlayerView sees them). */
    private fun handleKey(event: KeyEvent): Boolean {
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        // A menu, while it's up: Up / Down choose, OK does it, Back (or the menu button) closes it.
        // Other keys (play, pause) go on to the player as before.
        if (menu.shown) return menu.handleKey(event.keyCode)
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
            showOptions(); return true
        }
        // The doorbell's card first: OK looks at the door, Back closes the card. (A card gliding out
        // is gone already, as far as the keys go.)
        if (doorbellCard.shown) {
            val camera = ringingCamera
            when {
                ok && camera != null -> { dismissDoorbell(); startActivity(DoorbellActivity.intent(this, camera)); return true }
                event.keyCode == KeyEvent.KEYCODE_BACK -> { dismissDoorbell(); return true }
            }
        }
        if (upNextPanel.shown) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { upNextPanel.choose(done = false); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { upNextPanel.choose(done = true); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_BACK -> { stopWatching(); return true }
            }
            if (ok) { if (upNextPanel.doneChosen) stopWatching() else playNext(); return true }
        }
        if (nextCard.shown && !view.isControllerFullyVisible) {
            when {
                ok -> { nextCard.hide(); playNext(); return true }
                event.keyCode == KeyEvent.KEYCODE_BACK -> { nextDismissedFor = player?.currentMediaItem?.mediaId; nextCard.hide(); return true }
            }
        }
        if (skipCard.shown && !view.isControllerFullyVisible && ok) {
            // To the first clean frame at or just after the intro's end: never before it.
            show(); player?.let { aimAt(it, introEnd); it.seekTo(introEnd) }; skipCard.hide(); return true
        }
        if (view.isControllerFullyVisible) {
            // Back while scrubbing: back to where it was. Otherwise Back puts the controls away
            // first, as on tvOS (a second Back leaves).
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (!timeBar.cancelScrub()) view.hideController()
                return true
            }
            // OK on the scrub bar plays / pauses, as a click on the Siri Remote does (while scrubbing,
            // the bar takes it: it plays from the spot chosen).
            if (ok && timeBar.isFocused && !timeBar.isScrubbing) {
                androidx.media3.common.util.Util.handlePlayPauseButtonAction(player)
                view.showController()
                return true
            }
        } else if (ok) {
            view.showController()
            return true
        }
        return false
    }

    companion object {
        // Half the first size: big enough to recognise a scene, small enough not to cover the film.
        private const val PREVIEW_W = 176
        private const val PREVIEW_H = 99
        /** How long the controls stay up after the last press (paused, they stay). */
        private const val CONTROLS_TIMEOUT_MS = 5_000
        /** Up next's countdown, and how far into the next episode its picture is taken from. */
        private const val UP_NEXT_SECONDS = 10
        private const val UP_NEXT_ART_MS = 90_000L
        /** A button with nothing to offer (no subtitles, or no sound, in the video). */
        private const val DIM = 0.4f
        private val SPEEDS = listOf("0.5×" to 0.5f, "0.75×" to 0.75f, "Normal" to 1f, "1.25×" to 1.25f, "1.5×" to 1.5f, "2×" to 2f)
        /** How long a message stays over the film: as long as Android's short toasts did. */
        private const val MESSAGE_MS = 2_000L
        /** A message there's more to read in, said once (a film's sound this TV can't play, "Can’t play …"). */
        private const val NOTICE_MESSAGE_MS = 6_000L
        /** Why a subtitle size didn't change, or isn't Woodruff's to choose (specs/player-feedback.md §3.6). */
        private const val SIZE_MESSAGE_MS = 3_500L
        /** How long a film waits to start for the subtitle files beside it to be found (a USB drive waking up). */
        private const val SIDECAR_WAIT_MS = 1_000L
        /** USB folders looked in for the rest of a queue's subtitle files, at most (a queue is usually one folder). */
        private const val MAX_SIDECAR_FOLDERS = 20
        /**
         * How long a stalled film waits before trying again when nothing says the network is back
         * (it was down for a moment only, or it's Google that's failing): see [RetryWait].
         */
        private const val RETRY_MS = 10_000L
        /** How long after a film's first picture its sound is looked at: after the window's own entrance. */
        private const val SOUND_CHECK_DELAY_MS = 1_000L
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_START = "start"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_TOGETHER = "together"
        /** Saved state: the film was left by choice (see leftByChoice). */
        private const val KEY_LEFT_BY_CHOICE = "leftByChoice"
        /** How near the end of a season's last episode the next season is looked for. */
        private const val NEXT_SEASON_LEAD_MS = 3 * 60_000L
        /** Media session ids must differ, and two players can overlap for a moment. */
        private var sessionIds = 0
        /** Players started so far in this process: the latest is the one in front (see inFront). Main thread. */
        private var starts = 0

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
 * The items in [p]'s queue other than the one playing, as [given] makes them (a film with the
 * subtitle files now known beside it; the item itself when nothing changes). Media3 takes a film's
 * subtitle files only as the item is added, and replacing an item in place only renames it, so
 * those that change are taken out and put back, in two runs (after the one playing, then before
 * it). The one playing is never touched: it plays on, from where it is.
 */
internal fun giveWaitingItems(p: Player, given: (MediaItem) -> MediaItem) {
    fun swap(from: Int, to: Int) {
        if (from >= to) return
        val items = (from until to).map { p.getMediaItemAt(it) }
        val now = items.map(given)
        if (now.indices.none { now[it] !== items[it] }) return
        p.removeMediaItems(from, to)
        p.addMediaItems(from, now)
    }
    val current = p.currentMediaItemIndex
    swap(current + 1, p.mediaItemCount)
    swap(0, current)
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

/**
 * The player's decoders. The TV's own come first, as before, and so does passing the sound
 * straight through to a soundbar or receiver that can decode it: Media3 gives each track to the
 * first renderer that fully handles it. FFmpeg's sound decoder (Jellyfin's build for Media3) comes
 * after them, for what the TV can neither decode nor pass through: DTS, DTS-HD, Dolby TrueHD, and
 * Dolby Digital (Plus) on TVs without Dolby. Remuxes of Blu-rays often carry only those, and
 * without it they played silently, or on whichever commentary track the TV could play.
 */
@OptIn(UnstableApi::class)
internal fun playerRenderers(context: Context): androidx.media3.exoplayer.DefaultRenderersFactory =
    androidx.media3.exoplayer.DefaultRenderersFactory(context)
        .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

/**
 * Whether a film keeps the screen on: meant to play ([playing]) and loading or playing, or trying
 * again after the connection dropped ([stalledMs]: how long it has been trying; null when it isn't).
 * Between tries the player is idle, which let the TV's screensaver cover "Reconnecting…" the moment
 * the Wi-Fi dropped, if nobody had pressed a key for a while. It keeps the screen on for the first
 * [RECONNECTING_SCREEN_MS] of trying, then lets the TV sleep as it would for a paused film.
 */
internal fun keepScreenOn(playing: Boolean, state: Int, stalledMs: Long?): Boolean =
    playing && (state == Player.STATE_BUFFERING || state == Player.STATE_READY || (stalledMs != null && stalledMs < RECONNECTING_SCREEN_MS))

/** See [keepScreenOn]. */
internal const val RECONNECTING_SCREEN_MS = 5 * 60_000L

/** Heaps of this many MB or fewer get a smaller player buffer. */
internal const val SMALL_HEAP_MB = 192
private const val MB = 1024 * 1024

/**
 * How the player buffers. It starts as soon as 1 s is loaded (2 s after a stall) instead of Media3's
 * 2.5 / 5 s, so Play feels immediate, and from then on keeps the film loaded [BUFFER_AHEAD_MS] ahead.
 * It keeps [backBufferMs] behind the film too (from the keyframe before, so all of it can be played),
 * so a short rewind needs nothing new from Drive. Ahead and behind share one byte cap: Media3's own
 * (about 140 MB), or on a heap of 192 MB or less [bufferCapBytes], which keeps it and what OkHttp has
 * fetched ahead to half the heap (a Blu-ray remux would fill Media3's otherwise).
 *
 * Ahead stays topped up: Media3 stops loading once it holds the most it may (50 s, or the byte cap)
 * and only starts again once the buffer is down to its minimum. That minimum was 15 s, so between
 * refills the film sank to 15 s ahead (a remux at the byte cap too), and whether a 20–40 s Wi-Fi
 * drop stalled it came down to when it struck. With the minimum equal to the maximum, as in Media3's
 * own defaults, it loads again as soon as it's below either, a little at a time, and rides out a drop
 * with all it can hold. The most it holds, and so its memory, is unchanged.
 */
@OptIn(UnstableApi::class)
internal fun playerLoadControl(heapMb: Int): androidx.media3.exoplayer.DefaultLoadControl =
    androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(BUFFER_AHEAD_MS, BUFFER_AHEAD_MS, START_BUFFER_MS, RESTART_BUFFER_MS)
        .setBackBuffer(backBufferMs(heapMb), /* retainBackBufferFromKeyframe = */ true)
        .apply { bufferCapBytes(heapMb)?.let { setTargetBufferBytes(it) } }
        .build()

/** How far ahead of the film the player keeps it loaded, at most and at least (see [playerLoadControl]). */
internal const val BUFFER_AHEAD_MS = 50_000
/** How much film has to be loaded before Play starts, and before it carries on after a stall. */
internal const val START_BUFFER_MS = 1_000
internal const val RESTART_BUFFER_MS = 2_000

/**
 * How much film the player keeps behind the playhead on a TV whose app heap is [heapMb] MB.
 *
 * 12 s where the heap is big: a press back is 10 s, aimed from where the film really is
 * (TvTimeBar.landing), so it lands 2 s inside what's kept. Media3 lets go of everything older, and a
 * rewind past that downloads it again and throws away what was loaded ahead too. It costs no extra
 * memory: on films heavy enough to reach the byte cap, the 2 s more behind come out of what's loaded
 * ahead (it used to keep 10 s, and one press back landed just outside them).
 *
 * 5 s on a small heap, as before: its cap is about half the size, and at a Blu-ray remux's 30 Mbps
 * 12 s would take over half of it, leaving under 10 s loaded ahead to ride out a Wi-Fi drop. A 10 s
 * rewind there still downloads again.
 */
internal fun backBufferMs(heapMb: Int): Int = if (heapMb > SMALL_HEAP_MB) BACK_BUFFER_MS else SMALL_HEAP_BACK_BUFFER_MS

/** See [backBufferMs]. */
internal const val BACK_BUFFER_MS = 12_000
internal const val SMALL_HEAP_BACK_BUFFER_MS = 5_000

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
