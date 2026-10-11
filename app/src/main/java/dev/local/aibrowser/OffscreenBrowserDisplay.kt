package dev.local.aibrowser

import android.app.Application
import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.ViewGroup
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoView

/**
 * Experimental, private, application-owned render surface.
 *
 * This DOES NOT mirror the phone's display, require MediaProjection, capture
 * other apps or bypass Android's background Activity restrictions.  The
 * Presentation renders only the application's GeckoView on a private
 * VirtualDisplay; device and OEM behavior must still be verified.
 */
class OffscreenBrowserDisplay(private val app: Application) {
    private val thread = HandlerThread("Abwr-display-drain").apply { start() }
    private var frames: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: BrowserPresentation? = null

    private class BrowserPresentation(context: Context, androidDisplay: android.view.Display) :
        Presentation(context, androidDisplay) {
        lateinit var browserView: GeckoView
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            browserView = GeckoView(context)
            val root = FrameLayout(context)
            root.addView(browserView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            setContentView(root)
        }
    }

    fun start(): GeckoView {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (presentation != null) return presentation!!.browserView
        try {
            val width = 640
            val height = 960
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            frames = reader
            // Drain the private VirtualDisplay surface so buffers do not block rendering.
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.close()
            }, Handler(thread.looper))
            val manager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val dpi = app.resources.displayMetrics.densityDpi.coerceAtLeast(160)
            val virtual = manager.createVirtualDisplay(
                "Abwr private browser render", width, height, dpi, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            ) ?: error("Android did not grant an app-private VirtualDisplay")
            display = virtual
            val window = BrowserPresentation(app, virtual.display)
            presentation = window
            window.show()
            return window.browserView
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    fun close() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
        frames?.close()
        frames = null
        thread.quitSafely()
    }
}
