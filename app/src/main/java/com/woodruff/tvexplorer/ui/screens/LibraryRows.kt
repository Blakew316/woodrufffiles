package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.ShowPrefs
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Shapes
import java.util.Calendar

/**
 * Movies / TV Shows as a streaming service shows them: a featured title across the top, then rows
 * — Continue watching, Recently added, Top rated, and one per genre. Shown when nothing is filtered;
 * picking a category or a sort switches to the full grid.
 */
@Composable
internal fun LibraryRows(
    files: List<DriveFile>,
    token: String?,
    onChoose: (DriveFile) -> Unit,
    /** Play a film straight away (the featured title's Play). */
    onPlay: (DriveFile) -> Unit,
    onMenu: (DriveFile) -> Unit,
    /** The focused title's art, for the backdrop behind the screen. */
    onFocusArt: (String?) -> Unit,
) {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val recent = remember(resumes) { RecentStore(context).all().filter { it.progress in 0.02f..0.97f } }
    val progressOf = remember(recent) { recent.associate { it.key to it.progress } }

    val rows = remember(files, recent) {
        val byKey = files.associateBy { MediaRef.Drive(it.id).key }
        val byShow = files.filter { it.isFolder }.associateBy { ShowPrefs.showKey(Library.info(it).title) }
        // A film you're part-way through, or a show one of whose episodes you are — most recent first.
        val continuing = recent.mapNotNull { r -> byKey[r.key] ?: byShow[ShowPrefs.showKey(r.title)] }.distinctBy { it.id }
        val recentlyAdded = files.sortedByDescending { it.modified }.take(20)
        val rated = files.filter { Library.info(it).rating != null }.sortedByDescending { Library.info(it).rating }
        buildList {
            if (continuing.isNotEmpty()) add("Continue watching" to continuing.take(20))
            add("Recently added" to recentlyAdded)
            if (rated.size >= 5) add("Top rated" to rated.take(20))
            Library.genres(files).take(8).forEach { g ->
                val inGenre = files.filter { g in Library.info(it).genres }
                if (inGenre.size >= 4) add(g to inGenre.sortedByDescending { Library.info(it).rating ?: 0.0 }.take(24))
            }
        }
    }
    // The featured title: what you're watching, else one of the best rated with wide art — a
    // different one each day.
    val featured = remember(rows) {
        rows.firstOrNull { it.first == "Continue watching" }?.second?.firstOrNull { Library.info(it).backdrop != null }
            ?: files.filter { Library.info(it).backdrop != null }.sortedByDescending { Library.info(it).rating ?: 0.0 }.take(7)
                .let { top -> top.getOrNull(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % top.size.coerceAtLeast(1)) }
    }
    val heroFocus = remember { FocusRequester() }
    val firstTile = remember { FocusRequester() }
    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (featured != null) item(key = "hero") {
            Hero(featured, token, heroFocus, onPlay = { if (featured.isFolder) onChoose(featured) else onPlay(featured) }, onDetails = { onChoose(featured) })
        }
        rows.forEachIndexed { r, (title, list) ->
            item(key = "row:$title") {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, bottom = 2.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                    ) {
                        items(list, key = { it.id }) { f ->
                            val key = MediaRef.Drive(f.id).key
                            PosterTile(
                                f, token, onClick = { onChoose(f) }, onLongClick = { onMenu(f) },
                                modifier = Modifier.width(150.dp)
                                    .then(if (featured == null && r == 0 && f == list.first()) Modifier.focusRequester(firstTile) else Modifier)
                                    .onFocusChanged { if (it.isFocused) onFocusArt(posterArt(f)) },
                                label = Library.label(f),
                                progress = progressOf[key],
                                watched = !f.isFolder && Watched.isWatched(context, key),
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { if (featured != null) heroFocus.requestFocus() else firstTile.requestFocus() }
    }
}

/** The featured title: its wide art in a rounded glass frame, name, year and genres, synopsis, and Play / Details. */
@Composable
private fun Hero(f: DriveFile, token: String?, focus: FocusRequester, onPlay: () -> Unit, onDetails: () -> Unit) {
    val context = LocalContext.current
    val info = remember(f) { Library.info(f) }
    val art = info.backdrop ?: posterArt(f)
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(300.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black)) {
        if (art != null && (token != null || !isGoogle(art))) {
            val request = remember(art, token) {
                ImageRequest.Builder(context).data(art)
                    .apply { if (isGoogle(art) && token != null) addHeader("Authorization", "Bearer $token") }
                    .size(1280, 720).crossfade(250).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.6f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.55f).padding(start = 36.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(info.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(info.year?.toString(), info.genres.take(2).joinToString(", ").ifBlank { null }, info.rating?.let { "★ %.1f".format(it) })
            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
            info.overview?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HeroButton(Icons.Rounded.PlayArrow, if (f.isFolder) "Episodes" else "Play", Modifier.focusRequester(focus), onPlay)
                HeroButton(Icons.Rounded.Info, "Details", Modifier, onDetails)
            }
        }
    }
}

@Composable
private fun HeroButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color(0xFF0B1220),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 22.dp)
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
