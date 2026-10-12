package dev.local.aibrowser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Stage only owner-selected/readable content URIs; never accept a caller's file path. */
internal object BrowserFiles {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2))
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    fun stage(context: Context, uris: List<Uri>): CompletableFuture<List<Uri>> {
        require(uris.size in 1..8 && uris.all { it.scheme == "content" }) { "Only 1–8 readable content URIs are accepted" }
        val result = CompletableFuture<List<Uri>>()
        try { worker.execute {
            val created = mutableListOf<File>()
            try {
                val root = File(context.cacheDir, "selected_files").apply { mkdirs() }
                root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3600000L }?.forEach { it.deleteRecursively() }
                if ((root.listFiles()?.size ?: 0) + uris.size > 16) throw BrowserOperationException("BUSY", "Selected-file staging capacity reached")
                var total = 0L
                val staged = uris.map { uri ->
                    if (result.isDone) throw java.util.concurrent.CancellationException()
                    var name = "upload.bin"
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.let { name = it }
                    }
                    name = name.replace('/', '_').replace('\\', '_').filter { it >= ' ' }.take(100)
                    if (name.isBlank() || name in setOf(".", "..")) name = "upload.bin"
                    val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                    created.add(directory)
                    val file = File(directory, name)
                    var size = 0L
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw BrowserOperationException("FILE_NOT_READABLE", "Selected file is not readable by Abwr")
                    input.use { source -> file.outputStream().use { target ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (result.isDone) throw java.util.concurrent.CancellationException()
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count; total += count
                            if (size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
                                throw BrowserOperationException("FILE_TOO_LARGE", "Selected files exceed 32 MiB each or 64 MiB total")
                            target.write(buffer, 0, count)
                        }
                    } }
                    Uri.fromFile(file)
                }
                result.complete(staged)
            } catch (error: Exception) {
                created.forEach { it.deleteRecursively() }
                result.completeExceptionally(if (error is BrowserOperationException) error else BrowserOperationException("FILE_NOT_READABLE", "Selected file could not be read"))
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            result.completeExceptionally(BrowserOperationException("BUSY", "Selected-file worker is busy"))
        }
        return result
    }
}
