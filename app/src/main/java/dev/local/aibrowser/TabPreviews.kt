package dev.local.aibrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Small real page previews in app-private cache; never fetch remote favicons or open dormant tabs. */
internal class TabPreviews(context: Context) {
    private val directory = File(context.cacheDir, "tab-previews-v1")
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private fun key(profile: String, tab: String) = MessageDigest.getInstance("SHA-256")
        .digest("$profile/$tab".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun read(profile: String, tab: String, ready: (Bitmap?) -> Unit) {
        val id = key(profile, tab)
        memory.get(id)?.let { ready(it); return }
        worker.execute {
            val bitmap = runCatching { BitmapFactory.decodeFile(File(directory, "$id.jpg").path) }.getOrNull()
            if (bitmap != null) memory.put(id, bitmap)
            ui.post { ready(bitmap) }
        }
    }
    fun save(profile: String, tab: String, jpeg: ByteArray) {
        val id = key(profile, tab)
        worker.execute {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = Integer.highestOneBit((bounds.outWidth / 320).coerceAtLeast(1))
                }
                val original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return@runCatching
                val width = minOf(360, original.width)
                val small = Bitmap.createScaledBitmap(original, width,
                    (original.height.toLong() * width / original.width).toInt().coerceAtLeast(1), true)
                if (small !== original) original.recycle()
                directory.mkdirs()
                val file = File(directory, "$id.jpg")
                file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                memory.put(id, small)
                // At most 96 previews and 12 MiB, independently from browser session residency.
                val files = directory.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                var bytes = 0L
                files.forEachIndexed { index, cached ->
                    bytes += cached.length()
                    if (index >= 96 || bytes > 12 * 1024 * 1024) cached.delete()
                }
            }
        }
    }
    fun remove(profile: String, tab: String) {
        val id = key(profile, tab)
        worker.execute { memory.remove(id); File(directory, "$id.jpg").delete() }
    }
}
