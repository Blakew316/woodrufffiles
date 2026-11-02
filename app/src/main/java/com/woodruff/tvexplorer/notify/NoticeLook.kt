package com.woodruff.tvexplorer.notify

/**
 * The notice card in the top-right corner, one size wherever it shows: in Woodruff (AppRoot's
 * banner), over other apps ([NoticeOverlay]) and over a film (the player's doorbell card).
 *
 * Compact: the words at about 70% of the first design's size, and the card only as wide as its
 * longest line (up to [MAX_WIDTH_DP], where longer words wrap), so there's no empty space beside
 * them. Every line stays: where it's from, the title and what it says, each allowed a line more
 * than before, so nothing is cut off. The card keeps its distance from the screen's edges (some
 * TVs crop them), growing leftwards from there.
 */
object NoticeLook {
    const val MAX_WIDTH_DP = 250
    const val PAD_H_DP = 13
    const val PAD_V_DP = 9
    const val CORNER_DP = 14
    /** The widest the words get: [MAX_WIDTH_DP] less the padding on both sides. */
    const val MAX_TEXT_DP = MAX_WIDTH_DP - 2 * PAD_H_DP

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
