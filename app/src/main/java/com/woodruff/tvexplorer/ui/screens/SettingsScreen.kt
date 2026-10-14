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
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    // Bumped whenever something changes (here, from the phone, or after returning from Android settings).
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { CompanionHub.changes.collect { version++ } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    BackHandler { vm.show(Screen.HOME) }

    val v = version // read so the values below refresh
    val clientId = remember(v) { GoogleAuth.clientId(context) }
    val configured = remember(v) { GoogleAuth.isConfigured(context) }
    val connected = remember(v) { GoogleAuth.isConnected(context) }
    val accounts = remember(v) { MailAccounts.list(context) }
    val overlay = remember(v) { Settings.canDrawOverlays(context) }
    val changed = { version++; CompanionHub.settingsChanged.tryEmit(Unit) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Text("Saved on this TV, encrypted, and kept across restarts and updates.", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
        }

        section("Appearance")
        item {
            SettingRow(if (Palette.light) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Theme", if (Palette.light) "Light" else "Dark") {
                ThemeSetting.toggle(context)
            }
        }

        section("Google Drive")
        item { SettingRow(Icons.Filled.AddToDrive, "Status", if (connected) "Signed in" else if (configured) "Keys saved · sign in from Google Drive" else "Not set up") { vm.show(Screen.DRIVE) } }
        item { SettingRow(Icons.Filled.Key, "Client ID", clientId?.let { mask(it, 18) } ?: "Not set") { dialog = SettingsDialog.ClientId } }
        item { SettingRow(Icons.Filled.Lock, "Client secret", if (configured) "•••••••• saved" else "Not set") { dialog = SettingsDialog.ClientSecret } }
        item { SettingRow(Icons.Filled.PhoneIphone, "Enter keys from your phone", "Easier than typing with the remote") { vm.show(Screen.TRANSFER) } }
        if (connected) item {
            SettingRow(Icons.Filled.Logout, "Sign out of Google", "Keeps the keys") {
                scope.launch { GoogleAuth.signOut(context); changed() }
            }
        }
        if (configured) item { SettingRow(Icons.Filled.Delete, "Remove Google keys", null, danger = true) { dialog = SettingsDialog.RemoveGoogle } }

        section("Mail")
        accounts.forEach { a ->
            item(key = a.id) { SettingRow(Icons.Filled.Email, a.email, a.provider.label) { dialog = SettingsDialog.RemoveMail(a) } }
        }
        item { SettingRow(Icons.Filled.PersonAdd, "Add a mail account", "iCloud, Gmail, Yahoo or any IMAP mail") { dialog = SettingsDialog.PickProvider } }

        section("Notifications")
        item {
            SettingRow(Icons.Filled.NotificationsActive, "Pop-ups over other apps", if (overlay) "Allowed" else "Off · select to allow") {
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

        section("Phone connection")
        item { SettingRow(Icons.Filled.PhoneIphone, "Connect a phone", TransferService.state.value.base ?: "Show the QR code") { vm.show(Screen.TRANSFER) } }
        item { SettingRow(Icons.Filled.Refresh, "Reset pairing", "Disconnects every paired phone and shortcut", danger = true) { dialog = SettingsDialog.ResetPairing } }

        section("About")
        item { SettingRow(Icons.Filled.Info, "Woodruff Pictures ${BuildConfig.VERSION_NAME}", "Type: Inter (SIL Open Font License) · Video: Media3") {} }
    }

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
            MailProvider.entries.map { p -> MenuAction(p.label, Icons.Filled.Email) { dialog = SettingsDialog.MailEmail(p) } },
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
private fun SettingRow(icon: ImageVector, title: String, value: String?, danger: Boolean = false, onClick: () -> Unit) {
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
        leadingContent = { TvIcon(icon, tint = if (danger) Palette.danger else Palette.accent) },
        headlineContent = { Text(title, color = if (danger) Palette.danger else androidx.compose.ui.graphics.Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (value != null) {
            { Text(value, color = Palette.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
    )
}

