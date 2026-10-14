package com.woodruff.tvexplorer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import com.woodruff.tvexplorer.phones.Phones
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.RemoteTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

/** History of everything sent from the phone and every new-mail alert. */
@Composable
fun NotificationsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val items by Notices.items.collectAsStateWithLifecycle()
    var announcing by remember { mutableStateOf(false) }
    BackHandler { vm.show(Screen.HOME) }
    LaunchedEffect(Unit) { Notices.markAllRead(context) }
    if (announcing) AnnounceDialog(vm, onDismiss = { announcing = false })

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 28.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Text("Notifications", style = MaterialTheme.typography.headlineLarge)
                Text("Messages from your phone and new mail", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
            ToolButton(Icons.Filled.Campaign, "Announce", { announcing = true })
            if (items.isNotEmpty()) ToolButton(Icons.Filled.ClearAll, "Clear", { Notices.clear(context) }, Modifier.padding(start = 12.dp))
        }
        if (items.isEmpty()) {
            EmptyState("Nothing yet. Send a message from your phone (Phone in the menu) or add a mail account.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { n ->
                ListItem(
                    selected = false,
                    onClick = {
                        val link = n.link ?: return@ListItem
                        val media = Regex("\\.(mp4|mkv|webm|m3u8|mov|mp3|m4a)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(link)
                        runCatching {
                            context.startActivity(
                                if (media) PlayerActivity.intent(context, listOf(link), listOf(n.title), link)
                                else Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { vm.say("No app on this TV can open that link") }
                    },
                    colors = ListItemDefaults.colors(focusedContainerColor = Palette.surfaceHigh, focusedContentColor = Palette.text),
                    leadingContent = {
                        TvIcon(
                            when (n.source) { NoticeSource.PHONE -> Icons.Filled.PhoneIphone; NoticeSource.MAIL -> Icons.Filled.Email; NoticeSource.SYSTEM -> Icons.Filled.Info },
                            tint = Palette.accent,
                        )
                    },
                    headlineContent = { Text(n.title, maxLines = 2) },
                    supportingContent = {
                        Column {
                            if (n.body.isNotBlank()) Text(n.body, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                            Text(Format.date(n.at) + if (n.link != null) " · Select to open" else "", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
            }
        }
    }
}

/** Sends a message to chosen phones (by the names they were given) and optionally shows it on the TV. */
@Composable
private fun AnnounceDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    remember { Phones.load(context) } // before reading, so every phone starts ticked
    val phones by Phones.items.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var chosen by remember { mutableStateOf(phones.map { it.id }.toSet()) }
    var onTv by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val send: () -> Unit = {
        val title = text.text.trim()
        if (title.isEmpty()) vm.say("Write the announcement first")
        else {
            if (onTv) Notices.post(context, NoticeSource.SYSTEM, title, "")
            val n = Phones.announce(context, chosen, title, "", "TV")
            vm.say(if (n == 0 && !onTv) "Choose at least one phone" else "Sent to $n phone${if (n == 1) "" else "s"}")
            if (n > 0 || onTv) onDismiss()
        }
    }
    DialogFrame("Announcement", onDismiss, width = 640) {
        RemoteTextField(
            text, { text = it }, label = "Announcement", placeholder = "Dinner's ready!",
            onSubmit = send, modifier = Modifier.fillMaxWidth(), textModifier = Modifier.focusRequester(field),
        )
        if (phones.isEmpty()) {
            Text("No phones yet. Open Woodruff on a phone (scan the QR in Phone) and give it a name under Settings → This phone.", color = Palette.muted)
        }
        LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(phones, key = { it.id }) { p ->
                val on = p.id in chosen
                ListItem(
                    selected = on,
                    onClick = { chosen = if (on) chosen - p.id else chosen + p.id },
                    leadingContent = { TvIcon(if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, tint = Palette.accent) },
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(if (p.topic != null) "Lock-screen alert" else "Shows when Woodruff is open on it", color = Palette.muted) },
                )
            }
            item {
                ListItem(
                    selected = onTv, onClick = { onTv = !onTv },
                    leadingContent = { TvIcon(if (onTv) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, tint = Palette.accent) },
                    headlineContent = { Text("Also show it on this TV") },
                )
            }
        }
        ButtonRow {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = send) { Text("Send") }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}
