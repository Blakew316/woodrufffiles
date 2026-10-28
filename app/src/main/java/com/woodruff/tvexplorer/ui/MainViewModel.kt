package com.woodruff.tvexplorer.ui

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.data.FileRepository
import com.woodruff.tvexplorer.data.OpProgress
import com.woodruff.tvexplorer.data.SortField
import com.woodruff.tvexplorer.data.SortOrder
import com.woodruff.tvexplorer.data.StorageRepository
import com.woodruff.tvexplorer.data.StorageRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for the main window: which screen is up, what the browser shows, the
 * clipboard, any running operation and transient messages. Screens only render this state and
 * call the action methods.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val files = FileRepository(app)
    private val storage = StorageRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _roots = MutableStateFlow<List<StorageRoot>>(emptyList())
    val roots: StateFlow<List<StorageRoot>> = _roots.asStateFlow()

    private val _browser = MutableStateFlow(BrowserState())
    val browser: StateFlow<BrowserState> = _browser.asStateFlow()

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()

    private val _operation = MutableStateFlow<Operation?>(null)
    val operation: StateFlow<Operation?> = _operation.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ViewSettings> = _settings.asStateFlow()

    private val history = ArrayDeque<Location>()
    private val focusMemory = HashMap<String, Int>()
    private var loadJob: Job? = null
    private var opJob: Job? = null

    init { refreshRoots() }

    // ---- Navigation -------------------------------------------------------------------------

    fun refreshRoots() {
        viewModelScope.launch {
            _roots.value = withContext(Dispatchers.IO) { runCatching { storage.roots() }.getOrDefault(emptyList()) }
        }
    }

    /** Where Back leaves the browser to: Home, or Settings when a drive was opened from there. */
    private var browserReturn = Screen.HOME

    /** Where Back from the Apps screen goes: Home, or Settings when opened from there. */
    var appsReturn = Screen.HOME
        private set

    fun openApps(from: Screen) {
        appsReturn = from
        show(Screen.APPS)
    }

    /** Switch top-level screen from the side rail. Leaving the browser forgets its back stack. */
    fun show(screen: Screen) {
        if (screen != Screen.BROWSER) {
            browserReturn = Screen.HOME
            loadJob?.cancel()
            history.clear()
            _browser.value = BrowserState()
        }
        _screen.value = screen
        if (screen == Screen.HOME) refreshRoots()
    }

    fun open(location: Location) {
        _browser.value.location?.let { if (it != location) history.addLast(it) }
        _screen.value = Screen.BROWSER
        load(location)
    }

    fun openFolder(dir: File) = open(Location.Folder(dir))

    /** Jump to a storage root from the side rail, starting a fresh back stack. */
    fun openRoot(dir: File, from: Screen = Screen.HOME) {
        browserReturn = from
        history.clear()
        _browser.value = BrowserState()
        open(Location.Folder(dir))
    }

    fun openDownloads() = openFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))

    /** Back key inside the browser: leave selection mode, then step back, then return Home. */
    /** Whether Back has somewhere to go within the browser (a selection to clear, a folder to go up from). */
    fun canGoBack(): Boolean = _browser.value.selecting || history.isNotEmpty()

    fun back() {
        if (_browser.value.selecting) return clearSelection()
        val prev = history.removeLastOrNull()
        if (prev != null) load(prev) else show(browserReturn)
    }

    /** Parent folder, but never above the storage root the user is in. */
    fun up() {
        val dir = _browser.value.folder ?: return
        val parent = dir.parentFile ?: return
        if (_roots.value.any { it.dir.absolutePath == dir.absolutePath }) return
        open(Location.Folder(parent))
    }

    fun reload() { _browser.value.location?.let(::load) }

    fun search(query: String) {
        val root = _browser.value.folder ?: _roots.value.firstOrNull()?.dir ?: return
        if (query.isNotBlank()) open(Location.Search(root, query))
    }

    private fun load(location: Location) {
        loadJob?.cancel()
        _browser.value = BrowserState(location = location, loading = true)
        loadJob = viewModelScope.launch {
            val s = _settings.value
            val result = runCatching {
                when (location) {
                    is Location.Folder -> files.list(location.dir, s.showHidden, s.sort)
                    is Location.Category -> FileRepository.sorted(files.category(location.kind, s.showHidden), s.sort)
                    is Location.Search -> files.search(location.root, location.query, s.showHidden)
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _browser.update {
                it.copy(
                    items = result.getOrDefault(emptyList()),
                    loading = false,
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "Couldn't open this folder" },
                )
            }
        }
    }

    // ---- Focus memory: return to the item you came from ---------------------------------------

    fun rememberFocus(key: String, index: Int) { focusMemory[key] = index }
    fun focusFor(key: String): Int = focusMemory[key] ?: 0

    // ---- Selection -------------------------------------------------------------------------

    fun toggleSelected(item: FileItem) = _browser.update {
        it.copy(selected = if (item.path in it.selected) it.selected - item.path else it.selected + item.path)
    }

    fun selectAll() = _browser.update { it.copy(selected = it.items.map { i -> i.path }.toSet()) }
    fun clearSelection() = _browser.update { it.copy(selected = emptySet()) }

    /** The files an action applies to: the selection if there is one, otherwise [fallback]. */
    fun targets(fallback: FileItem?): List<File> {
        val s = _browser.value
        return if (s.selecting) s.items.filter { it.path in s.selected }.map { it.file } else listOfNotNull(fallback?.file)
    }

    // ---- Settings ----------------------------------------------------------------------------

    fun updateSettings(change: (ViewSettings) -> ViewSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit()
            .putBoolean("hidden", next.showHidden).putBoolean("grid", next.grid)
            .putString("sort", next.sort.field.name).putBoolean("asc", next.sort.ascending)
            .apply()
        reload()
    }

    private fun loadSettings() = ViewSettings(
        showHidden = prefs.getBoolean("hidden", false),
        grid = prefs.getBoolean("grid", true),
        sort = SortOrder(
            runCatching { SortField.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(SortField.NAME),
            prefs.getBoolean("asc", true),
        ),
    )

    // ---- File operations -------------------------------------------------------------------

    fun copyToClipboard(targets: List<File>, move: Boolean) {
        if (targets.isEmpty()) return
        _clipboard.value = Clipboard(targets, move)
        clearSelection()
        say("${targets.size} item(s) ${if (move) "cut" else "copied"}. Open a folder and choose Paste.")
    }

    fun clearClipboard() { _clipboard.value = null }

    fun paste() {
        val clip = _clipboard.value ?: return
        val dest = _browser.value.folder ?: return say("Open a folder to paste into")
        runOp(if (clip.move) "Moving" else "Copying", "Pasted ${clip.files.size} item(s)") { p ->
            FileOperations.copy(clip.files, dest, clip.move, p)
            if (clip.move) _clipboard.value = null
        }
    }

    fun delete(targets: List<File>) = runOp("Deleting", "Deleted ${targets.size} item(s)") { p ->
        FileOperations.delete(targets, p)
    }

    fun rename(file: File, name: String) = runOp("Renaming", "Renamed to $name") { FileOperations.rename(file, name) }

    fun createFolder(name: String) {
        val dir = _browser.value.folder ?: return
        runOp("Creating folder", "Created $name") { FileOperations.createFolder(dir, name) }
    }

    fun compress(targets: List<File>, name: String) {
        val dir = targets.firstOrNull()?.parentFile ?: return
        val zipName = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
        runOp("Compressing", "Created $zipName") { p ->
            FileOperations.validateName(zipName)
            FileOperations.zip(targets, FileOperations.uniqueTarget(dir, zipName), p)
        }
    }

    fun extract(zip: File) {
        val dir = zip.parentFile ?: return
        val dest = FileOperations.uniqueTarget(dir, zip.nameWithoutExtension)
        runOp("Extracting", "Extracted to ${dest.name}") { p -> FileOperations.unzip(zip, dest, p) }
    }

    fun cancelOperation() { opJob?.cancel() }

    private fun runOp(title: String, done: String, block: suspend ((OpProgress) -> Unit) -> Unit) {
        if (opJob?.isActive == true) return say("Another operation is still running")
        _operation.value = Operation(title)
        var lastEmit = 0L
        val onProgress: (OpProgress) -> Unit = { p ->
            val now = System.currentTimeMillis()
            if (now - lastEmit > 120) { lastEmit = now; _operation.value = Operation(title, p) }
        }
        opJob = viewModelScope.launch {
            try {
                block(onProgress)
                say(done)
            } catch (e: CancellationException) {
                say("$title cancelled")
            } catch (e: Exception) {
                say(e.message ?: "$title failed")
            } finally {
                _operation.value = null
                clearSelection()
                reload()
                refreshRoots()
            }
        }
    }

    // ---- Messages -----------------------------------------------------------------------

    fun say(text: String) { _message.value = text }
    fun dismissMessage() { _message.value = null }
}

data class ViewSettings(
    val showHidden: Boolean = false,
    val grid: Boolean = false,
    val sort: SortOrder = SortOrder(),
)
