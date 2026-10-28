package com.woodruff.tvexplorer.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Videocam
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.tvs.LinkedTv
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.server.AutoStart
import com.woodruff.tvexplorer.update.Updater
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.runtime.produceState
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.nest.NestStep
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.PowerSettingsNew
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayCircle
import com.woodruff.tvexplorer.calendar.TodayCalendar
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Cast
import com.woodruff.tvexplorer.util.Handoff
import com.woodruff.tvexplorer.photos.GooglePhotos
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.woodruff.tvexplorer.phones.Phone
import com.woodruff.tvexplorer.phones.Phones
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.util.Format
import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.Location
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FolderOpen
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.ButtonRow
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Button
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.mail.MailAccount
import com.woodruff.tvexplorer.mail.MailAccounts
import com.woodruff.tvexplorer.mail.MailClient
import com.woodruff.tvexplorer.mail.MailProvider
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.PairingKey
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.ConfirmDialog
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.ui.components.IconBadge
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.ThemeSetting
import com.woodruff.tvexplorer.util.OpenResult
import com.woodruff.tvexplorer.util.Opener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Every popup the Settings screen can show. */
private sealed interface SettingsDialog {
    data object ClientId : SettingsDialog
    data object ClientSecret : SettingsDialog
    data object RemoveGoogle : SettingsDialog
    data object ResetPairing : SettingsDialog
    data object PickProvider : SettingsDialog
    data class MailEmail(val provider: MailProvider) : SettingsDialog
    data class MailPassword(val provider: MailProvider, val email: String) : SettingsDialog
    data class MailHost(val provider: MailProvider, val email: String, val password: String) : SettingsDialog
    data class RemoveMail(val account: MailAccount) : SettingsDialog
    data object OverlayHelp : SettingsDialog
    data object DreamHelp : SettingsDialog
    data object NestProject : SettingsDialog
    data object TmdbKey : SettingsDialog
    data object TvName : SettingsDialog
    data object LinkCode : SettingsDialog
    data object AddTv : SettingsDialog
    data object MediaAccessHelp : SettingsDialog
    data object UnlinkHubs : SettingsDialog
    data object ICloud : SettingsDialog
    data object BrowseTypes : SettingsDialog
    data class RemoveTv(val tv: LinkedTv) : SettingsDialog
    data class RemovePhone(val phone: Phone) : SettingsDialog
}

/**
 * All settings in one place. Secrets (Google keys, mail passwords, tokens) are stored encrypted
 * on the TV, survive restarts and updates, and can be typed here or, more comfortably, from the
 * phone (Phone → Settings tab).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // focusProperties.exit
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var showPhotos by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    val calendar by TodayCalendar.state.collectAsStateWithLifecycle()
    // Bumped whenever something changes (here, from the phone, or after returning from Android settings).
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { CompanionHub.changes.collect { version++ } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler { openSidebar() }

    val v = version // read so the values below refresh
    val clientId = remember(v) { GoogleAuth.clientId(context) }
    val configured = remember(v) { GoogleAuth.isConfigured(context) }
    val connected = remember(v) { GoogleAuth.isConnected(context) }
    val nestProject = remember(v) { GoogleAuth.nestProject(context) }
    val nestConnected = remember(v) { GoogleAuth.isConnected(context, GoogleAuth.Grant.NEST) }
    val tmdbSet = remember(v) { Tmdb.isConfigured(context) }
    val tvName = remember(v) { TvLinks.name(context) }
    val autoStart = remember(v) { AutoStart.enabled(context) }
    val autoUpdate = remember(v) { Updater.autoEnabled(context) }
    val testTv = remember(v) { Updater.isTestTv(context) }
    val update by Updater.state.collectAsStateWithLifecycle()
    val nestState by Nest.state.collectAsStateWithLifecycle()
    val doorbellStatus by Doorbell.status.collectAsStateWithLifecycle()
    val otherTvs by produceState(emptyList<com.woodruff.tvexplorer.tvs.TvStatus>(), v) {
        value = runCatching { TvLinks.statuses(context) }.getOrDefault(emptyList())
    }
    val seesOtherApps = remember(v) { NowPlaying.canSeeOtherApps(context) }
    remember { TvLinks.load(context) }
    val linkedTvs by TvLinks.linked.collectAsStateWithLifecycle()
    val photosConnected = remember(v, showPhotos) { GoogleAuth.isConnected(context, GoogleAuth.Grant.PHOTOS) }
    val accounts = remember(v) { MailAccounts.list(context) }
    val overlay = remember(v) { Settings.canDrawOverlays(context) }
    val changed = { version++; CompanionHub.settingsChanged.tryEmit(Unit) }
    LaunchedEffect(Unit) { Phones.load(context) }
    val phones by Phones.items.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshRoots() }

    // Two panes, like the TV's own settings: categories on the left, that category's settings on
    // the right. Moving down the left list switches the right side at once; Right or OK goes in.
    // (One long list of every setting took ages to scroll through.)
    val categories = listOf(
        SettingsCategory("Overview", Icons.Rounded.Info) {
            item {
                SettingRow(Icons.Rounded.Info, "This TV", "Version ${BuildConfig.VERSION_NAME} · phone connection ${if (TransferService.state.value.running) "on" else "off"}") {}
            }
            item { SettingRow(Icons.Rounded.AddToDrive, "Google Drive", if (connected) "Connected" else if (configured) "Not signed in" else "Not set up") { vm.show(Screen.DRIVE) } }
            item {
                val cams = nestState
                SettingRow(Icons.Rounded.Videocam, "Cameras", when {
                    cams.step != NestStep.CONNECTED -> "Not connected"
                    cams.error != null -> cams.error
                    cams.loading -> "Loading…"
                    else -> "${cams.cameras.size} camera${if (cams.cameras.size == 1) "" else "s"} · working"
                }) { vm.show(Screen.CAMERAS) }
            }
            item {
                SettingRow(Icons.Rounded.NotificationsActive, "Doorbell alerts", doorbellStatus ?: if (Doorbell.subscription(context) != null) "Watching" else "Off (set up in the phone app)") {}
            }
            // Same as the Updates page: installs when an update is ready, otherwise checks.
            item {
                // Slow frames per screen on this TV since the app started (press to start counting afresh).
                var stats by remember { mutableStateOf(com.woodruff.tvexplorer.util.FrameStats.summary()) }
                SettingRow(Icons.Rounded.Speed, "Smoothness", stats) {
                    com.woodruff.tvexplorer.util.FrameStats.reset(); stats = com.woodruff.tvexplorer.util.FrameStats.summary()
                }
            }
            item {
                SettingRow(Icons.Rounded.SystemUpdate, "Updates", updateStatus(update)) {
                    if (update.step == Updater.Step.READY) startUpdate(context, vm) else Updater.checkNow(context)
                }
            }
            otherTvs.drop(1).forEach { t ->
                item(key = "status-" + t.id) {
                    SettingRow(Icons.Rounded.Tv, t.name, if (t.online) "Online · ${t.version?.let { "version $it" } ?: "older version"}" else "Off or unreachable") {}
                }
            }
        },
        SettingsCategory("Google", Icons.Rounded.AddToDrive) {
            section("Google Drive")
            item { SettingRow(Icons.Rounded.AddToDrive, "Browse Google Drive", null, brand = R.drawable.brand_google_drive) { vm.show(Screen.DRIVE) } }
            item { SettingRow(Icons.Rounded.AddToDrive, "Status", if (connected) "Signed in" else if (configured) "Not signed in" else "Not set up", brand = R.drawable.brand_google_drive) { vm.show(Screen.DRIVE) } }
            item { SettingRow(Icons.Rounded.Key, "Client ID", clientId?.let { mask(it, 18) } ?: "Not set") { dialog = SettingsDialog.ClientId } }
            item { SettingRow(Icons.Rounded.Lock, "Client secret", if (configured) "•••••••• saved" else "Not set") { dialog = SettingsDialog.ClientSecret } }
            if (connected) item {
                SettingRow(Icons.Rounded.Logout, "Sign out of Google", null) {
                    scope.launch { GoogleAuth.signOut(context); changed() }
                }
            }
            if (configured) item { SettingRow(Icons.Rounded.Delete, "Remove Google keys", null, danger = true) { dialog = SettingsDialog.RemoveGoogle } }
            section("Google Photos")
            item {
                SettingRow(Icons.Rounded.PhotoLibrary, "Google Photos", if (photosConnected) "Add photos & videos" else "Connect", brand = R.drawable.brand_google_photos) {
                    showPhotos = true
                }
            }
            item { SettingRow(Icons.Rounded.PhotoLibrary, "Photos", null) { vm.openRoot(GooglePhotos.folder.apply { mkdirs() }, from = Screen.SETTINGS) } }
            item { SettingRow(Icons.Rounded.Slideshow, "Videos", null) { vm.openRoot(GooglePhotos.videoFolder.apply { mkdirs() }, from = Screen.SETTINGS) } }
            if (photosConnected) item {
                SettingRow(Icons.Rounded.Logout, "Disconnect Google Photos", null) { GooglePhotos.signOut { changed() } }
            }
            section("Google Calendar")
            item {
                SettingRow(Icons.Rounded.Event, "Today's Activities", if (calendar.connected) "Connected" else "Connect", brand = R.drawable.brand_google_calendar) {
                    if (!calendar.connected) showCalendar = true
                }
            }
            if (calendar.connected) item {
                SettingRow(Icons.Rounded.Logout, "Disconnect Google Calendar", null) { TodayCalendar.signOut { changed() } }
            }
        },
        SettingsCategory("Mail & posters", Icons.Rounded.Email) {
            section("Mail")
            accounts.forEach { a ->
                item(key = a.id) { SettingRow(Icons.Rounded.Email, a.email, a.provider.label) { dialog = SettingsDialog.RemoveMail(a) } }
            }
            item { SettingRow(Icons.Rounded.PersonAdd, "Add a mail account", null) { dialog = SettingsDialog.PickProvider } }
            section("Movie & show posters")
            item { SettingRow(Icons.Rounded.Key, "TMDB API key", if (tmdbSet) "•••••••• saved" else "Not set") { dialog = SettingsDialog.TmdbKey } }
        },
        SettingsCategory("Cameras", Icons.Rounded.Videocam) {
            item {
                SettingRow(Icons.Rounded.Videocam, "Command center", if (nestConnected) "Connected" else "Not connected", brand = R.drawable.brand_google_home) { vm.show(Screen.CAMERAS) }
            }
            item {
                SettingRow(Icons.Rounded.Key, "Device Access Project ID", nestProject?.let { mask(it, 13) } ?: "Not set") {
                    dialog = SettingsDialog.NestProject
                }
            }
            item {
                // Turn off on extra TVs if Google starts limiting camera requests.
                SettingRow(Icons.Rounded.Videocam, "Cameras on Home", Appearance.homeCameras.label) {
                    Appearance.nextHomeCameras(context)
                }
            }
            if (nestConnected) item {
                SettingRow(Icons.Rounded.Logout, "Disconnect cameras", null) { Nest.signOut { changed() } }
            }
        },
        SettingsCategory("TVs", Icons.Rounded.Tv) {
            item { SettingRow(Icons.Rounded.Tv, "This TV's name", tvName) { dialog = SettingsDialog.TvName } }
            item { SettingRow(Icons.Rounded.Link, "Link this TV to another", null) { dialog = SettingsDialog.LinkCode } }
            item { SettingRow(Icons.Rounded.LinkOff, "Stop other TVs seeing this one", null, danger = true) { dialog = SettingsDialog.UnlinkHubs } }
            item { SettingRow(Icons.Rounded.Add, "Add another TV", if (linkedTvs.isEmpty()) null else "${linkedTvs.size} linked") { dialog = SettingsDialog.AddTv } }
            linkedTvs.forEach { tv ->
                item(key = "tv-" + tv.id) { SettingRow(Icons.Rounded.Tv, tv.name, tv.base.removePrefix("http://")) { dialog = SettingsDialog.RemoveTv(tv) } }
            }
            item {
                SettingRow(Icons.Rounded.PlayCircle, "See what other apps are playing", if (seesOtherApps) "On" else "Off") {
                    val r = Opener.firstThatStarts(context, listOf(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")), "")
                    if (r is OpenResult.Failed) dialog = SettingsDialog.MediaAccessHelp
                }
            }
        },
        SettingsCategory("Phone", Icons.Rounded.PhoneIphone) {
            section("Phone connection")
            item { SettingRow(Icons.Rounded.PhoneIphone, "Connect a phone", TransferService.state.value.base) { vm.show(Screen.TRANSFER) } }
            item {
                SettingRow(Icons.Rounded.PowerSettingsNew, "Start when the TV turns on", if (autoStart) "On" else "Off") {
                    AutoStart.setEnabled(context, !autoStart); changed()
                }
            }
            item { SettingRow(Icons.Rounded.Refresh, "Reset pairing", null, danger = true) { dialog = SettingsDialog.ResetPairing } }
            section("Phones")
            if (phones.isEmpty()) item { SettingRow(Icons.Rounded.Smartphone, "No phones yet", null) { vm.show(Screen.TRANSFER) } }
            phones.forEach { p ->
                item(key = "phone-" + p.id) {
                    SettingRow(Icons.Rounded.Smartphone, p.name, Format.date(p.lastSeen)) {
                        dialog = SettingsDialog.RemovePhone(p)
                    }
                }
            }
            section("Notifications")
            item {
                SettingRow(Icons.Rounded.NotificationsActive, "Pop-ups over other apps", if (overlay) "On" else "Off") {
                    if (!overlay) {
                        val r = Opener.firstThatStarts(
                            context,
                            listOf(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                            ),
                            "",
                        )
                        if (r is OpenResult.Failed) dialog = SettingsDialog.OverlayHelp
                    }
                }
            }
        },
        SettingsCategory("Look & screensaver", Icons.Rounded.Wallpaper) {
            section("Appearance")
            item {
                SettingRow(if (Palette.light) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Theme", if (Palette.light) "Light" else "Dark") {
                    ThemeSetting.toggle(context)
                }
            }
            section("Screensaver & background")
            item {
                SettingRow(Icons.Rounded.Wallpaper, "App background", Appearance.background?.let { File(it).name } ?: "None") {
                    if (Appearance.background != null) { Appearance.setBackground(context, null); vm.say("Background removed") }
                    else vm.say("In Files, press and hold on a photo and choose Set as app background")
                }
            }
            item {
                SettingRow(Icons.Rounded.PhotoLibrary, "Slideshow photos", Appearance.slideshowFolder?.let { File(it).name } ?: "Pictures & Camera") {
                    if (Appearance.slideshowFolder != null) Appearance.setSlideshowFolder(context, null)
                    else vm.say("In Files, press and hold on a folder and choose Use for photo slideshow")
                }
            }
            item { SettingRow(Icons.Rounded.Timer, "Show each photo for", "${Appearance.seconds} seconds") { Appearance.nextSpeed(context) } }
            item {
                SettingRow(Icons.Rounded.Bedtime, "Start slideshow when idle", if (Appearance.idleMinutes == 0) "Off" else "${Appearance.idleMinutes} min") {
                    Appearance.nextIdle(context)
                }
            }
            item { SettingRow(Icons.Rounded.Slideshow, "Start slideshow now", null) { SlideshowActivity.start(context) } }
            item {
                SettingRow(Icons.Rounded.Tv, "Use as the TV's screensaver", null) {
                    val r = Opener.firstThatStarts(context, listOf(Intent(Settings.ACTION_DREAM_SETTINGS)), "")
                    if (r is OpenResult.Failed) dialog = SettingsDialog.DreamHelp
                }
            }
        },
        SettingsCategory("Apps & storage", Icons.Rounded.Storage) {
            section("Storage & apps")
            roots.forEach { r ->
                item(key = "root-" + r.dir.path) {
                    SettingRow(
                        if (r.removable) Icons.Rounded.SdStorage else Icons.Rounded.Storage, r.title,
                        if (r.totalBytes > 0) "${Format.bytes(r.freeBytes)} free of ${Format.bytes(r.totalBytes)}" else null,
                    ) { vm.openRoot(r.dir, from = Screen.SETTINGS) }
                }
            }

            item { SettingRow(Icons.Rounded.FolderOpen, "Browse by type", "Videos, music, photos, documents…") { dialog = SettingsDialog.BrowseTypes } }
            item { SettingRow(Icons.Rounded.Apps, "Apps", null) { vm.openApps(Screen.SETTINGS) } }
            item { SettingRow(Icons.Rounded.Cloud, "iCloud", "Mail, Drive and Photos", brand = R.drawable.brand_icloud) { dialog = SettingsDialog.ICloud } }
            section("Video calls")
            item { SettingRow(Icons.Rounded.Videocam, "Google Meet", null, brand = R.drawable.brand_google_meet) { Handoff.launch(context, Handoff.MEET) } }
            item { SettingRow(Icons.Rounded.Cast, "AirScreen (iPhone camera & screen)", null) { Handoff.launch(context, Handoff.AIRSCREEN) } }
        },
        SettingsCategory("Updates", Icons.Rounded.SystemUpdate) {
            section("Updates")
            item {
                SettingRow(Icons.Rounded.SystemUpdate, "Woodruff Family ${BuildConfig.VERSION_NAME}", updateStatus(update)) {
                    if (update.step == Updater.Step.READY) startUpdate(context, vm) else Updater.checkNow(context)
                }
            }
            item {
                SettingRow(Icons.Rounded.Autorenew, "Install updates automatically", if (autoUpdate) "On" else "Off") {
                    Updater.setAuto(context, !autoUpdate); changed()
                }
            }
            item {
                // One TV gets each update first; the rest wait a day so a bad build never reaches them all.
                SettingRow(Icons.Rounded.Science, "Test TV", if (testTv) "On · gets updates first" else "Off · updates a day after the test TV") {
                    Updater.setTestTv(context, !testTv); changed()
                }
            }
            section("About")
            item { SettingRow(Icons.Rounded.Info, "Posters", "TMDB (not endorsed by TMDB)") {} }
        },
    )
    var category by rememberSaveable { mutableIntStateOf(0) }
    // Holding Down through the categories shouldn't rebuild the right side for each one passed:
    // it switches once the focus rests (or at once on OK).
    var resting by remember { mutableIntStateOf(-1) }
    LaunchedEffect(resting) { if (resting >= 0) { kotlinx.coroutines.delay(200); category = resting } }
    val pane = remember { FocusRequester() }
    // Each category, so Left from the settings goes back to the one they belong to (a plain
    // geometric move landed on whichever category was level with the row, and switched to it).
    val catFocus = remember(categories.size) { List(categories.size) { FocusRequester() } }
    // Into the settings once the chosen category is drawn there (straight away, they'd still be the
    // previous category's rows, swapped out from under the focus a moment later).
    val enterPane: () -> Unit = {
        scope.launch {
            androidx.compose.runtime.withFrameNanos {}
            runCatching { pane.requestFocus() }
        }
    }
    Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 20.dp)) {
        // Scrolls (and follows the focus) when the categories don't all fit, so none is ever cut off.
        Column(
            Modifier.width(300.dp).fillMaxHeight()
                // Right: the category the focus is on, even within the 200 ms before it would switch.
                .onPreviewKeyEvent { e ->
                    if (e.key == androidx.compose.ui.input.key.Key.DirectionRight) {
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                            if (resting >= 0) category = resting
                            enterPane()
                        }
                        true
                    } else false
                }
                .verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            categories.forEachIndexed { i, c ->
                ListItem(
                    selected = i == category,
                    onClick = { category = i; resting = i; enterPane() },
                    modifier = Modifier.focusRequester(catFocus[i]).onFocusChanged { if (it.isFocused) resting = i },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        selectedContainerColor = Palette.surface,
                        focusedContainerColor = Palette.surfaceHigh,
                        focusedContentColor = Palette.text,
                        selectedContentColor = Palette.text,
                    ),
                    shape = ListItemDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                    scale = ListItemDefaults.scale(focusedScale = 1.02f),
                    leadingContent = { TvIcon(c.icon, size = 20.dp) },
                    headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        // The chosen category's settings. A fresh scroll position per category, starting at the top.
        val list = remember(category) { LazyListState() }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp).focusRequester(pane)
                .focusProperties {
                    exit = { d -> if (d == androidx.compose.ui.focus.FocusDirection.Left) catFocus[category] else FocusRequester.Default }
                }
                .focusGroup(),
            state = list,
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "title-" + category) {
                Text(categories[category].name, style = MaterialTheme.typography.headlineSmall, color = Palette.muted, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp))
            }
            categories[category].content(this)
        }
    }

    if (showCalendar) CalendarConnectDialog(onDismiss = { showCalendar = false })
    if (showPhotos) GooglePhotosDialog(onDismiss = { showPhotos = false }, onSetUpGoogle = { vm.show(Screen.DRIVE) })

    when (val d = dialog) {
        null -> Unit
        SettingsDialog.ClientId -> TextInputDialog(
            "Google Client ID", clientId.orEmpty(), "Save",
            onConfirm = { id ->
                if (!id.endsWith(".apps.googleusercontent.com")) vm.say("A Client ID ends in .apps.googleusercontent.com")
                else { GoogleAuth.saveClientId(context, id); changed() }
            },
            onDismiss = { dialog = null },
            hint = "From Google Cloud → Credentials → your Web application client.",
        )
        SettingsDialog.ClientSecret -> TextInputDialog(
            "Google Client secret", "", "Save",
            onConfirm = { secret -> GoogleAuth.saveClientSecret(context, secret); changed() },
            onDismiss = { dialog = null }, secret = true, hint = "Usually starts with GOCSPX-",
        )
        SettingsDialog.RemoveGoogle -> ConfirmDialog(
            "Remove Google keys?", "Signs out of Google Drive and deletes the saved Client ID and secret from this TV.", "Remove",
            onConfirm = { GoogleAuth.resetClient(context); changed() }, onDismiss = { dialog = null },
        )
        SettingsDialog.ResetPairing -> ConfirmDialog(
            "Reset pairing?", "Every phone, bookmark and Shortcut will need to scan the new QR code.", "Reset",
            onConfirm = {
                PairingKey.reset(context)
                scope.launch {
                    TransferService.stop(context)
                    TransferService.state.first { !it.running }
                    TransferService.start(context)
                    vm.say("Pairing reset. Scan the new QR code from Phone.")
                }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.PickProvider -> ActionMenu(
            "Add a mail account", "You'll need an app-specific password, not your normal one.",
            MailProvider.entries.map { p -> MenuAction(p.label, Icons.Rounded.Email) { dialog = SettingsDialog.MailEmail(p) } },
            onDismiss = { dialog = null },
        )
        is SettingsDialog.MailEmail -> TextInputDialog(
            "${d.provider.label}: email address", "", "Next",
            onConfirm = { dialog = SettingsDialog.MailPassword(d.provider, it) }, onDismiss = { dialog = null },
        )
        is SettingsDialog.MailPassword -> TextInputDialog(
            "App-specific password", "", if (d.provider == MailProvider.CUSTOM) "Next" else "Add",
            onConfirm = { pw ->
                if (d.provider == MailProvider.CUSTOM) dialog = SettingsDialog.MailHost(d.provider, d.email, pw)
                else addMail(context, vm, d.provider, d.email, pw, null) { changed() }
            },
            onDismiss = { dialog = null }, secret = true, hint = d.provider.passwordHelp,
        )
        is SettingsDialog.MailHost -> TextInputDialog(
            "IMAP server", "imap.", "Add",
            onConfirm = { host -> addMail(context, vm, d.provider, d.email, d.password, host) { changed() } },
            onDismiss = { dialog = null }, hint = "Port 993 with SSL is used.",
        )
        is SettingsDialog.RemoveMail -> ConfirmDialog(
            "Remove ${d.account.email}?", "Deletes this account and its saved password from the TV.", "Remove",
            onConfirm = { MailAccounts.remove(context, d.account.id); changed() }, onDismiss = { dialog = null },
        )
        SettingsDialog.TvName -> TextInputDialog(
            "This TV's name", tvName, "Save",
            onConfirm = { name ->
                TvLinks.setName(context, name)
                TransferService.state.value.base?.substringAfterLast(':')?.toIntOrNull()?.let { TvLinks.advertise(context, it) }
                changed()
            },
            onDismiss = { dialog = null }, hint = "Shown to your other TVs and on the phone, e.g. Living Room.",
        )
        SettingsDialog.LinkCode -> LinkCodeDialog(onDismiss = { dialog = null })
        SettingsDialog.AddTv -> AddTvDialog(onDismiss = { dialog = null }, onResult = { vm.say(it); changed() })
        is SettingsDialog.RemoveTv -> ConfirmDialog(
            "Unlink ${d.tv.name}?", "This TV stops showing what ${d.tv.name} is playing.", "Unlink",
            onConfirm = { TvLinks.remove(context, d.tv.id) }, onDismiss = { dialog = null },
        )
        SettingsDialog.ICloud -> ICloudDialog(onDismiss = { dialog = null }, onStart = { dialog = null; vm.show(Screen.TRANSFER) })
        SettingsDialog.BrowseTypes -> ActionMenu(
            title = "Browse by type", subtitle = "Everything of that kind on this TV and attached drives",
            onDismiss = { dialog = null },
            actions = listOf(
                FileKind.VIDEO to "Videos", FileKind.AUDIO to "Music", FileKind.IMAGE to "Photos",
                FileKind.DOCUMENT to "Documents", FileKind.APK to "APK files", FileKind.ARCHIVE to "Archives",
            ).map { (kind, title) -> MenuAction(title, kind.icon()) { vm.open(Location.Category(kind, title)) } } +
                MenuAction("Downloads", Icons.Rounded.Download) { vm.openDownloads() },
        )
        SettingsDialog.UnlinkHubs -> ConfirmDialog(
            "Stop other TVs seeing this one?", "Every TV linked to this one loses access. Link again with a new code.", "Stop",
            onConfirm = { TvLinks.resetLinkKey(context); vm.say("Other TVs can no longer see this one") }, onDismiss = { dialog = null },
        )
        SettingsDialog.MediaAccessHelp -> ConfirmDialog(
            "Allow with adb",
            "This TV hides the Notification access setting. From a computer with adb connected to the TV, run:\n\n" +
                "adb shell cmd notification allow_listener ${context.packageName}/com.woodruff.tvexplorer.nowplaying.MediaWatcher",
            "OK", onConfirm = {}, onDismiss = { dialog = null },
        )
        SettingsDialog.TmdbKey -> TextInputDialog(
            "TMDB API key", "", "Save",
            onConfirm = { key -> Tmdb.saveKey(context, key); changed() },
            onDismiss = { dialog = null }, secret = true,
            hint = "Free at themoviedb.org → Settings → API. The API Key or the Read Access Token both work.",
        )
        SettingsDialog.NestProject -> TextInputDialog(
            "Device Access Project ID", GoogleAuth.nestProject(context).orEmpty(), "Save",
            onConfirm = { id ->
                try { GoogleAuth.saveNestProject(context, id); changed() } catch (e: IllegalArgumentException) { vm.say(e.message ?: "Check the Project ID") }
            },
            onDismiss = { dialog = null },
            hint = "From console.nest.google.com/device-access → your project.",
        )
        SettingsDialog.DreamHelp -> ConfirmDialog(
            "Set the screensaver with adb",
            "This TV hides the screensaver picker. From a computer with adb connected to the TV, run:\n\n" +
                "adb shell settings put secure screensaver_components ${context.packageName}/com.woodruff.tvexplorer.screensaver.SlideshowDream\n\n" +
                "Or turn on \"Start slideshow when idle\" to use it inside this app.",
            "OK", onConfirm = {}, onDismiss = { dialog = null },
        )
        is SettingsDialog.RemovePhone -> ConfirmDialog(
            "Forget ${d.phone.name}?", "It stops getting announcements until it opens Woodruff again.", "Forget",
            onConfirm = { Phones.remove(context, d.phone.id) }, onDismiss = { dialog = null },
        )
        SettingsDialog.OverlayHelp -> ConfirmDialog(
            "Allow pop-ups with adb",
            "This TV hides the setting. From a computer with adb connected to the TV, run:\n\nadb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
            "OK", onConfirm = {}, onDismiss = { dialog = null },
        )
    }
}

/** Signs in once before saving, so a wrong password is caught now rather than at the next check. */
private fun addMail(
    context: android.content.Context, vm: MainViewModel,
    provider: MailProvider, email: String, password: String, host: String?, done: () -> Unit,
) {
    vm.say("Checking ${provider.label}…")
    kotlinx.coroutines.MainScope().launch {
        try {
            val account = MailAccounts.add(context, provider, email, password, host, null)
            try {
                MailClient.test(account)
            } catch (e: Exception) {
                MailAccounts.remove(context, account.id)
                throw e
            }
            vm.say("Added $email")
            done()
        } catch (e: Exception) {
            vm.say(e.message ?: "Couldn't add that account")
        }
    }
}

/** iOS Settings-style badge colors (Apple's system palette), picked by what the row is about. */
private fun badgeColor(icon: ImageVector, danger: Boolean): Color {
    if (danger) return Color(0xFFFF453A)
    return when (icon) {
        Icons.Rounded.LightMode, Icons.Rounded.DarkMode, Icons.Rounded.Bedtime -> Color(0xFF5E5CE6)
        Icons.Rounded.Wallpaper, Icons.Rounded.Slideshow -> Color(0xFFBF5AF2)
        Icons.Rounded.PhotoLibrary -> Color(0xFFFF375F)
        Icons.Rounded.Timer, Icons.Rounded.Refresh -> Color(0xFFFF9F0A)
        Icons.Rounded.Videocam, Icons.Rounded.NotificationsActive -> Color(0xFFFF453A)
        Icons.Rounded.Dashboard, Icons.Rounded.Tv -> Color(0xFF40C8E0)
        Icons.Rounded.PhoneIphone, Icons.Rounded.Smartphone -> Color(0xFF30D158)
        Icons.Rounded.Key, Icons.Rounded.Lock, Icons.Rounded.Logout, Icons.Rounded.Info -> Color(0xFF8E8E93)
        Icons.Rounded.Storage, Icons.Rounded.SdStorage -> Color(0xFF636366)
        Icons.Rounded.Apps -> Color(0xFF34C759)
        Icons.Rounded.Link, Icons.Rounded.Add -> Color(0xFF5E5CE6)
        Icons.Rounded.PlayCircle -> Color(0xFFFF9F0A)
        Icons.Rounded.Cast -> Color(0xFF0A84FF)
        Icons.Rounded.Email, Icons.Rounded.PersonAdd -> Color(0xFF0A84FF)
        else -> Palette.accent
    }
}

/** One entry in the Settings list on the left, and the rows it shows on the right. */
private class SettingsCategory(val name: String, val icon: ImageVector, val content: LazyListScope.() -> Unit)

private fun mask(s: String, keep: Int) = if (s.length <= keep) s else s.take(keep) + "…"

private fun LazyListScope.section(title: String) {
    item {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted,
            modifier = Modifier.padding(start = 14.dp, top = 22.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String?, danger: Boolean = false, brand: Int? = null, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        colors = ListItemDefaults.colors(
            containerColor = Palette.surface,
            focusedContainerColor = Palette.surfaceHigh,
            focusedContentColor = Palette.text,
        ),
        shape = ListItemDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
        scale = ListItemDefaults.scale(focusedScale = 1.01f),
        leadingContent = {
            if (brand != null) Image(painterResource(brand), contentDescription = null, modifier = Modifier.size(30.dp))
            else IconBadge(icon, badgeColor(icon, danger), size = 30.dp)
        },
        headlineContent = { Text(title, color = if (danger) Palette.danger else androidx.compose.ui.graphics.Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (value != null) {
            { Text(value, color = Palette.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
    )
}


/**
 * Apple offers no way for Android apps to sign in to iCloud, so this explains the reliable route:
 * the iPhone's own file picker can upload straight from iCloud Drive and Photos to "Send files".
 */
@Composable
private fun ICloudDialog(onDismiss: () -> Unit, onStart: () -> Unit) {
    DialogFrame("iCloud on your TV", onDismiss, width = 640) {
        Text(
            "iCloud Mail: add it in Settings → Mail with an app-specific password from account.apple.com.\n\n" +
                "iCloud Drive and Photos: Apple doesn't offer Android apps a way to sign in to iCloud Drive, so send files from your iPhone or iPad:",
            color = Palette.muted,
        )
        listOf(
            "1.  Choose Start below to show the Phone QR code.",
            "2.  Scan it with the iPhone Camera app (once: it stays paired).",
            "3.  In Files, tap +, then Upload Photos or Files, and choose iCloud Drive or your photo library.",
            "4.  Files land in the folder you picked, ready to play here.",
        ).forEach { Text(it) }
        ButtonRow {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
            Button(onClick = onStart) { Text("Start") }
        }
    }
}
