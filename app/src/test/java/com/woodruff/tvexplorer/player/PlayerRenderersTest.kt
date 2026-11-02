package com.woodruff.tvexplorer.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every film has sound: FFmpeg's decoder plays what the TV's own can't, and only that. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerRenderersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun ffmpegDecodesOnlyWhatTheTvsOwnDecodersCant() {
        val renderers = playerRenderers(context).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            TextOutput { },
            MetadataOutput { },
        ).map { it.name }
        val platform = renderers.indexOf("MediaCodecAudioRenderer")
        val ffmpeg = renderers.indexOf("FfmpegAudioRenderer")
        assertTrue("the TV's own sound decoder is there: $renderers", platform >= 0)
        // Found by name, as Media3 looks for it: the library is in the app.
        assertTrue("FFmpeg's sound decoder is there: $renderers", ffmpeg >= 0)
        // Media3 gives a track to the first renderer that fully handles it: the TV's own decoders and
        // passthrough to a soundbar win whenever they can play it.
        assertTrue("FFmpeg comes after the TV's own decoder: $renderers", ffmpeg > platform)
        // The video decoders are still the TV's own, first.
        assertEquals("MediaCodecVideoRenderer", renderers.first())
    }
}
