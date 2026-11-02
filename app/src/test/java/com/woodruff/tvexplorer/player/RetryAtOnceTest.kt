package com.woodruff.tvexplorer.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one try a Drive film gets at once when Google Drive or the connection fails it: a token Drive
 * refuses is dropped off the main thread and the film tried again with a new one, which plays; a
 * sign-in Google has ended fails that one try and the player goes on to wait (where it finds the
 * sign-in gone and says how to sign in again); other trouble is tried again at once as it was. A real
 * ExoPlayer reads a stand-in Drive through the player's own Drive sources ([driveAuthorized]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetryAtOnceTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val job = SupervisorJob()
    /** Where the player's own coroutines run: its main thread. */
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private val others = ArrayList<ExoPlayer>()
    private val film = MediaRef.Drive("1AbCdEfGhIj")

    /** What the player went on to do after its one try: wait (the HTTP answer), or move on ("can't play"). */
    private val waits = CopyOnWriteArrayList<Int?>()
    private val cantPlay = CopyOnWriteArrayList<String>()

    @After fun release() {
        player?.release()
        others.forEach { it.release() }
        scope.cancel()
    }

    @Test fun aTokenDriveRefusesIsDroppedOffTheMainThreadAndTheFilmPlaysWithANewOne() {
        // A token kept while the TV's clock was off: it looks good for most of an hour, Drive says no.
        val tokens = Tokens(kept = "stale") { "fresh" }
        val drive = FakeDrive(takes = setOf("fresh"))
        val p = drivePlayer(drive, tokens)

        until { p.playbackState == Player.STATE_READY || waits.isNotEmpty() || cantPlay.isNotEmpty() }

        assertEquals(Player.STATE_READY, p.playbackState)
        assertEquals(emptyList<Int?>(), waits)
        assertEquals(emptyList<String>(), cantPlay)
        // Dropped once, never on the main thread (GoogleAuth's lock can be held by a renewal).
        assertEquals(1, tokens.dropped.size)
        assertNotSame(Looper.getMainLooper().thread, tokens.dropped.single())
        // Asked with the refused token (Media3 asks again a few times by itself), then only with the new one.
        assertEquals("Bearer stale", drive.asked.first())
        assertEquals(listOf("Bearer fresh"), drive.asked.dropWhile { it == "Bearer stale" }.distinct())
    }

    @Test fun aSignInGoogleEndedFailsItsOneTryAndThePlayerGoesOnToWait() {
        // Renewing finds Google ended the sign-in: GoogleAuth has no token to give ("Bearer ").
        val tokens = Tokens(kept = "stale") { "" }
        val drive = FakeDrive(takes = setOf("fresh"))
        val p = drivePlayer(drive, tokens)

        until { waits.isNotEmpty() || cantPlay.isNotEmpty() }

        // One try with a new token, then the wait (where PlayerActivity checks the sign-in, finds it
        // gone, stops trying and says "Sign in to Google Drive again").
        assertEquals(listOf<Int?>(401), waits)
        assertEquals(emptyList<String>(), cantPlay)
        assertEquals(1, tokens.dropped.size)
        assertFalse(drive.asked.dropWhile { it == "Bearer stale" }.contains("Bearer stale"))
        assertTrue(drive.asked.contains("Bearer "))
        // Nothing more by itself: further tries are the wait's, not this one's.
        val asked = drive.asked.size
        idle(Duration.ofSeconds(6))
        assertEquals(asked, drive.asked.size)
        assertEquals(Player.STATE_IDLE, p.playbackState)
        assertEquals(1, tokens.dropped.size)
    }

    @Test fun otherTroubleIsTriedAgainAtOnceWithTheSameToken() {
        // Google busy for a moment (503): the token is fine, so it's kept.
        val tokens = Tokens(kept = "good") { "renewed" }
        val drive = FakeDrive(takes = setOf("good")).apply { busy = true }
        val p = drivePlayer(drive, tokens, beforeTry = { drive.busy = false })

        until { p.playbackState == Player.STATE_READY || waits.isNotEmpty() || cantPlay.isNotEmpty() }

        assertEquals(Player.STATE_READY, p.playbackState)
        assertEquals(emptyList<Int?>(), waits)
        assertEquals(0, tokens.dropped.size)
        assertEquals(listOf("Bearer good"), drive.asked.distinct())
    }

    @Test fun onlyADriveFilmIsTriedAgainAtOnceAndOnlyARefusedTokenIsRenewed() {
        assertEquals(RetryAtOnce.Way.NEW_TOKEN, RetryAtOnce.way(http = 401, drive = true, tried = false))
        // Busy, refused for another reason (a quota), failing, or no answer at all: as it was.
        for (http in listOf(403, 429, 500, 503, null)) assertEquals(RetryAtOnce.Way.AS_IT_WAS, RetryAtOnce.way(http, drive = true, tried = false))
        // Had its try: the player waits.
        for (http in listOf(401, 403, 503, null)) assertNull(RetryAtOnce.way(http, drive = true, tried = true))
        // A link from the phone that fails won't change its mind a moment later.
        for (http in listOf(401, 503, null)) assertNull(RetryAtOnce.way(http, drive = false, tried = false))
    }

    @Test fun eachFilmHasOneTryUntilItPlays() {
        val p = Counting(base())
        val retry = RetryAtOnce(scope, current = { p }) {}
        assertTrue(retry.tryAgain(p, "drive:a", 503, drive = true))
        assertEquals(1, p.prepares)
        p.state = Player.STATE_IDLE // that try failed too
        assertFalse(retry.tryAgain(p, "drive:a", 503, drive = true))
        // Next pressed: the next film has a try of its own.
        assertTrue(retry.tryAgain(p, "drive:b", 503, drive = true))
        // It played a while: trouble later on gets its own try.
        retry.played()
        p.state = Player.STATE_IDLE
        assertTrue(retry.tryAgain(p, "drive:b", null, drive = true))
        assertFalse(retry.tryAgain(p, "https://example.com/clip.mp4", 503, drive = false))
        assertEquals(3, p.prepares)
    }

    @Test fun aPlayerLetGoWhileItsTokenIsDroppedIsLeftAlone() {
        val p = Counting(base())
        var current: Player? = p
        val gate = CountDownLatch(1)
        val retry = RetryAtOnce(scope, current = { current }) { gate.await(5, TimeUnit.SECONDS) }
        assertTrue(retry.tryAgain(p, "drive:a", 401, drive = true))
        // Back pressed meanwhile: the player stopped and was let go.
        current = null
        gate.countDown()
        until { job.children.none() }
        assertEquals(0, p.prepares)
    }

    @Test fun aFilmStartedWhileItsTokenIsDroppedIsNotStartedTwice() {
        val p = Counting(base())
        val gate = CountDownLatch(1)
        val retry = RetryAtOnce(scope, current = { p }) { gate.await(5, TimeUnit.SECONDS) }
        assertTrue(retry.tryAgain(p, "drive:a", 401, drive = true))
        // Play pressed meanwhile: it starts at once (with whatever token there is by then).
        p.prepare()
        gate.countDown()
        until { job.children.none() }
        assertEquals(1, p.prepares)
    }

    /**
     * A player reading Drive through [drive] with [tokens], built as PlayerActivity builds its Drive
     * sources, and doing what PlayerActivity does when a film fails: trouble with the connection or
     * Google gets the one try at once ([RetryAtOnce]), or the player waits ([waits]); anything else
     * can't play. [beforeTry]: what changes in the world just as it fails (a hiccup passing).
     */
    private fun drivePlayer(drive: FakeDrive, tokens: Tokens, beforeTry: () -> Unit = {}): ExoPlayer {
        val p = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(driveAuthorized(drive.sources, tokens::token))).build()
        player = p
        val retry = RetryAtOnce(scope, current = { player }, forgetAccess = tokens::forget)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val key = p.currentMediaItem?.mediaId
                val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val isDrive = key?.startsWith("drive:") == true
                if (PlaybackErrors.stallOf(http, error.errorCode, isDrive) == null) { cantPlay += error.errorCodeName; return }
                beforeTry()
                if (retry.tryAgain(p, key, http, isDrive)) return
                waits += http
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) retry.played()
            }
        })
        p.setMediaItem(MediaItem.Builder().setMediaId(film.key).setUri(film.uri).build())
        p.prepare()
        p.play()
        return p
    }

    /** A player never started, under one that only counts how often it's started, in the state the test sets. */
    private fun base(): ExoPlayer = ExoPlayer.Builder(context).build().also { others += it }

    private class Counting(base: Player) : ForwardingPlayer(base) {
        var prepares = 0
        var state = Player.STATE_IDLE

        override fun prepare() {
            prepares++
            state = Player.STATE_BUFFERING
        }

        override fun getPlaybackState(): Int = state
    }

    /**
     * The TV's Drive token as GoogleAuth hands it out: the one [kept] until it's dropped ([forget],
     * noting on which thread), then whatever renewing gives ([renewed]; "" when Google ended the
     * sign-in, which leaves nothing kept).
     */
    private class Tokens(private var kept: String?, private val renewed: () -> String) {
        val dropped = CopyOnWriteArrayList<Thread>()

        @Synchronized fun token(): String = kept ?: renewed().also { kept = it.ifEmpty { null } }

        @Synchronized fun forget() {
            dropped += Thread.currentThread()
            kept = null
        }
    }

    /**
     * Google Drive as the player meets it: the film to a request with a token it [takes], 401 to any
     * other (as Drive answers a token it doesn't take), 503 to everything while [busy]. Notes each
     * request's Authorization header in [asked].
     */
    private inner class FakeDrive(private val takes: Set<String>) {
        val asked = CopyOnWriteArrayList<String>()
        @Volatile var busy = false
        val sources = DataSource.Factory { Source() }

        private inner class Source : DataSource {
            private var spec: DataSpec? = null
            private var position = 0L
            private var end = 0L

            override fun open(dataSpec: DataSpec): Long {
                val auth = dataSpec.httpRequestHeaders["Authorization"].orEmpty()
                asked += auth
                fun answer(code: Int): Nothing = throw HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), dataSpec, ByteArray(0))
                if (busy) answer(503)
                if (auth.removePrefix("Bearer ") !in takes) answer(401)
                spec = dataSpec
                position = dataSpec.position
                end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) WAV.size.toLong() else minOf(WAV.size.toLong(), dataSpec.position + dataSpec.length)
                return end - position
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= end) return C.RESULT_END_OF_INPUT
                val n = minOf(length.toLong(), end - position).toInt()
                System.arraycopy(WAV, position.toInt(), buffer, offset, n)
                position += n
                return n
            }

            override fun getUri(): Uri? = spec?.uri

            override fun close() {
                spec = null
            }

            override fun addTransferListener(transferListener: TransferListener) = Unit
        }
    }

    /**
     * Runs the main thread (and the clock the player's own threads keep time by) until [done]: about a
     * second, but a JVM's first player on a busy machine can take far longer.
     */
    private fun until(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        if (!done()) fail("timed out: player ${player?.playbackState} ${player?.playerError?.errorCodeName}, waits $waits, tries under way ${job.children.count()}")
    }

    /** Lets [time] go by on the clock the player keeps time by. */
    private fun idle(time: Duration) {
        repeat((time.toMillis() / 20).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
        }
    }

    private companion object {
        /** A second of silence as a WAV file: something Media3 opens with no decoder at all (as SidecarPlaybackTest's). */
        val WAV: ByteArray = run {
            val rate = 8_000
            val data = ByteArray(rate * 2)
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data.size)
            }.array()
            header + data
        }
    }
}
