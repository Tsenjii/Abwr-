package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class AutomationInputsTest {
    @Test fun pathAllowsPositionPauseAndPressButMustRelease() {
        PointerPath.validate(listOf(PointerStep("move", 10.0, 20.0, 0), PointerStep("down", 12.0, 20.0, 300),
            PointerStep("move", 30.0, 40.0, 500), PointerStep("up", 30.0, 40.0, 900)), 100, 100)
        assertThrows(IllegalArgumentException::class.java) { PointerPath.validate(listOf(PointerStep("down", 1.0, 1.0, 0), PointerStep("move", 2.0, 2.0, 10)), 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { PointerPath.validate(listOf(PointerStep("down", 1.0, 1.0, 20), PointerStep("up", 1.0, 1.0, 10)), 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { PointerPath.validate(listOf(PointerStep("down", 100.0, 1.0, 0), PointerStep("up", 1.0, 1.0, 10)), 100, 100) }
    }
    @Test fun allNativeArgumentsValidateBeforeDispatch() {
        CommandArguments.validate(mapOf("action" to "pointer_path", "steps" to listOf(mapOf("kind" to "down", "x" to 1, "y" to 2, "atMs" to 0), mapOf("kind" to "up", "x" to 1, "y" to 2, "atMs" to 50))))
        assertThrows(BrowserOperationException::class.java) { CommandArguments.validate(mapOf("action" to "key_sequence", "keys" to List(128) { "Enter" }, "intervalMs" to 1000)) }
        assertThrows(BrowserOperationException::class.java) { CommandArguments.validate(mapOf("action" to "wait", "condition" to "document_changed")) }
        CommandArguments.validate(mapOf("action" to "wait", "condition" to "document_changed", "previousDocumentId" to "d1", "timeoutMs" to 20000))
    }
    @Test fun proxySyntaxSeparatesCredentialsAndPreservesScheme() {
        assertEquals("socks", ProxySpec.parse("socks5://127.0.0.1:7901", "owner", "secret").type)
        assertEquals("http://localhost:7902", ProxySpec.parse("localhost:7902").server)
        for (bad in listOf("host:7901:owner:secret", "http://owner:secret@host:7901", "http://host:0", "http://host:7901/path", "ftp://host:7901"))
            assertThrows(IllegalArgumentException::class.java) { ProxySpec.parse(bad) }
    }
}
