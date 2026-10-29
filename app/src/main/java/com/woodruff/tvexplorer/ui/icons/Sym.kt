package com.woodruff.tvexplorer.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's symbols, drawn the way Apple draws SF Symbols: one fine stroke weight, rounded caps
 * and joins, plain geometric shapes, and no color of their own (they take the color of the text
 * around them, so they turn dark on a white focus). Play and the rating star are solid, as Apple
 * fills those. The shapes are Lucide's (lucide.dev, ISC license, see assets/licenses/lucide.txt);
 * each is built once, the first time it's shown.
 *
 * Names follow the Material icons they replaced, so `Icons.Rounded.Tv` became `Sym.Tv`.
 */
object Sym {
    /** Lucide "play". */
    val PlayArrow: ImageVector by lazy { solid("M 5 5 a 2 2 0 0 1 3.008 -1.728 l 11.997 6.998 a 2 2 0 0 1 0.003 3.458 l -12 7 A 2 2 0 0 1 5 19 z") }
    /** Lucide "tv". */
    val Tv: ImageVector by lazy { line("m 17 2 l -5 5 l -5 -5", "M 4 7 H 20 A 2 2 0 0 1 22 9 V 20 A 2 2 0 0 1 20 22 H 4 A 2 2 0 0 1 2 20 V 9 A 2 2 0 0 1 4 7 Z") }
    /** Lucide "clapperboard". */
    val Movie: ImageVector by lazy { line("m 12.296 3.464 l 3.02 3.956", "M 20.2 6 L 3 11 l -0.9 -2.4 c -0.3 -1.1 0.3 -2.2 1.3 -2.5 l 13.5 -4 c 1.1 -0.3 2.2 0.3 2.5 1.3 z", "M 3 11 h 18 v 8 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z", "m 6.18 5.276 l 3.1 3.899") }
    /** Lucide "mail". */
    val Email: ImageVector by lazy { line("m 22 7 l -8.991 5.727 a 2 2 0 0 1 -2.009 0 L 2 7", "M 4 4 H 20 A 2 2 0 0 1 22 6 V 18 A 2 2 0 0 1 20 20 H 4 A 2 2 0 0 1 2 18 V 6 A 2 2 0 0 1 4 4 Z") }
    /** Lucide "rotate-cw". */
    val Refresh: ImageVector by lazy { line("M 21 12 a 9 9 0 1 1 -9 -9 c 2.52 0 4.93 1 6.74 2.74 L 21 8", "M 21 3 v 5 h -5") }
    /** Lucide "trash-2". */
    val Delete: ImageVector by lazy { line("M 10 11 v 6", "M 14 11 v 6", "M 19 6 v 14 a 2 2 0 0 1 -2 2 H 7 a 2 2 0 0 1 -2 -2 V 6", "M 3 6 h 18", "M 8 6 V 4 a 2 2 0 0 1 2 -2 h 4 a 2 2 0 0 1 2 2 v 2") }
    /** Lucide "cloud". */
    val AddToDrive: ImageVector by lazy { line("M 17.5 19 H 9 a 7 7 0 1 1 6.71 -9 h 1.79 a 4.5 4.5 0 1 1 0 9 Z") }
    /** Lucide "webcam". */
    val Videocam: ImageVector by lazy { line("M 4 10 A 8 8 0 1 0 20 10 A 8 8 0 1 0 4 10 Z", "M 9 10 A 3 3 0 1 0 15 10 A 3 3 0 1 0 9 10 Z", "M 7 22 h 10", "M 12 22 v -4") }
    /** Lucide "images". */
    val PhotoLibrary: ImageVector by lazy { line("m 22 11 l -1.296 -1.296 a 2.4 2.4 0 0 0 -3.408 0 L 11 16", "M 4 8 a 2 2 0 0 0 -2 2 v 10 a 2 2 0 0 0 2 2 h 10 a 2 2 0 0 0 2 -2", "M 12 7 A 1 1 0 1 0 14 7 A 1 1 0 1 0 12 7 Z", "M 10 2 H 20 A 2 2 0 0 1 22 4 V 14 A 2 2 0 0 1 20 16 H 10 A 2 2 0 0 1 8 14 V 4 A 2 2 0 0 1 10 2 Z") }
    /** Lucide "info". */
    val Info: ImageVector by lazy { line("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z", "M 12 16 v -4", "M 12 8 h 0.01") }
    /** Lucide "folder-open". */
    val FolderOpen: ImageVector by lazy { line("m 6 14 l 1.5 -2.9 A 2 2 0 0 1 9.24 10 H 20 a 2 2 0 0 1 1.94 2.5 l -1.54 6 a 2 2 0 0 1 -1.95 1.5 H 4 a 2 2 0 0 1 -2 -2 V 5 a 2 2 0 0 1 2 -2 h 3.9 a 2 2 0 0 1 1.69 0.9 l 0.81 1.2 a 2 2 0 0 0 1.67 0.9 H 18 a 2 2 0 0 1 2 2 v 2") }
    /** Lucide "eye". */
    val Visibility: ImageVector by lazy { line("M 2.062 12.348 a 1 1 0 0 1 0 -0.696 a 10.75 10.75 0 0 1 19.876 0 a 1 1 0 0 1 0 0.696 a 10.75 10.75 0 0 1 -19.876 0", "M 9 12 A 3 3 0 1 0 15 12 A 3 3 0 1 0 9 12 Z") }
    /** Lucide "settings". */
    val Settings: ImageVector by lazy { line("M 9.671 4.136 a 2.34 2.34 0 0 1 4.659 0 a 2.34 2.34 0 0 0 3.319 1.915 a 2.34 2.34 0 0 1 2.33 4.033 a 2.34 2.34 0 0 0 0 3.831 a 2.34 2.34 0 0 1 -2.33 4.033 a 2.34 2.34 0 0 0 -3.319 1.915 a 2.34 2.34 0 0 1 -4.659 0 a 2.34 2.34 0 0 0 -3.32 -1.915 a 2.34 2.34 0 0 1 -2.33 -4.033 a 2.34 2.34 0 0 0 0 -3.831 A 2.34 2.34 0 0 1 6.35 6.051 a 2.34 2.34 0 0 0 3.319 -1.915", "M 9 12 A 3 3 0 1 0 15 12 A 3 3 0 1 0 9 12 Z") }
    /** Lucide "search". */
    val Search: ImageVector by lazy { line("m 21 21 l -4.34 -4.34", "M 3 11 A 8 8 0 1 0 19 11 A 8 8 0 1 0 3 11 Z") }
    /** Lucide "smartphone". */
    val PhoneIphone: ImageVector by lazy { line("M 7 2 H 17 A 2 2 0 0 1 19 4 V 20 A 2 2 0 0 1 17 22 H 7 A 2 2 0 0 1 5 20 V 4 A 2 2 0 0 1 7 2 Z", "M 12 18 h 0.01") }
    /** Lucide "key-round". */
    val Key: ImageVector by lazy { line("M 2.586 17.414 A 2 2 0 0 0 2 18.828 V 21 a 1 1 0 0 0 1 1 h 3 a 1 1 0 0 0 1 -1 v -1 a 1 1 0 0 1 1 -1 h 1 a 1 1 0 0 0 1 -1 v -1 a 1 1 0 0 1 1 -1 h 0.172 a 2 2 0 0 0 1.414 -0.586 l 0.814 -0.814 a 6.5 6.5 0 1 0 -4 -4 z", "M 16 7.5 A 0.5 0.5 0 1 0 17 7.5 A 0.5 0.5 0 1 0 16 7.5 Z") }
    /** Lucide "check". */
    val Check: ImageVector by lazy { line("M 20 6 L 9 17 l -5 -5") }
    /** Lucide "sliders-horizontal". */
    val Tune: ImageVector by lazy { line("M 10 5 H 3", "M 12 19 H 3", "M 14 3 v 4", "M 16 17 v 4", "M 21 12 h -9", "M 21 19 h -5", "M 21 5 h -7", "M 8 10 v 4", "M 8 12 H 3") }
    /** Lucide "image-play". */
    val Slideshow: ImageVector by lazy { line("M 15 15.003 a 1 1 0 0 1 1.517 -0.859 l 4.997 2.997 a 1 1 0 0 1 0 1.718 l -4.997 2.997 a 1 1 0 0 1 -1.517 -0.86 z", "M 21 12.17 V 5 a 2 2 0 0 0 -2 -2 H 5 a 2 2 0 0 0 -2 2 v 14 a 2 2 0 0 0 2 2 h 6", "m 6 21 l 5 -5", "M 7 9 A 2 2 0 1 0 11 9 A 2 2 0 1 0 7 9 Z") }
    /** Lucide "rotate-ccw". */
    val Replay: ImageVector by lazy { line("M 3 12 a 9 9 0 1 0 9 -9 a 9.75 9.75 0 0 0 -6.74 2.74 L 3 8", "M 3 3 v 5 h 5") }
    /** Lucide "log-out". */
    val Logout: ImageVector by lazy { line("m 16 17 l 5 -5 l -5 -5", "M 21 12 H 9", "M 9 21 H 5 a 2 2 0 0 1 -2 -2 V 5 a 2 2 0 0 1 2 -2 h 4") }
    /** Lucide "download". */
    val Download: ImageVector by lazy { line("M 12 15 V 3", "M 21 15 v 4 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 v -4", "m 7 10 l 5 5 l 5 -5") }
    /** Lucide "x". */
    val Close: ImageVector by lazy { line("M 18 6 L 6 18", "m 6 6 l 12 12") }
    /** Lucide "square-check". */
    val CheckBox: ImageVector by lazy { line("M 5 3 H 19 A 2 2 0 0 1 21 5 V 19 A 2 2 0 0 1 19 21 H 5 A 2 2 0 0 1 3 19 V 5 A 2 2 0 0 1 5 3 Z", "m 16 9 l -5.5 5.5 L 8 12") }
    /** Lucide "image". */
    val Wallpaper: ImageVector by lazy { line("M 5 3 H 19 A 2 2 0 0 1 21 5 V 19 A 2 2 0 0 1 19 21 H 5 A 2 2 0 0 1 3 19 V 5 A 2 2 0 0 1 5 3 Z", "M 7 9 A 2 2 0 1 0 11 9 A 2 2 0 1 0 7 9 Z", "m 21 15 l -3.086 -3.086 a 2 2 0 0 0 -2.828 0 L 6 21") }
    /** Lucide "eye-off". */
    val VisibilityOff: ImageVector by lazy { line("M 10.733 5.076 a 10.744 10.744 0 0 1 11.205 6.575 a 1 1 0 0 1 0 0.696 a 10.747 10.747 0 0 1 -1.444 2.49", "M 14.084 14.158 a 3 3 0 0 1 -4.242 -4.242", "M 17.479 17.499 a 10.75 10.75 0 0 1 -15.417 -5.151 a 1 1 0 0 1 0 -0.696 a 10.75 10.75 0 0 1 4.446 -5.143", "m 2 2 l 20 20") }
    /** Lucide "circle-arrow-down". */
    val SystemUpdate: ImageVector by lazy { line("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z", "M 12 8 v 8", "m 8 12 l 4 4 l 4 -4") }
    /** Lucide "hard-drive". */
    val Storage: ImageVector by lazy { line("M 10 16 h 0.01", "M 2.212 11.577 a 2 2 0 0 0 -0.212 0.896 V 18 a 2 2 0 0 0 2 2 h 16 a 2 2 0 0 0 2 -2 v -5.527 a 2 2 0 0 0 -0.212 -0.896 L 18.55 5.11 A 2 2 0 0 0 16.76 4 H 7.24 a 2 2 0 0 0 -1.79 1.11 z", "M 21.946 12.013 H 2.054", "M 6 16 h 0.01") }
    /** Lucide "arrow-up-down". */
    val Sort: ImageVector by lazy { line("m 21 16 l -4 4 l -4 -4", "M 17 20 V 4", "m 3 8 l 4 -4 l 4 4", "M 7 4 v 16") }
    /** Lucide "smartphone". */
    val Smartphone: ImageVector by lazy { line("M 7 2 H 17 A 2 2 0 0 1 19 4 V 20 A 2 2 0 0 1 17 22 H 7 A 2 2 0 0 1 5 20 V 4 A 2 2 0 0 1 7 2 Z", "M 12 18 h 0.01") }
    /** Lucide "circle". */
    val RadioButtonUnchecked: ImageVector by lazy { line("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z") }
    /** Lucide "pin". */
    val PushPin: ImageVector by lazy { line("M 12 17 v 5", "M 9 10.76 a 2 2 0 0 1 -1.11 1.79 l -1.78 0.9 A 2 2 0 0 0 5 15.24 V 16 a 1 1 0 0 0 1 1 h 12 a 1 1 0 0 0 1 -1 v -0.76 a 2 2 0 0 0 -1.11 -1.79 l -1.78 -0.9 A 2 2 0 0 1 15 10.76 V 7 a 1 1 0 0 1 1 -1 a 2 2 0 0 0 0 -4 H 8 a 2 2 0 0 0 0 4 a 1 1 0 0 1 1 1 z") }
    /** Lucide "bell-ring". */
    val NotificationsActive: ImageVector by lazy { line("M 10.268 21 a 2 2 0 0 0 3.464 0", "M 22 8 c 0 -2.3 -0.8 -4.3 -2 -6", "M 3.262 15.326 A 1 1 0 0 0 4 17 h 16 a 1 1 0 0 0 0.74 -1.673 C 19.41 13.956 18 12.499 18 8 A 6 6 0 0 0 6 8 c 0 4.499 -1.411 5.956 -2.738 7.326", "M 4 2 C 2.8 3.7 2 5.7 2 8") }
    /** Lucide "ellipsis". */
    val MoreHoriz: ImageVector by lazy { line("M 11 12 A 1 1 0 1 0 13 12 A 1 1 0 1 0 11 12 Z", "M 18 12 A 1 1 0 1 0 20 12 A 1 1 0 1 0 18 12 Z", "M 4 12 A 1 1 0 1 0 6 12 A 1 1 0 1 0 4 12 Z") }
    /** Lucide "link". */
    val Link: ImageVector by lazy { line("M 10 13 a 5 5 0 0 0 7.54 0.54 l 3 -3 a 5 5 0 0 0 -7.07 -7.07 l -1.72 1.71", "M 14 11 a 5 5 0 0 0 -7.54 -0.54 l -3 3 a 5 5 0 0 0 7.07 7.07 l 1.71 -1.71") }
    /** Lucide "image". */
    val Image: ImageVector by lazy { line("M 5 3 H 19 A 2 2 0 0 1 21 5 V 19 A 2 2 0 0 1 19 21 H 5 A 2 2 0 0 1 3 19 V 5 A 2 2 0 0 1 5 3 Z", "M 7 9 A 2 2 0 1 0 11 9 A 2 2 0 1 0 7 9 Z", "m 21 15 l -3.086 -3.086 a 2 2 0 0 0 -2.828 0 L 6 21") }
    /** Lucide "house". */
    val Home: ImageVector by lazy { line("M 15 21 v -8 a 1 1 0 0 0 -1 -1 h -4 a 1 1 0 0 0 -1 1 v 8", "M 3 10 a 2 2 0 0 1 0.709 -1.528 l 7 -6 a 2 2 0 0 1 2.582 0 l 7 6 A 2 2 0 0 1 21 10 v 9 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z") }
    /** Lucide "layout-grid". */
    val GridView: ImageVector by lazy { line("M 4 3 H 9 A 1 1 0 0 1 10 4 V 9 A 1 1 0 0 1 9 10 H 4 A 1 1 0 0 1 3 9 V 4 A 1 1 0 0 1 4 3 Z", "M 15 3 H 20 A 1 1 0 0 1 21 4 V 9 A 1 1 0 0 1 20 10 H 15 A 1 1 0 0 1 14 9 V 4 A 1 1 0 0 1 15 3 Z", "M 15 14 H 20 A 1 1 0 0 1 21 15 V 20 A 1 1 0 0 1 20 21 H 15 A 1 1 0 0 1 14 20 V 15 A 1 1 0 0 1 15 14 Z", "M 4 14 H 9 A 1 1 0 0 1 10 15 V 20 A 1 1 0 0 1 9 21 H 4 A 1 1 0 0 1 3 20 V 15 A 1 1 0 0 1 4 14 Z") }
    /** Lucide "folder-archive". */
    val FolderZip: ImageVector by lazy { line("M 13 19 A 2 2 0 1 0 17 19 A 2 2 0 1 0 13 19 Z", "M 20.9 19.8 A 2 2 0 0 0 22 18 V 8 a 2 2 0 0 0 -2 -2 h -7.9 a 2 2 0 0 1 -1.69 -0.9 L 9.6 3.9 A 2 2 0 0 0 7.93 3 H 4 a 2 2 0 0 0 -2 2 v 13 a 2 2 0 0 0 2 2 h 5.1", "M 15 11 v -1", "M 15 17 v -2") }
    /** Lucide "folder-symlink". */
    val FolderShared: ImageVector by lazy { line("M 2 9.35 V 5 a 2 2 0 0 1 2 -2 h 3.9 a 2 2 0 0 1 1.69 0.9 l 0.81 1.2 a 2 2 0 0 0 1.67 0.9 H 20 a 2 2 0 0 1 2 2 v 10 a 2 2 0 0 1 -2 2 H 4 a 2 2 0 0 1 -2 -2 v -3 a 2 2 0 0 1 2 -2 h 7", "m 8 16 l 3 -3 l -3 -3") }
    /** Lucide "folder". */
    val Folder: ImageVector by lazy { line("M 20 20 a 2 2 0 0 0 2 -2 V 8 a 2 2 0 0 0 -2 -2 h -7.9 a 2 2 0 0 1 -1.69 -0.9 L 9.6 3.9 A 2 2 0 0 0 7.93 3 H 4 a 2 2 0 0 0 -2 2 v 13 a 2 2 0 0 0 2 2 Z") }
    /** Lucide "calendar". */
    val Event: ImageVector by lazy { line("M 8 2 v 3", "M 16 2 v 3", "M 5 3 H 19 A 2 2 0 0 1 21 5 V 19 A 2 2 0 0 1 19 21 H 5 A 2 2 0 0 1 3 19 V 5 A 2 2 0 0 1 5 3 Z", "M 3 9 h 18") }
    /** Lucide "file-text". */
    val Description: ImageVector by lazy { line("M 6 22 a 2 2 0 0 1 -2 -2 V 4 a 2 2 0 0 1 2 -2 h 8 a 2.4 2.4 0 0 1 1.704 0.706 l 3.588 3.588 A 2.4 2.4 0 0 1 20 8 v 12 a 2 2 0 0 1 -2 2 z", "M 14 2 v 5 a 1 1 0 0 0 1 1 h 5", "M 10 9 H 8", "M 16 13 H 8", "M 16 17 H 8") }
    /** Lucide "moon". */
    val DarkMode: ImageVector by lazy { line("M 20.985 12.486 a 9 9 0 1 1 -9.473 -9.472 c 0.405 -0.022 0.617 0.46 0.402 0.803 a 6 6 0 0 0 8.268 8.268 c 0.344 -0.215 0.825 -0.004 0.803 0.401") }
    /** Lucide "scissors". */
    val ContentCut: ImageVector by lazy { line("M 3 6 A 3 3 0 1 0 9 6 A 3 3 0 1 0 3 6 Z", "M 8.12 8.12 L 12 12", "M 20 4 L 8.12 15.88", "M 3 18 A 3 3 0 1 0 9 18 A 3 3 0 1 0 3 18 Z", "M 14.8 14.8 L 20 20") }
    /** Lucide "copy". */
    val ContentCopy: ImageVector by lazy { line("M 10 8 H 20 A 2 2 0 0 1 22 10 V 20 A 2 2 0 0 1 20 22 H 10 A 2 2 0 0 1 8 20 V 10 A 2 2 0 0 1 10 8 Z", "M 4 16 c -1.1 0 -2 -0.9 -2 -2 V 4 c 0 -1.1 0.9 -2 2 -2 h 10 c 1.1 0 2 0.9 2 2") }
    /** Lucide "circle-check". */
    val CheckCircle: ImageVector by lazy { line("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z", "m 16 9 l -5.5 5.5 L 8 12") }
    /** Lucide "square". */
    val CheckBoxOutlineBlank: ImageVector by lazy { line("M 5 3 H 19 A 2 2 0 0 1 21 5 V 19 A 2 2 0 0 1 19 21 H 5 A 2 2 0 0 1 3 19 V 5 A 2 2 0 0 1 5 3 Z") }
    /** Lucide "chevron-left". */
    val ArrowBack: ImageVector by lazy { line("m 15 18 l -6 -6 l 6 -6") }
    /** Lucide "plus". */
    val Add: ImageVector by lazy { line("M 5 12 h 14", "M 12 5 v 14") }
    /** Lucide "list". */
    val ViewList: ImageVector by lazy { line("M 3 5 h 0.01", "M 3 12 h 0.01", "M 3 19 h 0.01", "M 8 5 h 13", "M 8 12 h 13", "M 8 19 h 13") }
    /** Lucide "video". */
    val VideoCall: ImageVector by lazy { line("m 16 13 l 5.223 3.482 a 0.5 0.5 0 0 0 0.777 -0.416 V 7.87 a 0.5 0.5 0 0 0 -0.752 -0.432 L 16 10.5", "M 4 6 H 14 A 2 2 0 0 1 16 8 V 16 A 2 2 0 0 1 14 18 H 4 A 2 2 0 0 1 2 16 V 8 A 2 2 0 0 1 4 6 Z") }
    /** Lucide "archive-restore". */
    val Unarchive: ImageVector by lazy { line("M 3 3 H 21 A 1 1 0 0 1 22 4 V 7 A 1 1 0 0 1 21 8 H 3 A 1 1 0 0 1 2 7 V 4 A 1 1 0 0 1 3 3 Z", "M 4 8 v 11 a 2 2 0 0 0 2 2 h 2", "M 20 8 v 11 a 2 2 0 0 1 -2 2 h -2", "m 9 15 l 3 -3 l 3 3", "M 12 12 v 9") }
    /** Lucide "timer". */
    val Timer: ImageVector by lazy { line("M 10 2 L 14 2", "M 12 14 L 15 11", "M 4 14 A 8 8 0 1 0 20 14 A 8 8 0 1 0 4 14 Z") }
    /** Lucide "file-text". */
    val TextSnippet: ImageVector by lazy { line("M 6 22 a 2 2 0 0 1 -2 -2 V 4 a 2 2 0 0 1 2 -2 h 8 a 2.4 2.4 0 0 1 1.704 0.706 l 3.588 3.588 A 2.4 2.4 0 0 1 20 8 v 12 a 2 2 0 0 1 -2 2 z", "M 14 2 v 5 a 1 1 0 0 0 1 1 h 5", "M 10 9 H 8", "M 16 13 H 8", "M 16 17 H 8") }
    /** Lucide "star". */
    val Star: ImageVector by lazy { solid("M 11.525 2.295 a 0.53 0.53 0 0 1 0.95 0 l 2.31 4.679 a 2.123 2.123 0 0 0 1.595 1.16 l 5.166 0.756 a 0.53 0.53 0 0 1 0.294 0.904 l -3.736 3.638 a 2.123 2.123 0 0 0 -0.611 1.878 l 0.882 5.14 a 0.53 0.53 0 0 1 -0.771 0.56 l -4.618 -2.428 a 2.122 2.122 0 0 0 -1.973 0 L 6.396 21.01 a 0.53 0.53 0 0 1 -0.77 -0.56 l 0.881 -5.139 a 2.122 2.122 0 0 0 -0.611 -1.879 L 2.16 9.795 a 0.53 0.53 0 0 1 0.294 -0.906 l 5.165 -0.755 a 2.122 2.122 0 0 0 1.597 -1.16 z") }
    /** Lucide "gauge". */
    val Speed: ImageVector by lazy { line("m 12 14 l 4 -4", "M 3.34 19 a 10 10 0 1 1 17.32 0") }
    /** Motion (Lucide move-horizontal): Reduce motion. */
    val Motion: ImageVector by lazy { line("M 18 8 L 22 12 L 18 16", "M 2 12 L 22 12", "M 6 8 L 2 12 L 6 16") }
    /** Lucide "check-check". */
    val SelectAll: ImageVector by lazy { line("M 18 6 L 7 17 l -5 -5", "m 22 10 l -7.5 7.5 L 13 16") }
    /** Lucide "usb". */
    val SdStorage: ImageVector by lazy { line("M 9 7 A 1 1 0 1 0 11 7 A 1 1 0 1 0 9 7 Z", "M 3 20 A 1 1 0 1 0 5 20 A 1 1 0 1 0 3 20 Z", "M 4.7 19.3 L 19 5", "m 21 3 l -3 1 l 2 2 Z", "M 9.26 7.68 L 5 12 l 2 5", "m 10 14 l 5 2 l 3.5 -3.5", "m 18 12 l 1 -1 l 1 1 l -1 1 Z") }
    /** Lucide "flask-conical". */
    val Science: ImageVector by lazy { line("M 14 2 v 6 a 2 2 0 0 0 0.245 0.96 l 5.51 10.08 A 2 2 0 0 1 18 22 H 6 a 2 2 0 0 1 -1.755 -2.96 l 5.51 -10.08 A 2 2 0 0 0 10 8 V 2", "M 6.453 15 h 11.094", "M 8.5 2 h 7") }
    /** Lucide "power". */
    val PowerSettingsNew: ImageVector by lazy { line("M 12 2 v 10", "M 18.4 6.6 a 9 9 0 1 1 -12.77 0.04") }
    /** Lucide "circle-play". */
    val PlayCircle: ImageVector by lazy { line("M 9 9.003 a 1 1 0 0 1 1.517 -0.859 l 4.997 2.997 a 1 1 0 0 1 0 1.718 l -4.997 2.997 A 1 1 0 0 1 9 14.996 z", "M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z") }
    /** Lucide "camera". */
    val PhotoCamera: ImageVector by lazy { line("M 13.997 4 a 2 2 0 0 1 1.76 1.05 l 0.486 0.9 A 2 2 0 0 0 18.003 7 H 20 a 2 2 0 0 1 2 2 v 9 a 2 2 0 0 1 -2 2 H 4 a 2 2 0 0 1 -2 -2 V 9 a 2 2 0 0 1 2 -2 h 1.997 a 2 2 0 0 0 1.759 -1.048 l 0.489 -0.904 A 2 2 0 0 1 10.004 4 z", "M 9 13 A 3 3 0 1 0 15 13 A 3 3 0 1 0 9 13 Z") }
    /** Lucide "user-plus". */
    val PersonAdd: ImageVector by lazy { line("M 16 21 v -2 a 4 4 0 0 0 -4 -4 H 6 a 4 4 0 0 0 -4 4 v 2", "M 5 7 A 4 4 0 1 0 13 7 A 4 4 0 1 0 5 7 Z", "M 19 8 L 19 14", "M 22 11 L 16 11") }
    /** Lucide "external-link". */
    val OpenInNew: ImageVector by lazy { line("M 15 3 h 6 v 6", "M 10 14 L 21 3", "M 18 13 v 6 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 V 8 a 2 2 0 0 1 2 -2 h 6") }
    /** Lucide "bell". */
    val Notifications: ImageVector by lazy { line("M 10.268 21 a 2 2 0 0 0 3.464 0", "M 3.262 15.326 A 1 1 0 0 0 4 17 h 16 a 1 1 0 0 0 0.74 -1.673 C 19.41 13.956 18 12.499 18 8 A 6 6 0 0 0 6 8 c 0 4.499 -1.411 5.956 -2.738 7.326") }
    /** Lucide "music". */
    val MusicNote: ImageVector by lazy { line("M 21 16 V 3 a 1 1 0 0 0 -1.28 -0.96 l -10 2.917 a 1 1 0 0 0 -0.72 0.96 V 19", "M 15 16 A 3 3 0 1 0 21 16 A 3 3 0 1 0 15 16 Z", "M 3 19 A 3 3 0 1 0 9 19 A 3 3 0 1 0 3 19 Z") }
    /** Lucide "lock". */
    val Lock: ImageVector by lazy { line("M 5 11 H 19 A 2 2 0 0 1 21 13 V 20 A 2 2 0 0 1 19 22 H 5 A 2 2 0 0 1 3 20 V 13 A 2 2 0 0 1 5 11 Z", "M 7 11 V 7 a 5 5 0 0 1 10 0 v 4") }
    /** Lucide "unlink". */
    val LinkOff: ImageVector by lazy { line("m 18.84 12.25 l 1.72 -1.71 h -0.02 a 5.004 5.004 0 0 0 -0.12 -7.07 a 5.006 5.006 0 0 0 -6.95 0 l -1.72 1.71", "m 5.17 11.75 l -1.71 1.71 a 5.004 5.004 0 0 0 0.12 7.07 a 5.006 5.006 0 0 0 6.95 0 l 1.71 -1.71", "M 8 2 L 8 5", "M 2 8 L 5 8", "M 16 19 L 16 22", "M 19 16 L 22 16") }
    /** Lucide "sun". */
    val LightMode: ImageVector by lazy { line("M 8 12 A 4 4 0 1 0 16 12 A 4 4 0 1 0 8 12 Z", "M 12 2 v 2", "M 12 20 v 2", "m 4.93 4.93 l 1.41 1.41", "m 17.66 17.66 l 1.41 1.41", "M 2 12 h 2", "M 20 12 h 2", "m 6.34 17.66 l -1.41 1.41", "m 19.07 4.93 l -1.41 1.41") }
    /** Lucide "external-link". */
    val Launch: ImageVector by lazy { line("M 15 3 h 6 v 6", "M 10 14 L 21 3", "M 18 13 v 6 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 V 8 a 2 2 0 0 1 2 -2 h 6") }
    /** Lucide "package-plus". */
    val InstallMobile: ImageVector by lazy { line("M 12 22 V 12", "M 16 17 h 6", "M 19 14 v 6", "M 21 10.535 V 8 a 2 2 0 0 0 -1 -1.73 l -7 -4 a 2 2 0 0 0 -2 0 l -7 4 A 2 2 0 0 0 3 8 v 8 a 2 2 0 0 0 1 1.729 l 7 4 a 2 2 0 0 0 2 0.001 l 1.675 -0.955", "M 3.29 7 L 12 12 l 8.71 -5", "m 7.5 4.27 l 8.997 5.148") }
    /** Lucide "file". */
    val InsertDriveFile: ImageVector by lazy { line("M 6 22 a 2 2 0 0 1 -2 -2 V 4 a 2 2 0 0 1 2 -2 h 8 a 2.4 2.4 0 0 1 1.704 0.706 l 3.588 3.588 A 2.4 2.4 0 0 1 20 8 v 12 a 2 2 0 0 1 -2 2 z", "M 14 2 v 5 a 1 1 0 0 0 1 1 h 5") }
    /** Lucide "users". */
    val Groups: ImageVector by lazy { line("M 16 21 v -2 a 4 4 0 0 0 -4 -4 H 6 a 4 4 0 0 0 -4 4 v 2", "M 16 3.128 a 4 4 0 0 1 0 7.744", "M 22 21 v -2 a 4 4 0 0 0 -3 -3.87", "M 5 7 A 4 4 0 1 0 13 7 A 4 4 0 1 0 5 7 Z") }
    /** Lucide "notebook-pen". */
    val EditNote: ImageVector by lazy { line("M 13.4 2 H 6 a 2 2 0 0 0 -2 2 v 16 a 2 2 0 0 0 2 2 h 12 a 2 2 0 0 0 2 -2 v -7.4", "M 2 6 h 4", "M 2 10 h 4", "M 2 14 h 4", "M 2 18 h 4", "M 21.378 5.626 a 1 1 0 1 0 -3.004 -3.004 l -5.01 5.012 a 2 2 0 0 0 -0.506 0.854 l -0.837 2.87 a 0.5 0.5 0 0 0 0.62 0.62 l 2.87 -0.837 a 2 2 0 0 0 0.854 -0.506 z") }
    /** Lucide "pencil". */
    val DriveFileRenameOutline: ImageVector by lazy { line("M 21.174 6.812 a 1 1 0 0 0 -3.986 -3.987 L 3.842 16.174 a 2 2 0 0 0 -0.5 0.83 l -1.321 4.352 a 0.5 0.5 0 0 0 0.623 0.622 l 4.353 -1.32 a 2 2 0 0 0 0.83 -0.497 z", "m 15 5 l 4 4") }
    /** Lucide "layout-dashboard". */
    val Dashboard: ImageVector by lazy { line("M 4 3 H 9 A 1 1 0 0 1 10 4 V 11 A 1 1 0 0 1 9 12 H 4 A 1 1 0 0 1 3 11 V 4 A 1 1 0 0 1 4 3 Z", "M 15 3 H 20 A 1 1 0 0 1 21 4 V 7 A 1 1 0 0 1 20 8 H 15 A 1 1 0 0 1 14 7 V 4 A 1 1 0 0 1 15 3 Z", "M 15 12 H 20 A 1 1 0 0 1 21 13 V 20 A 1 1 0 0 1 20 21 H 15 A 1 1 0 0 1 14 20 V 13 A 1 1 0 0 1 15 12 Z", "M 4 16 H 9 A 1 1 0 0 1 10 17 V 20 A 1 1 0 0 1 9 21 H 4 A 1 1 0 0 1 3 20 V 17 A 1 1 0 0 1 4 16 Z") }
    /** Lucide "folder-plus". */
    val CreateNewFolder: ImageVector by lazy { line("M 12 10 v 6", "M 9 13 h 6", "M 20 20 a 2 2 0 0 0 2 -2 V 8 a 2 2 0 0 0 -2 -2 h -7.9 a 2 2 0 0 1 -1.69 -0.9 L 9.6 3.9 A 2 2 0 0 0 7.93 3 H 4 a 2 2 0 0 0 -2 2 v 13 a 2 2 0 0 0 2 2 Z") }
    /** Lucide "clipboard". */
    val ContentPaste: ImageVector by lazy { line("M 9 2 H 15 A 1 1 0 0 1 16 3 V 5 A 1 1 0 0 1 15 6 H 9 A 1 1 0 0 1 8 5 V 3 A 1 1 0 0 1 9 2 Z", "M 16 4 h 2 a 2 2 0 0 1 2 2 v 14 a 2 2 0 0 1 -2 2 H 6 a 2 2 0 0 1 -2 -2 V 6 a 2 2 0 0 1 2 -2 h 2") }
    /** Lucide "cloud". */
    val Cloud: ImageVector by lazy { line("M 17.5 19 H 9 a 7 7 0 1 1 6.71 -9 h 1.79 a 4.5 4.5 0 1 1 0 9 Z") }
    /** Lucide "chevron-right". */
    val ChevronRight: ImageVector by lazy { line("m 9 18 l 6 -6 l -6 -6") }
    /** Lucide "cast". */
    val Cast: ImageVector by lazy { line("M 2 8 V 6 a 2 2 0 0 1 2 -2 h 16 a 2 2 0 0 1 2 2 v 12 a 2 2 0 0 1 -2 2 h -6", "M 2 12 a 9 9 0 0 1 8 8", "M 2 16 a 5 5 0 0 1 4 4", "M 2 20 L 2.01 20") }
    /** Lucide "megaphone". */
    val Campaign: ImageVector by lazy { line("M 11 6 a 13 13 0 0 0 8.4 -2.8 A 1 1 0 0 1 21 4 v 12 a 1 1 0 0 1 -1.6 0.8 A 13 13 0 0 0 11 14 H 5 a 2 2 0 0 1 -2 -2 V 8 a 2 2 0 0 1 2 -2 z", "M 6 14 a 12 12 0 0 0 2.4 7.2 a 2 2 0 0 0 3.2 -2.4 A 8 8 0 0 1 10 14", "M 8 6 v 8") }
    /** Lucide "hourglass". */
    val Bedtime: ImageVector by lazy { line("M 5 22 h 14", "M 5 2 h 14", "M 17 22 v -4.172 a 2 2 0 0 0 -0.586 -1.414 L 12 12 l -4.414 4.414 A 2 2 0 0 0 7 17.828 V 22", "M 7 2 v 4.172 a 2 2 0 0 0 0.586 1.414 L 12 12 l 4.414 -4.414 A 2 2 0 0 0 17 6.172 V 2") }
    /** Lucide "download". */
    val Backup: ImageVector by lazy { line("M 12 15 V 3", "M 21 15 v 4 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 v -4", "m 7 10 l 5 5 l 5 -5") }
    /** Lucide "refresh-cw". */
    val Autorenew: ImageVector by lazy { line("M 3 12 a 9 9 0 0 1 9 -9 a 9.75 9.75 0 0 1 6.74 2.74 L 21 8", "M 21 3 v 5 h -5", "M 21 12 a 9 9 0 0 1 -9 9 a 9.75 9.75 0 0 1 -6.74 -2.74 L 3 16", "M 8 16 H 3 v 5") }
    /** Lucide "file-music". */
    val AudioFile: ImageVector by lazy { line("M 11.65 22 H 18 a 2 2 0 0 0 2 -2 V 8 a 2.4 2.4 0 0 0 -0.706 -1.706 l -3.588 -3.588 A 2.4 2.4 0 0 0 14 2 H 6 a 2 2 0 0 0 -2 2 v 10.35", "M 14 2 v 5 a 1 1 0 0 0 1 1 h 5", "M 8 20 v -7 l 3 1.474", "M 4 20 A 2 2 0 1 0 8 20 A 2 2 0 1 0 4 20 Z") }
    /** Lucide "arrow-up". */
    val ArrowUpward: ImageVector by lazy { line("m 5 12 l 7 -7 l 7 7", "M 12 19 V 5") }
    /** Lucide "archive". */
    val Archive: ImageVector by lazy { line("M 3 3 H 21 A 1 1 0 0 1 22 4 V 7 A 1 1 0 0 1 21 8 H 3 A 1 1 0 0 1 2 7 V 4 A 1 1 0 0 1 3 3 Z", "M 4 8 v 11 a 2 2 0 0 0 2 2 h 12 a 2 2 0 0 0 2 -2 V 8", "M 10 12 h 4") }
    /** Lucide "layout-grid". */
    val Apps: ImageVector by lazy { line("M 4 3 H 9 A 1 1 0 0 1 10 4 V 9 A 1 1 0 0 1 9 10 H 4 A 1 1 0 0 1 3 9 V 4 A 1 1 0 0 1 4 3 Z", "M 15 3 H 20 A 1 1 0 0 1 21 4 V 9 A 1 1 0 0 1 20 10 H 15 A 1 1 0 0 1 14 9 V 4 A 1 1 0 0 1 15 3 Z", "M 15 14 H 20 A 1 1 0 0 1 21 15 V 20 A 1 1 0 0 1 20 21 H 15 A 1 1 0 0 1 14 20 V 15 A 1 1 0 0 1 15 14 Z", "M 4 14 H 9 A 1 1 0 0 1 10 15 V 20 A 1 1 0 0 1 9 21 H 4 A 1 1 0 0 1 3 20 V 15 A 1 1 0 0 1 4 14 Z") }
    /** Lucide "package". */
    val Android: ImageVector by lazy { line("M 11 21.73 a 2 2 0 0 0 2 0 l 7 -4 A 2 2 0 0 0 21 16 V 8 a 2 2 0 0 0 -1 -1.73 l -7 -4 a 2 2 0 0 0 -2 0 l -7 4 A 2 2 0 0 0 3 8 v 8 a 2 2 0 0 0 1 1.73 z", "M 12 22 V 12", "M 3.29 7 L 12 12 L 20.71 7", "m 7.5 4.27 l 9 5.15") }
    /** Lucide "circle-user". */
    val AccountCircle: ImageVector by lazy { line("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z", "M 9 10 A 3 3 0 1 0 15 10 A 3 3 0 1 0 9 10 Z", "M 7 20.662 V 19 a 2 2 0 0 1 2 -2 h 6 a 2 2 0 0 1 2 2 v 1.662") }
    /** Lucide "tv-minimal-play". */
    val Streaming: ImageVector by lazy { line("M 15.033 9.44 a 0.647 0.647 0 0 1 0 1.12 l -4.065 2.352 a 0.645 0.645 0 0 1 -0.968 -0.56 V 7.648 a 0.645 0.645 0 0 1 0.967 -0.56 z", "M 7 21 h 10", "M 4 3 H 20 A 2 2 0 0 1 22 5 V 15 A 2 2 0 0 1 20 17 H 4 A 2 2 0 0 1 2 15 V 5 A 2 2 0 0 1 4 3 Z") }
}

/** Stroke weight on the 24-unit grid: close to SF Symbols' regular weight at TV sizes. */
private const val WEIGHT = 1.75f

private fun line(vararg paths: String) = symbol(filled = false, paths)

private fun solid(vararg paths: String) = symbol(filled = true, paths)

private fun symbol(filled: Boolean, paths: Array<out String>): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.White) else null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = WEIGHT,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/**
 * [icon] drawn for [size], the way SF Symbols are: big symbols get relatively finer lines
 * (24 dp and under keep the regular weight). Variants are built once and kept.
 */
fun symbolAt(icon: ImageVector, size: androidx.compose.ui.unit.Dp): ImageVector {
    val tier = when {
        size.value <= 30f -> return icon
        size.value <= 56f -> 0
        else -> 1
    }
    return synchronized(variants) {
        val slots = variants.getOrPut(icon) { arrayOfNulls(2) }
        slots[tier] ?: reweigh(icon, if (tier == 0) 1.35f else 1.0f).also { slots[tier] = it }
    }
}

// By identity: each symbol is a single object, and hashing a whole vector on every draw would cost.
private val variants = java.util.IdentityHashMap<ImageVector, Array<ImageVector?>>()

private fun reweigh(icon: ImageVector, weight: Float): ImageVector =
    ImageVector.Builder(
        defaultWidth = icon.defaultWidth, defaultHeight = icon.defaultHeight,
        viewportWidth = icon.viewportWidth, viewportHeight = icon.viewportHeight,
    ).apply {
        for (node in icon.root) {
            if (node is androidx.compose.ui.graphics.vector.VectorPath) addPath(
                pathData = node.pathData,
                fill = node.fill,
                stroke = node.stroke,
                strokeLineWidth = if (node.stroke != null) weight else node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
            )
        }
    }.build()
