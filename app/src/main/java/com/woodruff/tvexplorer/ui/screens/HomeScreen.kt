package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.woodruff.tvexplorer.calendar.TodayCalendar
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.tvs.TvStatus
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.FilterPill
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.UsageBar
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.theme.CardStyle
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.glassPanel
import com.woodruff.tvexplorer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.woodruff.tvexplorer.weather.Weather
import com.woodruff.tvexplorer.notify.Board
import com.woodruff.tvexplorer.photos.Memories
import androidx.compose.runtime.produceState
import com.woodruff.tvexplorer.nest.CameraStills
import com.woodruff.tvexplorer.nest.CameraStreams
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.player.Watched
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Sample TVs and cameras for the UI gallery, which has no network or cameras. Never set in the app. */
internal object DashboardSamples {
    var tvs: List<TvStatus>? = null
    var cameras: List<String>? = null
}

/**
 * Home: a one-screen dashboard. Each panel is a glance with the most useful action right there:
 * resume what was playing, see and switch between the family's TVs, peek at the cameras, and the
 * latest notifications, notes and today's calendar. Setup lives in Settings.
 */
@Composable
fun HomeScreen(vm: MainViewModel, drive: DriveViewModel) {
    val context = LocalContext.current
    val store = remember { RecentStore(context) }
    var recent by remember { mutableStateOf(store.continueWatching()) }
    var removeFor by remember { mutableStateOf<RecentItem?>(null) }
    // Coming back from the player: pick up the position it just saved (and a fresh token for the
    // Google-hosted posters: the one from start-up lasts an hour), and what the other TVs watched.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recent = store.continueWatching(); drive.refreshToken(); FamilySync.pullSoon(context) }
    // Another TV's progress merged in while Home is up: show it.
    val syncSeen = remember { FamilySync.updates.value }
    LaunchedEffect(Unit) { FamilySync.updates.collect { if (it != syncSeen) recent = store.continueWatching() } }
    val play = { r: RecentItem -> context.startActivity(PlayerActivity.intent(context, listOf(r.key), listOf(r.title), r.key)) }
    // Recently added movies and shows, after what's in progress.
    val added by drive.recentlyAdded.collectAsStateWithLifecycle()
    // Posters for what's in progress, from the Movies / TV Shows entries they belong to.
    val artwork by drive.artwork.collectAsStateWithLifecycle()
    remember { com.woodruff.tvexplorer.cloud.EpisodeShows.load(context) }
    val shows by com.woodruff.tvexplorer.cloud.EpisodeShows.map.collectAsStateWithLifecycle()
    val arts = remember(recent, artwork, shows) { recent.associate { it.key to com.woodruff.tvexplorer.cloud.artworkFor(artwork, it.key, it.title, shows) } }
    // Which show (or film folder) each one sits under, looked up once on Drive.
    LaunchedEffect(recent, artwork) { if (artwork.isNotEmpty()) drive.resolveShows(recent.map { it.key }) }
    val driveToken by remember(drive) { drive.state.map { it.token }.distinctUntilChanged() }.collectAsStateWithLifecycle(drive.state.value.token)
    val openAdded = { (place, f): Pair<DrivePlace, com.woodruff.tvexplorer.cloud.DriveFile> ->
        if (f.isFolder) { drive.openRoot(place); drive.open(DrivePlace.Folder(f.id, f.name)); vm.show(Screen.DRIVE) }
        else { val key = MediaRef.Drive(f.id).key; context.startActivity(PlayerActivity.intent(context, listOf(key), listOf(f.name), key)) }
    }
    val scope = rememberCoroutineScope()
    val linked by TvLinks.linked.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        PinnedMessage()
        Row(Modifier.fillMaxWidth().height(196.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ContinuePanel(
                recent, arts, play, onMenu = { removeFor = it },
                added = added, token = driveToken, openAdded = openAdded,
                onMovies = { drive.openRoot(DrivePlace.Movies); vm.show(Screen.DRIVE) },
                onShows = { drive.openRoot(DrivePlace.TvShows); vm.show(Screen.DRIVE) },
                modifier = Modifier.weight(1.5f).fillMaxHeight(),
            )
            TvsPanel(vm, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(232.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CamerasPanel(vm, Modifier.weight(1.5f).fillMaxHeight())
            GlancePanel(vm, Modifier.weight(1f).fillMaxHeight())
        }
    }

    removeFor?.let { r ->
        ActionMenu(
            title = r.title, subtitle = if (r.upNext) upNextCaption(r.title) else "${(r.progress * 100).toInt()}% watched",
            onDismiss = { removeFor = null },
            actions = listOfNotNull(
                // Up next hasn't started: it plays from the beginning.
                MenuAction(if (r.upNext) "Play" else "Play from where you left off", Sym.PlayArrow) { play(r) },
                // Same Drive video on every linked TV, from the same spot.
                if (linked.isNotEmpty() && MediaRef.parse(r.key) is MediaRef.Drive) MenuAction("Watch together on all TVs", Sym.Groups) {
                    scope.launch {
                        withContext(Dispatchers.IO) { TvLinks.forEachLinked(context) { TvLinks.watch(it, r.key, r.title, r.positionMs) } }
                        // This TV leads: pause, seek and its position keep the others in step.
                        context.startActivity(PlayerActivity.intent(context, listOf(r.key), listOf(r.title), r.key, r.positionMs, together = true))
                    }
                } else null,
                // As finishing it in the player does: off Continue watching, with a ✓ on its poster
                // (both reach the other TVs too).
                MenuAction("Mark as watched", Sym.CheckCircle) {
                    store.remove(r.key); Watched.mark(context, r.key); recent = store.continueWatching()
                },
                MenuAction("Remove from Continue watching", Sym.Delete, destructive = true) {
                    store.removeShow(r); recent = store.continueWatching()
                },
            ),
        )
    }
}

/** An up-next tile's caption, in place of the progress bar: "Next · S2 E5" ("Up next" without an episode number). */
internal fun upNextCaption(title: String): String = episodeCode(title)?.let { "Next · $it" } ?: "Up next"

// ---- Family message board ------------------------------------------------------------------

/** The message pinned from a phone, on every TV until dismissed here. */
@Composable
private fun PinnedMessage() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Board.load(context) } }
    val pin by Board.pin.collectAsStateWithLifecycle()
    val p = pin ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.text.copy(alpha = 0.08f)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIcon(Sym.PushPin, size = 22.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(p.body.takeIf { it.isNotBlank() }, p.from?.let { "from $it" }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PanelButton("Dismiss", Sym.Close) { Board.dismiss(context) }
    }
}

// ---- Header: greeting and clock --------------------------------------------------

@Composable
private fun Header() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val weather by Weather.now.collectAsStateWithLifecycle()
    val cal by TodayCalendar.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { Weather.refresh(context); delay(10 * 60_000L) } }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    val accent = Palette.accent
    val title = remember(greeting, accent) {
        buildAnnotatedString {
            append("$greeting, ")
            withStyle(SpanStyle(color = accent)) { append("Woodruff Family") }
        }
    }
    // A new line each hour, the same one all hour (no flicker when the clock ticks).
    val lines = when (hour) { in 5..11 -> MORNING; in 12..16 -> AFTERNOON; in 17..23 -> EVENING; else -> LATE }
    val day = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
    // Mornings: a briefing (weather and today's calendar) instead of the playful line.
    val line = if (hour in 4..9 && (weather != null || cal.connected)) briefing(weather, cal) else lines[(day * 24 + hour) % lines.size]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), style = MaterialTheme.typography.headlineSmall)
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now)) + (weather?.let { "  ·  ${it.tempF}° ${it.summary}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
        }
    }
}

/** "Partly cloudy, 64° · high 72°, low 55° · 20% rain · 2 events today, first Dentist 9:30 AM". */
private fun briefing(w: Weather.Now?, cal: com.woodruff.tvexplorer.calendar.CalendarState): String {
    val parts = mutableListOf<String>()
    if (w != null) {
        parts += "${w.summary}, ${w.tempF}°"
        parts += "high ${w.highF}°, low ${w.lowF}°"
        if (w.rainChance >= 20) parts += "${w.rainChance}% chance of rain"
    }
    if (cal.connected) {
        val n = cal.events.size
        parts += when (n) {
            0 -> "nothing on the calendar"
            else -> "$n event${if (n == 1) "" else "s"} today, first ${cal.events.first().title} ${eventTime(cal.events.first())}"
        }
    }
    return parts.joinToString("  ·  ")
}

private val MORNING = listOf(
    "Coffee first, credits later.",
    "Rise and stream.",
    "Cartoons and cereal o'clock.",
    "Early show, best seats in the house.",
    "Morning matinee, anyone?",
)
private val AFTERNOON = listOf(
    "Perfect time for a matinee.",
    "Popcorn pairs well with lunch.",
    "An intermission from the day.",
    "One more episode won't hurt.",
    "Snack break, then the next episode.",
)
private val EVENING = listOf(
    "What are we watching tonight?",
    "Lights down, phones away.",
    "Prime time at the Woodruffs'.",
    "Grab the blankets and the popcorn.",
    "Just one more episode… probably.",
    "The remote is the real MVP.",
)
private val LATE = listOf(
    "Lights down, volume low.",
    "Still up? Same.",
    "Midnight movie club is in session.",
    "Shh, the house is sleeping.",
)

// ---- Panel frame ----------------------------------------------------------------------------

@Composable
private fun Panel(title: String, modifier: Modifier, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glassPanel(RoundedCornerShape(26.dp)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = Palette.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (note != null) Text(note, color = Palette.muted, style = MaterialTheme.typography.labelSmall)
        }
        content()
    }
}

/** A small text button inside a panel. */
@Composable
private fun PanelButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surfaceHigh, contentColor = Palette.text,
            focusedContainerColor = Palette.text, focusedContentColor = Palette.background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 18.dp)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

// ---- Continue watching ----------------------------------------------------------------------

@Composable
private fun ContinuePanel(
    recent: List<RecentItem>, arts: Map<String, com.woodruff.tvexplorer.cloud.DriveFile?>, play: (RecentItem) -> Unit, onMenu: (RecentItem) -> Unit,
    added: List<Pair<DrivePlace, com.woodruff.tvexplorer.cloud.DriveFile>>, token: String?,
    openAdded: (Pair<DrivePlace, com.woodruff.tvexplorer.cloud.DriveFile>) -> Unit,
    onMovies: () -> Unit, onShows: () -> Unit, modifier: Modifier,
) {
    val title = if (recent.isEmpty() && added.isNotEmpty()) "Recently added" else "Continue watching"
    Panel(title, modifier) {
        val r = recent.firstOrNull()
        if (r == null && added.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                items(added, key = { "new-" + it.second.id }) { a ->
                    PosterTile(a.second, token, onClick = { openAdded(a) }, onLongClick = { openAdded(a) }, modifier = Modifier.fillMaxHeight())
                }
            }
            return@Panel
        }
        if (r == null) {
            Text("Nothing in progress.", style = MaterialTheme.typography.titleMedium)
            Text("Pick something to start; it shows up here to resume.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelButton("Movies", Sym.Movie, onMovies)
                PanelButton("TV Shows", Sym.Tv, onShows)
            }
            return@Panel
        }
        // Posters, like the libraries, newest first; a bar shows how far through. A row clips at its
        // ends, so it leaves room there for a focused tile's grown edge and outline.
        LazyRow(
            Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            item(key = r.key) {
                ResumeTile(r, arts[r.key], token, play, onMenu, Modifier.fillMaxHeight().aspectRatio(2f / 3f))
            }
            items(recent.drop(1).take(8), key = { it.key }) { m ->
                ResumeTile(m, arts[m.key], token, play, onMenu, Modifier.fillMaxHeight().aspectRatio(2f / 3f))
            }
            // Then what's new in Movies and TV Shows.
            items(added, key = { "new-" + it.second.id }) { a ->
                PosterTile(a.second, token, onClick = { openAdded(a) }, onLongClick = { openAdded(a) }, modifier = Modifier.fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ResumeTile(
    r: RecentItem, art: com.woodruff.tvexplorer.cloud.DriveFile?, token: String?,
    play: (RecentItem) -> Unit, onMenu: (RecentItem) -> Unit, modifier: Modifier,
) {
    Surface(
        onClick = { play(r) }, onLongClick = { onMenu(r) },
        modifier = modifier.onMenuKey { onMenu(r) },
        shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxSize()) {
            val poster = art?.posterUrl?.takeIf { token != null || !isGoogle(it) }
            ResumeArt(r, poster, token)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)) {
                // A poster carries its own title; an episode still says which one it is (up next, the caption does).
                val label = when {
                    poster == null -> Format.title(r.title)
                    r.upNext -> null
                    else -> episodeCode(r.title)
                }
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // The next episode hasn't started: which one it is, instead of an empty bar.
                if (r.upNext) Text(
                    upNextCaption(r.title), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
                else UsageBar(r.progress, Modifier.padding(top = 4.dp))
            }
        }
    }
}


/**
 * Artwork for a resume tile: the film's or show's poster when the library has one, else the
 * video's own frame when it's on the TV, else a plain card with a play symbol.
 */
@Composable
private fun ResumeArt(r: RecentItem, poster: String?, token: String?) {
    val context = LocalContext.current
    val ref = MediaRef.parse(r.key)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.background)))) {
        when {
            poster != null -> {
                val request = remember(poster, token) {
                    coil.request.ImageRequest.Builder(context).data(poster)
                        .apply { if (isGoogle(poster) && token != null) addHeader("Authorization", "Bearer $token") }
                        .size(300, 450).crossfade(150).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            ref is MediaRef.Local -> AsyncImage(model = File(ref.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> TvIcon(Sym.PlayArrow, size = 34.dp, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center).offset(y = (-14).dp))
        }
        // Darken only the bottom, under the label and the bar.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
    }
}


// ---- TVs ------------------------------------------------------------------------------------

/** Every TV in the family: switch between them to see what's on and a live picture. */
@Composable
private fun TvsPanel(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    // Starts from what the TVs were doing when last asked (instead of "Looking…"), then refreshes.
    var tvs by remember { mutableStateOf(DashboardSamples.tvs ?: TvLinks.lastStatuses()) }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (DashboardSamples.tvs != null) return@LaunchedEffect
        withContext(Dispatchers.IO) { TvLinks.load(context) }
        while (true) {
            if (AppVisibility.foreground || tvs.isEmpty()) tvs = runCatching { TvLinks.statuses(context) }.getOrDefault(tvs)
            delay(10_000)
        }
    }
    val sel = tvs.firstOrNull { it.id == selected } ?: tvs.firstOrNull()
    Panel("TVs", modifier, note = if (tvs.size > 1) "${tvs.count { it.online }} on" else null) {
        if (sel == null) { Text("Looking…", color = Palette.muted); return@Panel }
        if (tvs.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                items(tvs, key = { it.id }) { t -> FilterPill(t.name, t.id == sel.id, { selected = t.id }) }
            }
        }
        // A live picture of another TV (only while Woodruff is on its screen); this TV needs none.
        val isThis = sel.id == tvs.first().id
        var shot by remember(sel.id) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(sel.id, sel.online) {
            if (isThis || !sel.online || DashboardSamples.tvs != null) return@LaunchedEffect
            val linked = TvLinks.find(sel.id) ?: return@LaunchedEffect
            while (true) {
                if (AppVisibility.foreground) {
                    shot = withContext(Dispatchers.IO) {
                        // A 112dp thumbnail: decode at a quarter size, not the full 960px frame.
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        runCatching { TvLinks.screen(linked)?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts)?.asImageBitmap() } }.getOrNull()
                    }
                }
                // Each picture makes the other TV capture its screen, so not too often.
                delay(10_000)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Only another TV has a picture worth showing; otherwise a small icon keeps room for the title.
            val s = shot
            if (s != null) {
                Image(
                    s, contentDescription = "${sel.name} screen", contentScale = ContentScale.Crop,
                    modifier = Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surfaceHigh), contentAlignment = Alignment.Center) {
                    TvIcon(Sym.Tv, size = 24.dp, tint = if (sel.online) Palette.text else Palette.muted)
                }
            }
            Column(Modifier.weight(1f)) {
                val p = sel.playing
                Text(sel.name + if (isThis && tvs.size > 1) " · this TV" else "", style = MaterialTheme.typography.labelMedium, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when { !sel.online -> "Off or unreachable"; p != null -> Format.title(p.title); else -> "Nothing playing" },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (sel.online && p != null) {
                    Text(
                        (if (p.playing) "Playing" else "Paused") + " · " + p.app,
                        color = if (p.playing) Palette.success else Palette.muted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val p = sel.playing
        if (sel.online && p != null && p.durationMs > 0) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                UsageBar(p.positionMs.toFloat() / p.durationMs, Modifier.weight(1f))
                Text(
                    "${Format.duration(p.positionMs)} / ${Format.duration(p.durationMs)}", color = Palette.muted,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (tvs.size == 1) {
            Spacer(Modifier.weight(1f))
            PanelButton("Link another TV", Sym.Settings) { vm.show(Screen.SETTINGS) }
        }
    }
}

// ---- Cameras ---------------------------------------------------------------------------------

/** Camera tiles; focusing one shows it live (one at a time), selecting opens it full screen. */
@Composable
private fun CamerasPanel(vm: MainViewModel, modifier: Modifier) {
    val state by Nest.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (DashboardSamples.cameras == null) Nest.warm() }
    val samples = DashboardSamples.cameras
    val cams = state.cameras
    val count = samples?.size ?: cams.size
    // Live only while Woodruff is on screen: a video playing on top, another app or the TV
    // sleeping stops the streams (each one counts against Google's limits).
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    // Wait a moment before connecting, so passing through Home (opening straight to the Command
    // center, or a quick switch) doesn't start four streams only to close them again.
    var onScreen by remember { mutableStateOf(false) }
    // Low-memory TVs can't decode four streams and stay responsive: there only the camera you're on plays.
    val context = LocalContext.current
    val lowRam = remember { context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true }
    var focusedCam by remember { mutableStateOf<String?>(null) }
    // Where the focus is right now; it only becomes the live tile once it rests there, so passing
    // across the tiles doesn't tear down and start a camera stream on every press.
    var focusRaw by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusRaw) {
        val r = focusRaw
        // Focus left the tiles: back to rotating (in "one at a time" mode) instead of keeping the last one live.
        if (r == null) { focusedCam = null; return@LaunchedEffect }
        delay(600)
        focusedCam = r
    }
    // "One at a time" (and low-memory TVs): one tile streams, moving on every minute; the others
    // show their last frame. The tile you're on always plays. (Each move stops one stream and
    // starts another, and Google allows only so many of those an hour per camera.)
    val mode = Appearance.homeCameras
    val rotate = mode == Appearance.HomeCameras.ROTATE || (lowRam && mode == Appearance.HomeCameras.ALL)
    var turn by remember { mutableIntStateOf(0) }
    LaunchedEffect(rotate, onScreen) { while (rotate && onScreen) { delay(60_000); turn++ } }
    LaunchedEffect(visible) { onScreen = false; if (visible) { delay(1_500); onScreen = true } }
    val liveTiles = onScreen && mode != Appearance.HomeCameras.OFF
    val shown = cams.take(4)
    val rotating = if (rotate && liveTiles && state.step == NestStep.CONNECTED) rotatingTile(shown.map { it.name }, focusedCam, turn) else null
    // When it moves on, the camera it was showing closes now instead of staying up for another
    // screen, so one at a time really is one (as are any still up from the Command center).
    LaunchedEffect(rotating) { if (rotating != null) CameraStreams.closeIdle() }
    Panel("Cameras", modifier, note = if (count > 0) "$count" else null) {
        when {
            // Four at a glance in a 2×2 grid; the rest are in the Command center.
            samples != null -> CameraGrid4(samples.take(4)) { _, name -> CameraTile(name, null, live = false, onClick = {}) }
            state.step == NestStep.CONNECTED && cams.isNotEmpty() -> CameraGrid4(shown) { i, cam ->
                // Play live on their own, like the Command center.
                key(cam.name) {
                    CameraTile(
                        cam.label, cam,
                        live = liveTiles && cam.webRtc && (!rotate || rotating == cam.name),
                        onFocus = { f -> if (f) focusRaw = cam.name else if (focusRaw == cam.name) focusRaw = null },
                        onClick = { openCameraAt = i; vm.show(Screen.CAMERAS) },
                    )
                }
            }
            state.step == NestStep.CONNECTED -> Text(if (state.loading) "Loading cameras…" else "No cameras found", color = Palette.muted)
            else -> {
                Text("Google Home cameras aren't connected.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                PanelButton("Set up in Settings", Sym.Settings) { vm.show(Screen.SETTINGS) }
            }
        }
    }
}

/**
 * "One at a time": which of Home's camera [tiles] plays live. The one you're on, else the one whose
 * [turn] it is (they take turns in order); null when there are none.
 */
internal fun rotatingTile(tiles: List<String>, focused: String?, turn: Int): String? = when {
    focused != null && focused in tiles -> focused
    tiles.isEmpty() -> null
    else -> tiles[turn.mod(tiles.size)]
}

/**
 * Up to four tiles, two per row. A plain layout on purpose, not a lazy grid: live video is an
 * Android view, and an Android view inside a lazy layout crashed ("release should only be called
 * once") when a tile stopped streaming or Home closed.
 */
@Composable
private fun <T> CameraGrid4(items: List<T>, tile: @Composable (Int, T) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, item -> Box(Modifier.weight(1f)) { tile(row * 2 + col, item) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CameraTile(label: String, cam: com.woodruff.tvexplorer.nest.Camera?, live: Boolean, onFocus: (Boolean) -> Unit = {}, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp).onFocusChanged { onFocus(it.isFocused) },
        shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        // Edge to edge, in the tile's own rounded shape: the picture fills the whole tile.
        Box(Modifier.fillMaxSize().clip(com.woodruff.tvexplorer.ui.theme.Shapes.card).background(Color(0xFF0B0E13))) {
            val stills by CameraStills.stills.collectAsStateWithLifecycle()
            val still = cam?.let { stills[it.name] }
            val stillImage = remember(still) { still?.bitmap?.asImageBitmap() }
            if (live && cam != null) LiveVideo(cam, audio = false, fill = true)
            else if (stillImage != null) Image(stillImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                TvIcon(Sym.Videocam, size = 28.dp, tint = Color.White.copy(alpha = 0.45f))
                // Older Nest models only stream over RTSP, which Google's API doesn't offer to apps.
                if (cam != null && !cam.webRtc) Text("No live view for this model", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            if (live) Text(
                "LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE5484D)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

// ---- At a glance: today, notifications, notes -------------------------------------------------

private data class GlanceItem(val icon: ImageVector, val title: String, val sub: String, val unread: Boolean, val onClick: (() -> Unit)?)

@Composable
private fun GlancePanel(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Notes.load(context) } }
    val notices by Notices.items.collectAsStateWithLifecycle()
    val notes by Notes.items.collectAsStateWithLifecycle()
    val cal by TodayCalendar.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val memories by produceState(0) {
        value = withContext(Dispatchers.IO) { runCatching { Memories.forToday(context) { Appearance.photos(context) }.size }.getOrDefault(0) }
    }
    val items = buildList {
        if (memories > 0) add(GlanceItem(Sym.PhotoLibrary, "On this day", "$memories photo${if (memories == 1) "" else "s"} from earlier years", false) {
            com.woodruff.tvexplorer.screensaver.SlideshowActivity.start(context, memories = true)
        })
        if (cal.connected) {
            val next = cal.events.firstOrNull { (it.end ?: it.start ?: Long.MAX_VALUE) > now }
            add(GlanceItem(Sym.Event, next?.title ?: "Nothing else today", next?.let { eventTime(it) } ?: "Calendar", false, null))
        }
        notices.take(2).forEach { n ->
            val icon = when (n.source) { NoticeSource.PHONE -> Sym.PhoneIphone; NoticeSource.MAIL -> Sym.Email; NoticeSource.SYSTEM -> Sym.Notifications }
            add(GlanceItem(icon, n.title, Format.date(n.at), !n.read) { vm.show(Screen.NOTIFICATIONS) })
        }
        (notes.firstOrNull { it.pinned } ?: notes.firstOrNull())?.let { n ->
            val first = n.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            add(GlanceItem(Sym.EditNote, n.title.ifBlank { first }, if (n.title.isBlank()) "Note" else first, false) { vm.show(Screen.NOTES) })
        }
    }.take(4)
    Panel("At a glance", modifier) {
        if (items.isEmpty()) Text("All caught up.", color = Palette.muted)
        items.forEach { g -> GlanceRow(g) }
    }
}

@Composable
private fun GlanceRow(g: GlanceItem) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { TvIcon(g.icon, size = 20.dp) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(g.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(g.sub, style = MaterialTheme.typography.labelSmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (g.unread) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Palette.accent))
        }
    }
    val click = g.onClick
    if (click == null) Box(Modifier.padding(bottom = 2.dp)) { content() }
    else Surface(
        onClick = click,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Palette.text,
            focusedContainerColor = Palette.surfaceHigh, focusedContentColor = Palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) { content() }
}
