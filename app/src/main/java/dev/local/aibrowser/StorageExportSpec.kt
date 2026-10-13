package dev.local.aibrowser

/** Explicit owner consent and a pinned document, checked before any export or audit. */
internal object StorageExportSpec {
    fun validate(input: Map<String, Any?>) {
        fun fail(message: String): Nothing = throw BrowserOperationException("BAD_ARGUMENTS", message)
        if (input.keys.any { it !in setOf("profile", "tab", "documentId", "expectedUrl", "consented") })
            fail("Unknown storage export argument")
        if (input["consented"] != true) fail("Explicit boolean consented:true required")
        for (key in listOf("profile", "tab", "documentId")) {
            val value = input[key] as? String ?: fail("$key is required as a string")
            if (value.isBlank() || value.length > 8000) fail("$key must be a nonempty bounded string")
        }
        if ("expectedUrl" in input) {
            val value = input["expectedUrl"] as? String ?: fail("expectedUrl must be a string")
            if (value.isBlank() || value.length > 8000) fail("expectedUrl must be a nonempty bounded string")
        }
    }
}
