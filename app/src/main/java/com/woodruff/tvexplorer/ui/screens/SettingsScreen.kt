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
    data class RemoveTv(val tv: LinkedTv) : SettingsDialog
    data class RemovePhone(val phone: Phone) : SettingsDialog
}

/**
 * All settings in one place. Secrets (Google keys, mail passwords, tokens) are stored encrypted
 * on the TV, survive restarts and updates, and can be typed here or, more comfortably, from the
 * phone (Phone → Settings tab).
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
    BackHandler { vm.show(Screen.HOME) }

    val v = version // read so the values below refresh
    val clientId = remember(v) { GoogleAuth.clientId(context) }
    val configured = remember(v) { GoogleAuth.isConfigured(context) }
    val connected = remember(v) { GoogleAuth.isConnected(context) }
    val nestProject = remember(v) { GoogleAuth.nestProject(context) }
    val nestConnected = remember(v) { GoogleAuth.isConnected(context, GoogleAuth.Grant.NEST) }
    val tmdbSet = remember(v) { Tmdb.isConfigured(context) }
    val tvName = remember(v) { TvLinks.name(context) }
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

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
        }

        section("Appearance")
        item {
            SettingRow(if (Palette.light) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Theme", if (Palette.light) "Light" else "Dark") {
                ThemeSetting.toggle(context)
            }
        }

        section("Storage & apps")
        roots.forEach { r ->
            item(key = "root-" + r.dir.path) {
                SettingRow(
                    if (r.removable) Icons.Rounded.SdStorage else Icons.Rounded.Storage, r.title,
                    if (r.totalBytes > 0) "${Format.bytes(r.freeBytes)} free of ${Format.bytes(r.totalBytes)}" else null,
                ) { vm.openRoot(r.dir, from = Screen.SETTINGS) }
            }
        }

        item { SettingRow(Icons.Rounded.Apps, "Apps", null) { vm.openApps(Screen.SETTINGS) } }

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

        section("Cameras")
        item {
            SettingRow(Icons.Rounded.Videocam, "Command center", if (nestConnected) "Connected" else "Not connected", brand = R.drawable.brand_google_home) { vm.show(Screen.CAMERAS) }
        }
        item {
            SettingRow(Icons.Rounded.Key, "Device Access Project ID", nestProject?.let { mask(it, 13) } ?: "Not set") {
                dialog = SettingsDialog.NestProject
            }
        }
        item {
            SettingRow(Icons.Rounded.Dashboard, "Open at start", if (Appearance.startOnCameras) "On" else "Off") {
                Appearance.toggleStartOnCameras(context)
            }
        }
        if (nestConnected) item {
            SettingRow(Icons.Rounded.Logout, "Disconnect cameras", null) { Nest.signOut { changed() } }
        }

        section("TVs")
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

        section("Video calls")
        item { SettingRow(Icons.Rounded.Videocam, "Google Meet", null, brand = R.drawable.brand_google_meet) { Handoff.launch(context, Handoff.MEET) } }
        item { SettingRow(Icons.Rounded.Cast, "AirScreen (iPhone camera & screen)", null) { Handoff.launch(context, Handoff.AIRSCREEN) } }

        section("Google Calendar")
        item {
            SettingRow(Icons.Rounded.Event, "Today's Activities", if (calendar.connected) "Connected" else "Connect", brand = R.drawable.brand_google_calendar) {
                if (!calendar.connected) showCalendar = true
            }
        }
        if (calendar.connected) item {
            SettingRow(Icons.Rounded.Logout, "Disconnect Google Calendar", null) { TodayCalendar.signOut { changed() } }
        }

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

        section("Movie & show posters")
        item { SettingRow(Icons.Rounded.Key, "TMDB API key", if (tmdbSet) "•••••••• saved" else "Not set") { dialog = SettingsDialog.TmdbKey } }

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

        section("Mail")
        accounts.forEach { a ->
            item(key = a.id) { SettingRow(Icons.Rounded.Email, a.email, a.provider.label) { dialog = SettingsDialog.RemoveMail(a) } }
        }
        item { SettingRow(Icons.Rounded.PersonAdd, "Add a mail account", null) { dialog = SettingsDialog.PickProvider } }

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

        section("Phones")
        if (phones.isEmpty()) item { SettingRow(Icons.Rounded.Smartphone, "No phones yet", null) { vm.show(Screen.TRANSFER) } }
        phones.forEach { p ->
            item(key = "phone-" + p.id) {
                SettingRow(Icons.Rounded.Smartphone, p.name, Format.date(p.lastSeen)) {
                    dialog = SettingsDialog.RemovePhone(p)
                }
            }
        }

        section("Phone connection")
        item { SettingRow(Icons.Rounded.PhoneIphone, "Connect a phone", TransferService.state.value.base) { vm.show(Screen.TRANSFER) } }
        item { SettingRow(Icons.Rounded.Refresh, "Reset pairing", null, danger = true) { dialog = SettingsDialog.ResetPairing } }

        section("About")
        item { SettingRow(Icons.Rounded.Info, "Woodruff Family ${BuildConfig.VERSION_NAME}", "Posters: TMDB (not endorsed by TMDB)") {} }
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

