package dev.local.aibrowser

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * A compositor surface owned by the browser service, independent of Activity/View visibility.
 * Renders only this GeckoSession. No screen mirroring, overlay, or MediaProjection permission.
 */
internal class OffscreenBrowserDisplay(val width: Int, val height: Int) :
    GeckoDisplay.NewSurfaceProvider {
    private val thread = HandlerThread("Abwr-compositor-drain").apply { start() }
    private var reader: ImageReader? = null
    private var display: GeckoDisplay? = null
    var session: GeckoSession? = null
        private set
    var generation: Long = 0L
        private set
    private var closed = false
    @Volatile var framesReceived: Long = 0L
        private set
    @Volatile var lastFrameElapsedMs: Long = 0L
        private set
    val attached: Boolean get() = !closed && display != null && reader?.surface?.isValid == true

    fun attach(next: GeckoSession) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed)
        if (session === next && attached) return
        detach()
        session = next
        display = next.acquireDisplay()
        try {
            replaceSurface()
            next.setActive(true)
            next.setFocused(true)
        } catch (error: Throwable) { detach(); throw error }
    }

    private fun replaceSurface() {
        display?.surfaceDestroyed()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        generation++
        framesReceived = 0L; lastFrameElapsedMs = 0L
        val created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = created
        created.setOnImageAvailableListener({ source ->
            runCatching {
                source.acquireLatestImage()?.use {
                    framesReceived++
                    lastFrameElapsedMs = SystemClock.elapsedRealtime()
                }
            }
        }, Handler(thread.looper))
        display!!.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(created.surface)
            .size(width, height).newSurfaceProvider(this).build())
        display!!.screenOriginChanged(0, 0)
    }

    override fun requestNewSurface() {
        if (!closed && display != null) replaceSurface()
    }

    fun capturePixels(): GeckoResult<Bitmap> {
        check(attached) { "Background compositor surface not attached" }
        return display!!.capturePixels()
    }

    private fun detach() {
        val previous = session
        val previousDisplay = display
        display = null; session = null
        if (previousDisplay != null) {
            previousDisplay.surfaceDestroyed()
            previous?.releaseDisplay(previousDisplay)
        }
        previous?.setFocused(false)
        previous?.setActive(false)
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        detach()
        thread.quitSafely()
    }
}
