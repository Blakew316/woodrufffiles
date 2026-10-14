package com.woodruff.tvexplorer.ui

import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.OpProgress
import java.io.File

enum class Screen { HOME, BROWSER, DRIVE, APPS, TRANSFER }

/** What the browser is showing: a real folder, a library category, or search results. */
sealed interface Location {
    val key: String
    val title: String

    data class Folder(val dir: File) : Location {
        override val key get() = "dir:${dir.absolutePath}"
        override val title: String get() = dir.name.ifEmpty { "/" }
    }

    data class Category(val kind: FileKind, override val title: String) : Location {
        override val key get() = "cat:$kind"
    }

    data class Search(val root: File, val query: String) : Location {
        override val key get() = "search:${root.absolutePath}:$query"
        override val title get() = "Search: $query"
    }
}

data class BrowserState(
    val location: Location? = null,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Paths of multi-selected items; selection mode is on whenever this is non-empty. */
    val selected: Set<String> = emptySet(),
) {
    val selecting: Boolean get() = selected.isNotEmpty()
    val folder: File? get() = (location as? Location.Folder)?.dir
}

data class Clipboard(val files: List<File>, val move: Boolean)

/** A running copy/move/delete/zip; null progress means it's still being prepared. */
data class Operation(val title: String, val progress: OpProgress? = null)
