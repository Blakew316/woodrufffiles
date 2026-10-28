package com.woodruff.tvexplorer.nest

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * A live camera view drawn into a TextureView, for small tiles: unlike a SurfaceView (which draws
 * in its own layer, outside the app's clipping) it takes the tile's rounded corners, and frames
 * are cropped to the view's shape so the picture fills the tile instead of sitting letterboxed
 * inside it. The full-screen camera keeps the SurfaceView (cheaper, and shows the whole frame).
 */
class TextureRenderer(context: Context, eglContext: EglBase.Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val egl = EglRenderer("camera-tile")

    init {
        egl.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        // Each frame a TextureView shows redraws the app's window (a SurfaceView's don't): 15 a
        // second is smooth for a small tile and halves that work on Home.
        egl.setFpsReduction(15f)
        surfaceTextureListener = this
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onFrame(frame: VideoFrame) = egl.onFrame(frame)

    /** A small copy of the next frame (for the still shown when the tile isn't streaming). */
    fun addFrameListener(listener: EglRenderer.FrameListener, scale: Float) = egl.addFrameListener(listener, scale)

    fun release() = egl.release()

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        fill(width, height)
        egl.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fill(width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Wait until the render thread has let go of the surface before it's destroyed.
        val done = CountDownLatch(1)
        egl.releaseEglSurface { done.countDown() }
        runCatching { done.await() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    /** Frames are cropped to the view's shape: the picture fills it, nothing stretched. */
    private fun fill(width: Int, height: Int) = egl.setLayoutAspectRatio(if (width > 0 && height > 0) width.toFloat() / height else 0f)
}
