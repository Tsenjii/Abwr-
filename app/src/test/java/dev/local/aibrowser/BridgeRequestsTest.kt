package dev.local.aibrowser

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class BridgeRequestsTest {
    @Test fun wrongSessionCannotConsumeReply() {
        val requests = BridgeRequests<Any, Any, String>()
        val session = Any(); val port = Any(); val result = CompletableFuture<String>()
        requests.add("r1", session, port, result)
        assertFalse(requests.reply("r1", Any(), port, "wrong page"))
        assertFalse(result.isDone)
        assertTrue(requests.reply("r1", session, port, "right page"))
        assertEquals("right page", result.join())
        assertEquals(0, requests.size)
    }
    @Test fun disconnectedOldDocumentCannotFailNewDocument() {
        val requests = BridgeRequests<Any, Any, String>()
        val session = Any(); val old = Any(); val replacement = Any()
        val first = CompletableFuture<String>(); val second = CompletableFuture<String>()
        requests.add("old", session, old, first)
        requests.add("new", session, replacement, second)
        requests.failPort(old, IllegalStateException("disconnected"))
        assertTrue(first.isCompletedExceptionally)
        assertFalse(second.isDone)
        assertTrue(requests.hasSession(session))
        assertFalse(requests.reply("new", session, old, "stale reply"))
        assertTrue(requests.reply("new", session, replacement, "fresh reply"))
    }
    @Test fun closingSessionFailsOnlyItsOutstandingRequests() {
        val requests = BridgeRequests<Any, Any, String>()
        val firstSession = Any(); val secondSession = Any(); val port = Any()
        val first = CompletableFuture<String>(); val second = CompletableFuture<String>()
        requests.add("one", firstSession, port, first)
        requests.add("two", secondSession, port, second)
        requests.failSession(firstSession, IllegalStateException("closed"))
        assertTrue(first.isCompletedExceptionally)
        assertFalse(second.isDone)
        assertFalse(requests.hasSession(firstSession))
        assertTrue(requests.hasSession(secondSession))
    }
    @Test fun timeoutThenLateReplyDoesNotCompleteTwice() {
        val requests = BridgeRequests<Any, Any, String>()
        val session = Any(); val port = Any(); val result = CompletableFuture<String>()
        requests.add("r", session, port, result)
        requests.fail("r", java.util.concurrent.TimeoutException())
        assertFalse(requests.reply("r", session, port, "late"))
        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, requests.size)
    }
    @Test fun cancellationCleanupCannotRemoveAnUnrelatedFuture() {
        val requests = BridgeRequests<Any, Any, String>()
        val session = Any(); val port = Any(); val result = CompletableFuture<String>()
        requests.add("r", session, port, result)
        requests.remove("r", CompletableFuture())
        assertEquals(1, requests.size)
        result.cancel(false)
        requests.remove("r", result)
        assertFalse(requests.hasSession(session))
    }
}
