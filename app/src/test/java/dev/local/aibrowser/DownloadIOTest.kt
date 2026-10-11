package dev.local.aibrowser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DownloadIOTest {
    @Test fun utf8DispositionWinsOverAsciiFallback() {
        assertEquals("測試.pdf", DownloadIO.filename("https://site.test/ignored?token=secret",
            "attachment; filename=plain.pdf; filename*=UTF-8''%E6%B8%AC%E8%A9%A6.pdf"))
    }
    @Test fun pathTraversalAndControlCharactersStayInsideFilename() {
        val name = DownloadIO.filename("https://site.test/", "attachment; filename=\"../../bad\\file\r\n.zip\"")
        assertFalse(name.contains('/')); assertFalse(name.contains('\\'))
        assertFalse(name.contains('\r')); assertFalse(name.contains('\n'))
        assertFalse(name.startsWith('.')); assertTrue(name.endsWith(".zip"))
    }
    @Test fun urlQueryAndFragmentsNeverBecomeFilename() {
        assertEquals("report+final.pdf", DownloadIO.filename("https://site.test/report%2Bfinal.pdf?code=secret#access_token=secret", null))
        assertEquals("download", DownloadIO.filename("blob:https://site.test/123", null))
    }
    @Test fun exactStreamingCopyWorksAcrossBufferBoundaries() {
        val original = ByteArray(180_001) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        var progress = 0L
        val size = DownloadIO.copy(ByteArrayInputStream(original), output, original.size.toLong(), progress = { progress = it })
        assertEquals(original.size.toLong(), size)
        assertEquals(size, progress)
        assertArrayEquals(original, output.toByteArray())
    }
    @Test(expected = IOException::class) fun truncatedResponseIsNotACompletedDownload() {
        DownloadIO.copy(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3)
    }
    @Test(expected = IOException::class) fun oversizedResponseDoesNotIgnoreExpectedLength() {
        DownloadIO.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), 2)
    }
    @Test fun unknownLengthStillWritesCompleteBody() {
        val output = ByteArrayOutputStream()
        assertEquals(3L, DownloadIO.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output))
        assertEquals(3, output.size())
    }
    @Test fun cancellationStopsBeforeWritingAnotherBuffer() {
        val output = ByteArrayOutputStream()
        var cancelled = false
        try {
            DownloadIO.copy(ByteArrayInputStream(ByteArray(130_000)), output,
                cancelled = { cancelled }, progress = { cancelled = true })
            fail("Cancelled download completed")
        } catch (_: CancellationException) {
            assertEquals(65536, output.size())
        }
    }
}
