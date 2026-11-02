package com.woodruff.tvexplorer.notify

/**
 * The notice card in the top-right corner, one size wherever it shows: in Woodruff (AppRoot's
 * banner), over other apps ([NoticeOverlay]) and over a film (the player's doorbell card).
 *
 * Compact: half the screen space of the first design, each side about 70% of it, with the words
 * scaled alike so the same text fits on each line. Every line stays: where it's from, the title
 * and what it says, each allowed a line more than before, so nothing is cut off for being smaller.
 * The card keeps its distance from the screen's edges (some TVs crop them).
 */
object NoticeLook {
    const val WIDTH_DP = 250
    const val PAD_H_DP = 13
    const val PAD_V_DP = 9
    const val CORNER_DP = 14

    /** "FROM YOUR PHONE", "NEW MAIL", "WOODRUFF FAMILY". */
    const val LABEL_SP = 9f
    const val LABEL_LINE_SP = 12f
    const val TITLE_SP = 12f
    const val TITLE_LINE_SP = 16f
    const val BODY_SP = 10f
    const val BODY_LINE_SP = 14f

    const val TITLE_LINES = 3
    const val BODY_LINES = 4
}
