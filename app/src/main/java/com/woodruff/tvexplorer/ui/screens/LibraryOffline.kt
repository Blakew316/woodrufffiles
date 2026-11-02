package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.said
import com.woodruff.tvexplorer.ui.components.FieldStatus
import com.woodruff.tvexplorer.ui.components.OfflineWords
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.tryFocus
import com.woodruff.tvexplorer.ui.icons.Sym
import com.woodruff.tvexplorer.ui.theme.Palette

/*
 * Streaming when Google Drive can't be reached (see the offline-states spec): over a saved library
 * the state lives in the search field's end, so nothing in the header moves; with nothing saved, a
 * calm page in place of the posters says what's happening and offers Try again. Keys Google refuses
 * get a page of their own, even over a saved library: nothing will refresh until they're fixed.
 */

/**
 * What Streaming's search field says at its end, or nothing: only over a saved library ([hasFiles];
 * with nothing saved the page says it), and only once the offline grace is over. "Back online", with
 * its green check, is the good news before the words go.
 */
internal fun libraryFieldStatus(status: DriveStatus, hasFiles: Boolean): FieldStatus? {
    if (!hasFiles) return null
    return when (status) {
        is DriveStatus.Offline -> if (status.shown) FieldStatus(Sym.CloudOff, OfflineWords.short(status.google)) else null
        is DriveStatus.Reconnecting -> FieldStatus(Sym.CloudOff, OfflineWords.RECONNECTING)
        is DriveStatus.BackOnline -> FieldStatus(Sym.Check, OfflineWords.BACK_ONLINE, good = true)
        DriveStatus.Online, is DriveStatus.Refused -> null
    }
}

/**
 * The search field's hint: "Search your saved library" while what it searches is the copy saved on
 * the TV because a refresh didn't get through (until "Back online" has gone), else "Search Movies & TV
 * Shows" (the field opens Search, which looks in both libraries, whichever [title] it's in).
 */
@Suppress("UNUSED_PARAMETER")
internal fun librarySearchHint(title: String, status: DriveStatus, hasFiles: Boolean): String =
    if (hasFiles && (status.said || status is DriveStatus.BackOnline || status is DriveStatus.Refused)) "Search your saved library"
    else SEARCH_BOX

/** A calm page Streaming shows in place of its posters. */
internal sealed interface LibraryPage {
    /** Nothing saved, and Google can't be reached: [google] the network is fine; [trying] a retry is under way. */
    data class Offline(val google: Boolean, val trying: Boolean) : LibraryPage

    /** Google refuses the keys ("Keys not accepted"): shown even over a saved library. */
    data object Keys : LibraryPage
}

/** The page for [status], or null for the posters (or "Loading", within the offline grace). */
internal fun libraryPage(status: DriveStatus, hasFiles: Boolean): LibraryPage? = when {
    status is DriveStatus.Refused -> LibraryPage.Keys
    hasFiles -> null
    status is DriveStatus.Offline && status.shown -> LibraryPage.Offline(status.google, trying = false)
    status is DriveStatus.Reconnecting -> LibraryPage.Offline(status.was.google, trying = true)
    else -> null
}

/** What a [LibraryPage] says: its symbol, headline, line, and its one button. */
@Immutable
internal data class PageWords(val icon: ImageVector, val title: String, val body: String, val action: String, val actionIcon: ImageVector)

/**
 * [page]'s words for what would show there: "Movies", "TV shows", or "This folder" ([what], as it
 * starts a sentence).
 */
internal fun libraryPageWords(page: LibraryPage, what: String): PageWords = when (page) {
    is LibraryPage.Offline -> PageWords(
        Sym.CloudOff,
        title = if (page.google) "Can’t reach Google Drive right now" else "This TV is offline",
        body = if (page.google) "Trying again by itself. $what will show here as soon as Google answers."
        else "$what will show here as soon as the TV is back online.",
        action = if (page.trying) "Trying…" else "Try again", actionIcon = Sym.Refresh,
    )
    LibraryPage.Keys -> PageWords(
        Sym.Key, "Check the Google keys",
        "Google doesn’t accept the Client ID or secret saved on this TV. Fix them in the phone app " +
            "(Settings → Connections → Google Drive) or here, then sign in again.",
        action = "Open Keys", actionIcon = Sym.Key,
    )
}

/** What would show in [place], as a page's sentence starts with it. */
internal fun libraryPageSubject(place: DrivePlace, top: Boolean): String = when {
    !top -> "This folder"
    place == DrivePlace.TvShows -> "TV shows"
    else -> place.title
}

/**
 * A [LibraryPage] in the space under the header: a symbol, the headline, a line or three, and one
 * button, in a centred column 520 dp wide. The button ([RoundButton], white when focused) takes the
 * focus as the page appears when [takeFocus] says it may (not while someone is choosing in the
 * header); Up goes to the header, and Right and Down stay. While a retry is under way it reads
 * "Trying…" and OK does nothing. The same page from one state to the next, so the focus stays on it.
 */
@Composable
internal fun LibraryPagePanel(
    page: LibraryPage,
    subject: String,
    onTryAgain: () -> Unit,
    onOpenKeys: () -> Unit,
    takeFocus: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val words = libraryPageWords(page, subject)
    val button = remember { FocusRequester() }
    CalmPage(
        words, modifier,
        onAction = {
            when (page) {
                LibraryPage.Keys -> onOpenKeys()
                is LibraryPage.Offline -> if (!page.trying) onTryAgain()
            }
        },
        buttonModifier = Modifier.focusRequester(button).focusProperties { right = FocusRequester.Cancel; down = FocusRequester.Cancel },
    )
    LaunchedEffect(Unit) {
        withFrameNanos {} // once the button is laid out
        if (!takeFocus()) return@LaunchedEffect
        // A few frames more if it isn't attached yet (a request with nothing attached does nothing).
        repeat(10) { if (button.tryFocus()) return@LaunchedEffect; withFrameNanos {} }
    }
}

/**
 * A calm page in place of a screen's content ([words]): a symbol, the headline, a line or three, and one
 * button ([onAction]), in a centred column 520 dp wide. Streaming's offline and keys pages, and Search's
 * pages for when Google Drive isn't ready, are this. [buttonModifier]: the button's focus handle and moves.
 */
@Composable
internal fun CalmPage(words: PageWords, modifier: Modifier = Modifier, onAction: () -> Unit, buttonModifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TvIcon(words.icon, size = 56.dp, tint = Palette.muted)
            // Lines of even length (no word left alone on the last one), as a centred block should read.
            Text(
                words.title, style = MaterialTheme.typography.headlineSmall.copy(lineBreak = LineBreak.Heading), color = Palette.text,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                words.body, style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Heading), color = Palette.muted,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
            )
            RoundButton(words.actionIcon, words.action, onClick = onAction, modifier = Modifier.padding(top = 24.dp).then(buttonModifier))
        }
    }
}
