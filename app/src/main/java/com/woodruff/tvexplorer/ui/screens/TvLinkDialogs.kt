package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Palette
import kotlinx.coroutines.launch

/** On the TV being added: shows a one-time code for 10 minutes. */
@Composable
fun LinkCodeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val code = remember { TvLinks.newCode() }
    val close = { TvLinks.cancelCode(); onDismiss() }
    val focus = remember { FocusRequester() }
    DialogFrame("Link this TV", close, width = 600) {
        Text("On the other TV: Settings → TVs → Add another TV, choose \"${TvLinks.name(context)}\" and enter:", color = Palette.muted)
        Text(code.chunked(3).joinToString(" "), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Palette.accent, letterSpacing = 6.sp)
        Text("Works once, for 10 minutes.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        ButtonRow { OutlinedButton(onClick = close, modifier = Modifier.focusRequester(focus)) { Text("Done") } }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** On the hub: finds Woodruff TVs on the network, then asks for the code the chosen one shows. */
@Composable
fun AddTvDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<TvLinks.Found>() }
    var chosen by remember { mutableStateOf<TvLinks.Found?>(null) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val stop = TvLinks.discover(context) { f ->
            // Found on a background thread: update the list on the main thread.
            main.post { if (found.none { it.base == f.base }) found += f }
        }
        onDispose { stop() }
    }
    val pick = chosen
    if (pick != null) {
        TextInputDialog(
            "Code shown on ${pick.name}", "", if (busy) "Linking…" else "Link",
            onConfirm = { code ->
                busy = true
                scope.launch {
                    val msg = try { "Linked ${TvLinks.link(context, pick.base, code).name}" } catch (e: Exception) { e.message ?: "Couldn't link" }
                    busy = false
                    onResult(msg)
                    onDismiss()
                }
            },
            onDismiss = { chosen = null },
            hint = "On ${pick.name}: Settings → TVs → Link this TV.",
        )
        return
    }
    DialogFrame("Add another TV", onDismiss, width = 640) {
        Text(if (found.isEmpty()) "Looking for TVs with Woodruff Family on this Wi-Fi…" else "Choose a TV:", color = Palette.muted)
        // Room above the first row and below the last: a focused row grows past them, and the list clips there.
        LazyColumn(Modifier.heightIn(max = 260.dp), contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(found, key = { it.base }) { f ->
                ListItem(
                    selected = false, onClick = { chosen = f },
                    leadingContent = { TvIcon(Sym.Tv) },
                    headlineContent = { Text(f.name) },
                    supportingContent = { Text(f.base.removePrefix("http://"), color = Palette.muted) },
                )
            }
        }
        ButtonRow { OutlinedButton(onClick = onDismiss) { Text("Close") } }
    }
}
