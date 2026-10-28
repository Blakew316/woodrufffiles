package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.ShowPrefs
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A title's page, Apple TV style: its wide art across the screen, the name, year, genres, rating
 * and cast, the synopsis, and the buttons — Play / Resume / Start over for a film. A show gets
 * Resume (the episode you were in), Play from the first episode, then season tabs over a row of
 * episode cards with progress bars and ✓s. "More like this" lists titles from your own library
 * that share its genres. Back returns to the grid.
 *
 * [artHeaders] adds the Google token for Drive-hosted art (never sent to TMDB).
 */
@Composable
fun TitleDetail(
    f: DriveFile,
    art: String?,
    token: String?,
    /** The rest of the library, for "More like this". */
    all: List<DriveFile>,
    isShow: Boolean,
    artHeaders: (String) -> Map<String, String>,
    onPlay: (fromStart: Boolean) -> Unit,
    /** Play [episode] with [queue] (its season, in order) after it. */
    onPlayEpisode: (episode: DriveFile, queue: List<DriveFile>, fromStart: Boolean) -> Unit,
    /** Play a single media key (resuming a show from Continue watching). */
    onPlayKey: (key: String, title: String) -> Unit,
    openLabel: String,
    onOpen: () -> Unit,
    onChoose: (DriveFile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val info = remember(f) { Library.info(f) }
    val recent = remember(f) { RecentStore(context).all() }
    val resumeMs = remember(f) { if (f.isFolder) 0L else RecentStore(context).resumePosition(MediaRef.Drive(f.id).key) }
    // A show: the episode last watched (by title, so it's found whichever season it's in).
    val showKey = remember(f) { ShowPrefs.showKey(info.title) }
    val resumeEpisode = remember(f) {
        if (!isShow) null else recent.firstOrNull { ShowPrefs.showKey(it.title) == showKey && it.progress in 0.02f..0.97f }
    }
    val cast by produceState(emptyList<String>(), f) {
        val id = info.tmdbId ?: return@produceState
        value = com.woodruff.tvexplorer.tmdb.Tmdb.cast(context, id, info.tv)
    }
    // Seasons (folders in the show's folder) or, without season folders, the episodes themselves.
    val seasons by produceState<List<Pair<String, DriveFile?>>?>(null, f, token) {
        if (!isShow || !f.isFolder || token == null) { value = emptyList(); return@produceState }
        value = runCatching {
            val inside = DriveApi.list(token, DrivePlace.Folder(f.id, f.name))
            val folders = inside.filter { it.isFolder }
            if (folders.isNotEmpty()) folders.map { it.name to it } else listOf("Episodes" to null)
        }.getOrDefault(emptyList())
    }
    var season by remember(f) { mutableStateOf(0) }
    val episodes by produceState<List<DriveFile>?>(null, seasons, season, token) {
        val list = seasons ?: return@produceState
        if (list.isEmpty() || token == null) { value = emptyList(); return@produceState }
        value = null
        val folder = list.getOrNull(season)?.second
        value = runCatching {
            DriveApi.list(token, if (folder != null) DrivePlace.Folder(folder.id, folder.name) else DrivePlace.Folder(f.id, f.name))
                .filter { it.kind == FileKind.VIDEO }
        }.getOrDefault(emptyList())
    }
    // The first season's episodes, kept so "Play S1 E1" stays put while you browse other seasons.
    var firstEps by remember(f) { mutableStateOf<List<DriveFile>?>(null) }
    LaunchedEffect(episodes) { if (season == 0 && episodes != null) firstEps = episodes }
    val similar = remember(f, all) {
        if (info.genres.isEmpty()) emptyList()
        else all.asSequence().filter { it.id != f.id }
            .map { it to Library.info(it).genres.count { g -> g in info.genres } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<DriveFile, Int>> { it.second }.thenByDescending { Library.info(it.first).rating ?: 0.0 })
            .take(14).map { it.first }.toList()
    }
    val primary = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val wide = info.backdrop ?: art
            if (wide != null) {
                val request = remember(wide) {
                    ImageRequest.Builder(context).data(wide)
                        .apply { artHeaders(wide).forEach { (k, v) -> addHeader(k, v) } }
                        .size(1280, 720).crossfade(250).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            // Darken the left and the bottom, where the text and rows sit, so they read on any picture.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.55f to Color.Black.copy(alpha = 0.5f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.94f))))

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 72.dp, end = 48.dp, top = 120.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(info.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(info.year?.toString(), info.genres.take(3).joinToString(", ").ifBlank { null })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
                        info.rating?.let { r ->
                            TvIcon(Icons.Rounded.Star, size = 18.dp, tint = Color(0xFFFFC857), modifier = Modifier.padding(start = 14.dp, end = 4.dp))
                            Text(String.format("%.1f", r), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    info.overview?.let {
                        Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    if (cast.isNotEmpty()) Text("Starring " + cast.joinToString(", "), color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // The buttons, all on one row.
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    when {
                        isShow && f.isFolder -> {
                            val firstEpisode = firstEps?.firstOrNull()
                            if (resumeEpisode != null) {
                                val label = episodeCode(resumeEpisode.title)?.let { "Resume $it" } ?: "Resume"
                                DetailButton(Icons.Rounded.PlayArrow, label, primary = true, Modifier.focusRequester(primary)) { onPlayKey(resumeEpisode.key, resumeEpisode.title) }
                                if (firstEpisode != null) DetailButton(Icons.Rounded.Replay, "Play from the start", primary = false) { onPlayEpisode(firstEpisode, firstEps.orEmpty(), true) }
                            } else if (firstEpisode != null) {
                                DetailButton(Icons.Rounded.PlayArrow, "Play " + (episodeCode(firstEpisode.name) ?: "first episode"), primary = true, Modifier.focusRequester(primary)) { onPlayEpisode(firstEpisode, firstEps.orEmpty(), true) }
                            }
                            DetailButton(Icons.Rounded.FolderOpen, openLabel, primary = resumeEpisode == null && firstEpisode == null, if (resumeEpisode == null && firstEpisode == null) Modifier.focusRequester(primary) else Modifier, onOpen)
                        }
                        f.isFolder -> DetailButton(Icons.Rounded.FolderOpen, openLabel, primary = true, Modifier.focusRequester(primary), onOpen)
                        resumeMs > 60_000 -> {
                            DetailButton(Icons.Rounded.PlayArrow, "Resume from ${Format.duration(resumeMs)}", primary = true, Modifier.focusRequester(primary)) { onPlay(false) }
                            DetailButton(Icons.Rounded.Replay, "Start over", primary = false) { onPlay(true) }
                        }
                        else -> DetailButton(Icons.Rounded.PlayArrow, "Play", primary = true, Modifier.focusRequester(primary)) { onPlay(false) }
                    }
                }
                // A show's seasons and episodes.
                val seasonList = seasons
                if (isShow && f.isFolder && seasonList != null && seasonList.isNotEmpty()) {
                    if (seasonList.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(seasonList.size) { i ->
                            SeasonTab(seasonList[i].first, selected = i == season) { season = i }
                        }
                    }
                    val eps = episodes
                    if (eps == null) Text("Loading episodes…", color = Color.White.copy(alpha = 0.6f))
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                        items(eps, key = { it.id }) { ep ->
                            val key = MediaRef.Drive(ep.id).key
                            EpisodeCard(
                                ep, token,
                                progress = recent.firstOrNull { it.key == key }?.progress,
                                watched = Watched.isWatched(context, key),
                            ) { onPlayEpisode(ep, eps, false) }
                        }
                    }
                }
                // More like this, from your own library.
                if (similar.isNotEmpty()) {
                    Text("More like this", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp)) {
                        items(similar, key = { it.id }) { s ->
                            PosterTile(s, token, onClick = { onChoose(s) }, onLongClick = { onChoose(s) }, modifier = Modifier.width(130.dp), label = Library.label(s))
                        }
                    }
                }
            }
        }
    }
    // Focus the main button once it exists (a show's Play appears when its episodes arrive), then leave focus alone.
    var focused by remember(f) { mutableStateOf(false) }
    LaunchedEffect(firstEps, seasons) {
        if (!focused && (!isShow || !f.isFolder || firstEps != null || seasons?.isEmpty() == true)) {
            focused = runCatching { primary.requestFocus() }.isSuccess
        }
    }
}

/** "S02E04" / "1x05" in a file name → "S2 E4"; null when there isn't one. */
internal fun episodeCode(name: String): String? {
    Regex("""(?i)s(\d{1,2})\s*e(\d{1,3})""").find(name)?.let { return "S${it.groupValues[1].toInt()} E${it.groupValues[2].toInt()}" }
    Regex("""(?i)(?<!\d)(\d{1,2})x(\d{2})(?!\d)""").find(name)?.let { return "S${it.groupValues[1].toInt()} E${it.groupValues[2].toInt()}" }
    return null
}

@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B1220),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/** An episode: its frame from Drive, the code and name, a progress bar or a ✓. */
@Composable
private fun EpisodeCard(ep: DriveFile, token: String?, progress: Float?, watched: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.width(280.dp)) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), focusedContainerColor = Color.White.copy(alpha = 0.2f)),
            border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(18.dp))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize()) {
                val thumb = ep.thumbnailLink?.let(DriveApi::big)
                if (thumb != null && token != null) {
                    val request = remember(thumb, token) {
                        ImageRequest.Builder(context).data(thumb).addHeader("Authorization", "Bearer $token").size(400, 225).crossfade(150).build()
                    }
                    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else TvIcon(Icons.Rounded.PlayArrow, size = 40.dp, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
                if (progress != null && progress > 0.01f && !watched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
                }
                if (watched) Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) { TvIcon(Icons.Rounded.Check, size = 18.dp, tint = Color(0xFF0B1220)) }
            }
        }
        val code = episodeCode(ep.name)
        val name = ep.name.substringBeforeLast('.').let { n ->
            // "Show.S01E03.The.Title.1080p" → "The Title" when there's a name after the code.
            Regex("""(?i)(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{2})[\s._-]*(.*)""").find(n)?.groupValues?.get(2)
                ?.replace(Regex("""[._]"""), " ")?.replace(Regex("""(?i)\b(\d{3,4}p|x26[45]|h\.?26[45]|web[- ]?dl|webrip|bluray|hdtv|aac|ddp?5\.1)\b.*"""), "")?.trim()
                ?.ifBlank { null } ?: n
        }
        Text(listOfNotNull(code, name.takeIf { it != code }).joinToString(" · "), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
    }
}

/** Apple TV buttons: frosted glass, turning solid white with dark text when focused. */
@Composable
private fun DetailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B1220),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 24.dp)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
        }
    }
}
