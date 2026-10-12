package dev.local.aibrowser

import java.net.URI

/** A requested destination is never a substitute for a committed document. */
internal class PageIdentity {
    var documentId: String? = null
        private set
    var url: String? = null
        private set
    var pendingUrl: String? = null
        private set
    var navigating = false
        private set
    var readyState = "unknown"
        private set
    private var replacedDocumentId: String? = null

    fun beginNavigation(target: String? = null) {
        if (!navigating) replacedDocumentId = documentId
        navigating = true
        readyState = "loading"
        pendingUrl = target
    }

    fun acceptDocument(id: String, address: String): Boolean {
        if (id.isBlank() || (navigating && id == replacedDocumentId)) return false
        if (id != documentId) readyState = "unknown"
        documentId = id; url = address
        pendingUrl = null; navigating = false; replacedDocumentId = null
        return true
    }

    fun observeReadyState(id: String, state: String): Boolean {
        if (navigating || id != documentId || state !in setOf("loading", "interactive", "complete")) return false
        readyState = state
        return true
    }

    fun updateSameDocument(id: String, address: String): Boolean {
        if (id != documentId) return false
        if (navigating && pendingUrl != null &&
            (!sameUrl(address, pendingUrl!!) || !sameUrl(withoutFragment(pendingUrl!!), withoutFragment(url.orEmpty())))) return false
        // Hash navigation keeps the same document, but a reload does not.
        if (navigating && withoutFragment(address) != withoutFragment(url.orEmpty())) return false
        if (navigating && address == url) return false
        url = address; pendingUrl = null; navigating = false; replacedDocumentId = null
        return true
    }

    fun navigationFailed() {
        navigating = false; pendingUrl = null; replacedDocumentId = null
    }

    companion object {
        private fun withoutFragment(url: String) = url.substringBefore('#')
        fun sameUrl(left: String, right: String): Boolean = canonical(left) == canonical(right)
        private fun canonical(raw: String): String = runCatching {
            val u = URI(URI(raw).normalize().toASCIIString())
            val scheme = u.scheme?.lowercase() ?: return@runCatching raw
            val host = u.host?.lowercase() ?: return@runCatching raw
            val port = if ((scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) -1 else u.port
            val authority = (if (host.contains(':')) "[$host]" else host) + if (port < 0) "" else ":$port"
            "$scheme://$authority" + u.rawPath.orEmpty().ifEmpty { "/" } +
                (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
        }.getOrDefault(raw)
    }
}
