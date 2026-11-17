package com.woodruff.tvexplorer.cloud

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Size
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every image request for a Drive picture is kept by the file version it shows ([DriveArt.Keys]), and its tag never reaches Google. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DriveArtKeysTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The rest of the image loader, as far as the interceptor can tell: what it was handed. */
    private class Chain(override val request: ImageRequest) : Interceptor.Chain {
        var handed: ImageRequest? = null
        override val size: Size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest): Interceptor.Chain = Chain(request)
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(request: ImageRequest): ImageResult {
            handed = request
            return ErrorResult(null, request, IllegalStateException("not fetched in a test"))
        }
    }

    private fun handed(request: ImageRequest): ImageRequest = Chain(request).also { runBlocking { DriveArt.Keys.intercept(it) } }.handed!!

    @Test fun aDrivePictureIsKeptUnderItsFileVersionWhateverItsLink() {
        val monday = DriveApi.poster(DriveArt.tag("https://lh3.googleusercontent.com/drive-storage/AAA=s220", "poster1", 42))
        val tuesday = DriveApi.poster(DriveArt.tag("https://lh3.googleusercontent.com/drive-storage/BBB=s220", "poster1", 42))
        val a = handed(ImageRequest.Builder(context).data(monday).size(320, 480).build())
        val b = handed(ImageRequest.Builder(context).data(tuesday).size(320, 480).build())
        assertEquals("drive:poster1:42=s480", a.diskCacheKey)
        assertEquals(MemoryCache.Key("drive:poster1:42=s480"), a.memoryCacheKey)
        assertEquals(a.diskCacheKey, b.diskCacheKey)
        assertEquals(a.memoryCacheKey, b.memoryCacheKey)
        // What's fetched is the link itself.
        assertEquals(monday, a.data)
    }

    @Test fun otherPicturesKeepTheirOwnKeys() {
        // TMDB's (their addresses never change), and a request that names its own keys.
        val tmdb = handed(ImageRequest.Builder(context).data("https://image.tmdb.org/t/p/w342/abc.jpg").build())
        assertNull(tmdb.diskCacheKey)
        assertNull(tmdb.memoryCacheKey)
        val own = handed(
            ImageRequest.Builder(context).data(DriveArt.tag("https://lh3.googleusercontent.com/x=s220", "f", 1))
                .memoryCacheKey("mine").diskCacheKey("mine").build(),
        )
        assertEquals("mine", own.diskCacheKey)
        assertEquals(MemoryCache.Key("mine"), own.memoryCacheKey)
    }

    @Test fun theTagIsNeverSentWithTheRequest() {
        val asked = ArrayList<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                synchronized(asked) { asked += session.uri + (session.queryParameterString?.let { "?$it" } ?: "") }
                return newFixedLengthResponse(Response.Status.OK, "image/png", "png")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val url = DriveArt.tag("http://127.0.0.1:${server.listeningPort}/drive-storage/AAA=s720", "file1", 99)
            // The image loader's own client, as Coil asks with it.
            com.woodruff.tvexplorer.net.Http.client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf("/drive-storage/AAA=s720"), synchronized(asked) { asked.toList() })
        } finally {
            server.stop()
        }
    }
}
