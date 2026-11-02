package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.woodruff.tvexplorer.ui.LocalOpenSidebar
import com.woodruff.tvexplorer.ui.motion.BackToTopHandler
import com.woodruff.tvexplorer.ui.motion.focusAtTop
import com.woodruff.tvexplorer.ui.motion.glideToTop
import com.woodruff.tvexplorer.ui.motion.isScrolled
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/*
 * Back to the top, where Back used to open the side menu straight away (browsing-motion §2, §3.3):
 * Streaming's Overview rows and its grid, and the top of Family Memories. The glides and the focus
 * rules are BackToTop's (ui/motion); these wire them to each screen's list.
 */

/** Where the focus is in the Overview, for Back to the top. */
internal class RowsBack {
    /** Whether the focus is anywhere in the banner or the rows. */
    val held: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The row whose poster last took the focus: null once the banner has it. Only meaningful while
     * [held] (the focus may have left for the header since).
     */
    val row: MutableState<String?> = mutableStateOf(null)

    private val starts = HashMap<String, FocusRequester>()

    /** The focus handle on [title]'s first poster. */
    fun start(title: String): FocusRequester = starts.getOrPut(title) { FocusRequester() }

    /**
     * A poster in [title] taking ([on]) or not having the focus. Only taking it counts: a poster
     * scrolled into view reports "not focused" as it's composed, which says nothing about the row.
     */
    fun focused(title: String, on: Boolean) {
        if (on) row.value = title
    }
}

@Composable
internal fun rememberRowsBack(): RowsBack = remember { RowsBack() }

/**
 * Back in the Overview, each press doing the first of these that applies: the page scrolled down →
 * it glides to the top and the banner's Play takes the focus (with no banner, the first row's first
 * poster, that row gliding back to its start too); at the top with the focused row scrolled along →
 * that row glides back to its start and its first poster takes the focus. Otherwise Back is left to
 * the screen (the side menu). Rows keep their sideways places otherwise.
 *
 * [firstRow] is the first row's title; [banner] the banner's Play, null when there's no banner.
 */
@Composable
internal fun RowsBackToTop(back: RowsBack, list: LazyListState, rowStates: Map<String, LazyListState>, firstRow: String?, banner: FocusRequester?) {
    val openSidebar = LocalOpenSidebar.current
    BackToTopHandler(
        scrolled = { back.held.value && (list.isScrolled() || back.row.value?.let { rowStates[it] }?.isScrolled() == true) },
        next = openSidebar,
    ) {
        // Where the focus was when Back was pressed: if it moves meanwhile, the glide was cut short
        // by that move, and the focus stays where it went.
        val from = back.row.value
        val stillHere = { back.held.value && back.row.value == from }
        if (list.isScrolled()) {
            val rowToStart = if (banner == null) firstRow?.let { rowStates[it] } else null
            // Both glides together; a move cutting either short ends here (await throws).
            coroutineScope {
                val page = async { list.glideToTop() }
                val first = rowToStart?.let { async { it.animateScrollToItem(0) } }
                page.await()
                first?.await()
            }
            focusAtTop(stillHere, listOfNotNull(banner, firstRow?.let(back::start)), orElse = openSidebar)
        } else if (from != null) {
            val row = rowStates[from] ?: return@BackToTopHandler
            row.animateScrollToItem(0)
            focusAtTop(stillHere, listOf(back.start(from)), orElse = openSidebar)
        }
    }
}

/**
 * Back far down a grid (Streaming's All, Unwatched, a genre or a decade; Family Memories' photos):
 * glides to its top and puts the focus on its first item ([first]). Only while the focus is in the
 * grid ([held]); a grid that fits on one screen leaves Back to the screen (the side menu).
 */
@Composable
internal fun GridBackToTop(grid: LazyGridState, first: FocusRequester, held: State<Boolean>) {
    val openSidebar = LocalOpenSidebar.current
    BackToTopHandler(scrolled = { held.value && grid.isScrolled() }, next = openSidebar) {
        grid.glideToTop()
        focusAtTop({ held.value }, listOf(first), orElse = openSidebar)
    }
}

/** [GridBackToTop] for a list (Family Memories shown as a list). */
@Composable
internal fun ListBackToTop(list: LazyListState, first: FocusRequester, held: State<Boolean>) {
    val openSidebar = LocalOpenSidebar.current
    BackToTopHandler(scrolled = { held.value && list.isScrolled() }, next = openSidebar) {
        list.glideToTop()
        focusAtTop({ held.value }, listOf(first), orElse = openSidebar)
    }
}
