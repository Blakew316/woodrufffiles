package com.woodruff.tvexplorer.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.calendar.TodayCalendar
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.data.FileKind
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
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
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
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.Handoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // Coming back from the player: pick up the position it just saved.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recent = store.continueWatching() }
    val play = { r: RecentItem -> context.startActivity(PlayerActivity.intent(context, listOf(r.key), listOf(r.title), r.key)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        Row(Modifier.fillMaxWidth().height(210.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ContinuePanel(
                recent, play, onMenu = { removeFor = it },
                onMovies = { drive.openRoot(DrivePlace.Movies); vm.show(Screen.DRIVE) },
                onShows = { drive.openRoot(DrivePlace.TvShows); vm.show(Screen.DRIVE) },
                modifier = Modifier.weight(1.5f).fillMaxHeight(),
            )
            TvsPanel(vm, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(182.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CamerasPanel(vm, Modifier.weight(1.5f).fillMaxHeight())
            GlancePanel(vm, Modifier.weight(1f).fillMaxHeight())
        }
    }

    removeFor?.let { r ->
        ActionMenu(
            title = r.title, subtitle = "${(r.progress * 100).toInt()}% watched",
            onDismiss = { removeFor = null },
            actions = listOf(
                MenuAction("Play from where you left off", Icons.Rounded.PlayArrow) { play(r) },
                MenuAction("Remove from Continue watching", Icons.Rounded.Delete, destructive = true) {
                    store.remove(r.key); recent = store.continueWatching()
                },
            ),
        )
    }
}

// ---- Header: greeting, quick launch, clock --------------------------------------------------

@Composable
private fun Header() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
    val line = lines[(day * 24 + hour) % lines.size]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1)
        }
        // Quick launch: the apps people open from here most.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            QuickButton("YouTube", brand = R.drawable.brand_youtube) { Handoff.launch(context, Handoff.YOUTUBE) }
            QuickButton("Apple Music", brand = R.drawable.brand_apple_music) { Handoff.launch(context, Handoff.APPLE_MUSIC) }
            QuickButton("Photo slideshow", icon = Icons.Rounded.Slideshow) { SlideshowActivity.start(context) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), style = MaterialTheme.typography.headlineSmall)
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now)), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
    }
}

@Composable
private fun QuickButton(label: String, brand: Int? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(46.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardStyle.colors(), border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (brand != null) Image(painterResource(brand), contentDescription = label, modifier = Modifier.size(30.dp))
            else if (icon != null) TvIcon(icon, size = 24.dp, tint = Palette.accent)
        }
    }
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
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Palette.surface).padding(14.dp)) {
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
            focusedContainerColor = Palette.accent, focusedContentColor = Palette.onAccent,
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
    recent: List<RecentItem>, play: (RecentItem) -> Unit, onMenu: (RecentItem) -> Unit,
    onMovies: () -> Unit, onShows: () -> Unit, modifier: Modifier,
) {
    Panel("Continue watching", modifier, note = if (recent.size > 1) "${recent.size} in progress" else null) {
        val r = recent.firstOrNull()
        if (r == null) {
            Text("Nothing in progress.", style = MaterialTheme.typography.titleMedium)
            Text("Pick something to start; it shows up here to resume.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelButton("Movies", Icons.Rounded.Movie, onMovies)
                PanelButton("TV Shows", Icons.Rounded.Tv, onShows)
            }
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = { play(r) }, onLongClick = { onMenu(r) },
                modifier = Modifier.width(180.dp).aspectRatio(16f / 9f).onMenuKey { onMenu(r) },
                shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            ) { ResumeArt(r) }
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(timeLeft(r), color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                UsageBar(r.progress)
            }
        }
        if (recent.size > 1) {
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent.drop(1).take(8), key = { it.key }) { m ->
                    Surface(
                        onClick = { play(m) }, onLongClick = { onMenu(m) },
                        modifier = Modifier.width(150.dp).onMenuKey { onMenu(m) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = CardStyle.colors(), border = CardStyle.border(),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(m.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            UsageBar(m.progress, Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun timeLeft(r: RecentItem): String =
    (if (r.durationMs > 0) "${Format.duration(r.durationMs - r.positionMs)} left" else "Resume") +
        if (MediaRef.parse(r.key) is MediaRef.Drive) " · Google Drive" else ""

@Composable
private fun ResumeArt(r: RecentItem) {
    val ref = MediaRef.parse(r.key)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.accent.copy(alpha = 0.35f), Palette.background))), contentAlignment = Alignment.Center) {
        if (ref is MediaRef.Drive) Image(painterResource(R.drawable.brand_google_drive), contentDescription = null, modifier = Modifier.size(40.dp))
        else TvIcon(FileKind.VIDEO.icon(), size = 40.dp, tint = Palette.video)
        if (ref is MediaRef.Local) AsyncImage(model = File(ref.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Palette.accent), contentAlignment = Alignment.Center) {
            TvIcon(Icons.Rounded.PlayArrow, size = 26.dp, tint = Palette.onAccent)
        }
    }
}

// ---- TVs ------------------------------------------------------------------------------------

/** Every TV in the family: switch between them to see what's on and a live picture. */
@Composable
private fun TvsPanel(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var tvs by remember { mutableStateOf(DashboardSamples.tvs ?: emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (DashboardSamples.tvs != null) return@LaunchedEffect
        TvLinks.load(context)
        while (true) {
            if (AppVisibility.foreground || tvs.isEmpty()) tvs = runCatching { TvLinks.statuses(context) }.getOrDefault(tvs)
            delay(10_000)
        }
    }
    val sel = tvs.firstOrNull { it.id == selected } ?: tvs.firstOrNull()
    Panel("TVs", modifier, note = if (tvs.size > 1) "${tvs.count { it.online }} on" else null) {
        if (sel == null) { Text("Looking…", color = Palette.muted); return@Panel }
        if (tvs.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
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
                        runCatching { TvLinks.screen(linked)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
                    }
                }
                delay(5_000)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(118.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0E13)),
                contentAlignment = Alignment.Center,
            ) {
                val s = shot
                if (s != null) Image(s, contentDescription = "${sel.name} screen", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else TvIcon(Icons.Rounded.Tv, size = 30.dp, tint = if (sel.online) Palette.accent else Color.White.copy(alpha = 0.35f))
            }
            Column(Modifier.weight(1f)) {
                val p = sel.playing
                Text(sel.name + if (isThis && tvs.size > 1) " (this TV)" else "", style = MaterialTheme.typography.labelMedium, color = Palette.muted, maxLines = 1)
                Text(
                    when { !sel.online -> "Off or unreachable"; p != null -> p.title; else -> "Nothing playing" },
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (sel.online && p != null) {
                    Text(
                        (if (p.playing) "Playing" else "Paused") + " · " + p.app,
                        color = if (p.playing) Palette.success else Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    )
                    if (p.durationMs > 0) UsageBar(p.positionMs.toFloat() / p.durationMs, Modifier.padding(top = 6.dp))
                }
            }
        }
        if (tvs.size == 1) {
            Spacer(Modifier.weight(1f))
            PanelButton("Link another TV", Icons.Rounded.Settings) { vm.show(Screen.SETTINGS) }
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
    Panel("Cameras", modifier, note = if (count > 0) "$count" else null) {
        when {
            samples != null -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(samples) { _, name -> CameraTile(name, null, onClick = {}) }
            }
            state.step == NestStep.CONNECTED && cams.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(cams, key = { _, c -> c.name }) { i, cam ->
                    CameraTile(cam.label, cam, onClick = { openCameraAt = i; vm.show(Screen.CAMERAS) })
                }
            }
            state.step == NestStep.CONNECTED -> Text(if (state.loading) "Loading cameras…" else "No cameras found", color = Palette.muted)
            else -> {
                Text("Google Home cameras aren't connected.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                PanelButton("Set up in Settings", Icons.Rounded.Settings) { vm.show(Screen.SETTINGS) }
            }
        }
    }
}

@Composable
private fun CameraTile(label: String, cam: com.woodruff.tvexplorer.nest.Camera?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    // Go live only after the tile has kept focus for a moment, so scrolling past doesn't start streams.
    LaunchedEffect(focused) { live = false; if (focused && cam?.webRtc == true) { delay(700); live = true } }
    Surface(
        onClick = onClick,
        modifier = Modifier.width(170.dp).aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused },
        shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0B0E13))) {
            if (live && cam != null) LiveVideo(cam, audio = false)
            else TvIcon(Icons.Rounded.Videocam, size = 28.dp, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.align(Alignment.Center))
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

private data class GlanceItem(val icon: ImageVector, val color: Color, val title: String, val sub: String, val unread: Boolean, val onClick: (() -> Unit)?)

@Composable
private fun GlancePanel(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    remember { Notes.load(context) }
    val notices by Notices.items.collectAsStateWithLifecycle()
    val notes by Notes.items.collectAsStateWithLifecycle()
    val cal by TodayCalendar.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val items = buildList {
        if (cal.connected) {
            val next = cal.events.firstOrNull { (it.end ?: it.start ?: Long.MAX_VALUE) > now }
            add(GlanceItem(Icons.Rounded.Event, Palette.danger, next?.title ?: "Nothing else today", next?.let { eventTime(it) } ?: "Calendar", false, null))
        }
        notices.take(2).forEach { n ->
            val icon = when (n.source) { NoticeSource.PHONE -> Icons.Rounded.PhoneIphone; NoticeSource.MAIL -> Icons.Rounded.Email; NoticeSource.SYSTEM -> Icons.Rounded.Notifications }
            add(GlanceItem(icon, Palette.accent, n.title, Format.date(n.at), !n.read) { vm.show(Screen.NOTIFICATIONS) })
        }
        (notes.firstOrNull { it.pinned } ?: notes.firstOrNull())?.let { n ->
            val first = n.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            add(GlanceItem(Icons.Rounded.EditNote, Color(0xFFFFB340), n.title.ifBlank { first }, if (n.title.isBlank()) "Note" else first, false) { vm.show(Screen.NOTES) })
        }
    }.take(3)
    val unread = notices.count { !it.read }
    Panel("At a glance", modifier, note = if (unread > 0) "$unread new" else null) {
        if (items.isEmpty()) Text("All caught up.", color = Palette.muted)
        items.forEach { g -> GlanceRow(g) }
    }
}

@Composable
private fun GlanceRow(g: GlanceItem) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(g.color), contentAlignment = Alignment.Center) {
                TvIcon(g.icon, size = 16.dp, tint = Color.White)
            }
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
