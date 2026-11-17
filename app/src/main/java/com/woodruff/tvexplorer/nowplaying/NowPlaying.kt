package com.woodruff.tvexplorer.nowplaying

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a TV is playing right now. [can]: what the phone may do to it ([MediaAction.wire] names),
 * worked out by [NowPlaying.current]; empty from a Woodruff that doesn't say, which the phone takes
 * as Play/Pause only. [key]: the media key in Woodruff's own player ("drive:<id>"), so the phone can
 * show its poster. [hasNext]: Woodruff's own player has another item queued after this one, and
 * [seekable]: it can jump within the one playing (not while a film is still opening, nor in a file
 * without an index). Both stay on this TV: they only decide whether Next, ↺10 and 30↻ are offered.
 */
data class Playing(
    val title: String,
    val subtitle: String?,
    val app: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val can: List<String> = emptyList(),
    val key: String? = null,
    val hasNext: Boolean = false,
    val seekable: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("subtitle", subtitle ?: "").put("app", app)
        .put("playing", playing).put("position", positionMs).put("duration", durationMs)
        .put("can", JSONArray(can))
        .apply { if (key != null) put("key", key) }

    companion object {
        fun fromJson(o: JSONObject?): Playing? = o?.takeIf { it.optString("title").isNotBlank() }?.let {
            val can = it.optJSONArray("can")
            Playing(it.optString("title"), it.optString("subtitle").ifBlank { null }, it.optString("app"),
                it.optBoolean("playing"), it.optLong("position"), it.optLong("duration"),
                can = can?.let { a -> (0 until a.length()).map { i -> a.optString(i) }.filter { w -> MediaAction.of(w) != null } }.orEmpty(),
                key = it.optString("key").ifBlank { null })
        }
    }
}

/** What the phone can ask of what's playing: its words on the wire ("playpause", "back10", "fwd30", "next"). */
enum class MediaAction(val wire: String) {
    PLAY_PAUSE("playpause"), BACK_10("back10"), FORWARD_30("fwd30"), NEXT("next");

    companion object {
        fun of(wire: String?): MediaAction? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * ↺10, 30↻ and Next for Woodruff's own player, sent from the phone through [NowPlaying.commands].
 * The player applies them itself: [target] gives where a skip lands; [NEXT] is its Next Episode.
 */
enum class PlayerCommand {
    BACK_10, FORWARD_30, NEXT;

    /** Where ↺10 / 30↻ lands from [positionMs]: never before the start, nor past the end ([durationMs], when known). Null for [NEXT]. */
    fun target(positionMs: Long, durationMs: Long): Long? = when (this) {
        BACK_10 -> (positionMs - 10_000).coerceAtLeast(0)
        FORWARD_30 -> (positionMs + 30_000).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        NEXT -> null
    }

    companion object {
        fun of(action: MediaAction): PlayerCommand? = when (action) {
            MediaAction.BACK_10 -> BACK_10
            MediaAction.FORWARD_30 -> FORWARD_30
            MediaAction.NEXT -> NEXT
            MediaAction.PLAY_PAUSE -> null
        }
    }
}

/** How asking for ↺10, 30↻ or Next went. */
sealed interface CommandResult {
    /** Done; [playing]: whether it's playing now (null when not known). */
    data class Done(val playing: Boolean?) : CommandResult
    /** Nothing is playing that this TV can see. */
    data object NothingPlaying : CommandResult
    /** What's playing can't do that (an app without seeking, nothing queued after it). */
    data object CantDo : CommandResult
}

/**
 * Now playing on this TV: Woodruff Family's own player first; otherwise whatever app is playing
 * media (Netflix, YouTube, Plex…), read from Android's media sessions. Reading other apps' sessions
 * needs "Notification access" for [MediaWatcher], granted once in Settings (or with adb).
 */
object NowPlaying {
    /** Updated by the built-in player every second and as it changes (play/pause, a jump, the next item); null when it's closed. */
    @Volatile var ownPlayer: Playing? = null

    private val _commands = MutableSharedFlow<PlayerCommand>(extraBufferCapacity = 8)

    /**
     * ↺10 / 30↻ / Next from the phone for Woodruff's own player. The player collects this while it's
     * on screen (PlayerActivity, while started: a jump to [PlayerCommand.target] of its position, or
     * its own Next Episode for [PlayerCommand.NEXT]); until something collects it, the phone is
     * offered Play/Pause only ([ownCan]), so it never shows a button that does nothing.
     */
    val commands: SharedFlow<PlayerCommand> = _commands.asSharedFlow()

    /** Whether Woodruff's own player is listening for [commands] right now. */
    val playerListens: Boolean get() = _commands.subscriptionCount.value > 0

    /** What's playing, with what the phone can do to it ([Playing.can]). */
    fun current(c: Context): Playing? = ownPlayer?.let { it.copy(can = ownCan(it, playerListens)) } ?: fromSessions(c)

    /**
     * Whether this TV's screen is on the Apple TV ([showsAppleTv]): on the input named for it, or Woodruff on an
     * Apple TV says it's on screen ([AppleTvScreen], at [now]) and this TV shows the input it showed as the Apple TV
     * said so, or nothing it can tell by. Without Notification access only the Apple TV's word counts. False while
     * Woodruff's own player is open.
     */
    fun onAppleTv(c: Context, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (ownPlayer != null) return false
        val shows = everything(c.applicationContext)
        shows?.let(AppleTvScreen::noteNamed)
        return showsAppleTv(shows, AppleTvScreen.words(now), AppleTvScreen.namedInput)
    }

    /**
     * Woodruff on an Apple TV ([from]: its id) said it's on its screen ([on]) for the next [forSeconds], or that it
     * isn't (POST /api/appletv), at [now]. As its word turns on, and for a few seconds after, what this TV shows is
     * noted with it ([AppleTvScreen.said]).
     */
    fun appleTvSaid(c: Context, on: Boolean, forSeconds: Int = AppleTvScreen.DEFAULT_SECONDS, from: String = "", now: Long = SystemClock.elapsedRealtime()) {
        val app = c.applicationContext
        AppleTvScreen.said(on, forSeconds, now, from) { everything(app) }
    }

    /**
     * For an Apple TV asking which TV it's plugged into (GET /api/appletv): whether this TV's screen is on one of its
     * inputs right now, and whether that input is named for an Apple TV ([screenReport]). Neither while Woodruff's own
     * player is open.
     */
    fun appleTvReport(c: Context): Pair<Boolean?, Boolean?> {
        if (ownPlayer != null) return false to false
        val shows = everything(c.applicationContext)
        shows?.let(AppleTvScreen::noteNamed)
        return screenReport(shows)
    }

    fun canSeeOtherApps(c: Context): Boolean = try {
        sessions(c); true
    } catch (e: Exception) {
        false // SecurityException until Notification access is granted
    }

    private fun sessions(c: Context): List<MediaController> {
        val msm = c.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return msm.getActiveSessions(ComponentName(c, MediaWatcher::class.java))
    }

    /** The app that's playing, else the one that played last (Android lists the most recent first). */
    private fun pick(all: List<MediaController>): MediaController? =
        all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()

    /**
     * The phone's Play/Pause while another app is in front (Netflix, YouTube…). With Notification
     * access it tells that app's media session directly; without, it sends the remote's Play/Pause
     * key, which Android hands to whichever app is playing.
     */
    fun playPause(c: Context) {
        val app = c.applicationContext
        val told = runCatching {
            val ctl = pick(sessions(app)) ?: return@runCatching false
            val state = ctl.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) ctl.transportControls.pause()
            else ctl.transportControls.play()
            true
        }.getOrDefault(false) // no Notification access (SecurityException), or the app went away
        if (told) return
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    /**
     * ↺10, 30↻ or Next from the phone: to Woodruff's own player through [commands]; to another app
     * through its media session, only where that session says it can seek or skip (Netflix often
     * can't, so the phone never promises it: [Playing.can]).
     */
    fun command(c: Context, cmd: PlayerCommand): CommandResult {
        ownPlayer?.let { own ->
            if (cmd.wire() !in ownCan(own, playerListens)) return CommandResult.CantDo
            return if (_commands.tryEmit(cmd)) CommandResult.Done(own.playing) else CommandResult.CantDo
        }
        return try {
            val ctl = pick(sessions(c.applicationContext)) ?: return CommandResult.NothingPlaying
            val state = ctl.playbackState ?: return CommandResult.NothingPlaying
            if (cmd.wire() !in sessionCan(state.actions)) return CommandResult.CantDo
            val playing = state.state == PlaybackState.STATE_PLAYING
            when (cmd) {
                PlayerCommand.NEXT -> ctl.transportControls.skipToNext()
                else -> {
                    val duration = ctl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
                    cmd.target(positionNow(state, playing), duration)?.let { ctl.transportControls.seekTo(it) }
                }
            }
            CommandResult.Done(playing)
        } catch (e: SecurityException) {
            CommandResult.NothingPlaying // no Notification access: other apps can't be seen, let alone steered
        } catch (e: Exception) {
            CommandResult.CantDo // the app went away meanwhile
        }
    }

    /** A session's position now: it reports the one at its last update, so move that on by the time since. */
    private fun positionNow(s: PlaybackState, playing: Boolean): Long =
        (if (playing && s.lastPositionUpdateTime > 0) s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        else s.position).coerceAtLeast(0)

    private fun fromSessions(c: Context): Playing? = try {
        pick(sessions(c))?.let { playingOf(c, it) }
    } catch (e: SecurityException) {
        null // notification access not granted: only Woodruff's own player is visible
    } catch (e: Exception) {
        null
    }

    /**
     * Everything Android's media sessions show on this TV, the most recent first (an app, or the TV's own input app
     * for the input on screen); null when it can't be seen (no Notification access).
     */
    private fun everything(c: Context): List<Playing>? = try {
        sessions(c).map { playingOf(c, it) }
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }

    /** What the media session [m] shows, as the phone sees it. */
    private fun playingOf(c: Context, m: MediaController): Playing {
        val meta = m.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val subtitle = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val app = runCatching {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(m.packageName, 0)).toString()
        }.getOrDefault(m.packageName)
        val state = m.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        // Sessions report the position at their last update; move it forward to now.
        val pos = state?.let { positionNow(it, playing) } ?: 0L
        return Playing(
            title = title?.takeIf { it.isNotBlank() } ?: app,
            subtitle = subtitle?.takeIf { it.isNotBlank() },
            app = app,
            playing = playing,
            positionMs = pos.coerceAtLeast(0),
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            can = sessionCan(state?.actions ?: 0L),
        )
    }
}

/**
 * Whether [p], what Android says is playing here, is one of the TV's inputs with the Apple TV on it.
 * While an input is on screen the TV's own input app says it is "playing" under the input's name
 * ("Apple TV HDMI 4"), with no length. An app called Apple TV playing a film isn't an input: it has
 * the film's name, and usually a length. Visible for tests.
 */
internal fun isAppleTvInput(p: Playing?): Boolean = p != null && p.playing && namedForAppleTv(p)

/** Whether [p] is one of the TV's inputs named for an Apple TV, playing or not ([isAppleTvInput]). Visible for tests. */
internal fun namedForAppleTv(p: Playing): Boolean {
    if (p.key != null || p.durationMs > 0) return false
    val title = p.title.lowercase()
    return isInput(p) && ("apple tv" in title || "appletv" in title)
}

/**
 * Whether this TV's screen shows the Apple TV, from what Android's media sessions show here ([shows], the most recent
 * first; null when they can't be seen), what the Apple TVs say ([words], those that hold: [AppleTvScreen]) and the
 * input this TV has shown named for an Apple TV ([named], if any). The input named for it counts by itself. Otherwise
 * an Apple TV's word holds while this TV shows the Apple TV's input, or nothing it can tell by:
 *  - nothing to be seen (no Notification access, or nothing here says anything): the word stands;
 *  - an input playing: the one this TV showed as the word began ([AppleTvScreen.Word.input]), or any, if it was on an
 *    app of its own then ([AppleTvScreen.Word.onApp]); never another one than the input named for an Apple TV, nor
 *    another one than it began on (a games console, Live TV);
 *  - an app of the TV's own playing (Netflix, YouTube): the TV has gone to it;
 *  - nothing playing, where the TV showed an input playing as the word began: it has left that input (its home
 *    screen, its settings);
 *  - nothing playing, the TV having shown no input: what it shows (an app paused) is from before the word
 *    ([AppleTvScreen.Word.before]: the same, not played since), which says nothing of where the TV is, or newer
 *    (an app paused, or an input left, since the Apple TV came on), which is the TV gone there.
 * An Apple TV's word goes on all through a film, whatever this TV shows: taken alone, it kept the TV's own doorbell
 * card off a TV switched to its home screen, a paused app or a games console. Visible for tests.
 */
internal fun showsAppleTv(shows: List<Playing>?, words: List<AppleTvScreen.Word>, named: Playing? = null): Boolean {
    val p = shows?.let(::shownOf)
    if (isAppleTvInput(p)) return true
    if (words.isEmpty()) return false
    if (p == null) return true
    if (p.playing) {
        if (!isInput(p) || (named != null && !sameShown(p, named))) return false
        return words.any { w -> w.input?.let { sameShown(p, it) } ?: w.onApp }
    }
    return words.any { w -> w.input == null && w.before.any { sameShown(p, it, positionToo = true) } }
}

/**
 * What this TV can say of its own screen ([shows], as for [showsAppleTv]): whether it's on one of its inputs right now
 * (an input is what plays), and whether that input is named for an Apple TV. Null for each when it can't tell (no
 * Notification access, or nothing seen). Visible for tests.
 */
internal fun screenReport(shows: List<Playing>?): Pair<Boolean?, Boolean?> {
    val p = shows?.let(::shownOf) ?: return null to null
    val input = p.playing && isInput(p)
    return input to (input && namedForAppleTv(p))
}

/** Of [all] (as Android lists them, the most recent first), the one this TV shows: the one playing, else the latest. */
internal fun shownOf(all: List<Playing>): Playing? = all.firstOrNull { it.playing } ?: all.firstOrNull()

/** How far a session left paused may have moved and still be the same, not played since (a pause a moment late). */
private const val SAME_SPOT_MS = 15_000L

/**
 * Whether [a] and [b] are the same thing shown: the same app's same title (an input's own name, for an input) and,
 * [positionToo], at the same spot give or take [SAME_SPOT_MS].
 */
internal fun sameShown(a: Playing, b: Playing, positionToo: Boolean = false): Boolean =
    a.app.trim().equals(b.app.trim(), ignoreCase = true) && a.title.trim().equals(b.title.trim(), ignoreCase = true) &&
        (!positionToo || kotlin.math.abs(a.positionMs - b.positionMs) <= SAME_SPOT_MS)

/** Whether [p] is one of the TV's inputs, as the TV's own input app says it plays one: by the input's name, or its own. */
internal fun isInput(p: Playing): Boolean = "hdmi" in p.title.lowercase() || p.app.trim().lowercase() in INPUT_APPS

/** What the TVs' own input apps are called. */
private val INPUT_APPS = setOf("tv", "live tv", "live channels", "tv input", "input", "inputs", "source", "sources")

private fun PlayerCommand.wire(): String = when (this) {
    PlayerCommand.BACK_10 -> MediaAction.BACK_10.wire
    PlayerCommand.FORWARD_30 -> MediaAction.FORWARD_30.wire
    PlayerCommand.NEXT -> MediaAction.NEXT.wire
}

/**
 * What the phone can do to Woodruff's own player [p]: Play/Pause always; once the player [listens]
 * for [NowPlaying.commands], ↺10 and 30↻ where it can jump ([Playing.seekable]) and Next when it has
 * another item queued. Visible for tests.
 */
internal fun ownCan(p: Playing, listens: Boolean): List<String> = buildList {
    add(MediaAction.PLAY_PAUSE.wire)
    if (listens) {
        if (p.seekable) { add(MediaAction.BACK_10.wire); add(MediaAction.FORWARD_30.wire) }
        if (p.hasNext) add(MediaAction.NEXT.wire)
    }
}

/**
 * What the phone can do to another app's media session with these PlaybackState [actions]:
 * Play/Pause always (the remote's key works anywhere), ↺10 and 30↻ only when it can seek, Next only
 * when it can skip. Visible for tests.
 */
internal fun sessionCan(actions: Long): List<String> = buildList {
    add(MediaAction.PLAY_PAUSE.wire)
    if (actions and PlaybackState.ACTION_SEEK_TO != 0L) { add(MediaAction.BACK_10.wire); add(MediaAction.FORWARD_30.wire) }
    if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add(MediaAction.NEXT.wire)
}

/** Exists only so Android can grant access to other apps' media sessions (Notification access). */
class MediaWatcher : NotificationListenerService()
