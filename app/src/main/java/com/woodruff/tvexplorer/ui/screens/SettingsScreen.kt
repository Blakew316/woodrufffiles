package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.tvs.LinkedTv
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.server.AutoStart
import com.woodruff.tvexplorer.update.Updater
import androidx.compose.runtime.produceState
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.calendar.TodayCalendar
import com.woodruff.tvexplorer.util.Handoff
import com.woodruff.tvexplorer.photos.GooglePhotos
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
            SettingItem(
                Sym.AddToDrive, "Google Drive", if (connected) "Signed in" else if (configured) "Not signed in" else "Not set up",
                about = "Movies, TV Shows and files from your Google Drive. OK opens it.",
            ) { vm.show(Screen.DRIVE) },
            SettingItem(
                Sym.PhotoLibrary, "Google Photos", if (photosConnected) "Connected" else "Connect",
                about = "Brings photos and videos into Family Memories.",
            ) { showPhotos = true },
            SettingItem(
                Sym.Event, "Google Calendar", if (calendar.connected) "Connected" else "Connect",
                about = "Today's events on Home.",
            ) { if (!calendar.connected) showCalendar = true },
            SettingItem(
                Sym.Videocam, "Cameras", if (nestConnected) "Connected" else "Not connected",
                about = "Live Google Home cameras in the Command Center. OK opens them.",
            ) { vm.show(Screen.CAMERAS) },
            opener(SettingsPage.MAIL, count(accounts.size, "account", "None")),
            opener(SettingsPage.KEYS, null, spaced = true),
        )
        SettingsPage.MAIL -> accounts.map { a ->
            SettingItem(Sym.Email, a.email, a.provider.label, about = "OK removes this account from the TV.") { dialog = SettingsDialog.RemoveMail(a) }
        } + SettingItem(
            Sym.PersonAdd, "Add a mail account", spaced = accounts.isNotEmpty(),
            about = "iCloud, Gmail, Outlook and others. You'll need an app-specific password, not your usual one.",
        ) { dialog = SettingsDialog.PickProvider }
        SettingsPage.KEYS -> buildList {
            add(SettingItem(Sym.Key, "Google Client ID", clientId?.let { mask(it, 18) } ?: "Not set", about = "From Google Cloud → Credentials. Drive, Photos, Calendar and cameras use it.") { dialog = SettingsDialog.ClientId })
            add(SettingItem(Sym.Lock, "Google Client secret", if (configured) "Saved" else "Not set", about = "Goes with the Client ID. Kept encrypted on this TV.") { dialog = SettingsDialog.ClientSecret })
            add(SettingItem(Sym.Key, "Cameras project ID", nestProject?.let { mask(it, 13) } ?: "Not set", about = "From Google Device Access. The cameras need it.") { dialog = SettingsDialog.NestProject })
            add(SettingItem(Sym.Key, "TMDB key", if (tmdbSet) "Saved" else "Not set", about = "From themoviedb.org. Fills in posters, ratings and episode pictures.") { dialog = SettingsDialog.TmdbKey })
            // Signing out, set apart from the keys.
            var gap = true
            fun out(on: Boolean, title: String, about: String, action: () -> Unit) {
                if (on) { add(SettingItem(Sym.Logout, title, spaced = gap, about = about, onClick = action)); gap = false }
            }
            out(connected, "Sign out of Google", "Signs this TV out of Google Drive.") { scope.launch { GoogleAuth.signOut(context); changed() } }
            out(photosConnected, "Disconnect Google Photos", "Stops adding from Google Photos. Photos already here stay.") { GooglePhotos.signOut { changed() } }
            out(calendar.connected, "Disconnect Google Calendar", "Stops showing today's events.") { TodayCalendar.signOut { changed() } }
            out(nestConnected, "Disconnect cameras", "Stops showing the cameras on this TV.") { Nest.signOut { changed() } }
            if (configured) add(SettingItem(Sym.Delete, "Remove Google keys", danger = true, spaced = gap, about = "Signs out and deletes the saved Client ID and secret from this TV.") { dialog = SettingsDialog.RemoveGoogle })
        }
        SettingsPage.LOOK -> listOf(
            SettingItem(if (Palette.light) Sym.LightMode else Sym.DarkMode, "Theme", if (Palette.light) "Light" else "Dark", about = "OK switches between light and dark.") { ThemeSetting.toggle(context) },
            SettingItem(
                Sym.Wallpaper, "Background photo", Appearance.background?.let { File(it).name } ?: "None",
                about = "To choose one, press and hold a photo in Files and pick Set as app background. OK removes it.",
            ) {
                if (Appearance.background != null) { Appearance.setBackground(context, null); vm.say("Background removed") }
                else vm.say("In Files, press and hold on a photo and choose Set as app background")
            },
            // Turn off on extra TVs if Google starts limiting camera requests.
            SettingItem(Sym.Videocam, "Cameras on Home", Appearance.homeCameras.label, about = "What Home shows of the cameras. OK changes it.") { Appearance.nextHomeCameras(context) },
        )
        SettingsPage.SCREENSAVER -> listOf(
            SettingItem(Sym.Bedtime, "Start when idle", if (Appearance.idleMinutes == 0) "Off" else "After ${Appearance.idleMinutes} min", about = "How long the TV sits unused before the slideshow starts. OK changes it.") { Appearance.nextIdle(context) },
            SettingItem(
                Sym.PhotoLibrary, "Photos", Appearance.slideshowFolder?.let { File(it).name } ?: "Pictures & Camera",
                about = "To choose a folder, press and hold it in Files and pick Use for photo slideshow. OK goes back to Pictures & Camera.",
            ) {
                if (Appearance.slideshowFolder != null) Appearance.setSlideshowFolder(context, null)
                else vm.say("In Files, press and hold on a folder and choose Use for photo slideshow")
            },
            SettingItem(Sym.Timer, "Each photo for", "${Appearance.seconds} seconds", about = "How long each photo stays up. OK changes it.") { Appearance.nextSpeed(context) },
            SettingItem(Sym.Slideshow, "Start now", spaced = true, about = "Starts the slideshow.") { SlideshowActivity.start(context) },
            SettingItem(Sym.Tv, "Use as the TV's screensaver", about = "Opens the TV's own screensaver settings to choose Woodruff Family.") {
                val r = Opener.firstThatStarts(context, listOf(Intent(Settings.ACTION_DREAM_SETTINGS)), "")
                if (r is OpenResult.Failed) dialog = SettingsDialog.DreamHelp
            },
        )
        SettingsPage.DEVICES -> listOf(
            SettingItem(Sym.Tv, "This TV's name", tvName, about = "Shown to your other TVs and on your phone.") { dialog = SettingsDialog.TvName },
            SettingItem(Sym.PhoneIphone, "Connect a phone", about = "Shows a QR code to scan with your phone's camera.") { vm.show(Screen.TRANSFER) },
            opener(SettingsPage.PHONES, count(phones.size, "phone", "None yet")),
            opener(SettingsPage.TVS, if (linkedTvs.isEmpty()) "None linked" else "${linkedTvs.size} linked"),
            SettingItem(Sym.PowerSettingsNew, "Start when the TV turns on", onOff(autoStart), about = "Opens Woodruff Family when the TV starts. OK turns it on or off.") { AutoStart.setEnabled(context, !autoStart); changed() },
        )
        SettingsPage.PHONES ->
            if (phones.isEmpty()) listOf(SettingItem(Sym.PhoneIphone, "Connect a phone", about = "Shows a QR code to scan with your phone's camera.") { vm.show(Screen.TRANSFER) })
            else phones.map { p -> SettingItem(Sym.Smartphone, p.name, Format.date(p.lastSeen), about = "Last seen ${Format.date(p.lastSeen)}. OK forgets this phone.") { dialog = SettingsDialog.RemovePhone(p) } }
        SettingsPage.TVS -> buildList {
            add(SettingItem(Sym.Add, "Add another TV", about = "Enter the code the other TV shows in its Settings.") { dialog = SettingsDialog.AddTv })
            linkedTvs.forEach { tv ->
                val s = otherTvs.firstOrNull { it.id == tv.id }
                val status = when {
                    s == null -> null
                    s.online -> "Online" + (s.version?.let { " · $it" } ?: "")
                    else -> "Off or unreachable"
                }
                add(SettingItem(Sym.Tv, tv.name, status, about = "OK unlinks this TV.") { dialog = SettingsDialog.RemoveTv(tv) })
            }
            add(SettingItem(Sym.Link, "Link this TV to another", spaced = true, about = "Shows a code to enter on the other TV.") { dialog = SettingsDialog.LinkCode })
            add(SettingItem(Sym.LinkOff, "Stop other TVs seeing this one", danger = true, about = "Every linked TV loses access. Link again with a new code.") { dialog = SettingsDialog.UnlinkHubs })
        }
        SettingsPage.STORAGE -> roots.map { r ->
            SettingItem(
                if (r.removable) Sym.SdStorage else Sym.Storage, r.title,
                if (r.totalBytes > 0) "${Format.bytes(r.freeBytes)} free" else null,
                about = (if (r.totalBytes > 0) "${Format.bytes(r.freeBytes)} free of ${Format.bytes(r.totalBytes)}. " else "") + "OK opens it.",
            ) { vm.openRoot(r.dir, from = Screen.SETTINGS) }
        } + listOf(
            SettingItem(Sym.FolderOpen, "Browse by type", spaced = roots.isNotEmpty(), about = "Every video, song, photo or document on this TV and attached drives.") { dialog = SettingsDialog.BrowseTypes },
            SettingItem(Sym.Apps, "Apps", about = "Open the apps on this TV.") { vm.openApps(Screen.SETTINGS) },
            SettingItem(Sym.Cloud, "iCloud", about = "How to bring iCloud mail, files and photos to this TV.") { dialog = SettingsDialog.ICloud },
            SettingItem(Sym.VideoCall, "Google Meet", spaced = true, about = "Video calls on the TV.") { Handoff.launch(context, Handoff.MEET) },
            SettingItem(Sym.Cast, "AirScreen", about = "Show an iPhone's camera or screen on the TV.") { Handoff.launch(context, Handoff.AIRSCREEN) },
        )
        SettingsPage.UPDATES -> listOf(
            SettingItem(Sym.SystemUpdate, "Version ${BuildConfig.VERSION_NAME}", updateStatus(update), about = "OK checks for an update, or installs one that's ready.") {
                if (update.step == Updater.Step.READY) startUpdate(context, vm) else Updater.checkNow(context)
            },
            SettingItem(Sym.Autorenew, "Install updates automatically", onOff(autoUpdate), about = "New versions install by themselves. OK turns it on or off.") { Updater.setAuto(context, !autoUpdate); changed() },
            // One TV gets each update first; the rest wait a day so a bad build never reaches them all.
            SettingItem(Sym.Science, "Test TV", onOff(testTv), about = "The test TV gets each update a day before the others, so a bad one never reaches them all.") { Updater.setTestTv(context, !testTv); changed() },
        )
        SettingsPage.ADVANCED -> listOf(
            SettingItem(Sym.Speed, "Smoothness", detail = smoothness, about = "Slow frames on each screen since the app started, and what they were spent on. OK starts counting again.") {
                com.woodruff.tvexplorer.util.FrameStats.reset(); smoothness = com.woodruff.tvexplorer.util.FrameStats.summary()
            },
            SettingItem(
                Sym.NotificationsActive, "Doorbell alerts", doorbellStatus ?: if (Doorbell.subscription(context) != null) "Watching" else "Off",
                about = "Doorbell presses pop up on this TV. Set it up in the phone app.",
            ),
            SettingItem(Sym.NotificationsActive, "Pop-ups over other apps", onOff(overlay), about = "Lets alerts show while another app is open.") {
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
            SettingItem(Sym.PlayCircle, "See what other apps are playing", onOff(seesOtherApps), about = "Lets Home and your other TVs show what's playing in other apps.") {
                val r = Opener.firstThatStarts(context, listOf(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")), "")
                if (r is OpenResult.Failed) dialog = SettingsDialog.MediaAccessHelp
            },
            SettingItem(Sym.Refresh, "Reset phone pairing", danger = true, spaced = true, about = "Every phone, bookmark and Shortcut will need to scan a new QR code.") { dialog = SettingsDialog.ResetPairing },
            SettingItem(
                Sym.Info, "Credits", "TMDB · Lucide", spaced = true,
                about = "Movie and show details and pictures come from TMDB; this app is not endorsed by TMDB. Symbols are from Lucide (ISC license).",
            ),
        )
    }

    // The row the focus is on (and its page), for the panel on the right.
    var hovered by remember { mutableStateOf<Pair<SettingsPage, Int>?>(null) }

    Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 56.dp)) {
        // The list, on the left.
        Column(Modifier.width(480.dp).fillMaxHeight()) {
            Crossfade(here, animationSpec = tween(220), label = "settings-title") { p ->
                Text(p.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 30.dp, bottom = 10.dp))
            }
            AnimatedContent(
                path,
                Modifier.weight(1f).fillMaxWidth()
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
                val page = shown.lastOrNull() ?: SettingsPage.HOME
                val items = rows(page)
                // The first row of a page just opened; after Back, the row that opened the page left.
                val focusOn = if (shown != path || moves == 0) null else items.indexOfFirst { it.opens != null && it.opens == left }.coerceAtLeast(0)
                // Only the page on screen opens another: until a new page takes the focus (a frame
                // later), a quick second press still reaches the one sliding out.
                SettingsList(items, focusOn, moves, onOpen = { p -> if (shown == path) open(p) }, onFocused = { i -> hovered = page to i })
            }
        }
        // On the right: what the focused row is about, following the focus (OK still does the choosing).
        // Until the page's row takes the focus, the row that will: after Back, the one that opened the page left.
        val showing = hovered?.takeIf { it.first == here }
            ?: (here to rows(here).indexOfFirst { it.opens != null && it.opens == left }.coerceAtLeast(0))
        Crossfade(showing, Modifier.weight(1f).fillMaxHeight().padding(start = 56.dp), animationSpec = tween(220), label = "settings-about") { (p, i) ->
            val item = rows(p).getOrNull(i)
            if (item != null) SettingAbout(item, item.opens?.let { rows(it) })
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
            MailProvider.entries.map { p -> MenuAction(p.label, Sym.Email) { dialog = SettingsDialog.MailEmail(p) } },
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
                MenuAction("Downloads", Sym.Download) { vm.openDownloads() },
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
private enum class SettingsPage(val title: String, val icon: ImageVector, val about: String) {
    HOME("Settings", Sym.Settings, ""),
    ACCOUNTS("Accounts", Sym.AccountCircle, "Google Drive, Photos, Calendar, cameras and mail."),
    MAIL("Mail", Sym.Email, "The mail accounts the Command Center shows."),
    KEYS("Keys & sign-out", Sym.Key, "The keys from Google and TMDB, and signing out."),
    LOOK("Appearance", Sym.DarkMode, "Light or dark, a background photo, and the cameras on Home."),
    SCREENSAVER("Screensaver", Sym.Slideshow, "A slideshow of your photos when the TV sits idle."),
    DEVICES("TVs & phones", Sym.Tv, "This TV's name, your phones and the other TVs in the house."),
    PHONES("Phones", Sym.Smartphone, "Phones connected to this TV."),
    TVS("Other TVs", Sym.Link, "Link TVs to see what each one is playing and watch together."),
    STORAGE("Storage & apps", Sym.Storage, "Drives, files by type, apps, iCloud and video calls."),
    UPDATES("Updates", Sym.SystemUpdate, "New versions of Woodruff Family."),
    ADVANCED("Advanced", Sym.Tune, "Smoothness, permissions, pairing and credits."),
}

/**
 * One row. A row that [opens] a page shows a chevron; any other runs [onClick]. [spaced]: a gap
 * above it. [about] and [detail] show on the right while the row has the focus.
 */
private class SettingItem(
    val icon: ImageVector, val title: String, val value: String? = null,
    val detail: String? = null, val danger: Boolean = false,
    val opens: SettingsPage? = null, val spaced: Boolean = false, val about: String? = null, val onClick: () -> Unit = {},
)

/** The row that opens [page], named and pictured like the page. */
private fun opener(page: SettingsPage, value: String?, spaced: Boolean = false) =
    SettingItem(page.icon, page.title, value, opens = page, spaced = spaced, about = page.about)

private fun onOff(on: Boolean) = if (on) "On" else "Off"

private fun count(n: Int, one: String, none: String) = when (n) {
    0 -> none
    1 -> "1 $one"
    else -> "$n ${one}s"
}

private fun mask(s: String, keep: Int) = if (s.length <= keep) s else s.take(keep) + "…"

/** One page's rows. [focusOn]: the row that takes the focus as the page appears after a move. */
@Composable
private fun SettingsList(items: List<SettingItem>, focusOn: Int?, moves: Int, onOpen: (SettingsPage) -> Unit, onFocused: (Int) -> Unit) {
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
            // A little room above the first row: the scrolling column clips at its top edge.
            .padding(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { i, item ->
            SettingRow(
                item,
                Modifier.padding(top = if (item.spaced) 22.dp else 0.dp)
                    .focusRequester(focus[i])
                    .onFocusChanged { if (it.isFocused) { focused = i; onFocused(i) } },
            ) { item.opens?.let(onOpen) ?: item.onClick() }
        }
    }
}

/**
 * The right side: what the focused row is about, Apple TV style. Its symbol, name and value, what
 * OK does, and for a group what's inside it (read only; the group opens with OK or Right).
 */
@Composable
private fun SettingAbout(item: SettingItem, inside: List<SettingItem>?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        TvIcon(item.icon, size = 72.dp, tint = Palette.text)
        Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 18.dp))
        if (item.value != null) {
            Text(item.value, style = MaterialTheme.typography.titleMedium, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (item.about != null) {
            Text(item.about, style = MaterialTheme.typography.bodyLarge, color = Palette.muted, modifier = Modifier.padding(top = 14.dp).widthIn(max = 460.dp))
        }
        if (item.detail != null) {
            Text(item.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp).widthIn(max = 460.dp))
        }
        if (!inside.isNullOrEmpty()) {
            Column(Modifier.padding(top = 26.dp).widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                inside.take(7).forEach { sub ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvIcon(sub.icon, size = 20.dp, tint = Palette.muted)
                        Text(
                            sub.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        if (sub.value != null) {
                            Text(
                                sub.value, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp).widthIn(max = 200.dp),
                            )
                        }
                    }
                }
                if (inside.size > 7) Text("and ${inside.size - 7} more", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            }
        }
    }
}

/** A row like the Apple TV's: plain until focused, then white with dark text. */
@Composable
private fun SettingRow(item: SettingItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
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
            // Red only off the focus: on the white platter it washes out, so it takes the row's dark text.
            Text(item.title, color = if (item.danger && !focused) Palette.danger else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = if (item.value != null || item.opens != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.value != null) {
                        Text(
                            item.value, color = LocalContentColor.current.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
                        )
                    }
                    if (item.opens != null) {
                        TvIcon(Sym.ChevronRight, Modifier.padding(start = 6.dp), size = 22.dp, tint = LocalContentColor.current.copy(alpha = 0.5f))
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
