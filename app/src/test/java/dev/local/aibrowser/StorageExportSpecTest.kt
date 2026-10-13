package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class StorageExportSpecTest {
    private val valid = mapOf("profile" to "TEST01", "tab" to "main", "documentId" to "doc1", "consented" to true)
    private fun rejects(input: Map<String, Any?>) {
        try { StorageExportSpec.validate(input); fail("Must refuse before export") }
        catch (error: BrowserOperationException) { assertEquals("BAD_ARGUMENTS", error.code) }
    }
    @Test fun requiresExactBooleanConsentAndCompleteDocumentScope() {
        StorageExportSpec.validate(valid)
        StorageExportSpec.validate(valid + ("expectedUrl" to "https://example.org/"))
        for (consent in listOf(false, "true", 1, null)) rejects(valid + ("consented" to consent))
        for (key in listOf("profile", "tab", "documentId")) {
            rejects(valid - key); rejects(valid + (key to "")); rejects(valid + (key to 1))
        }
    }
    @Test fun refusesUnknownFieldsAndMalformedOptionalUrlBeforeDispatch() {
        rejects(valid + ("rawCookies" to true)); rejects(valid + ("expectedUrl" to 1))
        rejects(valid + ("expectedUrl" to "")); rejects(valid + ("documentId" to "x".repeat(8001)))
    }
}
