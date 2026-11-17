package com.woodruff.tvexplorer.server

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Mute All's key (/api/key "muteon"): the TV's sound is muted and stays muted, never flipped as "mute" does (which
 * unmuted a TV that was muted already). A key this build doesn't know changes nothing, as builds before "muteon" do
 * with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompanionMuteTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)
    private val audio = ctx.getSystemService(AudioManager::class.java)

    @Test fun muteOnMutesAndKeepsItMuted() {
        assertEquals(NanoHTTPD.Response.Status.OK, key("muteon").status)
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        // Mute All again, or a TV muted already: still muted.
        assertEquals(NanoHTTPD.Response.Status.OK, key("muteon").status)
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test fun aKeyItDoesntKnowIsTurnedDownAndChangesNothing() {
        val before = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val r = key("muteoff")
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
        assertEquals(before, audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    private fun key(k: String) = routes.answerFor(phoneRequest("/api/key", NanoHTTPD.Method.POST, body = JSONObject().put("key", k)))
}
