package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class BrowserBookmarksTest {
    @Test fun escapedBookmarksRoundTripWithoutRenderingMarkup() {
        val entries = listOf(BrowserBookmarks.Entry("https://example.org/?a=1&b=2", "中文 <标题> & \"引號\""))
        val html = BrowserBookmarks.export(entries)
        assertTrue(html.contains("&lt;"))
        assertEquals(entries, BrowserBookmarks.parse(html))
    }
    @Test fun importedScriptsEmbeddedCredentialsAndDuplicatesAreRejected() {
        val html = "<a href='javascript:alert(1)'>bad</a><a href='https://u:p@example.org/'>secret</a>" +
            "<A HREF='https://example.org/'>&#x4E2D;&#25991;</A><a href='https://example.org/'>duplicate</a>"
        assertEquals(listOf(BrowserBookmarks.Entry("https://example.org/", "中文")), BrowserBookmarks.parse(html))
    }
    @Test fun privateClosedTabsNeverEnterTheUndoListAndOwnershipIsPreserved() {
        val tabs = ClosedTabs()
        tabs.remember("A", "https://example.org/a", "A", false)
        tabs.remember("B", "https://example.org/b", "B", false)
        tabs.remember("A", "https://example.org/private", "secret", true)
        assertEquals("https://example.org/a", tabs.peek("A")!!.url)
        tabs.clear("A")
        assertNull(tabs.peek("A"))
        assertEquals("B", tabs.peek("B")!!.title)
    }
}
