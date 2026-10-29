package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.woodruff.tvexplorer.ui.components.TvIcon
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.photos.GooglePhotos
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.ConfirmDialog
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.UsageBar
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.rememberQrBitmap

/**
 * Pick photos from Google Photos for the slideshow. QR first at a fixed size (TVs are only
 * ~960dp wide), text beside it. Closing while photos download lets the download finish.
 * Google's picker doesn't list albums, so the window says how to reach one (search its name), and what the
 * pick will do to the photos already on the TV (replace them, or be added), with their size.
 */
@Composable
fun GooglePhotosDialog(onDismiss: () -> Unit, onSetUpGoogle: () -> Unit) {
    val context = LocalContext.current
    val state by GooglePhotos.state.collectAsStateWithLifecycle()
    val onTv by GooglePhotos.onTv.collectAsStateWithLifecycle()
    val replace by GooglePhotos.replace.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val close = {
        if (state.step != GooglePhotos.Step.DOWNLOADING && state.step != GooglePhotos.Step.DONE) GooglePhotos.cancel()
        onDismiss()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { GooglePhotos.open() }

    DialogFrame("Google Photos", close, width = 760) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val qr = state.qrUrl
            if (qr != null && (state.step == GooglePhotos.Step.SIGN_IN || state.step == GooglePhotos.Step.PICKING)) {
                Box(Modifier.size(230.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    rememberQrBitmap(qr, 600)?.let { Image(it, contentDescription = "QR code", modifier = Modifier.size(230.dp)) }
                }
            } else {
                TvIcon(Sym.PhotoLibrary, size = 96.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state.step) {
                    GooglePhotos.Step.NEED_GOOGLE -> Text("Set up the Google keys first (same as Google Drive).")
                    GooglePhotos.Step.SIGN_IN -> Text(if (state.qrUrl == null) "Preparing…" else "Scan with your phone and sign in to Google.")
                    GooglePhotos.Step.READY -> Text("Opening Google Photos…")
                    GooglePhotos.Step.PICKING -> {
                        Text("Scan with your phone, choose photos, then tap Done.")
                        Note("Made an album for the TV? Type its name in the picker's search bar, then select its photos.")
                        if (onTv.items > 0) Note(
                            (if (replace) "They'll replace the " else "They'll be added to the ") +
                                "${onTv.label()} on this TV (${Format.bytes(onTv.bytes)}).",
                        )
                    }
                    GooglePhotos.Step.DOWNLOADING -> {
                        Text("Adding ${state.done} of ${state.total}")
                        UsageBar(if (state.total > 0) state.done.toFloat() / state.total else 0f)
                        if (state.kept > 0) Note("${state.kept} already on the TV")
                    }
                    GooglePhotos.Step.DONE -> {
                        Text(
                            if (onTv.items > 0) "${onTv.label()} on this TV (${Format.bytes(onTv.bytes)})."
                            else "Nothing was added.",
                        )
                        listOfNotNull(
                            (state.added + state.videos).takeIf { it > 0 }?.let { "$it new" },
                            state.kept.takeIf { it > 0 }?.let { "$it already here" },
                            state.removed.takeIf { it > 0 }?.let { "$it removed" },
                        ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Note(it) }
                        if (state.failed > 0) Text(
                            "${state.failed} couldn't download" + (if (replace) ", so nothing was removed" else "") +
                                ". Pick again to try again.",
                            color = Palette.danger, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                state.error?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        ButtonRow {
            when (state.step) {
                GooglePhotos.Step.NEED_GOOGLE -> Button(onClick = { onDismiss(); onSetUpGoogle() }, modifier = Modifier.focusRequester(focus)) { Text("Set up Google") }
                GooglePhotos.Step.PICKING -> if (onTv.items > 0) {
                    OutlinedButton(onClick = { GooglePhotos.setReplace(!replace) }) { Text(if (replace) "Add instead" else "Replace instead") }
                    OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove all") }
                }
                GooglePhotos.Step.DONE -> {
                    OutlinedButton(onClick = { GooglePhotos.startPicking() }) { Text("Pick again") }
                    if (onTv.photos > 0) Button(onClick = { onDismiss(); SlideshowActivity.start(context) }) { Text("Start slideshow") }
                }
                else -> Unit
            }
            OutlinedButton(onClick = close, modifier = if (state.step == GooglePhotos.Step.NEED_GOOGLE) Modifier else Modifier.focusRequester(focus)) {
                Text(if (state.step == GooglePhotos.Step.DOWNLOADING) "Hide" else "Close")
            }
        }
    }
    if (confirmRemove) ConfirmDialog(
        title = "Remove from this TV?",
        body = "Deletes the ${onTv.label()} (${Format.bytes(onTv.bytes)}) this TV saved from Google Photos. " +
            "Your Google Photos library isn't touched.",
        confirmLabel = "Remove",
        onConfirm = { GooglePhotos.removeAll() },
        onDismiss = { confirmRemove = false },
    )
    // Also when the buttons for what's on the TV come or go (after Remove all), so the focus is never left on nothing.
    LaunchedEffect(state.step, onTv.items > 0) { runCatching { focus.requestFocus() } }
}

@Composable
private fun Note(text: String) = Text(text, color = Palette.muted, style = MaterialTheme.typography.bodyMedium)

/** "214 photos and 3 videos" */
private fun GooglePhotos.OnTv.label(): String = listOfNotNull(
    photos.takeIf { it > 0 }?.let { "$it photo${if (it == 1) "" else "s"}" },
    videos.takeIf { it > 0 }?.let { "$it video${if (it == 1) "" else "s"}" },
).joinToString(" and ")
