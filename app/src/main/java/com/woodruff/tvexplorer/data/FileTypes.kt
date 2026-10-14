package com.woodruff.tvexplorer.data

import android.webkit.MimeTypeMap
import java.util.Locale

/** Extension-based type detection. MIME sniffing is avoided because it means opening every file. */
object FileTypes {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "3gp", "wmv", "flv", "mpg", "mpeg")
    private val audio = setOf("mp3", "flac", "aac", "m4a", "ogg", "opus", "wav", "wma", "mka", "alac")
    private val document = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "rtf")
    private val text = setOf("txt", "md", "log", "json", "xml", "csv", "srt", "ass", "vtt", "ini", "cfg", "conf", "yml", "yaml", "html", "htm", "sh", "prop", "properties", "nfo", "m3u", "m3u8")
    private val archive = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindOf(name: String): FileKind = when (extension(name)) {
        in image -> FileKind.IMAGE
        in video -> FileKind.VIDEO
        in audio -> FileKind.AUDIO
        in document -> FileKind.DOCUMENT
        in text -> FileKind.TEXT
        in archive -> FileKind.ARCHIVE
        "apk" -> FileKind.APK
        else -> FileKind.OTHER
    }

    fun extensionsFor(kind: FileKind): Set<String> = when (kind) {
        FileKind.IMAGE -> image
        FileKind.VIDEO -> video
        FileKind.AUDIO -> audio
        FileKind.DOCUMENT -> document
        FileKind.TEXT -> text
        FileKind.ARCHIVE -> archive
        FileKind.APK -> setOf("apk")
        else -> emptySet()
    }

    fun mimeOf(name: String): String {
        val ext = extension(name)
        if (ext == "apk") return "application/vnd.android.package-archive"
        if (ext == "mkv") return "video/x-matroska"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (kindOf(name)) {
            FileKind.VIDEO -> "video/*"
            FileKind.AUDIO -> "audio/*"
            FileKind.IMAGE -> "image/*"
            FileKind.TEXT -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
