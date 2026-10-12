package dev.local.aibrowser

/** Keeps a small per-container undo list. Private entries never enter it. */
internal class ClosedTabs {
    data class Entry(val profile: String, val url: String, val title: String)
    private val items = mutableListOf<Entry>()
    fun remember(profile: String, url: String, title: String, privateMode: Boolean) {
        if (privateMode || !BrowserBookmarks.validUrl(url)) return
        items.add(Entry(profile, url, title.take(250)))
        while (items.size > 30) items.removeAt(0)
    }
    fun list(profile: String) = items.filter { it.profile == profile }.asReversed()
    fun peek(profile: String) = items.lastOrNull { it.profile == profile }
    fun remove(entry: Entry) { items.remove(entry) }
    fun clear(profile: String) { items.removeAll { it.profile == profile } }
}
