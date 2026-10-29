package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.mail.MailClient
import com.woodruff.tvexplorer.mail.MailViewModel
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ListRowShape
import com.woodruff.tvexplorer.ui.components.PlatterListItem
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.focusPlatterHost
import com.woodruff.tvexplorer.ui.motion.rememberFocusPlatter
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.glassPanel
import com.woodruff.tvexplorer.util.Format
import kotlinx.coroutines.launch

@Composable
fun MailScreen(vm: MainViewModel) {
    val mail: MailViewModel = viewModel()
    val state by mail.state.collectAsStateWithLifecycle()
    // Mail stays signed in while it's on show, so messages open at once; its connections close when
    // it goes (another screen, another app, the TV asleep).
    LifecycleStartEffect(mail) {
        mail.shown(true)
        onStopOrDispose { mail.shown(false) }
    }

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
                ToolButton(Sym.Email, if (a.id == state.selected) "• ${a.email}" else a.email, { mail.select(a.id) })
            }
            if (state.accounts.isNotEmpty()) ToolButton(Sym.Refresh, null, mail::refresh)
            ToolButton(Sym.Settings, if (state.accounts.isEmpty()) "Add an account" else null, { vm.show(Screen.SETTINGS) })
        }
        when {
            state.accounts.isEmpty() -> EmptyState(
                "Add iCloud Mail, Gmail or another account in Settings.\nYour phone makes it easy: Settings → Phone → scan the QR code.",
            )
            state.loading -> EmptyState("Loading ${state.account?.email}…")
            state.error != null -> EmptyState(state.error!!)
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val list = rememberLazyListState()
                val openRow = remember { FocusRequester() }
                // One glass highlight gliding from message to message.
                val platter = rememberFocusPlatter()
                val scheme = MaterialTheme.colorScheme
                // The focused message comes to rest a third of the way down, as on Apple TV, the list
                // gliding on the platter's spring so the two move as one.
                PivotScroll(list, Pivot(fraction = 0.35f), TvMotion.Glide) {
                    LazyColumn(
                        Modifier.width(300.dp).fillMaxHeight().focusPlatterHost(platter, ListRowShape, Palette.surfaceHigh),
                        state = list,
                        contentPadding = PaddingValues(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.messages, key = { it.uid }) { m ->
                            val open = m.uid == state.openUid
                            // The colours tv-material's list item gave it: the open message's words in the
                            // selected colour off the focus.
                            PlatterListItem(
                                platter,
                                onClick = { mail.open(m) },
                                content = if (open) scheme.onSecondaryContainer else scheme.onSurface,
                                focusedContent = Palette.text,
                                modifier = (if (open) Modifier.focusRequester(openRow) else Modifier).semantics { selected = open },
                                container = if (open) Palette.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
                                focusedScale = 1.01f,
                                leading = {
                                    // At 80 %, as the list item drew it.
                                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (m.seen) androidx.compose.ui.graphics.Color.Transparent else Palette.accent.copy(alpha = 0.8f)))
                                },
                                headline = { tint ->
                                    RowText(m.from, tint.color, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (m.seen) FontWeight.Normal else FontWeight.SemiBold)
                                },
                                supporting = { tint ->
                                    Column {
                                        RowText(m.subject, tint.soft, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                        Text(Format.date(m.date), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                            )
                        }
                    }
                }
                // Left from the message goes back to its row while that's on screen. (Plain focus movement
                // picks the row level with the middle of the pane: often another message.)
                Reader(state.open, state.openLoading) {
                    list.layoutInfo.visibleItemsInfo.any { it.key == state.openUid } && runCatching { openRow.requestFocus() }.isSuccess
                }
            }
        }
    }
}

/**
 * Message pane. It's focusable so Up/Down scroll the message with the remote; Left goes back to
 * the list ([onLeft], or normal focus movement when that returns false). A message with an HTML
 * version is shown as mail apps show it (in a web view with scripts and links off); otherwise as text.
 */
@Composable
private fun Reader(body: com.woodruff.tvexplorer.mail.MailBody?, loading: Boolean, onLeft: () -> Boolean) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Some TVs have no web view installed: those show the text version. Checked only when a message
    // has an HTML version: the check starts the web engine, which holds up the screen for a moment.
    val webOk by remember { lazy { runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }.isSuccess } }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    // The page's web process crashed, or Android ended it to free memory: this message shows as text.
    var webGone by remember(body) { mutableStateOf(false) }
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
                    Key.DirectionLeft -> onLeft()
                    else -> false
                }
            }
            .focusable(),
    ) {
        when {
            loading -> EmptyState("Opening…")
            body == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                TvIcon(Sym.Email, size = 44.dp, tint = Palette.muted)
                Spacer(Modifier.height(10.dp))
                Text("Choose a message", color = Palette.muted)
            }
            body.html != null && !webGone && webOk -> Column(Modifier.fillMaxSize().padding(26.dp)) {
                MessageHeader(body)
                Spacer(Modifier.height(16.dp))
                val html = body.html
                // Pictures sent inside the message: the page asks for them, on the web view's own thread.
                val pictures = rememberUpdatedState(body.pictures)
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            webViewClient = object : android.webkit.WebViewClient() {
                                // Links stay put: there's no browser to follow them into here.
                                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) = true
                                override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                                    if (request.url.host != MailClient.PICTURE_HOST) return null
                                    val (type, bytes) = request.url.lastPathSegment?.let { pictures.value[it] } ?: return null
                                    return android.webkit.WebResourceResponse(type, null, java.io.ByteArrayInputStream(bytes))
                                }
                                // Unhandled, the page's process ending takes the whole app down with it.
                                override fun onRenderProcessGone(view: android.webkit.WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                    if (web === view) web = null
                                    webGone = true // shows the text version; onRelease destroys this web view
                                    return true
                                }
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
