package com.woodruff.tvexplorer.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Qr {
    /** Black-on-white QR bitmap with a quiet zone, sized in pixels. */
    fun bitmap(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

/**
 * The QR code for [text], [size] px square, made off the main thread: encoding it and filling a few
 * hundred thousand pixels took a TV a frame or more when done while composing the screen. Null until
 * it's ready (a moment), and again while a new [text] is being made, so an old code never shows for a
 * new link.
 */
@Composable
fun rememberQrBitmap(text: String, size: Int): ImageBitmap? {
    val qr = remember(text, size) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(text, size) {
        qr.value = withContext(Dispatchers.Default) {
            // Handed to the graphics thread now, so its first frame on screen doesn't upload it.
            runCatching { Qr.bitmap(text, size).asImageBitmap().also { it.prepareToDraw() } }.getOrNull()
        }
    }
    return qr.value
}
