package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class PageIdentityTest {
    @Test fun navigationCannotReadAReconnectedOldDocument() {
        val page = PageIdentity()
        assertTrue(page.acceptDocument("old", "https://a.example/"))
        page.beginNavigation("https://b.example/")
        assertFalse(page.acceptDocument("old", "https://a.example/"))
        assertTrue(page.navigating)
        assertEquals("https://a.example/", page.url)
        assertEquals("https://b.example/", page.pendingUrl)
        assertTrue(page.acceptDocument("new", "https://b.example/canonical"))
        assertFalse(page.navigating)
        assertNull(page.pendingUrl)
        assertEquals("new", page.documentId)
    }
    @Test fun reloadAtSameUrlStillRequiresANewDocument() {
        val page = PageIdentity()
        page.acceptDocument("first", "https://example.org/")
        page.beginNavigation("https://example.org/")
        assertFalse(page.updateSameDocument("first", "https://example.org/"))
        assertFalse(page.acceptDocument("first", "https://example.org/"))
        assertTrue(page.acceptDocument("reloaded", "https://example.org/"))
    }
    @Test fun redirectStartsCannotLoseTheBlockedDocumentIdentity() {
        val page = PageIdentity()
        page.acceptDocument("old", "https://example.org/")
        page.beginNavigation("https://example.org/login")
        page.beginNavigation("https://accounts.example.org/")
        assertFalse(page.acceptDocument("old", "https://example.org/"))
        assertTrue(page.acceptDocument("oauth", "https://accounts.example.org/"))
    }
    @Test fun fragmentNavigationKeepsTheDocumentAndFailedNavigationCanRecover() {
        val page = PageIdentity()
        page.acceptDocument("first", "https://example.org/")
        page.beginNavigation("https://example.org/#next")
        assertTrue(page.updateSameDocument("first", "https://example.org/#next"))
        assertFalse(page.navigating)
        page.beginNavigation("https://missing.example/")
        page.navigationFailed()
        assertTrue(page.acceptDocument("first", "https://example.org/#next"))
    }
    @Test fun canonicalUrlsAcceptBrowserSerializationButKeepRealChanges() {
        assertTrue(PageIdentity.sameUrl("https://EXAMPLE.org:443", "https://example.org/"))
        assertTrue(PageIdentity.sameUrl("http://example.org:80", "http://example.org/"))
        assertFalse(PageIdentity.sameUrl("https://example.org/", "https://example.org/#next"))
        assertFalse(PageIdentity.sameUrl("https://example.org/a?x=1", "https://example.org/a?x=2"))
    }
    @Test fun unicodePathsQueriesAndFragmentsMatchBrowserSerialization() {
        assertTrue(PageIdentity.sameUrl("https://example.org/中文?q=測試#章節",
            "https://example.org/%E4%B8%AD%E6%96%87?q=%E6%B8%AC%E8%A9%A6#%E7%AB%A0%E7%AF%80"))
        assertFalse(PageIdentity.sameUrl("https://example.org/中文?q=測試",
            "https://example.org/%E4%B8%AD%E6%96%87?q=different"))
    }
}
