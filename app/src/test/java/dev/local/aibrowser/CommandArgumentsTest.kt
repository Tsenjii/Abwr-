package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class CommandArgumentsTest {
    private fun rejected(args: Map<String, Any?>) {
        try { CommandArguments.validate(args); fail("Expected validation failure") }
        catch (error: BrowserOperationException) { assertEquals("BAD_ARGUMENTS", error.code) }
    }
    @Test fun writesRejectMissingAndNumericTargetsBeforeDispatch() {
        rejected(mapOf("action" to "click"))
        rejected(mapOf("action" to "type", "text" to "hello", "target" to 12))
        rejected(mapOf("action" to "toggle", "target" to "#box", "checked" to "true"))
    }
    @Test fun unknownArgumentsCannotBeSilentlyIgnored() {
        rejected(mapOf("action" to "click", "selector" to "#login", "force" to true))
        rejected(mapOf("action" to "navigate", "url" to "https://example.org/", "unexpected" to true))
    }
    @Test fun frameAndDocumentGuardsAndEmptyClearTextAreValid() {
        CommandArguments.validate(mapOf("action" to "type", "text" to "", "target" to "e123_1",
            "frame" to "child-document", "documentId" to "top-document", "expectedFrameUrl" to "https://a.example/"))
        CommandArguments.validate(mapOf("action" to "snapshot", "includeHidden" to true, "offset" to 100))
    }
    @Test fun nativeCoordinatesAndKeysRequireValidTypes() {
        rejected(mapOf("action" to "click_point", "x" to Double.NaN, "y" to 10))
        rejected(mapOf("action" to "key"))
        CommandArguments.validate(mapOf("action" to "key", "key" to "Ctrl+A"))
        CommandArguments.validate(mapOf("action" to "click_point", "x" to 30, "y" to 40, "imageWidth" to 1080, "imageHeight" to 1800))
    }
}
