package com.woodruff.tvexplorer.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.tv.material3.LocalContentColor
import com.woodruff.tvexplorer.ui.SmoothEase
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.foundation.layout.size
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
 * Settings, the Apple TV way: one short list at a time. The first page is a handful of groups; OK
 * (or Right) opens one in its place, and Back (or Left) returns to the row it came from. The left
 * side only shows where you are. (Every category and all of its settings on screen at once was a
 * lot to take in.) Secrets (Google keys, mail passwords, tokens) are stored encrypted on the TV and
 * survive restarts and updates.
 */
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

    // The pages opened from the first one, in order: the last is on screen.
    var path by remember { mutableStateOf(emptyList<SettingsPage>()) }
    // Page changes so far, and the page Back just left (the row that opened it takes the focus again).
    var moves by remember { mutableIntStateOf(0) }
    var left by remember { mutableStateOf<SettingsPage?>(null) }
    val open: (SettingsPage) -> Unit = { p -> left = null; path = path + p; moves++ }
    val back: () -> Unit = { left = path.last(); path = path.dropLast(1); moves++ }
    BackHandler { if (path.isEmpty()) openSidebar() else back() }
    val here = path.lastOrNull() ?: SettingsPage.HOME

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
    val doorbellStatus by Doorbell.status.collectAsStateWithLifecycle()
    // Asked of the other TVs over the network, so only while their page is open.
    val otherTvs by produceState(emptyList<com.woodruff.tvexplorer.tvs.TvStatus>(), v, here) {
        if (here == SettingsPage.TVS) value = runCatching { TvLinks.statuses(context) }.getOrDefault(emptyList())
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
    // Slow frames per screen since the app started (read afresh each time a page opens).
    var smoothness by remember(moves) { mutableStateOf(com.woodruff.tvexplorer.util.FrameStats.summary()) }

    fun rows(page: SettingsPage): List<SettingItem> = when (page) {
        SettingsPage.HOME -> {
            val linked = listOf(connected, photosConnected, calendar.connected, nestConnected).count { it } + accounts.size
            listOf(
                opener(SettingsPage.ACCOUNTS, if (linked == 0) "Not set up" else "$linked connected"),
                opener(SettingsPage.LOOK, if (Palette.light) "Light" else "Dark"),
                opener(SettingsPage.SCREENSAVER, if (Appearance.idleMinutes == 0) "Off" else "After ${Appearance.idleMinutes} min"),
                opener(SettingsPage.DEVICES, tvName),
                opener(SettingsPage.STORAGE, roots.firstOrNull { it.totalBytes > 0 }?.let { "${Format.bytes(it.freeBytes)} free" }),
                opener(SettingsPage.UPDATES, updateStatus(update)),
                opener(SettingsPage.ADVANCED, null, spaced = true),
            )
        }
        SettingsPage.ACCOUNTS -> listOf(
            SettingItem(Icons.Rounded.AddToDrive, "Google Drive", if (connected) "Signed in" else if (configured) "Not signed in" else "Not set up") { vm.show(Screen.DRIVE) },
            SettingItem(Icons.Rounded.PhotoLibrary, "Google Photos", if (photosConnected) "Connected" else "Connect") { showPhotos = true },
            SettingItem(Icons.Rounded.Event, "Google Calendar", if (calendar.connected) "Connected" else "Connect") {
                if (!calendar.connected) showCalendar = true
            },
            SettingItem(Icons.Rounded.Videocam, "Cameras", if (nestConnected) "Connected" else "Not connected") { vm.show(Screen.CAMERAS) },
            opener(SettingsPage.MAIL, count(accounts.size, "account", "None")),
            opener(SettingsPage.KEYS, null, spaced = true),
        )
        SettingsPage.MAIL -> accounts.map { a ->
            SettingItem(Icons.Rounded.Email, a.email, a.provider.label) { dialog = SettingsDialog.RemoveMail(a) }
        } + SettingItem(Icons.Rounded.PersonAdd, "Add a mail account", spaced = accounts.isNotEmpty()) { dialog = SettingsDialog.PickProvider }
        SettingsPage.KEYS -> buildList {
            add(SettingItem(Icons.Rounded.Key, "Google Client ID", clientId?.let { mask(it, 18) } ?: "Not set") { dialog = SettingsDialog.ClientId })
            add(SettingItem(Icons.Rounded.Lock, "Google Client secret", if (configured) "Saved" else "Not set") { dialog = SettingsDialog.ClientSecret })
            add(SettingItem(Icons.Rounded.Key, "Cameras project ID", nestProject?.let { mask(it, 13) } ?: "Not set") { dialog = SettingsDialog.NestProject })
            add(SettingItem(Icons.Rounded.Key, "TMDB key (posters)", if (tmdbSet) "Saved" else "Not set") { dialog = SettingsDialog.TmdbKey })
            // Signing out, set apart from the keys.
            var gap = true
            fun out(on: Boolean, title: String, action: () -> Unit) {
                if (on) { add(SettingItem(Icons.Rounded.Logout, title, spaced = gap, onClick = action)); gap = false }
            }
            out(connected, "Sign out of Google") { scope.launch { GoogleAuth.signOut(context); changed() } }
            out(photosConnected, "Disconnect Google Photos") { GooglePhotos.signOut { changed() } }
            out(calendar.connected, "Disconnect Google Calendar") { TodayCalendar.signOut { changed() } }
            out(nestConnected, "Disconnect cameras") { Nest.signOut { changed() } }
            if (configured) add(SettingItem(Icons.Rounded.Delete, "Remove Google keys", danger = true, spaced = gap) { dialog = SettingsDialog.RemoveGoogle })
        }
        SettingsPage.LOOK -> listOf(
            SettingItem(if (Palette.light) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Theme", if (Palette.light) "Light" else "Dark") { ThemeSetting.toggle(context) },
            SettingItem(Icons.Rounded.Wallpaper, "Background photo", Appearance.background?.let { File(it).name } ?: "None") {
                if (Appearance.background != null) { Appearance.setBackground(context, null); vm.say("Background removed") }
                else vm.say("In Files, press and hold on a photo and choose Set as app background")
            },
            // Turn off on extra TVs if Google starts limiting camera requests.
            SettingItem(Icons.Rounded.Videocam, "Cameras on Home", Appearance.homeCameras.label) { Appearance.nextHomeCameras(context) },
        )
        SettingsPage.SCREENSAVER -> listOf(
            SettingItem(Icons.Rounded.Bedtime, "Start when idle", if (Appearance.idleMinutes == 0) "Off" else "After ${Appearance.idleMinutes} min") { Appearance.nextIdle(context) },
            SettingItem(Icons.Rounded.PhotoLibrary, "Photos", Appearance.slideshowFolder?.let { File(it).name } ?: "Pictures & Camera") {
                if (Appearance.slideshowFolder != null) Appearance.setSlideshowFolder(context, null)
                else vm.say("In Files, press and hold on a folder and choose Use for photo slideshow")
            },
            SettingItem(Icons.Rounded.Timer, "Each photo for", "${Appearance.seconds} seconds") { Appearance.nextSpeed(context) },
            SettingItem(Icons.Rounded.Slideshow, "Start now", spaced = true) { SlideshowActivity.start(context) },
            SettingItem(Icons.Rounded.Tv, "Use as the TV's screensaver") {
                val r = Opener.firstThatStarts(context, listOf(Intent(Settings.ACTION_DREAM_SETTINGS)), "")
                if (r is OpenResult.Failed) dialog = SettingsDialog.DreamHelp
            },
        )
        SettingsPage.DEVICES -> listOf(
            SettingItem(Icons.Rounded.Tv, "This TV's name", tvName) { dialog = SettingsDialog.TvName },
            SettingItem(Icons.Rounded.PhoneIphone, "Connect a phone") { vm.show(Screen.TRANSFER) },
            opener(SettingsPage.PHONES, count(phones.size, "phone", "None yet")),
            opener(SettingsPage.TVS, if (linkedTvs.isEmpty()) "None linked" else "${linkedTvs.size} linked"),
            SettingItem(Icons.Rounded.PowerSettingsNew, "Start when the TV turns on", onOff(autoStart)) { AutoStart.setEnabled(context, !autoStart); changed() },
        )
        SettingsPage.PHONES ->
            if (phones.isEmpty()) listOf(SettingItem(Icons.Rounded.PhoneIphone, "Connect a phone") { vm.show(Screen.TRANSFER) })
            else phones.map { p -> SettingItem(Icons.Rounded.Smartphone, p.name, Format.date(p.lastSeen)) { dialog = SettingsDialog.RemovePhone(p) } }
        SettingsPage.TVS -> buildList {
            add(SettingItem(Icons.Rounded.Add, "Add another TV") { dialog = SettingsDialog.AddTv })
            linkedTvs.forEach { tv ->
                val s = otherTvs.firstOrNull { it.id == tv.id }
                val status = when {
                    s == null -> null
                    s.online -> "Online" + (s.version?.let { " · $it" } ?: "")
                    else -> "Off or unreachable"
                }
                add(SettingItem(Icons.Rounded.Tv, tv.name, status) { dialog = SettingsDialog.RemoveTv(tv) })
            }
            add(SettingItem(Icons.Rounded.Link, "Link this TV to another", spaced = true) { dialog = SettingsDialog.LinkCode })
            add(SettingItem(Icons.Rounded.LinkOff, "Stop other TVs seeing this one", danger = true) { dialog = SettingsDialog.UnlinkHubs })
        }
        SettingsPage.STORAGE -> roots.map { r ->
            SettingItem(
                if (r.removable) Icons.Rounded.SdStorage else Icons.Rounded.Storage, r.title,
                if (r.totalBytes > 0) "${Format.bytes(r.freeBytes)} free of ${Format.bytes(r.totalBytes)}" else null,
            ) { vm.openRoot(r.dir, from = Screen.SETTINGS) }
        } + listOf(
            SettingItem(Icons.Rounded.FolderOpen, "Browse by type", "Videos, music, photos…", spaced = roots.isNotEmpty()) { dialog = SettingsDialog.BrowseTypes },
            SettingItem(Icons.Rounded.Apps, "Apps") { vm.openApps(Screen.SETTINGS) },
            SettingItem(Icons.Rounded.Cloud, "iCloud") { dialog = SettingsDialog.ICloud },
            SettingItem(Icons.Rounded.VideoCall, "Google Meet", spaced = true) { Handoff.launch(context, Handoff.MEET) },
            SettingItem(Icons.Rounded.Cast, "AirScreen", "iPhone camera & screen") { Handoff.launch(context, Handoff.AIRSCREEN) },
        )
        SettingsPage.UPDATES -> listOf(
            SettingItem(Icons.Rounded.SystemUpdate, "Version ${BuildConfig.VERSION_NAME}", updateStatus(update)) {
                if (update.step == Updater.Step.READY) startUpdate(context, vm) else Updater.checkNow(context)
            },
            SettingItem(Icons.Rounded.Autorenew, "Install updates automatically", onOff(autoUpdate)) { Updater.setAuto(context, !autoUpdate); changed() },
            // One TV gets each update first; the rest wait a day so a bad build never reaches them all.
            SettingItem(Icons.Rounded.Science, "Test TV", if (testTv) "On · gets updates first" else "Off") { Updater.setTestTv(context, !testTv); changed() },
        )
        SettingsPage.ADVANCED -> listOf(
            // OK starts counting afresh.
            SettingItem(Icons.Rounded.Speed, "Smoothness", "Start over", detail = smoothness) {
                com.woodruff.tvexplorer.util.FrameStats.reset(); smoothness = com.woodruff.tvexplorer.util.FrameStats.summary()
            },
            SettingItem(Icons.Rounded.NotificationsActive, "Doorbell alerts", doorbellStatus ?: if (Doorbell.subscription(context) != null) "Watching" else "Set up in the phone app"),
            SettingItem(Icons.Rounded.NotificationsActive, "Pop-ups over other apps", onOff(overlay)) {
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
            },
            SettingItem(Icons.Rounded.PlayCircle, "See what other apps are playing", onOff(seesOtherApps)) {
                val r = Opener.firstThatStarts(context, listOf(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")), "")
                if (r is OpenResult.Failed) dialog = SettingsDialog.MediaAccessHelp
            },
            SettingItem(Icons.Rounded.Refresh, "Reset phone pairing", danger = true, spaced = true) { dialog = SettingsDialog.ResetPairing },
            SettingItem(Icons.Rounded.Info, "Posters", "From TMDB (not endorsed by TMDB)", spaced = true),
        )
    }

    Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 64.dp)) {
        // Where you are: the page's symbol and name.
        Crossfade(here, Modifier.width(360.dp).fillMaxHeight(), animationSpec = tween(260), label = "settings-where") { p ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                TvIcon(p.icon, size = 132.dp, tint = Palette.text)
                Text(p.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 22.dp))
                if (p == SettingsPage.HOME) {
                    Text("Version ${BuildConfig.VERSION_NAME}", color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        AnimatedContent(
            path,
            Modifier.weight(1f).fillMaxHeight()
                // Left goes back a page (on the first page it brings up the sidebar, as everywhere).
                .onPreviewKeyEvent { e ->
                    if (e.key == androidx.compose.ui.input.key.Key.DirectionLeft && path.isNotEmpty()) {
                        if (e.type == KeyEventType.KeyDown) back()
                        true
                    } else false
                },
            transitionSpec = {
                val dir = if (targetState.size >= initialState.size) 1 else -1
                (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(320, easing = SmoothEase)) { dir * it / 10 }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(320, easing = SmoothEase)) { -dir * it / 10 }) using
                    SizeTransform(clip = false)
            },
            label = "settings-page",
        ) { shown ->
            val items = rows(shown.lastOrNull() ?: SettingsPage.HOME)
            // The first row of a page just opened; after Back, the row that opened the page left.
            val focusOn = if (shown != path || moves == 0) null else items.indexOfFirst { it.opens != null && it.opens == left }.coerceAtLeast(0)
            SettingsList(items, focusOn, moves, open)
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

/** The pages of Settings: the first, and the ones opened from it. */
private enum class SettingsPage(val title: String, val icon: ImageVector) {
    HOME("Settings", Icons.Rounded.SettingsIcon),
    ACCOUNTS("Accounts", Icons.Rounded.AccountCircle),
    MAIL("Mail", Icons.Rounded.Email),
    KEYS("Keys & sign-out", Icons.Rounded.Key),
    LOOK("Appearance", Icons.Rounded.DarkMode),
    SCREENSAVER("Screensaver", Icons.Rounded.Slideshow),
    DEVICES("TVs & phones", Icons.Rounded.Tv),
    PHONES("Phones", Icons.Rounded.Smartphone),
    TVS("Other TVs", Icons.Rounded.Link),
    STORAGE("Storage & apps", Icons.Rounded.Storage),
    UPDATES("Updates", Icons.Rounded.SystemUpdate),
    ADVANCED("Advanced", Icons.Rounded.Tune),
}

/** One row. A row that [opens] a page shows a chevron; any other runs [onClick]. [spaced]: a gap above it. */
private class SettingItem(
    val icon: ImageVector, val title: String, val value: String? = null,
    val detail: String? = null, val danger: Boolean = false,
    val opens: SettingsPage? = null, val spaced: Boolean = false, val onClick: () -> Unit = {},
)

/** The row that opens [page], named and pictured like the page. */
private fun opener(page: SettingsPage, value: String?, spaced: Boolean = false) =
    SettingItem(page.icon, page.title, value, opens = page, spaced = spaced)

private fun onOff(on: Boolean) = if (on) "On" else "Off"

private fun count(n: Int, one: String, none: String) = when (n) {
    0 -> none
    1 -> "1 $one"
    else -> "$n ${one}s"
}

private fun mask(s: String, keep: Int) = if (s.length <= keep) s else s.take(keep) + "…"

/** One page's rows. [focusOn]: the row that takes the focus as the page appears after a move. */
@Composable
private fun SettingsList(items: List<SettingItem>, focusOn: Int?, moves: Int, onOpen: (SettingsPage) -> Unit) {
    val focus = remember(items.size) { List(items.size) { FocusRequester() } }
    var focused by remember { mutableIntStateOf(0) }
    LaunchedEffect(moves) {
        if (focusOn == null) return@LaunchedEffect
        withFrameNanos {} // once the page is laid out
        (focus.getOrNull(focusOn) ?: focus.firstOrNull())?.let { runCatching { it.requestFocus() } }
    }
    Column(
        Modifier.fillMaxSize()
            // Right opens a group too, like stepping into it.
            .onPreviewKeyEvent { e ->
                val opens = items.getOrNull(focused)?.opens
                if (e.key == androidx.compose.ui.input.key.Key.DirectionRight && opens != null) {
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onOpen(opens)
                    true
                } else false
            }
            .verticalScroll(rememberScrollState())
            .padding(top = 72.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { i, item ->
            SettingRow(
                item,
                Modifier.padding(top = if (item.spaced) 22.dp else 0.dp)
                    .focusRequester(focus[i])
                    .onFocusChanged { if (it.isFocused) focused = i },
            ) { item.opens?.let(onOpen) ?: item.onClick() }
        }
    }
}

/** A row like the Apple TV's: plain until focused, then white with dark text. */
@Composable
private fun SettingRow(item: SettingItem, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Palette.background,
        ),
        shape = ListItemDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
        scale = ListItemDefaults.scale(focusedScale = 1.03f),
        // Plain symbols in the row's color (dark on the white focus), Apple style.
        leadingContent = { TvIcon(item.icon, size = 26.dp) },
        headlineContent = {
            Text(item.title, color = if (item.danger) Palette.danger else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (item.detail != null) {
            { Text(item.detail, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f), maxLines = 4) }
        } else null,
        trailingContent = if (item.value != null || item.opens != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.value != null) {
                        Text(
                            item.value, color = LocalContentColor.current.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp),
                        )
                    }
                    if (item.opens != null) {
                        TvIcon(Icons.Rounded.ChevronRight, Modifier.padding(start = 6.dp), size = 22.dp, tint = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                }
            }
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
