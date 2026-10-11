package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class NavigationReadinessTest {
    @Test fun freshCompleteRequestedDocumentDoesNotRequireProgressStart() {
        val wait = NavigationReadiness("https://example.org/new", "old", "https://example.org/old")
        assertFalse(wait.accepts("new", "https://example.org/new", "interactive"))
        assertFalse(wait.accepts("old", "https://example.org/old", "complete"))
        assertTrue(wait.accepts("new", "https://example.org/new", "complete"))
    }
    @Test fun redirectsRequireObservedRequestAndRejectPreviousPage() {
        val wait = NavigationReadiness("https://example.org/login", "old", "https://example.org/")
        assertFalse(wait.accepts("redirect", "https://accounts.example.org/", "complete"))
        wait.observe("https://example.org/login")
        assertTrue(wait.accepts("redirect", "https://accounts.example.org/", "complete"))
        assertFalse(wait.accepts("late-home", "https://example.org/", "complete"))
    }
    @Test fun reloadNeedsANewDocumentAtTheSameAddress() {
        val wait = NavigationReadiness("https://example.org/", "old", "https://example.org/")
        assertFalse(wait.accepts("old", "https://example.org/", "complete"))
        assertTrue(wait.accepts("reloaded", "https://example.org/", "complete"))
    }
    @Test fun fragmentNavigationAcceptsTheOriginalDocumentOnlyAtTheRequestedFragment() {
        val wait = NavigationReadiness("https://example.org/#next", "old", "https://example.org/")
        assertFalse(wait.accepts("old", "https://example.org/", "complete"))
        assertTrue(wait.accepts("old", "https://example.org/#next", "complete"))
        val differentPage = NavigationReadiness("https://example.org/new#next", "old", "https://example.org/")
        assertFalse(differentPage.accepts("old", "https://example.org/new#next", "complete"))
    }
    @Test fun nativeRedirectMayReturnToPreviousUrlButCannotAcceptTheOldDocument() {
        val wait = NavigationReadiness("https://example.org/auth", "old", "https://example.org/")
        wait.observeRedirect("https://example.org/")
        assertFalse(wait.accepts("late-home", "https://example.org/", "complete"))
        wait.observe("https://example.org/auth")
        assertFalse(wait.accepts("late-home", "https://example.org/", "complete"))
        wait.observeRedirect("https://example.org/")
        assertTrue(wait.matchesTarget("https://example.org/"))
        assertTrue(wait.accepts("new-home", "https://example.org/", "complete"))
        assertFalse(wait.accepts("old", "https://example.org/", "complete"))
    }
}
