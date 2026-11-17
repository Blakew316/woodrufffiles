package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.io.InterruptedIOException

/**
 * What the player loads while nothing needs it: Google's daily download allowance ran out for the
 * whole family, and two kinds of loads were for nothing.
 *
 * A paused film was loaded 50 s ahead, as one playing is. Every stop let the player go and the film
 * came back from Drive: a look at the doorbell, Home and back, the screensaver over a paused film, an
 * update putting the film back paused overnight. Each of those cost 50 s of film again (up to about
 * 140 MB on the bigger TVs), whether or not anyone pressed Play. Paused now, it loads
 * [PAUSED_AHEAD_MS] (enough to show its picture and to start the moment Play is pressed) and carries
 * on from there once it plays.
 *
 * And Media3 loads the next episode in a queue during the last 50 s of the one playing, and went on
 * while Up next counted down: stopped there (Done, Back, "Sleep at the end of this episode", the TV
 * turned off), that was up to 50 s of an episode nobody chose. Now what comes after waits until it's
 * the one playing ([holds]): Up next, Next Episode, the remote's Next and the phone's start it as
 * they did, loading from its first byte then, as any film starts. Songs play straight on (no pause at
 * their end, [holdNext] false): the next one loads ahead as before, so there's no gap between them.
 *
 * [playing] and [holdNext] are set on the main thread as the player moves from item to item; the
 * player's loading threads read them ([GatedLoadControl], [HeldDriveSource]).
 */
internal class PlayerLoads {
    /** The item playing (its media id) as the player last said; null before it starts. */
    @Volatile var playing: String? = null

    /** The player pauses at the end of each item (a video, for Up next): what comes after it waits to be chosen. */
    @Volatile var holdNext: Boolean = true

    /** Whether a load for item [loading] (its media id) waits: it isn't the one playing, and the player pauses before it. */
    fun holds(loading: String?): Boolean {
        val now = playing
        return holdNext && now != null && loading != null && loading != now
    }

    /** Whether the player may go on loading [loading], meant to play ([playWhenReady]) or not, with [bufferedUs] loaded ahead. */
    fun allows(loading: String?, playWhenReady: Boolean, bufferedUs: Long): Boolean =
        !holds(loading) && (playWhenReady || bufferedUs < PAUSED_AHEAD_MS * 1_000L)
}

/**
 * [inner] (the player's own [playerLoadControl]), except that it loads only what [loads] allows: a few
 * seconds of a paused film, and nothing of the next item until it plays ([PlayerLoads]). Everything
 * else is [inner]'s: its memory, its buffer sizes, when Play starts. [inner] is asked every time, so
 * its own state follows the player's as before.
 */
@OptIn(UnstableApi::class)
internal class GatedLoadControl(private val inner: LoadControl, private val loads: PlayerLoads) : LoadControl {
    // Only ever used on the player's own thread.
    private val window = Timeline.Window()
    private val period = Timeline.Period()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val wanted = inner.shouldContinueLoading(parameters)
        return wanted && loads.allows(itemOf(parameters), parameters.playWhenReady, parameters.bufferedDurationUs)
    }

    /** The media id of the item [p] is about (the one loading); null when it can't be told, which holds nothing back. */
    private fun itemOf(p: LoadControl.Parameters): String? = runCatching {
        val index = p.timeline.getIndexOfPeriod(p.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) null
        else p.timeline.getWindow(p.timeline.getPeriod(index, period).windowIndex, window).mediaItem.mediaId
    }.getOrNull()

    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        inner.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

    override fun getAllocator(): Allocator = inner.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = inner.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = inner.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        inner.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)
}

/**
 * [upstream] (the player's way to Google Drive), with a Drive film's requests waiting while it isn't
 * the one playing ([PlayerLoads.holds]). Media3 opens the next item in a queue to read its header as
 * soon as the one playing is loaded to its end, whatever its load control says: that was a request
 * for the next episode, and everything Drive sent of it, in the last 50 s of every episode. Held here
 * until it's chosen, then it goes on at once; a load the player lets go of meanwhile (Done, Back)
 * ends without a byte asked of Google. What the TV kept of its start ([FilmStarts]) is read all the
 * same: this sits between that and Drive. Anything not a Drive film passes straight through.
 */
@OptIn(UnstableApi::class)
internal class HeldDriveSource(
    private val upstream: DataSource,
    private val loads: PlayerLoads,
    /** Waits [Long] ms on the loading thread; false when it's interrupted (the player let go of the load). */
    private val pause: (Long) -> Boolean = ::sleepFor,
) : DataSource {
    override fun open(dataSpec: DataSpec): Long {
        val key = driveFileKey(dataSpec.uri)
        while (key != null && loads.holds(key)) {
            if (!pause(LOOK_AGAIN_MS)) throw InterruptedIOException("The player let go of this load")
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    companion object {
        /** How often a held request looks whether its film is the one playing now. */
        const val LOOK_AGAIN_MS = 50L

        /** [upstream] with its Drive films held while they aren't the one playing. */
        fun factory(upstream: DataSource.Factory, loads: PlayerLoads): DataSource.Factory =
            DataSource.Factory { HeldDriveSource(upstream.createDataSource(), loads) }
    }
}

/** Waits [ms] on the loading thread; false when it's interrupted (the player let go of its loads). */
private fun sleepFor(ms: Long): Boolean = try {
    Thread.sleep(ms)
    true
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}

/**
 * How far ahead a paused film is loaded ([PlayerLoads]): more than Media3 needs to start again after
 * a stall ([RESTART_BUFFER_MS]), so Play starts at once, and a few seconds instead of [BUFFER_AHEAD_MS].
 */
internal const val PAUSED_AHEAD_MS = 3_000L
