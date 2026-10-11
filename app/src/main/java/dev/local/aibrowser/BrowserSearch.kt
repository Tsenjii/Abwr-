package dev.local.aibrowser

import android.net.Uri

/** Shared address-bar behavior for the normal browser UI. */
object BrowserSearch {
    private val providers = linkedMapOf(
        "google" to "Google",
        "duckduckgo" to "DuckDuckGo",
        "brave" to "Brave Search",
        "bing" to "Bing"
    )
    fun names(): List<String> = providers.values.toList()
    fun keys(): List<String> = providers.keys.toList()
    fun label(key: String): String = providers[key] ?: providers.getValue("google")
    fun normalizeProvider(key: String): String = key.takeIf { providers.containsKey(it) } ?: "google"

    fun home(provider: String): String = when (normalizeProvider(provider)) {
        "duckduckgo" -> "https://duckduckgo.com/"
        "brave" -> "https://search.brave.com/"
        "bing" -> "https://www.bing.com/"
        else -> "https://www.google.com/"
    }
    fun search(query: String, provider: String): String {
        val base = when (normalizeProvider(provider)) {
            "duckduckgo" -> "https://duckduckgo.com/?q="
            "brave" -> "https://search.brave.com/search?q="
            "bing" -> "https://www.bing.com/search?q="
            else -> "https://www.google.com/search?q="
        }
        return base + Uri.encode(query)
    }
    fun resolve(text: String, provider: String): String {
        val value = text.trim()
        require(value.isNotEmpty()) { "Empty address" }
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            val uri = Uri.parse(value)
            if (uri.host.isNullOrBlank()) return search(value, provider)
            return value
        }
        // Non-web schemes, spaces and plain terms are ordinary search queries.
        if (value.contains(" ") || !value.contains('.') || value.contains("://") ||
            value.startsWith("javascript:", true) || value.startsWith("data:", true))
            return search(value, provider)
        return "https://$value"
    }
}
