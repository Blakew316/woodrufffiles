package com.woodruff.tvexplorer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.woodruff.tvexplorer.cloud.GoogleAuth

/** Configures Coil so grid view can show video frames as thumbnails, not just images. */
class TvExplorerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.woodruff.tvexplorer.util.CrashLog.install(this)
        // Decrypt the saved keys and tokens in the background before any screen asks for them.
        Thread({ runCatching { com.woodruff.tvexplorer.settings.SecretStore.get(this).warmAll() } }, "secrets-warmup")
            .apply { priority = Thread.NORM_PRIORITY + 1 }.start()
        GoogleAuth.migrate(this)
        com.woodruff.tvexplorer.nest.Nest.init(this)
        com.woodruff.tvexplorer.photos.GooglePhotos.init(this)
        com.woodruff.tvexplorer.calendar.TodayCalendar.init(this)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // The app's one HTTP client, so pictures and the player share its warm connections. Made
        // when the first picture is fetched, off the main thread.
        .okHttpClient { com.woodruff.tvexplorer.net.Http.client }
        .components { add(VideoFrameDecoder.Factory()) }
        // At most two decodes at once: a folder of videos otherwise opens a decoder per tile and
        // the TV's few hardware decoders (and the CPU) choke while scrolling.
        .decoderDispatcher(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2))
        // Posters and thumbnails are opaque: half the memory per image, so more of them stay cached
        // and scrolling back is instant. Keep them on disk even when Google's servers say "no-cache"
        // (the same poster URL never changes), so revisits don't refetch every tile.
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .crossfade(150)
        .build()
}
