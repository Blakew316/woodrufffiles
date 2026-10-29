package com.woodruff.tvexplorer.cloud

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Folder listings (a show's seasons, a season's episodes, a film's own folder), so title pages
 * open complete: [peek] gives the last listing at once, [list] fetches it fresh and keeps it.
 */
object FolderCache {
    private val memory = ConcurrentHashMap<String, List<DriveFile>>()

    /** The last listing of [folderId]; null when it hasn't been listed. */
    fun peek(c: Context, folderId: String): List<DriveFile>? = memory[folderId]

    /** Lists [folder] from Drive with a current token and keeps it; null when it can't be listed. */
    suspend fun list(c: Context, token: String?, folder: DriveFile): List<DriveFile>? = try {
        val t = GoogleAuth.token(c) ?: token
        if (t == null) null else DriveApi.list(t, DrivePlace.Folder(folder.id, folder.name)).also { memory[folder.id] = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Signed out: forget every listing. */
    fun clear(c: Context) = memory.clear()
}
