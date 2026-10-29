package com.woodruff.tvexplorer.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's one HTTP client: the player's Drive streams and the images share its connections, so
 * a request reuses a warm connection instead of a new handshake each time. Never give it an
 * interceptor that adds the Google token: TMDB and other hosts use it too.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
