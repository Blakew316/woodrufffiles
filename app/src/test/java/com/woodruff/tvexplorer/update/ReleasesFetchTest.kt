package com.woodruff.tvexplorer.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reading GitHub's list of releases again ([Updater.fetchReleasesFrom]): asked with the tag the last
 * full list came with, an unchanged list comes back as "not modified", with no page to send, and the
 * list already read is used. A server of the test's own, on this machine, stands in for GitHub.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleasesFetchTest {
    private val page = """[{"tag_name":"v2.0.170","name":"2.0.170","published_at":"2026-11-01T10:00:00Z","draft":false,"prerelease":false,""" +
        """"assets":[{"name":"WoodruffFamily.apk","size":4,"browser_download_url":"https://example.invalid/apk","state":"uploaded"}]}]"""
    /** The tag each request came with (null: none). */
    private val asked = CopyOnWriteArrayList<String>()
    @Volatile private var tag: String? = "W/\"abc\""
    @Volatile private var sent = 0

    private val github = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val with = session.headers["if-none-match"]
            asked += with ?: "none"
            if (with != null && with == tag) return newFixedLengthResponse(Response.Status.NOT_MODIFIED, "application/json", "")
            sent++
            return newFixedLengthResponse(Response.Status.OK, "application/json", page).apply { tag?.let { addHeader("ETag", it) } }
        }
    }
    private val address get() = "http://127.0.0.1:${github.listeningPort}/releases"

    @Before fun open() {
        Updater.resetForTest()
        github.start(5_000, true)
    }

    @After fun close() {
        github.stop()
        Updater.resetForTest()
    }

    @Test fun anUnchangedListIsntSentAgain() {
        val first = Updater.fetchReleasesFrom(address)
        assertEquals(listOf(170), first.map { it.build })
        val again = Updater.fetchReleasesFrom(address)
        assertEquals(first, again)
        assertEquals(listOf("none", "W/\"abc\""), asked.toList())
        assertEquals(1, sent)
    }

    @Test fun aChangedListIsReadInFull() {
        Updater.fetchReleasesFrom(address)
        // A release published (or a file finished uploading): the page and its tag change.
        tag = "W/\"def\""
        assertEquals(listOf(170), Updater.fetchReleasesFrom(address).map { it.build })
        assertEquals(2, sent)
    }

    @Test fun withoutATagEveryCheckReadsTheList() {
        tag = null
        Updater.fetchReleasesFrom(address)
        Updater.fetchReleasesFrom(address)
        assertEquals(listOf("none", "none"), asked.toList())
        assertEquals(2, sent)
    }
}
