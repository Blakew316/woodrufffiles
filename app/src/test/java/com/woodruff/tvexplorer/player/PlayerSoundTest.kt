package com.woodruff.tvexplorer.player

import android.os.Looper
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The player saying what it can't play: the tracks Media3 reports become the Audio menu's (the
 * ones this TV can't play included), a menu row that can't be chosen keeps the menu up, and a long
 * message wraps into even lines.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerSoundTest {
    @Test fun everySoundTrackIsListedInTheFilesOrderWithWhatThisTvCanPlay() {
        val dts = Format.Builder().setId("1").setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(8).setLanguage("eng")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val commentary = Format.Builder().setId("2").setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(2).setLanguage("en")
            .setLabel("Commentary").build()
        val maybe = Format.Builder().setId("3").setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setLanguage("spa").build()
        val video = Format.Builder().setId("0").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).build()
        val tracks = Tracks(listOf(
            Tracks.Group(TrackGroup("v", video), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            Tracks.Group(TrackGroup("a1", dts), false, intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE), booleanArrayOf(false)),
            Tracks.Group(TrackGroup("a2", commentary), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            // A decoder that might manage it (not fully supported) and isn't playing: not offered.
            Tracks.Group(TrackGroup("a3", maybe), false, intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES), booleanArrayOf(false)),
        ))

        val sound = SoundTracks.of(tracks)

        assertEquals(
            listOf(
                SoundTrack("en", 8, MimeTypes.AUDIO_DTS_HD, null, 0, isDefault = true, playable = false, selected = false),
                SoundTrack("en", 2, MimeTypes.AUDIO_AC3, "Commentary", 0, isDefault = false, playable = true, selected = true),
                SoundTrack("es", 6, MimeTypes.AUDIO_E_AC3, null, 0, isDefault = false, playable = false, selected = false),
            ),
            sound.map { it.track },
        )
        // Each keeps where Media3 has it, so the menu can choose it.
        assertEquals(listOf("a1", "a2", "a3"), sound.map { it.group.mediaTrackGroup.id })
        assertEquals(listOf(0, 0, 0), sound.map { it.index })
        assertEquals(
            SoundVerdict.MainUnplayable(playing = 1, codec = "DTS-HD MA"),
            SoundTracks.verdict(sound.map { it.track }, preferredLanguage = null),
        )
    }

    @Test fun aTrackThePlayerPickedItselfCountsAsPlayable() {
        // Media3 may still pick a track a decoder only might manage when nothing else can play.
        val eac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setLanguage("en").build()
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup(eac3), false, intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES), booleanArrayOf(true))))
        val only = SoundTracks.of(tracks).single().track
        assertTrue(only.playable)
        assertTrue(only.selected)
    }

    @Test fun aVideoWithNoSoundHasNoSoundTracks() {
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup(video), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        assertTrue(SoundTracks.of(tracks).isEmpty())
        assertFalse(tracks.containsType(C.TRACK_TYPE_AUDIO))
    }

    @Test fun okOnARowThatCantBeChosenKeepsTheMenuUp() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val menu = TvMenu(activity, root)
        val did = mutableListOf<String>()
        menu.show("Audio", listOf(
            TvMenu.Row("English · 5.1", checked = true) { did += "english" },
            TvMenu.Row("English · 7.1 · Dolby TrueHD", detail = SoundTracks.CANT_PLAY, enabled = false) { did += "why" },
        ))
        settle()
        assertTrue(menu.shown)

        // Down onto the row that can't play, OK: it says why, and the menu stays for another choice.
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_CENTER))
        settle()
        assertEquals(listOf("why"), did)
        assertTrue(menu.shown)

        // Up and OK on a track that plays: chosen, and the menu goes.
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_CENTER))
        settle()
        assertEquals(listOf("why", "english"), did)
        assertFalse(menu.shown)
    }

    @Test fun aRowWithADetailShowsItAtTheRightEnd() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val menu = TvMenu(activity, root)
        menu.show("Audio", listOf(
            TvMenu.Row("English · 5.1", checked = true) {},
            TvMenu.Row("English · 7.1 · Dolby TrueHD", detail = SoundTracks.CANT_PLAY, enabled = false) {},
        ))
        settle()
        val texts = mutableListOf<android.widget.TextView>()
        fun collect(v: android.view.View) {
            if (v is android.widget.TextView) texts += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(menu.panel)
        val detail = texts.single { it.text == SoundTracks.CANT_PLAY }
        val label = texts.single { it.text == "English · 7.1 · Dolby TrueHD" }
        // Both dimmed (50 % white: 5.2:1 on the menu, REVIEW.md §2.5) while the platter is on the first row.
        assertEquals(0x80FFFFFF.toInt(), detail.currentTextColor)
        assertEquals(0x80FFFFFF.toInt(), label.currentTextColor)
        // The detail ends where the row's words end, after the label, on the label's baseline.
        val words = detail.parent as android.view.ViewGroup
        val row = words.parent as android.view.ViewGroup
        assertEquals(row.width - row.paddingRight, words.left + detail.right)
        assertTrue(label.right <= detail.left)
        assertEquals(label.top + label.baseline, detail.top + detail.baseline)
        // On the white platter: ink, at 65 % (5.3:1).
        menu.handleKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(0xA61C1C1E.toInt(), detail.currentTextColor)
        assertEquals(0xA61C1C1E.toInt(), label.currentTextColor)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun aLongMessageWrapsIntoEvenLinesInACardAsWideAsTheLongest() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val message = PlayerStyle.message(activity)
        root.addView(message, PlayerStyle.messageParams(activity))
        message.text = SoundTracks.startMessage(SoundVerdict.MainUnplayable(1, "DTS-HD MA"), listOf("", "English · Stereo"))
        settle()

        val lines = message.layout
        assertEquals(2, lines.lineCount)
        val widths = (0 until lines.lineCount).map { lines.getLineWidth(it) }
        // Even lines, not a full one and a stray word ("MA)." alone).
        assertTrue("lines $widths", widths.min() / widths.max() > 0.75f)
        // The card is only as wide as its longest line.
        val tight = kotlin.math.ceil(widths.max()).toInt() + message.compoundPaddingLeft + message.compoundPaddingRight
        assertEquals(tight, message.width)
        assertTrue(message.width < activity.resources.displayMetrics.widthPixels * 3 / 5)
        // The format's name stays whole, on the last line.
        val last = message.text.substring(lines.getLineStart(1))
        assertTrue(last, last.contains("(DTS‑HD MA)."))

        // A short message stays on one line, as wide as its words.
        message.text = "Sleeping in 30 minutes"
        settle()
        assertEquals(1, message.layout.lineCount)
        assertTrue(message.width < activity.resources.displayMetrics.widthPixels / 3)
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }
}
