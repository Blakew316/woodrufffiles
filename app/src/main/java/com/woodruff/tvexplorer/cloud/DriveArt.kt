package com.woodruff.tvexplorer.cloud

import coil.intercept.Interceptor
import coil.request.ImageResult

/**
 * Google-hosted pictures of Drive files (a video's frame, a folder's poster image) known by the version of the file
 * they show, not by their link.
 *
 * Drive hands out a new short-lived thumbnail link for the same picture every few hours. As cache keys those links
 * made a picture already on the TV miss Coil's memory and disk caches and come from Google again, on every TV, and
 * made every listing look changed, so it was saved and shown again. So each link a listing brings is tagged with
 * its file's id and modified time, as the link's fragment ("…=s220#drive:<id>:<modified>"): a fragment is never sent
 * with a request, so Google gets the link as it gave it. [key] is the picture's key in the caches ([Keys] sets it on
 * every image request): the same whatever link Drive gave this time, and a new one when the file changes in Drive (a
 * new poster put in its place moves its modified time), so that picture is fetched again. Listings are compared
 * without their links ([sameIgnoringLinks]).
 */
object DriveArt {
    private const val TAG = "#drive:"

    /**
     * How old a saved listing's links may get before a listing whose only change is new links saves it anyway: Drive's
     * links last hours, and the phone's posters are fetched with the saved ones.
     */
    const val SAVED_LINKS_MS = 60 * 60_000L

    /** Whether a saved listing whose links were the latest at [savedAt] (0: none saved) keeps them at [now] ([SAVED_LINKS_MS]). */
    fun savedLinksLast(savedAt: Long, now: Long): Boolean = savedAt > 0 && now - savedAt in 0 until SAVED_LINKS_MS

    /** A thumbnail's size as Drive's link asks for it ("=s720", "=s220-c", "=w342"). */
    private val SIZE = Regex("=[swh]\\d+[^/=#]*$")

    /** [link] tagged as file [id]'s picture at version [modified] (a tag it had is replaced). */
    fun tag(link: String, id: String, modified: Long): String = link(link) + TAG + id + ":" + modified

    /** [url] without its tag: the link Drive gave. */
    fun link(url: String): String = url.indexOf(TAG).let { if (it < 0) url else url.substring(0, it) }

    /** [url]'s tag ("#drive:<id>:<modified>"), or "" when it has none: what goes back on the end of a link changed. */
    fun tagOf(url: String): String = url.indexOf(TAG).let { if (it < 0) "" else url.substring(it) }

    /**
     * [url]'s key in the image caches: the file version it shows and the size it's asked at
     * ("drive:<id>:<modified>=s720"), whatever its link; null for a picture whose link isn't tagged (TMDB's, whose
     * addresses never change, or one saved before links were tagged).
     */
    fun key(url: String): String? {
        val at = url.indexOf(TAG)
        if (at < 0) return null
        val size = SIZE.find(url.substring(0, at))?.value.orEmpty()
        return url.substring(at + 1) + size
    }

    /** What a listing compares of [url]: its [key], else the address itself. */
    private fun identity(url: String?): String? = url?.let { key(it) ?: it }

    /** [f] with its Drive links as their [key]s: what stays the same while only the links change. */
    private fun withoutLinks(f: DriveFile): DriveFile =
        if (f.thumbnailLink == null && f.posterUrl == null) f else f.copy(thumbnailLink = identity(f.thumbnailLink), posterUrl = identity(f.posterUrl))

    /** Whether [a] and [b] are the same but for Drive's links: the same file, name, version, details and art. */
    fun sameIgnoringLinks(a: DriveFile, b: DriveFile): Boolean = a == b || withoutLinks(a) == withoutLinks(b)

    /** [sameIgnoringLinks] for two listings, entry by entry; none (null) is the same only as none. */
    fun sameIgnoringLinks(a: List<DriveFile>?, b: List<DriveFile>?): Boolean {
        if (a === b) return true
        if (a == null || b == null || a.size != b.size) return false
        for (i in a.indices) if (!sameIgnoringLinks(a[i], b[i])) return false
        return true
    }

    /**
     * Gives each request for a tagged Drive picture its [key] as its memory and disk cache key (a request that names
     * its own keeps them). Installed in the app's image loader (TvExplorerApp), so every screen's pictures, and those
     * loaded ahead, are found by it.
     */
    object Keys : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            val url = when (val data = request.data) {
                is String -> data
                is android.net.Uri -> data.toString()
                is okhttp3.HttpUrl -> data.toString()
                else -> null
            }
            val key = url?.let(::key)
            if (key == null || (request.memoryCacheKey != null && request.diskCacheKey != null)) return chain.proceed(request)
            return chain.proceed(
                request.newBuilder().apply {
                    if (request.memoryCacheKey == null) memoryCacheKey(key)
                    if (request.diskCacheKey == null) diskCacheKey(key)
                }.build(),
            )
        }
    }
}
