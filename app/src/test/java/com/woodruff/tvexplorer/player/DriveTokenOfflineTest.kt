package com.woodruff.tvexplorer.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration

/**
 * A Drive film started while there's no Google token to be had (the TV's Keystore still waking after
 * a power cut, or Google out of reach): it reconnects, as for a dropped connection, rather than asking
 * Drive without a token and saying "Google Drive turned this down". Signed out, it's asked as before.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DriveTokenOfflineTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var player: ExoPlayer? = null
    private val waking = GoogleAuth.TokenResult.Offline(IOException("The Google keys can't be read just now."))

    @After fun release() {
        player?.release()
    }

    @Test fun aTokenIsCarriedAsItIs() {
        assertEquals("ya29.token", driveToken(GoogleAuth.TokenResult.Ok("ya29.token")))
    }

    @Test fun signedOutOrKeysRefusedItIsAskedAsBefore() {
        // Drive's answer brings up how to sign in again (or that it's turned down).
        assertEquals("", driveToken(GoogleAuth.TokenResult.SignedOut))
        assertEquals("", driveToken(GoogleAuth.TokenResult.Rejected("Keys not accepted")))
    }

    @Test fun noTokenJustNowIsAConnectionThatDroppedNotDriveTurningItDown() {
        val e = runCatching { driveToken(waking) }.exceptionOrNull()
        assertTrue("fails the request: $e", e is DataSourceException)
        val code = (e as DataSourceException).reason
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, code)
        assertEquals(Stall.NETWORK, PlaybackErrors.stallOf(http = null, errorCode = code, drive = true))
        assertEquals(Buffering.RECONNECTING, Buffering.look(null, Stall.NETWORK, stalledMs = 0, reduced = false)?.caption)
    }

    @Test fun aDriveFilmStartedWithoutATokenReconnectsAndAsksDriveNothing() {
        var asked = 0
        // Stands in for OkHttp: counts what reaches Drive (nothing should), and never touches the network.
        val drive = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long {
                    asked++
                    throw HttpDataSource.InvalidResponseCodeException(401, "Unauthorized", null, emptyMap(), dataSpec, ByteArray(0))
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("not open")
                override fun getUri(): Uri? = null
                override fun close() = Unit
            }
        }
        // As the player builds its sources: Drive's own URLs get the token (here, none to be had).
        val authed = ResolvingDataSource.Factory(drive) { spec ->
            val uri = spec.uri
            if (carriesDriveToken(uri.scheme, uri.host, uri.path)) spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${driveToken(waking)}")) else spec
        }
        val sources = SidecarLoading.dataSourceFactory(DefaultDataSource.Factory(context, FilmStarts.dataSourceFactory(context, authed)))
        val p = ExoPlayer.Builder(context)
            // Media3 gives up at the first failure here, instead of after its usual tries.
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)))
            .build()
        player = p
        var failed: PlaybackException? = null
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = error }
        })
        p.setMediaItem(MediaItem.fromUri("https://www.googleapis.com/drive/v3/files/film?alt=media&supportsAllDrives=true"))
        p.prepare()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (failed == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertNotNull("the film fails its start", failed)
        val error = failed!!
        assertEquals(0, asked)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, error.errorCode)
        // No answer from Drive at all, so no "turned this down": the ring says "Reconnecting…".
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        assertNull(http)
        assertEquals(Stall.NETWORK, PlaybackErrors.stallOf(http, error.errorCode, drive = true))
    }
}
