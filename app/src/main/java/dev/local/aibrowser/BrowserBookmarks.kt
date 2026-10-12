package dev.local.aibrowser

import java.net.URI

/** Netscape bookmarks interchange, without interpreting imported markup. */
internal object BrowserBookmarks {
    data class Entry(val url: String, val title: String)
    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length in 1..4096 && uri.scheme?.lowercase() in setOf("https", "http") &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
    fun export(entries: List<Entry>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Abwr Bookmarks</TITLE>\n<H1>Abwr 書籤</H1>\n<DL><p>\n")
        entries.filter { validUrl(it.url) }.distinctBy { it.url }.take(250).forEach {
            append("<DT><A HREF=\"").append(escape(it.url)).append("\">")
                .append(escape(it.title.ifBlank { it.url }.take(250))).append("</A>\n")
        }
        append("</DL><p>\n")
    }
    fun parse(html: String): List<Entry> {
        require(html.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "書籤檔案過大（上限 1 MB）" }
        val anchor = Regex("<a\\b[^>]*\\bhref\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return anchor.findAll(html).map { match ->
            Entry(decode(match.groupValues[2]).trim(), decode(match.groupValues[3].replace(Regex("<[^>]*>"), "")).trim().take(250))
        }.filter { validUrl(it.url) }.distinctBy { it.url }.take(250).toList()
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
    private fun decode(value: String): String = Regex("&(#x[0-9a-f]+|#[0-9]+|amp|quot|lt|gt|apos);", RegexOption.IGNORE_CASE).replace(value) {
        val entity = it.groupValues[1]
        when (entity.lowercase()) {
            "amp" -> "&"; "quot" -> "\""; "lt" -> "<"; "gt" -> ">"; "apos" -> "'"
            else -> {
                val code = if (entity.startsWith("#x", true)) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF && code > 0)
                    String(Character.toChars(code)) else it.value
            }
        }
    }
}
