package com.woodruff.tvexplorer.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.woodruff.tvexplorer.R

/**
 * How a dialog's window arrives and goes. WindowManager runs these (res/anim) on the system's own
 * thread, so they stay smooth while the app is busy building what's in the window; the numbers are
 * [TvMotion]'s. Every dialog gets [Dialog] from the app's theme (android:dialogTheme), so only the
 * others are asked for, with [DialogWindowMotion].
 */
enum class WindowMotion(internal val style: Int) {
    /** A dialog or pop-up: in from just larger than itself as it fades in, over the dimming screen. */
    Dialog(R.style.Animation_TVExplorer_Dialog),
    /** A full-screen page (a title's page, search): grows in from just smaller as it fades in. */
    Page(R.style.Animation_TVExplorer_Page),
    /** A panel at the right edge (the filters): slides in from that edge as it fades in, and back out. */
    SideSheet(R.style.Animation_TVExplorer_SideSheet),
}

/**
 * First thing in a Dialog's content: its window arrives and goes with [motion] rather than the dialog
 * theme's zoom. A dialog's content is composed as its window is first attached, within the window's
 * first layout pass and before the window's attributes go to the system with that pass's layout (and
 * before its first frame, which is what starts the entrance), so the entrance already uses [motion].
 * Its exit uses it too. Don't animate the content's own entrance as well: the window does.
 */
@Composable
fun DialogWindowMotion(motion: WindowMotion) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window, motion) {
        window?.setWindowAnimations(motion.style)
        onDispose {}
    }
}
