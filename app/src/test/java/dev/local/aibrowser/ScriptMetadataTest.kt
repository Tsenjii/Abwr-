package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class ScriptMetadataTest {
    @Test fun importsMetadataAndKeepsExplicitRunTime() {
        val parsed = ScriptMetadata.parse("// ==UserScript==\n// @name Reader\n// @match https://*.example.org/*\n// @exclude-match https://private.example.org/*\n// @run-at document-end\n// @grant GM_addStyle\n// ==/UserScript==\ndocument.body.dataset.reader = 'yes';")
        assertEquals("Reader", parsed.name); assertEquals("document_end", parsed.runAt)
        assertEquals(listOf("GM_addStyle"), parsed.grants); assertEquals(1, parsed.excludes.size)
    }
    @Test fun rejectsPrivilegeRequestsBeforeRegistration() {
        for (line in listOf("@grant GM_xmlhttpRequest", "@require https://evil.example/lib.js", "@include *", "@run-at context-menu")) {
            try { ScriptMetadata.parse("// ==UserScript==\n// $line\n// ==/UserScript==\n1;"); fail(line) }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun acceptsOnlyWebMatchPatterns() {
        for (pattern in listOf("https://example.org/*", "http://127.0.0.1/*", "*://*.example.org/*", "<all_urls>")) assertTrue(pattern, ScriptMetadata.validMatch(pattern))
        for (pattern in listOf("http://127.0.0.1:8877/*", "file:///*", "moz-extension://*/*", "https://example.org", "https://example.org/ a", "javascript:alert(1)")) assertFalse(pattern, ScriptMetadata.validMatch(pattern))
    }
}
