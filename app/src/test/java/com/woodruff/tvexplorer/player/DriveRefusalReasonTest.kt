package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/** Google's own word for why Drive turned a film's request down, read from its answer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DriveRefusalReasonTest {
    private val spec = DataSpec(Uri.parse("https://www.googleapis.com/drive/v3/files/film?alt=media"))

    private fun refused(body: String) = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, body.toByteArray())

    @Test fun theReasonIsReadFromGooglesAnswer() {
        val limit = refused("""{"error":{"errors":[{"domain":"usageLimits","reason":"downloadQuotaExceeded","message":"x"}],"code":403}}""")
        assertEquals("downloadQuotaExceeded", PlaybackErrors.reasonOf(limit))
        // Under the player's own error, as Media3 hands it on.
        assertEquals("downloadQuotaExceeded", PlaybackErrors.reasonOf(RuntimeException(limit)))
    }

    @Test fun noReasonWithoutGooglesKindOfAnswer() {
        assertNull(PlaybackErrors.reasonOf(refused("")))
        assertNull(PlaybackErrors.reasonOf(refused("<html>Forbidden</html>")))
        assertNull(PlaybackErrors.reasonOf(IOException("no answer")))
        assertNull(PlaybackErrors.reasonOf(null))
    }
}
