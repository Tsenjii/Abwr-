package dev.local.aibrowser

/** A fresh complete document is authoritative even when Gecko omits a load-start callback. */
internal class NavigationReadiness(val expectedUrl: String, val previousDocument: String?,
    private val previousUrl: String?) {
    companion object {
        fun joinsInitialLoad(previousDocument: String?, pendingUrl: String?, requestedUrl: String): Boolean =
            previousDocument == null && pendingUrl != null && PageIdentity.sameUrl(pendingUrl, requestedUrl)
    }
    var requestedLocationObserved = false
        private set
    private var redirectedUrl: String? = null
    fun observe(address: String) {
        if (PageIdentity.sameUrl(address, expectedUrl)) requestedLocationObserved = true
    }
    fun observeRedirect(address: String) {
        if (requestedLocationObserved) redirectedUrl = address
    }
    fun matchesTarget(address: String): Boolean = PageIdentity.sameUrl(address, expectedUrl) ||
        redirectedUrl?.let { PageIdentity.sameUrl(address, it) } == true
    private val fragmentNavigation = previousUrl != null && !PageIdentity.sameUrl(previousUrl, expectedUrl) &&
        PageIdentity.sameUrl(previousUrl.substringBefore('#'), expectedUrl.substringBefore('#'))
    fun accepts(id: String, address: String, readyState: String): Boolean =
        id.isNotBlank() && (id != previousDocument || fragmentNavigation) && readyState == "complete" &&
            (matchesTarget(address) || (requestedLocationObserved &&
                previousUrl != null && !PageIdentity.sameUrl(address, previousUrl)))
}
