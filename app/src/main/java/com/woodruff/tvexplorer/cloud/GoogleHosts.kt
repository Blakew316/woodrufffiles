package com.woodruff.tvexplorer.cloud

/**
 * Whether [url] is one of Google's own hosts over https — the only places the Google token may be
 * sent. Drive's thumbnails and owners' posters are on googleusercontent.com, the API on
 * googleapis.com. A look-alike ("evilgoogle.com", "google.com.example.net") or plain http is not.
 */
fun isGoogleUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!"https".equals(uri.scheme, ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return GOOGLE_DOMAINS.any { host == it || host.endsWith(".$it") }
}

private val GOOGLE_DOMAINS = listOf("googleusercontent.com", "google.com", "googleapis.com")
