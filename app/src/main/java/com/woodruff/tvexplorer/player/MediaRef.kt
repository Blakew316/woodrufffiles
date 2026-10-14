package com.woodruff.tvexplorer.player

import android.net.Uri
import com.woodruff.tvexplorer.cloud.DriveApi
import java.io.File

/**
 * Something the player or photo viewer can show: a local file, a Google Drive file or a web URL.
 * Encoded as a plain string ("/storage/…", "drive:<id>" or "https://…") so lists of them fit in Intent extras
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

    /** A web address sent from the phone ("Open on TV"). */
    data class Remote(val url: String) : MediaRef {
        override val key get() = url
        override val uri: Uri get() = Uri.parse(url)
        override val needsDriveAuth get() = false
    }

    companion object {
        fun parse(key: String): MediaRef = when {
            key.startsWith("drive:") -> Drive(key.removePrefix("drive:"))
            key.startsWith("http://") || key.startsWith("https://") -> Remote(key)
            else -> Local(key)
        }
    }
}
