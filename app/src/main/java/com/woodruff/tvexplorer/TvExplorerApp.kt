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
        GoogleAuth.migrate(this)
        com.woodruff.tvexplorer.nest.Nest.init(this)
        com.woodruff.tvexplorer.photos.GooglePhotos.init(this)
        com.woodruff.tvexplorer.calendar.TodayCalendar.init(this)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        // At most two decodes at once: a folder of videos otherwise opens a decoder per tile and
        // the TV's few hardware decoders (and the CPU) choke while scrolling.
        .decoderDispatcher(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2))
        .crossfade(true)
        .build()
}
