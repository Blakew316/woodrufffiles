package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.episodePlayTitle
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A title page asked for: [file] in its library [place] (Movies or TV Shows), opened at episode [focusEpisodeId] when set. */
data class TitleRequest(val place: DrivePlace, val file: DriveFile, val focusEpisodeId: String? = null)

/**
 * Title pages for the whole app: whatever shows a title (Home's Recently added, Streaming's rows,
 * grid and banner) asks here, and [TitleHost] shows the page over it, so a title's page is the same
 * wherever it's opened. Back closes it, and the focus is back on what opened it.
 */
object TitlePages {
    private val _request = MutableStateFlow<TitleRequest?>(null)
    /** The page on show; null when none is. */
    val request: StateFlow<TitleRequest?> = _request.asStateFlow()

    private val _closed = MutableStateFlow(0)
    /** Ticks each time a page closes: what's under it reads its ✓s again (some may have been marked on the page). */
    val closed: StateFlow<Int> = _closed.asStateFlow()

    /**
     * Opens [file]'s page (in its library [place]); [focusEpisodeId] opens a show at that episode (a
     * poster saying it has new episodes opens at the first of them). A title picked on a page replaces it.
     */
    fun open(place: DrivePlace, file: DriveFile, focusEpisodeId: String? = null) {
        _request.value = TitleRequest(place, file, focusEpisodeId)
    }

    /** Closes the page on show, if any. */
    fun close() {
        if (_request.value == null) return
        _request.value = null
        _closed.update { it + 1 }
    }
}

/**
 * Shows the page [TitlePages] has been asked for, over whatever screen asked: composed only while one
 * is asked for. What its buttons play starts here, the same from every screen: an episode with the rest
 * of its season after it and its real name for the player ("Backyard Science S02E05 The Volcano", see
 * [episodePlayTitle]); "All folders" and "Open" open the folder in Streaming.
 */
@Composable
fun TitleHost(vm: MainViewModel, drive: DriveViewModel) {
    val request by TitlePages.request.collectAsStateWithLifecycle()
    val r = request ?: return
    val context = LocalContext.current
    // Only the token: the full Drive state changes with every download tick and listing.
    val token by remember(drive) { drive.state.map { it.token }.distinctUntilChanged() }.collectAsStateWithLifecycle(drive.state.value.token)
    val news by drive.newEpisodes.collectAsStateWithLifecycle()
    val f = r.file
    val isShow = r.place == DrivePlace.TvShows
    // The library the title is in, for "More like this" (as listed when the page opened).
    val all = remember(r.place, f.id) { drive.libraryFiles(r.place).orEmpty() }
    // Closing (Back, or a button starting something): what's under it reads its ✓s and new episodes again.
    val close = { TitlePages.close(); drive.recheckNew() }
    TitleDetail(
        f, posterArt(f), token, all = all, isShow = isShow,
        artHeaders = { url -> if (isGoogle(url) && token != null) mapOf("Authorization" to "Bearer $token") else emptyMap() },
        // The page has fetched the other TVs' latest (and waited for it) before calling these.
        onPlay = { fromStart -> close(); playTitle(context, f, fromStart) },
        onPlayEpisode = { ep, queue, fromStart -> close(); playEpisodes(context, f, ep, queue, fromStart) },
        onPlayKey = { key, title -> close(); context.startActivity(PlayerActivity.intent(context, listOf(key), listOf(title), key)) },
        openLabel = if (isShow) "All folders" else "Open",
        onOpen = { close(); drive.openIn(r.place, f); vm.show(Screen.DRIVE) },
        onChoose = { TitlePages.open(r.place, it) },
        onDismiss = close,
        focusEpisodeId = r.focusEpisodeId,
        fresh = news[f.id]?.episodes.orEmpty(),
    )
}

/** A film that's a file of its own, on its own (no rolling on into the next title): where it was, or [fromStart]. */
private fun playTitle(context: Context, f: DriveFile, fromStart: Boolean) {
    val key = MediaRef.Drive(f.id).key
    context.startActivity(PlayerActivity.intent(context, listOf(key), listOf(f.name), key, positionMs = if (fromStart) 0L else null))
}

/**
 * [episode] of [show] (or a film kept in its folder), with [queue] (its season, in order) after it,
 * where it was or [fromStart]. Each is remembered as the show's, so Continue watching shows its poster
 * whatever its file is called, and goes to the player by its real name when TMDB knows it.
 */
private fun playEpisodes(context: Context, show: DriveFile, episode: DriveFile, queue: List<DriveFile>, fromStart: Boolean) {
    EpisodeShows.remember(context, queue.associate { MediaRef.Drive(it.id).key to show.id })
    val name = Library.info(show).title
    context.startActivity(
        PlayerActivity.intent(
            context, queue.map { MediaRef.Drive(it.id).key }, queue.map { episodePlayTitle(name, it) },
            MediaRef.Drive(episode.id).key, positionMs = if (fromStart) 0L else null,
        ),
    )
}
