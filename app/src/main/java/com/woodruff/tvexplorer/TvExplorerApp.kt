package com.woodruff.tvexplorer

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.woodruff.tvexplorer.cloud.GoogleAuth

/** Configures Coil so grid view can show video frames as thumbnails, not just images. */
class TvExplorerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.woodruff.tvexplorer.util.CrashLog.install(this)
        // Read in the background, before any screen asks (a screen's first read otherwise waits on
        // the disk or the Keystore on the main thread, in the middle of drawing).
        Thread({
            // Asking for a settings file starts reading it in, on a thread of its own.
            SETTINGS_FILES.forEach { runCatching { getSharedPreferences(it, Context.MODE_PRIVATE) } }
            // Which show each played episode belongs to: Home's Continue watching reads it at once.
            runCatching { com.woodruff.tvexplorer.cloud.EpisodeShows.load(this) }
            // The saved keys and tokens, decrypted.
            runCatching { com.woodruff.tvexplorer.settings.SecretStore.get(this).warmAll() }
            // The posters' ✓s: the first question about any title reads them all in.
            runCatching { com.woodruff.tvexplorer.player.Watched.isWatched(this, "") }
        }, "startup-warmup").apply { priority = Thread.NORM_PRIORITY + 1 }.start()
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
        // Pictures of files (USB and folders on the TV, video frames, the background photo) are
        // remembered by path alone. With the file's date in the key, every one asked for while
        // scrolling first checked the disk, on the main thread. (A file changed in place keeps its
        // old picture until the memory cache lets it go.)
        .addLastModifiedToFileCacheKey(false)
        .crossfade(150)
        .build()

    private companion object {
        /** Every settings file the app keeps (the names it passes to getSharedPreferences). */
        val SETTINGS_FILES = listOf(
            "settings", "appearance", "notices", "secrets", "recent", "episode_shows", "watched", "player_shows",
            "player", "updates", "weather", "chime", "board", "doorbell", "family_sync", "mail_poll", "startup",
            "google_oauth",
        )
    }
}
