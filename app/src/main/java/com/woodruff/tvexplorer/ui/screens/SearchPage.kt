package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.NewShow
import com.woodruff.tvexplorer.cloud.SignInStep
import com.woodruff.tvexplorer.cloud.TitleSearch
import com.woodruff.tvexplorer.cloud.said
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.FocusWhenReady
import com.woodruff.tvexplorer.ui.components.OfflineWords
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.components.RowTint
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.rememberOfflineShown
import com.woodruff.tvexplorer.ui.components.rememberRowTint
import com.woodruff.tvexplorer.ui.components.tryFocus
import com.woodruff.tvexplorer.ui.icons.Sym
import com.woodruff.tvexplorer.ui.motion.DialogWindowMotion
import com.woodruff.tvexplorer.ui.motion.FocusPlatter
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.WindowMotion
import com.woodruff.tvexplorer.ui.motion.dpadGate
import com.woodruff.tvexplorer.ui.motion.focusPlatterHost
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState
import com.woodruff.tvexplorer.ui.motion.rememberFocusPlatter
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
import com.woodruff.tvexplorer.util.NetworkWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/*
 * Search (search spec): one page for Movies and TV Shows together, opened from the side menu's first item,
 * the remote's search button or Streaming's search field. Woodruff's own keyboard on the left (the TV's
 * keyboard never pops up), the answers as posters on the right, ranked by TitleSearch, the rule the phone
 * shares. A poster opens its title page over Search, and Back comes straight back to it.
 */

/** Where Search was opened from: what it does first, and where the focus goes back as it closes (search spec §2). */
internal enum class SearchFrom {
    /** The side menu's Search: the focus starts on "a"; closing leaves the menu open on Search. */
    MENU,

    /** The remote's search button: it listens at once when the TV can; closing returns the focus where it was. */
    REMOTE,

    /** Streaming's search field: that library's titles come first among equals; closing returns to the field. */
    STREAMING,
}

/** Search asked for ([id]: a fresh open each time, starting empty). [prefer]: the library it was opened from; [listen]: listen at once. */
@Immutable
internal data class SearchRequest(val from: SearchFrom, val prefer: DrivePlace?, val listen: Boolean, val id: Long)

/**
 * Search for the whole app: anything asks here and [SearchHost] (in the main window) shows the page over
 * whatever is on screen, which doesn't change underneath.
 */
object SearchPages {
    private val ids = AtomicLong()
    private val _request = MutableStateFlow<SearchRequest?>(null)
    internal val request: StateFlow<SearchRequest?> = _request.asStateFlow()
    private val _listenAgain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val listenAgain: SharedFlow<Unit> = _listenAgain.asSharedFlow()

    /** Opens Search afresh (empty), from [from]; [prefer]: the library it's opened from; [listen]: start listening. */
    internal fun open(from: SearchFrom, prefer: DrivePlace? = null, listen: Boolean = false) {
        _request.value = SearchRequest(from, prefer, listen, ids.incrementAndGet())
    }

    /** Closes Search. */
    fun close() { _request.value = null }

    /** Whether Search is on screen. */
    val isOpen: Boolean get() = _request.value != null

    /**
     * The remote's search (or voice) button, pressed in any Woodruff window: Search opens listening (when the
     * TV can listen; else as the side menu's item opens it), or, already open, listens again.
     */
    fun searchKey() {
        if (_request.value != null) _listenAgain.tryEmit(Unit) else open(SearchFrom.REMOTE, listen = true)
    }
}

/**
 * Whether this TV can listen for a search (a speech recognizer answers Android's "recognize speech"), worked out
 * once a run, off the main thread, before Search first opens: the keyboard has its mic key only then, and never
 * changes shape while it's on screen.
 */
internal object SearchVoice {
    @Volatile var available: Boolean? = null

    /** Works [available] out, once. Binder calls: a background thread. */
    fun check(c: Context) {
        if (available != null) return
        available = runCatching {
            @Suppress("DEPRECATION")
            c.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        }.getOrDefault(false)
    }

    /** Android's own listening screen, asking for a title. */
    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie or show")
}

/**
 * The remote's search (or voice) button pressed in one of Woodruff's own pop-up windows (a title page, the filter
 * sheet), whose keys never reach the activity: Search opens over it, as from a screen ([SearchPages.searchKey]).
 * Only those two keys are taken, and only when nothing inside used them.
 */
internal fun Modifier.searchKeyOpensSearch(): Modifier = onKeyEvent { e ->
    val code = e.nativeKeyEvent.keyCode
    if (code != NativeKeyEvent.KEYCODE_SEARCH && code != NativeKeyEvent.KEYCODE_VOICE_ASSIST) return@onKeyEvent false
    if (e.nativeKeyEvent.action == NativeKeyEvent.ACTION_UP) SearchPages.searchKey()
    true
}

/** The box the phone's keyboard types into while Search is up (as the phone shows it). */
internal const val SEARCH_BOX = "Search Movies & TV Shows"

/** Query changes closer together than this (phone typing, a held key, a pasted word) replace the results once, this long after the last. */
internal const val RESULTS_CALM_MS = 150L

/** Recently added posters shown for an empty query. */
private const val RECENT_SHOWN = 9

/** How long to wait before matching a query typed [sinceLast] ms after the one before: at once for a single key, else [RESULTS_CALM_MS]. */
internal fun resultsWait(sinceLast: Long): Long = if (sinceLast in 0 until RESULTS_CALM_MS) RESULTS_CALM_MS else 0L

/** [query] with [c] typed at its end: a space never first or twice in a row; nothing past [TitleSearch.QUERY_MAX]. */
internal fun typedInto(query: String, c: Char): String = when {
    query.length >= TitleSearch.QUERY_MAX -> query
    c == ' ' && (query.isEmpty() || query.endsWith(' ')) -> query
    else -> query + c
}

/** What the voice heard, as the query: trimmed, at most [TitleSearch.QUERY_MAX] characters. */
internal fun spokenQuery(heard: String): String = heard.trim().take(TitleSearch.QUERY_MAX)

/** What the phone typed, as the query (it replaces it), at most [TitleSearch.QUERY_MAX] characters. */
internal fun phoneQuery(typed: String): String = typed.take(TitleSearch.QUERY_MAX)

/** The character a real keyboard's key types into Search ([code]: a letter, a digit or the space bar); null for any other key. */
internal fun keyChar(code: Int): Char? = when (code) {
    in NativeKeyEvent.KEYCODE_A..NativeKeyEvent.KEYCODE_Z -> 'a' + (code - NativeKeyEvent.KEYCODE_A)
    in NativeKeyEvent.KEYCODE_0..NativeKeyEvent.KEYCODE_9 -> '0' + (code - NativeKeyEvent.KEYCODE_0)
    in NativeKeyEvent.KEYCODE_NUMPAD_0..NativeKeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - NativeKeyEvent.KEYCODE_NUMPAD_0)
    NativeKeyEvent.KEYCODE_SPACE -> ' '
    else -> null
}

/** What stands in the way of searching: nothing, or Google Drive not ready (a page of its own, search spec §5). */
internal enum class SearchBlock { NONE, NEEDS_DRIVE, SIGN_IN_ENDED, KEYS }

/**
 * What stands in the way: Google Drive not set up, or signed out ([step]); its sign-in ended (Google ended it,
 * [ended]); or the keys Google won't take ([refused]).
 */
internal fun searchBlock(step: SignInStep, ended: Boolean, refused: Boolean): SearchBlock = when {
    step == SignInStep.SETUP -> SearchBlock.NEEDS_DRIVE
    step == SignInStep.SIGN_IN -> if (ended) SearchBlock.SIGN_IN_ENDED else SearchBlock.NEEDS_DRIVE
    refused -> SearchBlock.KEYS
    else -> SearchBlock.NONE
}

/** The page for [block] ([SearchBlock.NONE] has none). */
internal fun searchBlockWords(block: SearchBlock): PageWords = when (block) {
    SearchBlock.SIGN_IN_ENDED -> PageWords(
        Sym.Search, "Sign in to Google Drive again",
        "Google ended this TV’s sign-in. Sign in again and search from anywhere.", "Sign in", Sym.AddToDrive,
    )
    SearchBlock.KEYS -> PageWords(
        Sym.Key, "Check the Google keys",
        "Google doesn’t accept the Client ID or secret saved on this TV. Fix them, then search from anywhere.", "Open Keys", Sym.Key,
    )
    else -> PageWords(
        Sym.Search, "Search needs Google Drive",
        "Movies and TV Shows come from your Google Drive. Set it up once on this TV, then search from anywhere.", "Set up Google Drive", Sym.AddToDrive,
    )
}

/**
 * The line over the answers while what's searched is the copy saved on the TV: "Offline · searching your saved
 * library" ([networkDown]: no network for longer than the offline grace), or "Can't reach Google · …" when
 * Google Drive didn't answer ([status], as Streaming last found it). Null when it's current.
 */
internal fun searchOfflineWords(networkDown: Boolean, status: DriveStatus): String? {
    val google = when {
        networkDown -> false
        status is DriveStatus.Offline && status.shown -> status.google
        status is DriveStatus.Reconnecting -> status.was.google
        else -> return null
    }
    return OfflineWords.short(google) + " · searching your saved library"
}

/** What Search's right side shows (search spec §5). */
internal sealed interface SearchShows {
    /** Nothing listed yet: "still loading", or offline with nothing saved. */
    data class Waiting(val words: String) : SearchShows

    /** An empty query and nothing recently added: what to do. */
    data class Hint(val words: String) : SearchShows

    /** An empty query: Recently added. */
    data object Recent : SearchShows

    /** The titles found. */
    data object Results : SearchShows

    /** Nothing matches [query]. */
    data class NoMatches(val query: String) : SearchShows
}

/**
 * What Search shows for [query] (the one its results are for): [found], with the libraries listed or not
 * ([listed]), offline or not ([down]), [recent] titles recently added, and whether the TV can listen ([voice]).
 */
internal fun searchShows(query: String, found: TitleSearch.Found, listed: Boolean, down: Boolean, recent: Int, voice: Boolean): SearchShows = when {
    !listed -> SearchShows.Waiting(if (down) "Search will work once the TV is back online." else "Movies and TV Shows are still loading…")
    query.isBlank() -> if (recent > 0) SearchShows.Recent else SearchShows.Hint(if (voice) "Type a title, or press the mic and say one." else "Type a title.")
    found.hits.isNotEmpty() -> SearchShows.Results
    else -> SearchShows.NoMatches(query.trim())
}

/** The results on show, and the query they're for. */
@Immutable
private data class Shown(val query: String, val found: TitleSearch.Found)

/**
 * Search, in the main window: shows the page when [SearchPages] asks for it, over whatever's on screen, and
 * listens through Android's speech screen for it (the result arrives here, outside the page's window).
 */
@Composable
fun SearchHost(vm: MainViewModel, drive: DriveViewModel) {
    val context = LocalContext.current
    // Whether the TV can listen, decided once, off the main thread, before Search first opens.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SearchVoice.check(context) } }
    val heard = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    // Listening now: the remote's button pressed again (or twice by two routes) doesn't start a second one.
    val listening = remember { BooleanArray(1) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        listening[0] = false
        // Cancelled or nothing heard: nothing changes.
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { heard.tryEmit(it) }
    }
    val listen: () -> Unit = remember(speech) {
        { if (!listening[0]) listening[0] = runCatching { speech.launch(SearchVoice.intent()) }.isSuccess }
    }
    val request by SearchPages.request.collectAsStateWithLifecycle()
    val r = request ?: return
    // A fresh open starts afresh: nothing typed, nothing focused, nothing listening.
    key(r.id) {
        LaunchedEffect(Unit) { listening[0] = false }
        SearchPage(r, vm, drive, listen, heard)
    }
}

/** Search's page window, with its own layer of title pages and its title menus over it. */
@Composable
private fun SearchPage(r: SearchRequest, vm: MainViewModel, drive: DriveViewModel, listen: () -> Unit, heard: SharedFlow<String>) {
    val context = LocalContext.current
    // Pages opened from the results: over Search, so Back comes straight back to the result.
    val layer = remember { TitleLayer() }
    val page by layer.request.collectAsStateWithLifecycle()
    val listings by drive.listings.collectAsStateWithLifecycle()
    val recent by drive.recentlyAdded.collectAsStateWithLifecycle()
    val news by drive.newEpisodes.collectAsStateWithLifecycle()
    // Only what Search needs of Drive's state: it changes with every download tick.
    val token by remember(drive) { drive.state.map { it.token }.distinctUntilChanged() }.collectAsStateWithLifecycle(drive.state.value.token)
    val access by remember(drive) { drive.state.map { Access(it.step, it.ended, it.status) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(drive.state.value.let { Access(it.step, it.ended, it.status) })
    // Keys Google refused the last time the sign-in was renewed (anywhere): read once, off the main thread, before
    // the page decides what to show (so it never flips from the keyboard to the keys page under the focus).
    val refusedBefore by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { GoogleAuth.keysRefused(context, GoogleAuth.Grant.DRIVE) } }
    val online by NetworkWatch.online.collectAsStateWithLifecycle()
    val networkDown = rememberOfflineShown(!online)
    var menu by remember { mutableStateOf<MenuFor?>(null) }
    val marks = remember { mutableIntStateOf(0) }
    val run = rememberTitleRun(drive, openPage = { place, f, ep -> layer.open(place, f, ep) }, changed = { marks.intValue++ })
    // Decided as it opens: the keyboard never changes shape while it's on screen.
    val voice = remember { SearchVoice.available == true }
    val pageClosed by TitlePages.closed.collectAsStateWithLifecycle()
    LaunchedEffect(pageClosed) { marks.intValue++ }
    Dialog(onDismissRequest = SearchPages::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The page grows in as the system moves its window; nothing of its own animates in on top.
        DialogWindowMotion(WindowMotion.Page)
        // A dialog's keys never pass the activity's repeat gate: held arrows are gated here.
        Box(Modifier.fillMaxSize().dpadGate()) {
            val refused = refusedBefore
            if (refused != null) {
                val block = searchBlock(access.step, access.ended, access.status is DriveStatus.Refused || (refused && access.step == SignInStep.CONNECTED))
                SearchScreen(
                    request = r, block = block,
                    libraries = drive::libraries, listings = listings, recent = recent, news = news,
                    offline = searchOfflineWords(networkDown, access.status), down = networkDown || access.status.said,
                    token = token, voice = voice, covered = page != null || menu != null, marks = marks,
                    onListen = listen, heard = heard, listenAgain = SearchPages.listenAgain,
                    onOpen = { hit -> layer.open(hit.place, hit.file, news[hit.file.id]?.takeIf { it.announced }?.first?.id) },
                    onMenu = { hit -> menu = MenuFor(hit.place, hit.file, MenuPlace.SEARCH) },
                    onPlay = { hit -> run.playNow(MenuFor(hit.place, hit.file, MenuPlace.SEARCH)) },
                    onFix = { b ->
                        SearchPages.close()
                        if (b == SearchBlock.KEYS) openSettingsAt(vm, SettingsAt.KEYS)
                        else {
                            // Streaming's sign-in panel, in the library it was last in.
                            val library = drive.state.value.stack.firstOrNull()?.takeIf { it == DrivePlace.Movies || it == DrivePlace.TvShows } ?: DrivePlace.Movies
                            drive.openRoot(library)
                            vm.show(Screen.DRIVE)
                        }
                    },
                )
            }
        }
        // Over Search: a result's title page (Back returns to the result) and its title menu.
        TitleHost(vm, drive, layer)
        TitleMenu(menu, run) { menu = null }
    }
}

/** What Search needs of Drive's sign-in: its step, whether Google ended it, and how the last listing went. */
@Immutable
private data class Access(val step: SignInStep, val ended: Boolean, val status: DriveStatus)

/**
 * Search's page itself, without its window (the gallery draws it as it is): the query field, Woodruff's own
 * keyboard and the hint under it on the left, the answers on the right; or, when Google Drive isn't ready
 * ([block]), a page saying what to do. The query lives here: typed on the keys, on a real keyboard, on the
 * phone ([RemoteInput]) or spoken ([heard]). Matching runs off the main thread, cancelled by the next letter.
 */
@Composable
internal fun SearchScreen(
    request: SearchRequest,
    block: SearchBlock,
    /** Movies and TV Shows as listed now (read when [listings] changes). */
    libraries: () -> Map<DrivePlace, List<DriveFile>>,
    listings: Int,
    recent: List<Pair<DrivePlace, DriveFile>>,
    news: Map<String, NewShow>,
    /** The offline line's words ([searchOfflineWords]); null when online. */
    offline: String?,
    /** Offline (or Google not answering), whether or not the line shows: for "nothing saved". */
    down: Boolean,
    token: String?,
    /** The TV can listen: the mic key, and listening. */
    voice: Boolean,
    /** A title page or a menu is over Search: the phone types into nothing here meanwhile. */
    covered: Boolean,
    /** Changes when ✓s or progress may have changed. */
    marks: State<Int>,
    onListen: () -> Unit,
    heard: Flow<String>,
    listenAgain: Flow<Unit>,
    onOpen: (TitleSearch.Hit) -> Unit,
    onMenu: (TitleSearch.Hit) -> Unit,
    onPlay: (TitleSearch.Hit) -> Unit,
    onFix: (SearchBlock) -> Unit,
    /** Gallery: what's typed as it opens. Search always opens empty in the app. */
    initialQuery: String = "",
) {
    val blockButton = remember { FocusRequester() }
    if (block != SearchBlock.NONE) {
        // Google Drive isn't ready: one page, its one button focused; Back closes Search.
        Box(Modifier.fillMaxSize().ambientBackdrop()) {
            CalmPage(searchBlockWords(block), onAction = { onFix(block) }, buttonModifier = Modifier.focusRequester(blockButton))
        }
        FocusWhenReady(blockButton)
        return
    }
    val scope = rememberCoroutineScope()
    val query = remember { mutableStateOf(initialQuery) }
    val shown = remember { mutableStateOf(Shown("", TitleSearch.Found.NONE)) }
    val grid = rememberLazyGridState()
    val keyA = remember { FocusRequester() }
    val keyMic = remember { FocusRequester() }
    // The key last focused, for Back from the results.
    val lastKey = remember { arrayOfNulls<FocusRequester>(1) }
    val firstResult = remember { FocusRequester() }
    val resultsFocused = remember { mutableStateOf(false) }
    val focusedResult = remember { arrayOfNulls<String>(1) }
    val latestLibraries by rememberUpdatedState(libraries)
    val listingsNow = rememberUpdatedState(listings)
    val latestRecent by rememberUpdatedState(recent)

    // What shows on the right for an empty query: Recently added, as the hits it shows.
    fun shownHits(s: Shown): List<TitleSearch.Hit> =
        if (s.query.isBlank()) latestRecent.take(RECENT_SHOWN).map { (place, f) -> TitleSearch.Hit(place, f) } else s.found.hits

    // Matching, off the main thread, again for each letter and when the libraries' listings change. A single
    // key answers at once; changes coming faster replace the results once, 150 ms after the last.
    LaunchedEffect(Unit) {
        var lastChange = Long.MIN_VALUE / 2
        var lastQuery = query.value
        snapshotFlow { query.value to listingsNow.value }.collectLatest { (q, _) ->
            if (q != lastQuery) {
                val t = SystemClock.uptimeMillis()
                val wait = resultsWait(t - lastChange)
                lastChange = t
                lastQuery = q
                if (wait > 0) delay(wait)
            }
            val found = withContext(Dispatchers.Default) { TitleSearch.find(q, latestLibraries(), request.prefer) }
            val before = focusedResult[0].takeIf { resultsFocused.value }
            val next = Shown(q, found)
            shown.value = next
            // The focused result went with the change (the phone typed on): the first result, else the keyboard.
            if (before != null && shownHits(next).none { it.file.id == before }) {
                if (shownHits(next).isNotEmpty()) { grid.scrollToItem(0); firstResult.focusSoon() }
                else (lastKey[0] ?: keyA).focusSoon()
            }
        }
    }

    // The phone's keyboard types here (its text replaces the query); Enter there goes to the first result.
    val inputId = remember { RemoteInput.newId() }
    DisposableEffect(covered) {
        if (!covered) RemoteInput.register(inputId, SEARCH_BOX, false, false, query.value)
        onDispose { RemoteInput.unregister(inputId) }
    }
    LaunchedEffect(Unit) { snapshotFlow { query.value }.collect { RemoteInput.updateText(inputId, it) } }
    LaunchedEffect(Unit) {
        RemoteInput.typed.collect { t ->
            if (t.id != inputId) return@collect
            query.value = phoneQuery(t.text)
            if (t.submit) scope.launch {
                withTimeoutOrNull(1_000) { snapshotFlow { shown.value }.first { it.query == query.value } }
                if (shownHits(shown.value).isNotEmpty()) firstResult.focusSoon()
            }
        }
    }
    // What the voice heard replaces the query; the remote's button pressed again listens again.
    LaunchedEffect(Unit) { heard.collect { query.value = spokenQuery(it) } }
    LaunchedEffect(Unit) { listenAgain.collect { if (voice) onListen() } }
    // Opened with the remote's button: listening at once, the focus on the mic.
    LaunchedEffect(Unit) { if (request.listen && voice) { withFrameNanos {}; onListen() } }

    val type: (Char) -> Unit = remember { { c -> query.value = typedInto(query.value, c) } }
    val erase: () -> Unit = remember { { query.value = query.value.dropLast(1) } }
    val clear: () -> Unit = remember { { query.value = "" } }
    Box(
        Modifier.fillMaxSize().ambientBackdrop().onPreviewKeyEvent { e ->
            // A real keyboard's letters, digits, space and delete type from anywhere on the page, never moving the
            // focus; the remote's search (or voice) button listens again.
            val n = e.nativeKeyEvent
            val c = keyChar(n.keyCode)
            when {
                n.keyCode == NativeKeyEvent.KEYCODE_SEARCH || n.keyCode == NativeKeyEvent.KEYCODE_VOICE_ASSIST -> {
                    if (n.action == NativeKeyEvent.ACTION_UP && voice) onListen()
                    true
                }
                n.keyCode == NativeKeyEvent.KEYCODE_DEL -> { if (n.action == NativeKeyEvent.ACTION_DOWN) erase(); true }
                c != null -> { if (n.action == NativeKeyEvent.ACTION_DOWN) type(c); true }
                else -> false
            }
        },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 40.dp)) {
            Column(Modifier.width(FIELD_WIDTH)) {
                QueryField(query, shown)
                Spacer(Modifier.height(16.dp))
                SearchKeyboard(voice, keyA, keyMic, lastKey, type, erase, clear, onListen)
                Text(
                    "Typing on your phone works too (Remote → keyboard).", color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.width(40.dp))
            Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { resultsFocused.value = it.hasFocus }) {
                SearchAnswers(
                    shown, recent, news, offline, down, libraries, listings, voice, token, marks, grid, firstResult, focusedResult,
                    onOpen, onMenu, onPlay,
                )
            }
        }
    }
    // Back on a result: to the keyboard (the key last on), the results gliding back to their top; the query stays.
    ResultsBack(resultsFocused) {
        (lastKey[0] ?: keyA).tryFocus()
        scope.launch {
            if (grid.firstVisibleItemIndex > 12) grid.scrollToItem(6)
            grid.animateScrollToItem(0)
        }
    }
    FocusWhenReady(if (request.listen && voice) keyMic else keyA)
}

/** Focuses [this] once its item is on screen: tried on each of the next ten frames until it takes. */
private suspend fun FocusRequester.focusSoon(): Boolean {
    repeat(10) {
        withFrameNanos {}
        if (tryFocus()) return true
    }
    return false
}

/** Back while a result has the focus ([focused]: read only here, so crossing the columns recomposes nothing else). */
@Composable
private fun ResultsBack(focused: State<Boolean>, onBack: () -> Unit) {
    BackHandler(enabled = focused.value, onBack = onBack)
}

/** The query field and its keys' width: 300 dp. */
private val FIELD_WIDTH = 300.dp

/**
 * The query, display only (never focusable): a pill like Streaming's search field at rest, the magnifier, what's
 * typed (its end kept in view, the start giving way) with a still caret after it, or the hint; and at its end
 * how many titles were found. Reads [query] and [shown] alone, so a letter recomposes only this and the answers.
 */
@Composable
private fun QueryField(query: State<String>, shown: State<Shown>) {
    val q = query.value
    val count = shown.value.takeIf { it.query.isNotBlank() }?.let { TitleSearch.countWords(it.found) }
    Row(
        Modifier.width(FIELD_WIDTH).height(48.dp).clip(Shapes.pill).background(Palette.surface).border(1.5.dp, Palette.rim, Shapes.pill)
            .padding(start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIcon(Sym.Search, size = 22.dp, tint = Palette.text.copy(alpha = 0.6f))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (q.isEmpty()) {
                Caret()
                Text(
                    "Movies & TV Shows", color = Palette.text.copy(alpha = 0.6f), style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp),
                )
            } else {
                Text(
                    q, color = Palette.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(2.dp))
                Caret()
            }
        }
        if (count != null) Text(count, color = Palette.muted, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The still caret, 2 × 22 dp in the Woodruff blue: it never blinks (a blink redraws the screen twice a second while idle). */
@Composable
private fun Caret() {
    Box(Modifier.width(2.dp).height(22.dp).background(Palette.accent))
}

/** A key's corners, and its gliding highlight's. */
private val KeyShape = RoundedCornerShape(12.dp)

/** A key's width: six to a 300 dp row, 4 dp apart. */
private val KEY = (FIELD_WIDTH - 4.dp * 5) / 6
private val KEY_DOUBLE = KEY * 2 + 4.dp
private val KEY_TRIPLE = KEY * 3 + 8.dp

/** The letters and digits, six to a row. */
private val LETTER_ROWS = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")

/**
 * Woodruff's own keyboard (Netflix's arrangement, the quickest with a D-pad): Space, ⌫, Clear and the mic (only on
 * a TV that can listen; else Clear takes its room, so every row still spans 300 dp), then a–z and 0–9, six to a
 * row. One white platter glides from key to key; typing never moves the focus. Holding OK on ⌫ clears.
 */
@Composable
private fun SearchKeyboard(
    voice: Boolean, keyA: FocusRequester, keyMic: FocusRequester, lastKey: Array<FocusRequester?>,
    onType: (Char) -> Unit, onErase: () -> Unit, onClear: () -> Unit, onListen: () -> Unit,
) {
    val platter = rememberFocusPlatter()
    Column(Modifier.focusPlatterHost(platter, KeyShape, Palette.text, clip = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WordKey("Space", KEY_DOUBLE, platter, lastKey) { onType(' ') }
            IconKey(Sym.Backspace, "Delete a letter", KEY, platter, lastKey, onLongClick = onClear) { onErase() }
            WordKey("Clear", if (voice) KEY_DOUBLE else KEY_TRIPLE, platter, lastKey) { onClear() }
            if (voice) IconKey(Sym.Mic, "Say a title", KEY, platter, lastKey, requester = keyMic) { onListen() }
        }
        for (row in LETTER_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in row) {
                    Key(KEY, platter, lastKey, requester = if (c == 'a') keyA else null, onClick = { onType(c) }) { tint ->
                        RowText(c.toString(), tint.color, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun WordKey(label: String, width: Dp, platter: FocusPlatter, lastKey: Array<FocusRequester?>, onClick: () -> Unit) =
    Key(width, platter, lastKey, onClick = onClick) { tint -> RowText(label, tint.color, style = MaterialTheme.typography.labelLarge, maxLines = 1) }

/** A key with a symbol; [description] is what a screen reader says for it. */
@Composable
private fun IconKey(
    icon: ImageVector, description: String, width: Dp, platter: FocusPlatter, lastKey: Array<FocusRequester?>,
    requester: FocusRequester? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) = Key(width, platter, lastKey, requester, onLongClick, onClick, description) { tint -> TintIcon(icon, tint.color, size = 20.dp) }

/**
 * One key: clear at rest, its label the text colour at 85 %; the keyboard's white platter glides under it when it's
 * focused (growing 1.08 with it) and its label turns dark as it draws. Taking the focus, it's the key Back from the
 * results returns to ([lastKey]).
 */
@Composable
private fun Key(
    width: Dp, platter: FocusPlatter, lastKey: Array<FocusRequester?>,
    requester: FocusRequester? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
    description: String? = null,
    label: @Composable (RowTint) -> Unit,
) {
    val motion = rememberFocusMotionState()
    val tint = rememberRowTint(motion, Palette.text.copy(alpha = 0.85f), Palette.background)
    val handle = requester ?: remember { FocusRequester() }
    TvCard(
        onClick = onClick,
        shape = KeyShape,
        modifier = Modifier.width(width).height(40.dp).focusRequester(handle).onFocusChanged { if (it.isFocused) lastKey[0] = handle }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        onLongClick = onLongClick,
        motion = motion,
        focusedScale = 1.08f,
        platter = platter,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { label(tint) }
    }
}

/**
 * The right side: what's found as posters in one ranked grid (three across, six in view), Recently added for an
 * empty query, or what to do; over them, while what's searched is the saved copy, the offline line.
 */
@Composable
private fun SearchAnswers(
    shown: State<Shown>,
    recent: List<Pair<DrivePlace, DriveFile>>,
    news: Map<String, NewShow>,
    offline: String?,
    down: Boolean,
    libraries: () -> Map<DrivePlace, List<DriveFile>>,
    listings: Int,
    voice: Boolean,
    token: String?,
    marks: State<Int>,
    grid: LazyGridState,
    firstResult: FocusRequester,
    focusedResult: Array<String?>,
    onOpen: (TitleSearch.Hit) -> Unit,
    onMenu: (TitleSearch.Hit) -> Unit,
    onPlay: (TitleSearch.Hit) -> Unit,
) {
    val context = LocalContext.current
    val s = shown.value
    val listed = remember(listings) { libraries().isNotEmpty() }
    val what = searchShows(s.query, s.found, listed, down, recent.size, voice)
    val progress = rememberProgress(marks)
    Column(Modifier.fillMaxSize()) {
        if (offline != null && listed) Row(Modifier.padding(start = 18.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(Sym.CloudOff, size = 16.dp, tint = Palette.muted)
            Text(offline, color = Palette.muted, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        // Under the offline line, 12 dp below it; else where the first posters' tops are (14 dp down).
        val line = offline != null && listed
        val above = if (line) 12.dp else 0.dp
        val noteTop = if (line) 12.dp else 14.dp
        when (what) {
            is SearchShows.Waiting -> AnswerNote(what.words, top = noteTop)
            is SearchShows.Hint -> AnswerNote(what.words, top = noteTop)
            is SearchShows.NoMatches -> Column(Modifier.padding(start = 18.dp, top = noteTop)) {
                Text("No matches for “${what.query}”", color = Palette.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Check the spelling, or try fewer letters.", color = Palette.muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            SearchShows.Recent, SearchShows.Results -> {
                val isRecent = what == SearchShows.Recent
                if (isRecent) Text(
                    "Recently added", color = Palette.text, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, modifier = Modifier.padding(start = 18.dp, top = above),
                )
                val hits = if (isRecent) recent.take(RECENT_SHOWN).map { (place, f) -> TitleSearch.Hit(place, f) } else s.found.hits
                // The next row's posters, loaded ahead once the focus rests (as Streaming's grid does): at the
                // library's size, so posters seen there are already in memory.
                val prefetchFrom = remember { mutableIntStateOf(-1) }
                LaunchedEffect(hits) {
                    snapshotFlow { prefetchFrom.intValue }.collectLatest { at ->
                        if (at < 0) return@collectLatest
                        delay(250)
                        prefetchPosters(context, hits.map { it.file }, at + 1, 3, token)
                    }
                }
                // Room above the first row for a focused poster's growth and lift (16 dp under the heading).
                val top = if (isRecent) 16.dp else 14.dp
                PivotScroll(grid, Pivot(offset = top)) {
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = top, bottom = 56.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(hits, key = { _, h -> h.file.id }) { i, hit ->
                            val f = hit.file
                            PosterTile(
                                f, token, onClick = { onOpen(hit) }, onLongClick = { onMenu(hit) },
                                // The answers glide to their new places as the query changes; no fades.
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    .then(if (i == 0) Modifier.focusRequester(firstResult) else Modifier)
                                    .onFocusChanged { if (it.isFocused) { focusedResult[0] = f.id; prefetchFrom.intValue = i } },
                                label = Library.label(f),
                                progress = progress.value?.get(MediaRef.Drive(f.id).key),
                                watched = run { marks.value; posterWatched(context, f, films = hit.place == DrivePlace.Movies) },
                                // A show with new episodes says what's new, in blue (Search's posters are wide enough for words).
                                caption = news[f.id]?.takeIf { it.announced }?.caption,
                                captionColor = ArtAccent,
                                onPlay = { onPlay(hit) },
                            )
                        }
                        if (!isRecent && s.found.capped) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Showing the first ${TitleSearch.LIMIT}. Keep typing to narrow it down.", color = Palette.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A line of words where the first posters would be: what to do, or why there's nothing yet. */
@Composable
private fun AnswerNote(words: String, top: Dp) {
    Text(words, color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 18.dp, top = top))
}
