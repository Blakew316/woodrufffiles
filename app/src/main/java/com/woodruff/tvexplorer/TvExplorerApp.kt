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
        // After this version closed while starting, every start looks for a fix at once (EarlyCrashes).
        com.woodruff.tvexplorer.update.Updater.atStart(this)
        // Every Woodruff screen from its first one: key presses anywhere count as use, and what's up
        // can be put back after an update (OnScreen).
        com.woodruff.tvexplorer.update.OnScreen.install(this)
        // Memory back for the TV's other apps once Woodruff has been off screen a while.
        com.woodruff.tvexplorer.util.LeanMemory.install(this)
        // Read in the background, before any screen asks (a screen's first read otherwise waits on
        // the disk or the Keystore on the main thread, in the middle of drawing).
        Thread({
            // Asking for a settings file starts reading it in, on a thread of its own.
            SETTINGS_FILES.forEach { runCatching { getSharedPreferences(it, Context.MODE_PRIVATE) } }
            val secrets = runCatching { com.woodruff.tvexplorer.settings.SecretStore.get(this) }.getOrNull()
            // What the first screen reads on the main thread as it's drawn (whether Drive and the
            // cameras are signed in), decrypted first, so those reads find them ready.
            FIRST_SCREEN_SECRETS.forEach { name -> runCatching { secrets?.get(name) } }
            // Which show each played episode belongs to: Home's Continue watching reads it at once.
            runCatching { com.woodruff.tvexplorer.cloud.EpisodeShows.load(this) }
            // The rest of the saved keys and tokens, decrypted.
            runCatching { secrets?.warmAll() }
            // The posters' ✓s: the first question about any title reads them all in.
            runCatching { com.woodruff.tvexplorer.player.Watched.isWatched(this, "") }
        }, "startup-warmup").apply { priority = Thread.NORM_PRIORITY + 1 }.start()
        GoogleAuth.migrate(this)
        // The TV health report: sign-ins Google ends, updates waiting (TV health, Settings and phone).
        // Before the cameras, Photos and the calendar start: it listens for Google ending a sign-in, which the
        // first token they ask for can find out (and nobody hears it then if nothing listens yet).
        com.woodruff.tvexplorer.tvs.Health.init(this)
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
        // From Android 8 on, pictures decode as HARDWARE bitmaps (4 bytes a pixel, in the graphics
        // memory) and allowRgb565 changes nothing: it only applies to software bitmaps (Android 7, and
        // the few Android 8 TVs Coil keeps off hardware bitmaps). Posters, banners and backdrops stay
        // HARDWARE: as RGB_565 their gradients band on a big screen, and each would be uploaded to the
        // graphics chip as it's first drawn, in the middle of a row's glide.
        .allowRgb565(true)
        // Those bitmaps aren't on the Java heap the cache is sized from, so it can hold a quarter of it
        // (Coil's default is a fifth; low-RAM TVs keep its 15 %): a few more rows of posters stay
        // decoded and scrolling back finds them at once. Given back once Woodruff has been off screen
        // a while (LeanMemory).
        .memoryCache {
            val lowRam = getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true
            coil.memory.MemoryCache.Builder(this).maxSizePercent(if (lowRam) 0.15 else 0.25).build()
        }
        // Keep them on disk even when Google's servers say "no-cache" (the same poster URL never
        // changes), so revisits don't refetch every tile.
        .respectCacheHeaders(false)
        // Pictures of files (USB and folders on the TV, video frames, the background photo) are
        // remembered by path alone. With the file's date in the key, every one asked for while
        // scrolling first checked the disk, on the main thread. (A file changed in place keeps its
        // old picture until the memory cache lets it go.)
        .addLastModifiedToFileCacheKey(false)
        .crossfade(150)
        .build()
        .also { com.woodruff.tvexplorer.util.LeanMemory.images = it }

    internal companion object {
        /** Every settings file the app keeps (the names it passes to getSharedPreferences). */
        val SETTINGS_FILES = listOf(
            "settings", "appearance", "notices", "secrets", "secrets_meta", "recent", "episode_shows", "watched",
            "player_shows", "player", "updates", "weather", "chime", "board", "doorbell", "family_sync", "mail_poll",
            "startup", "google_oauth", "tvs", "health",
        )

        /**
         * The secrets read on the main thread as Woodruff's first screen is set up, decrypted first:
         * - Drive's sign-in step, read as the Drive library's ViewModel is made (every start) and as
         *   Streaming opens: the sign-in; when there's none, the keys and whether Google ended it (the
         *   panel then asks to sign in *again*).
         * - The cameras' step, read as the Command Center opens: the keys, the project and their sign-in.
         * Home's camera panel, calendar and TVs read theirs off the main thread. Settings' rows (when
         * Woodruff reopens on Settings after an update chosen there) come with all the rest, just after.
         */
        val FIRST_SCREEN_SECRETS = listOf(
            GoogleAuth.Grant.DRIVE.refreshKey, GoogleAuth.K_CLIENT_ID, GoogleAuth.K_CLIENT_SECRET,
            GoogleAuth.Grant.DRIVE.endedKey, GoogleAuth.K_NEST_PROJECT, GoogleAuth.Grant.NEST.refreshKey,
        )
    }
}
