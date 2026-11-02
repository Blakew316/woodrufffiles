package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.nowplaying.PlayerCommand

/**
 * What the player does with ↺10, 30↻ or Next sent from the phone (its Watch tab and TV Screen
 * sheet, through [com.woodruff.tvexplorer.nowplaying.NowPlaying.commands]): see [phoneStep].
 */
internal sealed interface PhoneStep {
    /** A jump to [toMs] in what's playing. [playOn]: back out of Up next into the episode's last seconds, playing them. */
    data class Seek(val toMs: Long, val playOn: Boolean = false) : PhoneStep

    /** On to the next item in the queue: the player's own Next Episode. */
    data object Next : PhoneStep

    /** Nothing to do (see [phoneStep]). */
    data object Stay : PhoneStep
}

/**
 * What [cmd] from the phone does to the player, at [positionMs] of [durationMs] (0 or less: not
 * known yet). ↺10 and 30↻ land where [PlayerCommand.target] says, never before the start nor past
 * the end, and only where the player can jump ([seekable]): not while a film is still opening, nor
 * in a file without an index, which a jump would start again from the beginning (the remote's ⏪ / ⏩
 * do nothing there either). Next only with something queued after it ([hasNext]): asked a moment
 * after the queue changed, it never marks the film watched with nowhere to go. With Up next counting
 * down ([upNext]) the episode is over: ↺10 goes back into its last seconds and plays them (Up next
 * comes again at the end), and 30↻ has nowhere to go, so Up next carries on.
 */
internal fun phoneStep(cmd: PlayerCommand, positionMs: Long, durationMs: Long, seekable: Boolean, hasNext: Boolean, upNext: Boolean): PhoneStep {
    if (cmd == PlayerCommand.NEXT) return if (hasNext) PhoneStep.Next else PhoneStep.Stay
    if (!seekable || (upNext && cmd == PlayerCommand.FORWARD_30)) return PhoneStep.Stay
    val to = cmd.target(positionMs, durationMs) ?: return PhoneStep.Stay
    return PhoneStep.Seek(to, playOn = upNext)
}

/**
 * The media key the phone is told for what's playing ([com.woodruff.tvexplorer.nowplaying.Playing.key]):
 * a Drive film's ("drive:<id>"), which the phone finds its poster by. Never a file's path on this TV,
 * nor a web address sent from the phone (one can carry a sign-in token in it): those have no poster
 * on the phone, which matches them by title as before.
 */
internal fun phoneKey(key: String?): String? = key?.takeIf { MediaRef.parse(it) is MediaRef.Drive }
