package com.woodruff.tvexplorer.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The notice Woodruff shows after a crash says where it failed, and nothing secret. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CrashLogTest {
    private val c: Context = ApplicationProvider.getApplicationContext()

    /** A crash whose exception messages carry a token, a key, addresses and an email, as real ones can. */
    private val crash = "Version 2.0.171 · Nov 1, 9:41 PM · thread main\n" +
        "java.lang.IllegalStateException: token=xyz789secret refused by 10.0.0.7\n" +
        "\tat androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:2861)\n" +
        "\tat com.woodruff.tvexplorer.player.PlayerActivity.onStop(PlayerActivity.kt:410)\n" +
        "\tat android.app.Activity.performStop(Activity.java:8501)\n" +
        "\tat android.app.ActivityThread.callActivityOnStop(ActivityThread.java:5208)\n" +
        "\tat android.app.ActivityThread.performStopActivityInner(ActivityThread.java:5188)\n" +
        "\tat android.app.ActivityThread.handleStopActivity(ActivityThread.java:5253)\n" +
        "\tat android.os.Handler.dispatchMessage(Handler.java:106)\n" +
        "\tat com.woodruff.tvexplorer.tmdb.Tmdb.search(Tmdb.kt:120)\n" +
        "Caused by: java.io.FileNotFoundException: https://api.themoviedb.org/3/search/movie?api_key=abc123secret&query=harbor\n" +
        "Caused by: java.net.ConnectException: failed to connect to /192.168.1.21 (port 8080) from /192.168.1.20 (port 41234)\n" +
        "Caused by: java.io.IOException: Unauthorized for someone@example.com, Authorization: Bearer ya29.a0AfH6SMexample\n"
    private val secrets = listOf("xyz789secret", "10.0.0.7", "abc123secret", "192.168.1.21", "192.168.1.20", "someone@example.com", "ya29.a0AfH6SMexample")

    @Test fun theNoticeOnTheTvNeverShowsKeysTokensAddressesOrEmails() {
        Notices.load(c)
        File(c.filesDir, "last_crash.txt").writeText(crash)
        CrashLog.report(c)
        val notice = Notices.items.value.first()
        assertEquals("Woodruff closed unexpectedly last time", notice.title)
        for (secret in secrets) assertFalse("$secret on screen", secret in notice.body)
        // Said once: the next start has nothing to report.
        val count = Notices.items.value.size
        CrashLog.report(c)
        assertEquals(count, Notices.items.value.size)
    }

    @Test fun theNoticeSaysWhereItFailedAndWhereTheWholeReportIs() {
        val body = CrashLog.noticeBody(crash)
        val lines = body.lines()
        // The top of the trace, then Woodruff's own frames and the causes further down, each trimmed.
        assertEquals("Version 2.0.171 · Nov 1, 9:41 PM · thread main", lines[0])
        assertEquals("java.lang.IllegalStateException: token=… refused by …", lines[1])
        assertTrue("at com.woodruff.tvexplorer.player.PlayerActivity.onStop(PlayerActivity.kt:410)" in lines)
        assertTrue("at com.woodruff.tvexplorer.tmdb.Tmdb.search(Tmdb.kt:120)" in lines)
        assertTrue("Caused by: java.io.FileNotFoundException: https://api.themoviedb.org/3/search/movie?api_key=…&query=harbor" in lines)
        assertTrue("Caused by: java.net.ConnectException: failed to connect to /… (port 8080) from /… (port 41234)" in lines)
        assertFalse(lines.any { it.contains("Handler.dispatchMessage") })
        // The phone app's own row for it, as the phone names it.
        assertTrue(body.endsWith("\n\nThe full report is in the phone app: Settings → Last Crash on This TV."))
    }
}
