package dev.local.aibrowser

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CancellationException

/** Platform-independent download rules; never use a website's filename as a path. */
internal object DownloadIO {
    fun filename(url: String, disposition: String?): String {
        val header = disposition.orEmpty().take(4096)
        val encoded = Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8'[^']*'([^;]+)")
            .find(header)?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]*))")
            .find(header)?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.trim() }
        fun decode(value: String) = runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(value)
        val suggested = encoded?.let(::decode) ?: plain ?: runCatching {
            decode(URI(url).rawPath.orEmpty().substringAfterLast('/'))
        }.getOrDefault("")
        val clean = suggested.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\u202a-\\u202e\\u2066-\\u2069]"), "")
            .trim().trim('.').take(140)
        return clean.ifBlank { "download" }
    }

    fun copy(input: InputStream, output: OutputStream, expectedBytes: Long = -1L,
        cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("Download cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            bytes += count
            if (expectedBytes >= 0L && bytes > expectedBytes)
                throw IOException("Download exceeds expected size")
            progress(bytes)
        }
        if (cancelled()) throw CancellationException("Download cancelled")
        if (expectedBytes >= 0L && bytes != expectedBytes)
            throw IOException("Download ended before expected size")
        return bytes
    }
}
