package dev.local.aibrowser

/** A fresh complete document is authoritative even when Gecko omits a load-start callback. */
internal class NavigationReadiness(val expectedUrl: String, val previousDocument: String?,
    private val previousUrl: String?) {
    var requestedLocationObserved = false
        private set
    fun observe(address: String) {
        if (PageIdentity.sameUrl(address, expectedUrl)) requestedLocationObserved = true
    }
    private val fragmentNavigation = previousUrl != null && !PageIdentity.sameUrl(previousUrl, expectedUrl) &&
        PageIdentity.sameUrl(previousUrl.substringBefore('#'), expectedUrl.substringBefore('#'))
    fun accepts(id: String, address: String, readyState: String): Boolean =
        id.isNotBlank() && (id != previousDocument || fragmentNavigation) && readyState == "complete" &&
            (PageIdentity.sameUrl(address, expectedUrl) || (requestedLocationObserved &&
                previousUrl != null && !PageIdentity.sameUrl(address, previousUrl)))
}
