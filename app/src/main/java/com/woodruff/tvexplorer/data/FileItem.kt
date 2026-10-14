package com.woodruff.tvexplorer.data

import java.io.File

/** Broad file categories used for icons, the Home library tiles and choosing a viewer. */
enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, TEXT, ARCHIVE, APK, OTHER }

/**
 * Immutable snapshot of one file or folder, captured when a directory is listed so the UI never
 * touches the disk while drawing.
 */
data class FileItem(
    val file: File,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    /** Number of entries for folders, or -1 when unknown/unreadable. */
    val childCount: Int,
) {
    val name: String get() = file.name.ifEmpty { file.path }
    val path: String get() = file.absolutePath
    val isHidden: Boolean get() = name.startsWith(".")
    val kind: FileKind get() = if (isDirectory) FileKind.FOLDER else FileTypes.kindOf(name)

    companion object {
        fun from(file: File): FileItem {
            val dir = file.isDirectory
            return FileItem(
                file = file,
                isDirectory = dir,
                size = if (dir) 0 else file.length(),
                lastModified = file.lastModified(),
                childCount = if (dir) file.list()?.size ?: -1 else -1,
            )
        }
    }
}

enum class SortField { NAME, DATE, SIZE, TYPE }

data class SortOrder(val field: SortField = SortField.NAME, val ascending: Boolean = true)
