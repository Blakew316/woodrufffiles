package com.woodruff.tvexplorer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.mail.MailViewModel
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.glassPanel
import com.woodruff.tvexplorer.util.Format
import kotlinx.coroutines.launch

@Composable
fun MailScreen(vm: MainViewModel) {
    val mail: MailViewModel = viewModel()
    val state by mail.state.collectAsStateWithLifecycle()

    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler { if (state.open != null) mail.closeMessage() else openSidebar() }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 28.dp)) {
        Text("Mail", style = MaterialTheme.typography.headlineLarge)
        // Scrolls sideways when there are many accounts. The scroll clips at its ends, so the buttons
        // get room inside it (shifted back into line) or a focused one's outline is cut off.
        Row(
            Modifier.padding(vertical = 14.dp).offset(x = (-16).dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.accounts.forEach { a ->
                ToolButton(Icons.Rounded.Email, if (a.id == state.selected) "• ${a.email}" else a.email, { mail.select(a.id) })
            }
            if (state.accounts.isNotEmpty()) ToolButton(Icons.Rounded.Refresh, null, mail::refresh)
            ToolButton(Icons.Rounded.Settings, if (state.accounts.isEmpty()) "Add an account" else null, { vm.show(Screen.SETTINGS) })
        }
        when {
            state.accounts.isEmpty() -> EmptyState(
                "Add iCloud Mail, Gmail or another account in Settings.\nYour phone makes it easy: Settings → Phone → scan the QR code.",
            )
            state.loading -> EmptyState("Loading ${state.account?.email}…")
            state.error != null -> EmptyState(state.error!!)
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LazyColumn(
                    Modifier.width(300.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.messages, key = { it.uid }) { m ->
                        ListItem(
                            selected = m.uid == state.openUid,
                            onClick = { mail.open(m) },
                            colors = ListItemDefaults.colors(
                                selectedContainerColor = Palette.accentSoft,
                                focusedContainerColor = Palette.surfaceHigh,
                                focusedContentColor = Palette.text,
                            ),
                            scale = ListItemDefaults.scale(focusedScale = 1.01f),
                            leadingContent = {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (m.seen) androidx.compose.ui.graphics.Color.Transparent else Palette.accent))
                            },
                            headlineContent = {
                                Text(m.from, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (m.seen) FontWeight.Normal else FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Column {
                                    Text(m.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(Format.date(m.date), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                                }
                            },
                        )
                    }
                }
                Reader(state.open, state.openLoading)
            }
        }
    }
}

/**
 * Message pane. It's focusable so Up/Down scroll the message with the remote; Left goes back to
 * the list as normal focus movement. A message with an HTML version is shown as mail apps show it
 * (in a web view with scripts and links off); otherwise as text.
 */
@Composable
private fun Reader(body: com.woodruff.tvexplorer.mail.MailBody?, loading: Boolean) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Some TVs have no web view installed: those show the text version.
    val webOk = remember { runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }.isSuccess }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp)
            .glassPanel(RoundedCornerShape(26.dp))
            .border(2.dp, if (focused) Palette.focusRim else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(26.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val w = web
                when (e.key) {
                    Key.DirectionDown -> {
                        if (w != null) w.pageDown(false) else scope.launch { scroll.animateScrollBy(260f) }
                        true
                    }
                    // At the top, Up leaves for the accounts above.
                    Key.DirectionUp -> if (w != null) w.pageUp(false) else if (scroll.value > 0) { scope.launch { scroll.animateScrollBy(-260f) }; true } else false
                    else -> false
                }
            }
            .focusable(),
    ) {
        when {
            loading -> EmptyState("Opening…")
            body == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                TvIcon(Icons.Rounded.Email, size = 44.dp, tint = Palette.muted)
                Spacer(Modifier.height(10.dp))
                Text("Choose a message", color = Palette.muted)
            }
            body.html != null && webOk -> Column(Modifier.fillMaxSize().padding(26.dp)) {
                MessageHeader(body)
                Spacer(Modifier.height(16.dp))
                val html = body.html
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Links stay put: there's no browser to follow them into here.
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) = true
                            }
                            // The pane takes the keys and scrolls this.
                            isFocusable = false
                            isFocusableInTouchMode = false
                            setBackgroundColor(android.graphics.Color.WHITE)
                            web = this
                        }
                    },
                    update = { w ->
                        if (w.tag != html) {
                            w.tag = html
                            w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                            w.scrollTo(0, 0)
                        }
                    },
                    onRelease = { w -> if (web === w) web = null; w.destroy() },
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                )
            }
            else -> Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(26.dp)) {
                MessageHeader(body)
                Spacer(Modifier.height(18.dp))
                Text(body.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun MessageHeader(body: com.woodruff.tvexplorer.mail.MailBody) {
    Text(body.subject, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(body.from, style = MaterialTheme.typography.titleMedium, color = Palette.accent)
    Text(
        listOfNotNull(body.to.takeIf { it.isNotBlank() }?.let { "To $it" }, Format.date(body.date)).joinToString(" · "),
        color = Palette.muted, style = MaterialTheme.typography.bodySmall,
    )
}
