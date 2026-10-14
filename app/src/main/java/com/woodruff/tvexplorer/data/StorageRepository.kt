package com.woodruff.tvexplorer.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** A mounted storage location: internal storage, an SD card or a USB drive. */
data class StorageRoot(
    val title: String,
    val dir: File,
    val removable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

/** Finds every mounted, readable volume. Re-query whenever a USB drive may have been attached. */
class StorageRepository(private val context: Context) {

    fun roots(): List<StorageRoot> {
        val sm = context.getSystemService(StorageManager::class.java)
        val seen = LinkedHashMap<String, StorageRoot>()

        val volumes: List<StorageVolume> = sm.storageVolumes
        for (v in volumes) {
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val dir = volumeDir(v) ?: continue
            if (!dir.canRead()) continue
            val title = if (v.isPrimary) "Internal storage" else v.getDescription(context) ?: dir.name
            seen[dir.absolutePath] = build(title, dir, v.isRemovable)
        }

        // Fallback for devices whose StorageVolume list is incomplete (common on cheap TV boxes):
        // anything readable directly under /storage that isn't the emulated alias.
        File("/storage").listFiles()?.forEach { dir ->
            if (dir.name == "emulated" || dir.name == "self" || !dir.canRead()) return@forEach
            if (seen.keys.none { File(it).canonicalPath == dir.canonicalPath }) {
                seen[dir.absolutePath] = build("USB / SD (${dir.name})", dir, removable = true)
            }
        }

        if (seen.isEmpty()) {
            val primary = Environment.getExternalStorageDirectory()
            seen[primary.absolutePath] = build("Internal storage", primary, removable = false)
        }
        return seen.values.toList()
    }

    private fun build(title: String, dir: File, removable: Boolean): StorageRoot {
        val (total, free) = try {
            val st = StatFs(dir.absolutePath)
            st.totalBytes to st.availableBytes
        } catch (e: IllegalArgumentException) {
            0L to 0L
        }
        return StorageRoot(title, dir, removable, total, free)
    }

    private fun volumeDir(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory
        // Pre-R: StorageVolume.getPath() exists but is hidden.
        return try {
            (StorageVolume::class.java.getMethod("getPath").invoke(v) as? String)?.let(::File)
        } catch (e: ReflectiveOperationException) {
            if (v.isPrimary) Environment.getExternalStorageDirectory() else null
        }
    }
}
