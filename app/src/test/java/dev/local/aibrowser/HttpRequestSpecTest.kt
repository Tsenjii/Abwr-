package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class HttpRequestSpecTest {
    private fun reject(args: Map<String, Any?>) {
        try { HttpRequestSpec.validate(mapOf("url" to "https://example.org/api") + args); fail("Must reject before dispatch") }
        catch (error: BrowserOperationException) { assertEquals("BAD_ARGUMENTS", error.code) }
    }
    @Test fun validatesPostAndExplicitAuthorizationHeader() {
        HttpRequestSpec.validate(mapOf("url" to "https://example.org/api", "method" to "POST",
            "headers" to mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ci-test"), "body" to "{}"))
    }
    @Test fun rejectsHeaderInjectionAndCookieJarOverrides() {
        reject(mapOf("headers" to mapOf("X-Test" to "a\r\nCookie: other-profile")))
        reject(mapOf("headers" to mapOf("Cookie" to "sid=other")))
        reject(mapOf("headers" to mapOf("Host" to "other.example")))
        reject(mapOf("headers" to mapOf("X-Test" to 1)))
        reject(mapOf("url" to "https://user:secret@example.org/api"))
    }
    @Test fun limitsBodiesTimeoutsAndUnsafeCoercions() {
        reject(mapOf("method" to "GET", "body" to "{}"))
        reject(mapOf("method" to "POST", "body" to "a", "bodyBase64" to "YQ=="))
        reject(mapOf("method" to "POST", "bodyBase64" to "*invalid*"))
        reject(mapOf("timeoutMs" to 60000)); reject(mapOf("maxBytes" to 2_000_001))
        reject(mapOf("stream" to "true")); reject(mapOf("timeoutMs" to 1.5))
        reject(mapOf("transport" to "native", "credentials" to "include"))
        HttpRequestSpec.validate(mapOf("url" to "https://example.org/", "stream" to true, "timeoutMs" to 60000))
    }
    @Test fun comparesOriginsIncludingDefaultPortsAndRejectsCrossOriginCookieUse() {
        assertTrue(HttpRequestSpec.sameOrigin("https://example.org/a", "https://example.org:443/b"))
        assertFalse(HttpRequestSpec.sameOrigin("https://example.org/a", "https://example.org:444/b"))
        assertFalse(HttpRequestSpec.sameOrigin("https://example.org/a", "https://other.example.org/b"))
    }
}
