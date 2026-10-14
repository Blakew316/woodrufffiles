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
        GoogleAuth.migrate(this)
        com.woodruff.tvexplorer.nest.Nest.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
