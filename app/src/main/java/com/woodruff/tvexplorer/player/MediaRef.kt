package com.woodruff.tvexplorer.player

import android.net.Uri
import com.woodruff.tvexplorer.cloud.DriveApi
import java.io.File

/**
 * Something the player or photo viewer can show: a local file or a Google Drive file.
 * Encoded as a plain string ("/storage/…" or "drive:<id>") so lists of them fit in Intent extras
 * and in the Continue-watching history.
 */
sealed interface MediaRef {
    val key: String
    val uri: Uri
    val needsDriveAuth: Boolean

    data class Local(val path: String) : MediaRef {
        override val key get() = path
        override val uri: Uri get() = Uri.fromFile(File(path))
        override val needsDriveAuth get() = false
    }

    data class Drive(val id: String) : MediaRef {
        override val key get() = "drive:$id"
        override val uri: Uri get() = Uri.parse(DriveApi.mediaUrl(id))
        override val needsDriveAuth get() = true
    }

    companion object {
        fun parse(key: String): MediaRef =
            if (key.startsWith("drive:")) Drive(key.removePrefix("drive:")) else Local(key)
    }
}
