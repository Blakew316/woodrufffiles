package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.ui.Location
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.OpenResult
import com.woodruff.tvexplorer.util.Opener
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.width
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.photos.GooglePhotos
import com.woodruff.tvexplorer.photos.MediaDates
import com.woodruff.tvexplorer.photos.PhotoFilter
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BrowserScreen(vm: MainViewModel) {
    val state by vm.browser.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    val location = state.location ?: return
    // Photos / Videos tabs: these folders fill up from Google Photos.
    val photosFolder = state.folder?.path?.let { p ->
        p.startsWith(GooglePhotos.folder.path) || p.startsWith(GooglePhotos.videoFolder.path)
    } == true
    val photos by GooglePhotos.state.collectAsStateWithLifecycle()
    // Photos / Videos: filter and sort by when each was taken (read in the background, then cached).
    val dateFilterable = photosFolder && location is Location.Folder && !state.selecting
    var photoFilter by remember(location.key) { mutableStateOf(photoFilterMemory[location.key] ?: PhotoFilter()) }
    val dates by produceState<Map<String, Long>?>(null, dateFilterable, state.items) {
        if (!dateFilterable) { value = null; return@produceState }
        val files = state.items.filter { !it.isDirectory }.map { it.file }
        // Show the folder at once (known dates, else file dates), then the exact capture times.
        val (quick, complete) = withContext(Dispatchers.IO) { MediaDates.quick(context, files) }
        value = quick
        if (!complete) value = withContext(Dispatchers.IO) { MediaDates.of(context, files) }
    }
    // Family Memories' search field: narrows what's shown as you'd expect, by name.
    var query by remember(location.key) { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    val shown = remember(state.items, dates, photoFilter, dateFilterable, query) {
        val d = dates
        val dated = if (dateFilterable && d != null) PhotoFilter.apply(state.items, { it.path }, { it.isDirectory }, d, photoFilter) else state.items
        if (query.isBlank()) dated else dated.filter { it.name.contains(query, ignoreCase = true) }
    }
    val listKey = if (dateFilterable) "${location.key}|$photoFilter|$query" else location.key
    LaunchedEffect(photos.step) { if (photos.step == GooglePhotos.Step.DONE) vm.reload() }

    // Back goes up a folder; at the top of Family Memories it brings up the sidebar, as on Apple TV.
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler {
        when {
            query.isNotEmpty() -> query = "" // first, Back clears a search
            photosFolder && !vm.canGoBack() -> openSidebar()
            else -> vm.back()
        }
    }

    val onOpen: (FileItem) -> Unit = { item ->
        when {
            state.selecting -> vm.toggleSelected(item)
            item.isDirectory -> vm.openFolder(item.file)
            else -> when (val r = Opener.open(context, item, shown)) {
                is OpenResult.Failed -> vm.say(r.message)
                OpenResult.NeedsInstallPermission -> dialog = BrowserDialog.InstallPermission
                OpenResult.Opened -> Unit
            }
        }
    }
    val onMenu: (FileItem) -> Unit = { dialog = BrowserDialog.Menu(it) }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp)) {
        if (photosFolder) {
            // Family Memories: Photos / Videos across the top, Netflix style.
            val inVideos = state.folder?.path?.startsWith(GooglePhotos.videoFolder.path) == true
            com.woodruff.tvexplorer.ui.components.TopTabs(
                listOf("Photos", "Videos"), selected = if (inVideos) 1 else 0,
                onSelect = { i -> vm.openRoot((if (i == 0) GooglePhotos.folder else GooglePhotos.videoFolder).apply { mkdirs() }) },
            )
        } else {
            Text(location.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (location) {
                    is Location.Folder -> location.dir.absolutePath
                    is Location.Category -> "All ${location.title.lowercase()} on this TV and attached drives"
                    is Location.Search -> "In ${location.root.absolutePath}"
                },
                color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Family Memories' two controls always fit: no scrolling, which would also clip the focused
        // search field's left edge as it grows.
        val toolScroll = rememberScrollState()
        Row(
            Modifier.padding(vertical = 16.dp).then(if (photosFolder && !state.selecting) Modifier else Modifier.horizontalScroll(toolScroll)),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selecting) {
                Text("${state.selected.size} selected", modifier = Modifier.padding(end = 8.dp))
                val targets = { vm.targets(null) }
                ToolButton(Sym.ContentCopy, "Copy", { vm.copyToClipboard(targets(), move = false) })
                ToolButton(Sym.ContentCut, "Move", { vm.copyToClipboard(targets(), move = true) })
                ToolButton(Sym.Delete, "Delete", { dialog = BrowserDialog.ConfirmDelete(targets()) })
                ToolButton(Sym.FolderZip, "Zip", { dialog = BrowserDialog.Zip(targets()) })
                ToolButton(Sym.SelectAll, null, vm::selectAll)
                ToolButton(Sym.Close, null, vm::clearSelection)
            } else if (photosFolder) {
                // Family Memories: a search field and Filter, nothing else.
                val inVideos = state.folder?.path?.startsWith(GooglePhotos.videoFolder.path) == true
                com.woodruff.tvexplorer.ui.components.SearchBar(
                    query, if (inVideos) "Search videos" else "Search photos", { typing = true }, Modifier.width(520.dp),
                )
                if (dateFilterable) RoundButton(Sym.Tune, "Filter", { filtering = true }, active = photoFilter != PhotoFilter())
            } else {
                val folder = state.folder
                if (folder != null) {
                    ToolButton(Sym.ArrowUpward, null, vm::up)
                    ToolButton(Sym.CreateNewFolder, null, { dialog = BrowserDialog.NewFolder })
                    clipboard?.let { clip ->
                        ToolButton(Sym.ContentPaste, "Paste ${clip.files.size}", vm::paste)
                        ToolButton(Sym.Close, null, vm::clearClipboard)
                    }
                }
                ToolButton(Sym.Search, null, { dialog = BrowserDialog.Search })
                // Photos / Videos sort by date with their own filters below.
                if (!dateFilterable) ToolButton(Sym.Sort, null, { dialog = BrowserDialog.Sort })
                ToolButton(if (settings.grid) Sym.ViewList else Sym.GridView, null, {
                    vm.updateSettings { it.copy(grid = !it.grid) }
                })
                ToolButton(if (settings.showHidden) Sym.Visibility else Sym.VisibilityOff, null, {
                    vm.updateSettings { it.copy(showHidden = !it.showHidden) }
                })
            }
        }

        if (filtering) {
            PhotoFilterSheet(dates?.values.orEmpty(), photoFilter, { photoFilter = it; photoFilterMemory[location.key] = it }, onDismiss = { filtering = false })
        }
        if (typing) {
            TextInputDialog(
                if (photosFolder && state.folder?.path?.startsWith(GooglePhotos.videoFolder.path) == true) "Search videos" else "Search photos",
                query, "Search", onConfirm = { query = it }, onDismiss = { typing = false },
            )
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> EmptyState("Loading…")
                state.error != null -> EmptyState(state.error!!)
                state.items.isEmpty() -> EmptyState(
                    when {
                        location is Location.Search -> "No matches"
                        photosFolder -> "Nothing here yet. Add some in Settings → Accounts → Google Photos."
                        else -> "This folder is empty"
                    },
                )
                dateFilterable && dates == null -> EmptyState("Loading…")
                shown.isEmpty() -> EmptyState(if (query.isNotBlank()) "Nothing called \u201c$query\u201d" else "Nothing from then")
                settings.grid -> FileGrid(vm, listKey, shown, state.selected, onOpen, onMenu)
                else -> FileList(vm, listKey, shown, state.selected, onOpen, onMenu)
            }
        }
    }

    dialog?.let { BrowserDialogs(it, vm, onDismiss = { dialog = null }, show = { next -> dialog = next }) }
}

/** Photos / Videos filter per folder, kept while the app runs. */
private val photoFilterMemory = HashMap<String, PhotoFilter>()

/**
 * Returns to the item you were on when you come back to a folder: scrolls to it, then focuses it.
 * Without this, TV users lose their place every time they press Back.
 */
@Composable
private fun rememberRestoredFocus(vm: MainViewModel, key: String, count: Int, scrollTo: suspend (Int) -> Unit): Pair<Int, FocusRequester> {
    val target = remember(key) { vm.focusFor(key).coerceIn(0, (count - 1).coerceAtLeast(0)) }
    val requester = remember(key) { FocusRequester() }
    LaunchedEffect(key) {
        scrollTo(target)
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return target to requester
}

@Composable
private fun FileList(
    vm: MainViewModel, key: String, items: List<FileItem>, selected: Set<String>,
    onOpen: (FileItem) -> Unit, onMenu: (FileItem) -> Unit,
) {
    val listState = rememberLazyListState()
    val (target, requester) = rememberRestoredFocus(vm, key, items.size) { listState.scrollToItem(it) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.path }) { i, item ->
            FileRow(
                item = item,
                selecting = selected.isNotEmpty(),
                selected = item.path in selected,
                onClick = { onOpen(item) },
                onLongClick = { onMenu(item) },
                modifier = Modifier
                    .then(if (i == target) Modifier.focusRequester(requester) else Modifier)
                    .onFocusChanged { if (it.isFocused) vm.rememberFocus(key, i) },
            )
        }
    }
}

@Composable
private fun FileGrid(
    vm: MainViewModel, key: String, items: List<FileItem>, selected: Set<String>,
    onOpen: (FileItem) -> Unit, onMenu: (FileItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val (target, requester) = rememberRestoredFocus(vm, key, items.size) { gridState.scrollToItem(it) }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(220.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.path }) { i, item ->
            FileTile(
                item = item,
                selecting = selected.isNotEmpty(),
                selected = item.path in selected,
                onClick = { onOpen(item) },
                onLongClick = { onMenu(item) },
                modifier = Modifier
                    .then(if (i == target) Modifier.focusRequester(requester) else Modifier)
                    .onFocusChanged { if (it.isFocused) vm.rememberFocus(key, i) },
            )
        }
    }
}
