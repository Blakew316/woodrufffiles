package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.notify.Chime
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ListRowShape
import com.woodruff.tvexplorer.ui.components.PlatterListItem
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.TvOutlinedButton
import com.woodruff.tvexplorer.ui.motion.FocusPlatter
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.focusPlatterHost
import com.woodruff.tvexplorer.ui.motion.rememberFocusPlatter
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

/** History of everything sent from the phone and every new-mail alert. */
@Composable
fun NotificationsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val items by Notices.items.collectAsStateWithLifecycle()
    var announcing by remember { mutableStateOf(false) }
    val announce = remember { FocusRequester() }
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler { openSidebar() }
    LaunchedEffect(Unit) { Notices.markAllRead(context) }
    if (announcing) AnnounceDialog(vm, onDismiss = { announcing = false })

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 28.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Text("Notifications", style = MaterialTheme.typography.headlineLarge)
            }
            ToolButton(Sym.Campaign, "Announce", { announcing = true }, Modifier.focusRequester(announce))
            // Clear goes away with the list: the focus moves to Announce first, or it's left nowhere.
            if (items.isNotEmpty()) ToolButton(Sym.Delete, "Clear", { runCatching { announce.requestFocus() }; Notices.clear(context) }, Modifier.padding(start = 12.dp))
        }
        if (items.isEmpty()) {
            EmptyState("No notifications")
            return@Column
        }
        // The focused notification comes to rest a third of the way down, as on Apple TV; one glass
        // highlight glides from one to the next, the list gliding with it on its spring.
        val list = rememberLazyListState()
        val platter = rememberFocusPlatter()
        PivotScroll(list, Pivot(fraction = 0.35f), TvMotion.Glide) {
            LazyColumn(
                Modifier.focusPlatterHost(platter, ListRowShape, Palette.surfaceHigh),
                state = list,
                contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.id }) { n ->
                    // Its words keep their colour on the glass highlight, as they did.
                    PlatterListItem(
                        platter,
                        onClick = {
                            val link = n.link ?: return@PlatterListItem
                            val media = Regex("\\.(mp4|mkv|webm|m3u8|mov|mp3|m4a)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(link)
                            runCatching {
                                context.startActivity(
                                    if (media) PlayerActivity.intent(context, listOf(link), listOf(n.title), link)
                                    else Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { vm.say("No app on this TV can open that link") }
                        },
                        content = MaterialTheme.colorScheme.onSurface,
                        focusedContent = Palette.text,
                        leading = {
                            TvIcon(
                                when (n.source) { NoticeSource.PHONE -> Sym.PhoneIphone; NoticeSource.MAIL -> Sym.Email; NoticeSource.SYSTEM -> Sym.Info },
                            )
                        },
                        headline = { Text(n.title, maxLines = 2) },
                        supporting = {
                            Column {
                                if (n.body.isNotBlank()) Text(n.body, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                                Text(Format.date(n.at), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Sends a message to chosen phones (by the names they were given) and TVs: this one and each
 * linked TV, which pop it up over whatever they're showing, with the chime.
 */
@Composable
private fun AnnounceDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    remember { Phones.load(context); TvLinks.load(context) } // before reading, so every phone and TV starts ticked
    val phones by Phones.items.collectAsStateWithLifecycle()
    val tvs by TvLinks.linked.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var chosen by remember { mutableStateOf(phones.map { it.id }.toSet()) }
    // The other TVs start ticked, like the phones: you're at this one.
    var chosenTvs by remember { mutableStateOf(tvs.map { it.id }.toSet()) }
    var onTv by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val send: () -> Unit = {
        val title = text.text.trim()
        if (title.isEmpty()) vm.say("Write the announcement first")
        else {
            if (onTv) Notices.post(context, NoticeSource.SYSTEM, title, "", chime = Chime.Sound.ANNOUNCE)
            // Sent in the background; a TV that's off is skipped. The others see which TV it's from.
            val others = TvLinks.announce(context, chosenTvs, title, "", TvLinks.name(context), NoticeSource.SYSTEM)
            val n = Phones.announce(context, chosen, title, "", "TV")
            vm.say(sentTo(onTv, others, n) ?: "Choose a TV or a phone")
            if (n > 0 || others > 0 || onTv) onDismiss()
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
        // Room above the first row and below the last: a focused row grows past them, and the list clips there.
        // One highlight glides from row to row.
        val platter = rememberFocusPlatter()
        LazyColumn(
            Modifier.heightIn(max = 220.dp).focusPlatterHost(platter, ListRowShape, MaterialTheme.colorScheme.inverseSurface),
            contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(phones, key = { it.id }) { p ->
                val on = p.id in chosen
                CheckRow(p.name, on, platter) { chosen = if (on) chosen - p.id else chosen + p.id }
            }
            // Keys apart from the phones' ids (which have no spaces or colons).
            item(key = "this tv") { CheckRow("This TV", onTv, platter) { onTv = !onTv } }
            items(tvs, key = { "tv:" + it.id }) { tv ->
                val on = tv.id in chosenTvs
                CheckRow(tv.name, on, platter) { chosenTvs = if (on) chosenTvs - tv.id else chosenTvs + tv.id }
            }
        }
        ButtonRow {
            TvOutlinedButton(onClick = onDismiss) { Text("Cancel") }
            TvButton(onClick = send) { Text("Send") }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

/**
 * A phone or TV to send to, ticked or not, in the colours tv-material's list item gave it (a ticked
 * one sits in the selected tint); the list's [platter] glides to it with the focus.
 */
@Composable
private fun CheckRow(name: String, on: Boolean, platter: FocusPlatter, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    PlatterListItem(
        platter,
        onClick = onClick,
        content = if (on) scheme.onSecondaryContainer else scheme.onSurface,
        focusedContent = scheme.inverseOnSurface,
        modifier = Modifier.semantics { selected = on },
        container = if (on) scheme.secondaryContainer.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent,
        leading = { tint -> TintIcon(if (on) Sym.CheckBox else Sym.CheckBoxOutlineBlank, tint.soft) },
        headline = { tint -> RowText(name, tint.color) },
    )
}

/** "Sent to this TV and 2 phones", "Sent to 3 TVs"; null when it went nowhere. */
private fun sentTo(thisTv: Boolean, otherTvs: Int, phones: Int): String? {
    val tvs = otherTvs + (if (thisTv) 1 else 0)
    val parts = listOfNotNull(
        when {
            tvs == 0 -> null
            thisTv && otherTvs == 0 -> "this TV"
            else -> "$tvs TV${if (tvs == 1) "" else "s"}"
        },
        if (phones > 0) "$phones phone${if (phones == 1) "" else "s"}" else null,
    )
    return if (parts.isEmpty()) null else "Sent to " + parts.joinToString(" and ")
}
