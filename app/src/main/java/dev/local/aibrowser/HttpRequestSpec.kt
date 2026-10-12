package dev.local.aibrowser

import java.net.URI

/** A bounded HTTP operation. Validation happens before any request has side effects. */
internal object HttpRequestSpec {
    val fields = setOf("action", "profile", "url", "method", "headers", "body", "bodyBase64", "timeoutMs",
        "maxBytes", "stream", "transport", "credentials", "responseEncoding", "followRedirects")
    fun validate(input: Map<String, Any?>) {
        fun fail(message: String): Nothing = throw BrowserOperationException("BAD_ARGUMENTS", message)
        if (input.keys.any { it !in fields }) fail("Unknown HTTP request argument")
        val uri = runCatching { URI(input["url"] as? String ?: "") }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank() || uri?.rawUserInfo != null)
            fail("url must be an absolute HTTP(S) URL without embedded credentials")
        if ((input["url"] as String).length > 8000) fail("url is too long")
        val method = input["method"] ?: "GET"
        if (method !is String || method.uppercase() !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) fail("Unsupported HTTP method")
        for (key in listOf("body", "bodyBase64", "profile")) if (key in input && input[key] !is String) fail("$key must be a string")
        if ("body" in input && "bodyBase64" in input) fail("Use body or bodyBase64, not both")
        if (method.uppercase() in setOf("GET", "HEAD") && ("body" in input || "bodyBase64" in input)) fail("GET/HEAD cannot have a body")
        if ((input["body"] as? String)?.toByteArray(Charsets.UTF_8)?.size?.let { it > 256_000 } == true ||
            (input["bodyBase64"] as? String)?.length?.let { it > 350_000 } == true) fail("Request body exceeds 256000 bytes")
        if ("bodyBase64" in input) {
            val decoded = runCatching { java.util.Base64.getDecoder().decode(input["bodyBase64"] as String) }.getOrNull() ?: fail("bodyBase64 is invalid")
            if (decoded.size > 256_000) fail("Request body exceeds 256000 bytes")
        }
        for (key in listOf("stream", "followRedirects")) if (key in input && input[key] !is Boolean) fail("$key must be boolean")
        val maximumTimeout = if (input["stream"] == true) 60000 else 20000
        for ((key, range) in mapOf("timeoutMs" to 1000..maximumTimeout, "maxBytes" to 1..2_000_000)) {
            if (key in input) {
                val n = input[key] as? Number ?: fail("$key must be an integer")
                if (!n.toDouble().isFinite() || n.toDouble() != n.toLong().toDouble() || n.toLong() !in range.first.toLong()..range.last.toLong()) fail("$key is outside its supported range")
            }
        }
        for ((key, choices) in mapOf("transport" to setOf("auto", "page", "native"), "credentials" to setOf("include", "omit"), "responseEncoding" to setOf("auto", "text", "base64")))
            if (key in input && input[key] !in choices) fail("Invalid $key")
        if (input["transport"] == "native" && input["credentials"] == "include") fail("Native transport cannot inherit profile cookies; use page transport on the target origin")
        if ("headers" in input) {
            val headers = input["headers"] as? Map<*, *> ?: fail("headers must be an object of strings")
            if (headers.size > 40) fail("Too many request headers")
            for ((name, value) in headers) {
                if (name !is String || !Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}").matches(name) || value !is String ||
                    value.length > 8000 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) fail("Invalid request header")
                if (name.lowercase() in setOf("cookie", "host", "content-length", "connection", "transfer-encoding", "proxy-authorization", "set-cookie") || name.startsWith("Sec-", true)) fail("Cookie and transport headers are managed by the browser")
            }
        }
    }
    fun sameOrigin(a: String, b: String): Boolean = runCatching {
        val x = URI(a); val y = URI(b)
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        x.scheme.equals(y.scheme, true) && x.host.equals(y.host, true) && port(x) == port(y)
    }.getOrDefault(false)
}
