package com.woodruff.tvexplorer.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.photos.GooglePhotos
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.UsageBar
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Qr

/**
 * Pick photos from Google Photos for the slideshow. QR first at a fixed size (TVs are only
 * ~960dp wide), text beside it. Closing while photos download lets the download finish.
 */
@Composable
fun GooglePhotosDialog(onDismiss: () -> Unit, onSetUpGoogle: () -> Unit) {
    val context = LocalContext.current
    val state by GooglePhotos.state.collectAsStateWithLifecycle()
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
                    val bitmap = remember(qr) { Qr.bitmap(qr, 600).asImageBitmap() }
                    Image(bitmap, contentDescription = "QR code", modifier = Modifier.size(230.dp))
                }
            } else {
                Image(painterResource(R.drawable.brand_google_photos), contentDescription = null, modifier = Modifier.size(96.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state.step) {
                    GooglePhotos.Step.NEED_GOOGLE -> Text("Set up the Google keys first (same as Google Drive).")
                    GooglePhotos.Step.SIGN_IN -> Text(if (state.qrUrl == null) "Preparing…" else "Scan with your phone and sign in to Google.")
                    GooglePhotos.Step.READY -> Text("Opening Google Photos…")
                    GooglePhotos.Step.PICKING -> Text("Scan with your phone, choose photos, then tap Done.")
                    GooglePhotos.Step.DOWNLOADING -> {
                        Text("Adding ${state.done} of ${state.total}")
                        UsageBar(if (state.total > 0) state.done.toFloat() / state.total else 0f)
                    }
                    GooglePhotos.Step.DONE -> Text(
                        if (state.added == 0) "No photos were added." else "Added ${state.added} photo${if (state.added == 1) "" else "s"}. The slideshow now uses them.",
                    )
                }
                state.error?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        ButtonRow {
            when (state.step) {
                GooglePhotos.Step.NEED_GOOGLE -> Button(onClick = { onDismiss(); onSetUpGoogle() }, modifier = Modifier.focusRequester(focus)) { Text("Set up Google") }
                GooglePhotos.Step.DONE -> {
                    OutlinedButton(onClick = { GooglePhotos.startPicking() }) { Text("Pick more") }
                    Button(onClick = { onDismiss(); SlideshowActivity.start(context) }, modifier = Modifier.focusRequester(focus)) { Text("Start slideshow") }
                }
                else -> Unit
            }
            OutlinedButton(onClick = close, modifier = if (state.step == GooglePhotos.Step.NEED_GOOGLE || state.step == GooglePhotos.Step.DONE) Modifier else Modifier.focusRequester(focus)) {
                Text(if (state.step == GooglePhotos.Step.DOWNLOADING) "Hide" else "Close")
            }
        }
    }
    LaunchedEffect(state.step) { runCatching { focus.requestFocus() } }
}
