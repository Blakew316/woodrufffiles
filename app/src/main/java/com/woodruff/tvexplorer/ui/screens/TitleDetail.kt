package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.util.Format

/**
 * A title's page, Apple TV style: its wide art across the screen, the name, year, genres and
 * rating, the synopsis, and the buttons — Play (or Resume, with Start over) for a film, the
 * seasons for a show. Back returns to the grid.
 *
 * [artHeaders] adds the Google token for Drive-hosted art (never sent to TMDB).
 */
@Composable
fun TitleDetail(
    f: DriveFile,
    art: String?,
    artHeaders: (String) -> Map<String, String>,
    onPlay: (fromStart: Boolean) -> Unit,
    /** For a folder: what opening it means ("Seasons & episodes" for a show, "Open" for a film's folder). */
    openLabel: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val info = remember(f) { Library.info(f) }
    val resumeMs = remember(f) { if (f.isFolder) 0L else RecentStore(context).resumePosition(MediaRef.Drive(f.id).key) }
    val primary = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val wide = info.backdrop ?: art
            if (wide != null) AsyncImage(
                model = ImageRequest.Builder(context).data(wide)
                    .apply { artHeaders(wide).forEach { (k, v) -> addHeader(k, v) } }
                    .size(1280, 720).crossfade(250).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            // Darken the left and the bottom, where the text sits, so it reads on any picture.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.55f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))

            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(0.58f).padding(start = 72.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
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
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (f.isFolder) {
                        DetailButton(Icons.Rounded.FolderOpen, openLabel, primary = true, Modifier.focusRequester(primary), onOpen)
                    } else if (resumeMs > 60_000) {
                        DetailButton(Icons.Rounded.PlayArrow, "Resume from ${Format.duration(resumeMs)}", primary = true, Modifier.focusRequester(primary)) { onPlay(false) }
                        DetailButton(Icons.Rounded.Replay, "Start over", primary = false) { onPlay(true) }
                    } else {
                        DetailButton(Icons.Rounded.PlayArrow, "Play", primary = true, Modifier.focusRequester(primary)) { onPlay(false) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }
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
