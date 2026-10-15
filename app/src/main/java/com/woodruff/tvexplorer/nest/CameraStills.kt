package com.woodruff.tvexplorer.nest

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The last picture seen from each camera (a small frame grabbed from any live view), so tiles
 * that aren't streaming show the camera's view instead of an icon. Google offers no on-demand
 * snapshots for these cameras, so this is the cheap way: no extra streams. Memory only.
 */
object CameraStills {
    class Still(val bitmap: Bitmap, val at: Long)

    private val _stills = MutableStateFlow<Map<String, Still>>(emptyMap())
    val stills: StateFlow<Map<String, Still>> = _stills

    fun put(camera: String, bitmap: Bitmap) {
        _stills.update { it + (camera to Still(bitmap, System.currentTimeMillis())) }
    }
}
