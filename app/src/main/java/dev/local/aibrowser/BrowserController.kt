package dev.local.aibrowser

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Debug
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.UUID
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Holds the agent's browsing state. No access to the user's Chrome/Brave data. */
class BrowserController(private val app: Application) {
    private val ui = Handler(Looper.getMainLooper())
    private val store = app.getSharedPreferences("browser_profiles", 0)
    private var runtime: GeckoRuntime? = null
    var replaceBrowserView: ((GeckoView) -> GeckoView)? = null
    private var extension: WebExtension? = null
    private val initialLoads = mutableMapOf<GeckoSession, String>()
    private val startingSessions = java.util.WeakHashMap<GeckoSession, Boolean>()
    private data class StartupNavigation(val profile: String, val url: String, val fromUser: Boolean,
        val completion: CompletableFuture<JSONObject>?)
    private val startupNavigations = mutableMapOf<GeckoSession, StartupNavigation>()
    private val sessions = LinkedHashMap<String, GeckoSession>(8, .75f, true)
    private val ports = mutableMapOf<GeckoSession, WebExtension.Port>()
    private val bridgeConnections = BridgeConnections<WebExtension.Port>()
    private val navigationEpochs = java.util.WeakHashMap<GeckoSession, Long>()
    private fun retireBridgePort(port: WebExtension.Port) { bridgeConnections.retire(port) }
    private fun postBridge(port: WebExtension.Port, message: JSONObject) {
        if (!bridgeConnections.writable(port) || port.sender.session?.isOpen == false)
            throw BrowserOperationException("BRIDGE_DISCONNECTED", "Bridge connection is no longer writable")
        port.postMessage(message)
    }
    private val bridgeStartAttempts = java.util.WeakHashMap<GeckoSession, Int>()
    private val bridgeLastErrors = java.util.WeakHashMap<GeckoSession, String>()
    private val documents = java.util.WeakHashMap<GeckoSession, PageIdentity>()
    private val automationAudit = AutomationAudit(app)
    private val browserTabIds = java.util.WeakHashMap<GeckoSession, Int>()
    private fun automationScope(session: GeckoSession): JSONObject {
        val profile = profileOf(session) ?: throw BrowserOperationException("PAGE_CHANGED", "Interaction tab closed")
        val tab = if (popupSessions[profile] === session) "popup" else if (sessions[profile] === session) "main"
            else tabSessions.entries.firstOrNull { it.value === session }?.key?.substringAfter('/') ?: "closed"
        return JSONObject().put("profile", profile).put("tab", tab).put("documentId", identity(session).documentId ?: JSONObject.NULL)
            .put("url", identity(session).url ?: JSONObject.NULL)
    }
    private val promptBroker = BrowserPromptBroker(app, ::automationScope,
        { prompt -> sitePromptHost?.dismissResolvedPrompt(prompt) }, { scope, outcome ->
        val profile = scope.optString("profile")
        automationAudit.record("site_prompt", profile, scope.optString("tab"), scope.optString("documentId"),
            actor = scope.optString("actor", "ui"), outcome = outcome, private = isPrivate(profile))
    })
    private data class PendingInteraction(val session: GeckoSession, val scope: JSONObject,
        val resolve: (JSONObject) -> JSONObject, val cancel: () -> Unit, val present: () -> Unit)
    private val pendingInteractions = linkedMapOf<String, PendingInteraction>()
    private val mediaInteractionIds = java.util.IdentityHashMap<GeckoSession.PermissionDelegate.MediaCallback, String>()
    private var proxyChangeInFlight = false
    private val pendingDownloadIds = java.util.IdentityHashMap<WebResponse, String>()
    private fun registerInteraction(session: GeckoSession, kind: String, resolve: (JSONObject) -> JSONObject, cancel: () -> Unit, present: () -> Unit = {}): String {
        if (pendingInteractions.size >= 16) throw BrowserOperationException("BUSY", "Interaction queue is full")
        val id = UUID.randomUUID().toString()
        val scope = automationScope(session).put("id", id).put("scopeToken", UUID.randomUUID().toString())
            .put("kind", kind).put("createdAtMs", System.currentTimeMillis()).put("choices", JSONArray(listOf("accept", "dismiss")))
        pendingInteractions[id] = PendingInteraction(session, scope, resolve, cancel, present)
        ui.postDelayed({ pendingInteractions.remove(id)?.let { it.cancel(); sitePromptHost?.dismissResolvedPermissions(it.session) } }, 120000L)
        return id
    }
    private val activeInputProfiles = mutableSetOf<String>()
    private val bridgeVersions = java.util.WeakHashMap<GeckoSession, String>()
    private fun identity(session: GeckoSession) = documents.getOrPut(session) { PageIdentity() }
    private data class FrameBridge(val port: WebExtension.Port, val documentId: String, var url: String, val epoch: Long)
    private val framePorts = mutableMapOf<GeckoSession, LinkedHashMap<String, FrameBridge>>()
    private fun bridgeReady(session: GeckoSession): Boolean = ports[session]?.let(bridgeConnections::writable) == true &&
        identity(session).documentId != null && !identity(session).navigating
    private fun discardFramePorts(session: GeckoSession) {
        framePorts.remove(session)?.values?.toList()?.forEach {
            bridgeRequests.failPort(it.port, BrowserOperationException("PAGE_CHANGED", "Frame document changed"))
            retireBridgePort(it.port)
        }
    }
    private fun beginPageNavigation(session: GeckoSession, target: String? = null) {
        navigationEpochs[session] = (navigationEpochs[session] ?: 0L) + 1L
        http.cancelSession(session)
        securityInfo.remove(session); currentPermissions.remove(session)
        identity(session).beginNavigation(target)
        ports[session]?.let {
            bridgeRequests.failPort(it, BrowserOperationException("PAGE_CHANGED", "Page is navigating; inspect the new document"))
        }
        framePorts[session]?.values?.forEach {
            bridgeRequests.failPort(it.port, BrowserOperationException("PAGE_CHANGED", "Page is navigating"))
        }
        // A cancelled load keeps its old document/ports. During navigation,
        // bridgeReady is false; content pagehide owns native disconnection.
    }
    private fun commitDocumentLocation(session: GeckoSession, url: String) {
        if (runCatching { requireWebUrl(url) }.isFailure) return
        popupSessions.entries.firstOrNull { it.value === session }?.let { popupUrls[it.key] = url; return }
        sessions.entries.firstOrNull { it.value === session }?.let { profileUrls[it.key] = url; return }
        tabSessions.entries.firstOrNull { it.value === session }?.key?.let { key ->
            tabUrls[key.substringBefore('/')]?.set(key.substringAfter('/'), url)
        }
    }
    // OAuth and other window.open() flows can have a child tab per profile.
    private val popupSessions = mutableMapOf<String, GeckoSession>()
    private val popupUrls = mutableMapOf<String, String>()
    private val popupTitles = mutableMapOf<String, String>()
    private val popupLoading = mutableMapOf<String, Boolean>()
    // Each browser tab is a distinct GeckoSession, within the same profile contextId.
    private val tabUrls = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val desktopProfiles = mutableSetOf<String>()
    private val bookmarkUrls = linkedMapOf<String, LinkedHashSet<String>>()
    private val historyUrls = linkedMapOf<String, MutableList<String>>()
    private val tabTitles = mutableMapOf<String, String>()
    private val tabSessions = LinkedHashMap<String, GeckoSession>(8, .75f, true)
    private val activeTabs = mutableMapOf<String, String>()
    private val closedMainProfiles = mutableSetOf<String>()
    private val privateProfiles = mutableSetOf<String>()
    private val recentlyClosed = ClosedTabs()
    private val securityInfo = java.util.WeakHashMap<GeckoSession, JSONObject>()
    private val currentPermissions = java.util.WeakHashMap<GeckoSession, List<GeckoSession.PermissionDelegate.ContentPermission>>()
    // Public profile IDs are stable UI/API names. Gecko context IDs are opaque
    // identities so recreating a deleted name cannot reuse storage still being
    // cleared asynchronously by Gecko.
    private val profileContexts = mutableMapOf<String, String>()
    internal val tabGroups = TabGroups(store.getLong("tab_group_next_id_v1", 1L))
    internal val previews = TabPreviews(app).apply { onChanged = { notifyUi() } }
    internal val http = BrowserHttp(app)
    internal val userScripts = UserScripts(app)
    private val proxyPrefs = app.getSharedPreferences("profile_proxies", 0)
    private val proxyConfigurations = JSONObject(proxyPrefs.getString("configurations", "{}"))
    private val cookieStoreIdentities = JSONObject(proxyPrefs.getString("cookie_stores", "{}"))
    internal val extensions = BrowserExtensions { notifyUi() }
    internal fun visibleSessionForExtension() = selectedLoadedSession(activeProfile)
    private val previewDocuments = mutableMapOf<String, String>()
    private var previewTask: Runnable? = null
    private var nextTabId: Long = 1L
    private fun tabKey(profile: String, tabId: String) = "$profile/$tabId"
    private fun sessionSettings(profile: String): GeckoSessionSettings {
        val desktop = profile in desktopProfiles
        val context = profileContexts[profile] ?: profile.also { profileContexts[profile] = it }
        return GeckoSessionSettings.Builder().contextId(context).usePrivateMode(profile in privateProfiles)
            .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                           else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                          else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
    }
    fun isDesktopMode(profile: String = activeProfile) = profile in desktopProfiles
    fun setDesktopMode(desktop: Boolean, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        if (desktop) desktopProfiles.add(profile) else desktopProfiles.remove(profile)
        val ua = if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                 else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        val viewport = if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                       else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        (sessions.filterKeys { it == profile }.values +
            tabSessions.filterKeys { it.startsWith("$profile/") }.values).forEach {
            it.settings.setUserAgentMode(ua)
            it.settings.setViewportMode(viewport)
        }
        // Avoid reloading every open tab in the profile, which could lose forms.
        if (profile == activeProfile) selectedLoadedSession(profile)?.reload()
        persist()
        notifyUi()
        return JSONObject().put("profile", profile).put("desktopMode", desktop)
    }
    fun findInPageAsync(query: String?, backwards: Boolean = false,
        profile: String = activeProfile): CompletableFuture<JSONObject> {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(query == null || (query.isNotBlank() && query.length <= 200)) {
            "Search text must be 1-200 characters"
        }
        val result = CompletableFuture<JSONObject>()
        val session = currentSession(profile)
        session.finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS
                    else GeckoSession.FINDER_FIND_FORWARD
        session.finder.find(query, flags).accept({ match ->
            if (match == null) {
                result.completeExceptionally(IllegalStateException("Find-in-page returned no result"))
            } else {
                result.complete(JSONObject().put("found", match.found)
                    .put("current", match.current).put("total", match.total)
                    .put("wrapped", match.wrapped)
                    .put("searchString", match.searchString))
            }
        }, { failure -> result.completeExceptionally(failure) })
        return result
    }
    fun clearFind(profile: String = activeProfile) {
        assertMain()
        selectedLoadedSession(profile)?.finder?.clear()
    }
    private fun selectedTab(profile: String = activeProfile) = activeTabs[profile] ?: "main"
    private fun currentSession(profile: String = activeProfile): GeckoSession =
        popupSessions[profile] ?: selectedTab(profile).takeIf { it != "main" }
            ?.let { openExtraTab(profile, it) } ?: openProfile(profile)
    // Status inspection must never lazily create a session or load a website.
    private fun selectedLoadedSession(profile: String = activeProfile): GeckoSession? =
        popupSessions[profile] ?: if (selectedTab(profile) == "main") sessions[profile]
            else tabSessions[tabKey(profile, selectedTab(profile))]
    private val bridgeRequests = BridgeRequests<GeckoSession, WebExtension.Port, JSONObject>()
    private val preparingSessions = mutableMapOf<GeckoSession, Int>()
    private val activeWorkSessions = mutableMapOf<GeckoSession, Runnable>()
    private val workLease = BrowserWorkLease(app)
    internal val downloads = BrowserDownloads(app).apply { onChanged = { notifyUi() } }
    private val downloadPins = mutableMapOf<GeckoSession, Int>()
    private fun hasSessionWork(session: GeckoSession): Boolean =
        pendingInteractions.values.any { it.session === session } || promptBroker.hasSession(session) || hasPendingNavigation(session) || bridgeRequests.hasSession(session) ||
            (downloadPins[session] ?: 0) > 0 || (preparingSessions[session] ?: 0) > 0 ||
            (captureSession === session && captureInFlight?.isDone == false)
    // UI-thread-owned bookkeeping: do not evict a GeckoSession that is serving an Agent request.
    private val inFlightByProfile = mutableMapOf<String, Int>()
    private data class PendingNavigation(
        val expectedUrl: String,
        val session: GeckoSession,
        val future: CompletableFuture<JSONObject>,
        val readiness: NavigationReadiness,
        val joinedInitialLoad: Boolean
    )
    private val navigationWaiters = mutableMapOf<String, PendingNavigation>()
    private fun hasPendingNavigation(session: GeckoSession): Boolean =
        navigationWaiters.values.any { it.session === session && !it.future.isDone }

    private fun rendered(session: GeckoSession) = view?.session === session ||
        backgroundHost?.session === session || captureAuxHost?.session === session
    private fun activateForWork(session: GeckoSession) {
        if (rendered(session)) return
        session.setActive(true)
        activeWorkSessions.remove(session)?.let { ui.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (hasSessionWork(session)) { ui.postDelayed(this, 1000L); return }
                activeWorkSessions.remove(session)
                if (!rendered(session)) session.setActive(false)
            }
        }
        activeWorkSessions[session] = task
        ui.postDelayed(task, 5000L)
    }
    private fun observeRequestedLocation(session: GeckoSession, url: String) {
        navigationWaiters.values.firstOrNull { it.session === session }?.readiness?.observe(url)
    }
    private fun observeLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest) {
        if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) return
        val readiness = navigationWaiters.values.firstOrNull { it.session === session }?.readiness ?: return
        if (request.isRedirect) readiness.observeRedirect(request.uri) else readiness.observe(request.uri)
    }
    private fun finishNavigationDocument(session: GeckoSession, json: JSONObject) {
        if (!bridgeReady(session)) return
        val entry = navigationWaiters.entries.firstOrNull { it.value.session === session } ?: return
        val waiter = entry.value
        val document = identity(session).documentId ?: return
        if (json.optString("documentId") != document) return
        val url = json.optString("url")
        if (!waiter.readiness.accepts(document, url, json.optString("readyState"))) return
        navigationWaiters.remove(entry.key)
        waiter.future.complete(JSONObject().put("profile", entry.key).put("url", url)
            .put("title", status(entry.key).optString("title"))
            .put("documentId", document).put("navigationCompleted", true).put("loadSucceeded", true)
            .put("joinedInitialLoad", waiter.joinedInitialLoad)
            .put("loadSignal", if (document == waiter.readiness.previousDocument) "same_document_ready_state_complete"
                else "fresh_document_ready_state_complete").put("httpStatusAvailable", false).put("networkObservationAvailable", true))
    }
    private fun navigationLoadError(session: GeckoSession, uri: String) {
        val entry = navigationWaiters.entries.firstOrNull {
            it.value.session === session && it.value.readiness.matchesTarget(uri)
        } ?: return
        navigationWaiters.remove(entry.key)
        entry.value.future.completeExceptionally(BrowserOperationException("NAVIGATION_FAILED", "Requested document failed to load"))
    }

    private fun failSessionRequests(session: GeckoSession, reason: String) {
        assertMain()
        startupNavigations.remove(session); startingSessions.remove(session); initialLoads.remove(session)
        val error = IllegalStateException(reason)
        activeWorkSessions.remove(session)?.let { ui.removeCallbacks(it) }
        bridgeRequests.failSession(session, error)
        discardFramePorts(session)
        documents.remove(session)
        bridgeVersions.remove(session)
        if (backgroundHost?.session === session) {
            backgroundHost?.close(); backgroundHost = null; surfaceGeneration++
        }
        promptBroker.cancel(session)
        sitePromptHost?.dismissPromptsFor(session)
        if (captureSession === session) captureInFlight?.completeExceptionally(error)
        navigationWaiters.entries.filter { it.value.session === session }.map { it.key }
            .forEach { profile ->
                navigationWaiters.remove(profile)?.future?.completeExceptionally(error)
            }
    }
    private val controllerCreatedAt = SystemClock.elapsedRealtime()
    private val profileUrls = linkedMapOf<String, String>()
    // A deleted profile ID cannot be reused until Gecko confirms its old
    // contextId's cookie/storage clear. Persist across process crashes.
    private val retiringProfiles = mutableSetOf<String>()
    private var activeProfile = "G001"
    private val pageLoading = mutableMapOf<String, Boolean>()
    private val tabLoading = mutableMapOf<String, Boolean>()
    // Weak keys avoid retaining evicted GeckoSession instances solely for nav UI state.
    private val canGoBackMap = java.util.WeakHashMap<GeckoSession, Boolean>()
    private val canGoForwardMap = java.util.WeakHashMap<GeckoSession, Boolean>()
    fun canGoBack(): Boolean =
        selectedLoadedSession()?.let { canGoBackMap[it] } ?: false
    fun canGoForward(): Boolean =
        selectedLoadedSession()?.let { canGoForwardMap[it] } ?: false
    private val pageTitles = mutableMapOf<String, String>()
    private data class CachedShot(val jpeg: ByteArray, val capturedAt: Long,
        val width: Int, val height: Int, val url: String, val tab: String, val documentId: String?)
    // Bounded JPEG cache: at most 3 images and 4 MiB across all profiles.
    private val lastShots = LinkedHashMap<String, CachedShot>(4, .75f, true)
    // A screenshot belongs to a profile's selected tab, not to the cookie container.
    private val screenshotGeneration = mutableMapOf<String, Long>()
    private fun invalidateScreenshot(profile: String) {
        lastShots.remove(profile)
        screenshotGeneration[profile] = (screenshotGeneration[profile] ?: 0L) + 1L
    }
    private var lastScreenshotError: String? = null
    private var lastScreenshotAt: Long = 0L
    private var view: GeckoView? = null
    private var surfaceGeneration = 0L
    private var captureInFlight: CompletableFuture<ByteArray>? = null
    private var captureSession: GeckoSession? = null
    private var captureAuxHost: OffscreenBrowserDisplay? = null
    private val imageEncoder = Executors.newSingleThreadExecutor()
    private var backgroundWidth = app.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    private var backgroundHeight = app.resources.displayMetrics.heightPixels.coerceAtLeast(1)
    private var backgroundHost: OffscreenBrowserDisplay? = null
    private var backgroundSurfaceError: String? = null
    private var backgroundSurfaceStartedElapsedMs = 0L
    private var lastBackgroundRecoveryElapsedMs = 0L
    private var backgroundRecoveryAttempts = 0
    private var lastBlockedSiteInteraction: JSONObject? = null
    private fun blockedSiteInteraction(session: GeckoSession, kind: String) {
        lastBlockedSiteInteraction = JSONObject().put("profile", profileOf(session) ?: JSONObject.NULL)
            .put("kind", kind).put("atMs", System.currentTimeMillis())
            .put("reason", "Source page not visible or prompt UI unavailable; request was dismissed")
        notifyUi()
    }

    private fun backgroundRenderPhase(): String {
        val host = backgroundHost
        if (host != null) {
            if (!host.attached) return "surface_detached"
            if (host.framesReceived == 0L) return if (host.successfulPixelCaptures > 0L)
                "pixels_captured" else "attached_no_frames_yet"
            val age = SystemClock.elapsedRealtime() - host.lastFrameElapsedMs
            return if (age <= 15000L) "frames_observed" else "no_recent_frames"
        }
        if (pendingBackgroundAttach != null) return "pending"
        if (view != null) return "foreground"
        if (backgroundSurfaceError != null) return "error"
        return if (backgroundRenderingEnabled()) "no_surface" else "disabled"
    }

    fun backgroundRenderingEnabled(): Boolean = app.getSharedPreferences("abwr_settings", 0)
        .getBoolean("background_rendering_enabled_v2", true)
    fun setBackgroundRendering(enabled: Boolean) {
        assertMain()
        app.getSharedPreferences("abwr_settings", 0).edit()
            .putBoolean("background_rendering_enabled_v2", enabled).apply()
        if (!enabled) {
            pendingBackgroundAttach?.let { ui.removeCallbacks(it) }; pendingBackgroundAttach = null
            backgroundHost?.close(); backgroundHost = null; surfaceGeneration++
        } else enterBackgroundSurface()
        notifyUi()
    }

    /**
     * Explicit, rate-limited best-effort recovery for the app's OWN offscreen
     * display. Never starts an Activity, captures other apps, or promises that
     * Android/OEM will keep the process alive.
     */
    fun recoverBackgroundSurface(): JSONObject {
        assertMain()
        require(backgroundRenderingEnabled()) {
            "Enable background rendering in Abwr settings first"
        }
        require(view == null || backgroundHost != null) {
            "App is foreground; background recovery is not needed"
        }
        val now = SystemClock.elapsedRealtime()
        require(now - lastBackgroundRecoveryElapsedMs >= 20000L ||
            lastBackgroundRecoveryElapsedMs == 0L) {
            "Recovery is cooling down; retry in 20 seconds"
        }
        lastBackgroundRecoveryElapsedMs = now
        backgroundRecoveryAttempts++
        pendingBackgroundAttach?.let { ui.removeCallbacks(it) }
        pendingBackgroundAttach = null
        if (backgroundHost != null) {
            surfaceGeneration++
            backgroundHost?.close()
            backgroundHost = null
        }
        backgroundSurfaceError = null
        backgroundSurfaceStartedElapsedMs = 0L
        enterBackgroundSurface()
        return JSONObject().put("retryScheduled", pendingBackgroundAttach != null)
            .put("recoveryAttempts", backgroundRecoveryAttempts)
            .put("note", "Best effort only; verify fresh frames and real screenshot")
    }
    private var pendingBackgroundAttach: Runnable? = null
    private val manualProfiles = mutableSetOf<String>()
    val manualMode: Boolean get() = activeProfile in manualProfiles
    @Volatile var engineReady = false
        private set
    @Volatile var extensionError: String? = null
        private set
    var onStateChanged: (() -> Unit)? = null

    init {
        closedMainProfiles.addAll(store.getStringSet("closed_main_profiles_v1", emptySet()) ?: emptySet())
        retiringProfiles.addAll(
            store.getStringSet("retiring_profiles_v1", emptySet())
                ?.filter { validProfile(it) } ?: emptyList())
        val saved = store.getString("profiles", null)
        if (saved != null) runCatching {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                if (validProfile(id) && id !in retiringProfiles) {
                    profileUrls[id] = obj.optString("url", "https://example.org/")
                    if (!obj.isNull("title")) pageTitles[id] = obj.optString("title").take(250)
                }
            }
        }
        if (profileUrls.isEmpty()) {
            val first = (1..300).map { "G%03d".format(it) }.first { it !in retiringProfiles }
            profileUrls[first] = "https://example.org/"
        }
        runCatching {
            val savedContexts = JSONObject(store.getString("profile_contexts_v1", "{}") ?: "{}")
            profileUrls.keys.forEach { id ->
                profileContexts[id] = savedContexts.optString(id).takeIf { it.isNotBlank() } ?: id
            }
        }.onFailure { profileUrls.keys.forEach { profileContexts[it] = it } }
        activeProfile = store.getString("active", "G001")!!.takeIf { profileUrls.containsKey(it) } ?: profileUrls.keys.first()
        manualProfiles.addAll(store.getStringSet("manual_profiles_v1", emptySet())
            ?.filter { it in profileUrls } ?: emptyList())
        desktopProfiles.clear()
        desktopProfiles.addAll(store.getStringSet("desktop_profiles_v1", emptySet())
            ?.filter { it in profileUrls } ?: emptyList())
        runCatching {
            val savedCollections = JSONObject(store.getString("collections_v1", "{}") ?: "{}")
            for (profile in profileUrls.keys) {
                val group = savedCollections.optJSONObject(profile) ?: continue
                fun copyStrings(source: JSONArray?, cap: Int): MutableList<String> {
                    val result = mutableListOf<String>()
                    if (source != null) for (i in 0 until minOf(source.length(), cap)) {
                        val url = source.optString(i)
                        if (runCatching { requireWebUrl(url) }.isSuccess) result.add(url)
                    }
                    return result
                }
                bookmarkUrls[profile] = LinkedHashSet(copyStrings(group.optJSONArray("bookmarks"), 250))
                historyUrls[profile] = copyStrings(group.optJSONArray("history"), 150)
            }
        }
        runCatching {
            val savedTabs = JSONObject(store.getString("tabs_v1", "{}") ?: "{}")
            for (profile in profileUrls.keys) {
                val entries = savedTabs.optJSONObject(profile) ?: continue
                val urls = entries.optJSONArray("items") ?: continue
                val tabs = linkedMapOf<String, String>()
                for (i in 0 until urls.length()) {
                    val t = urls.getJSONObject(i)
                    val id = t.optString("id")
                    val url = t.optString("url")
                    if (id.matches(Regex("T[0-9]+")) && tabs.size < 120 &&
                            runCatching { requireWebUrl(url) }.isSuccess) {
                        tabs[id] = url
                        if (!t.isNull("title")) tabTitles[tabKey(profile, id)] = t.optString("title").take(250)
                        nextTabId = maxOf(nextTabId, id.drop(1).toLongOrNull()?.plus(1) ?: 1)
                    }
                }
                if (tabs.isNotEmpty()) tabUrls[profile] = LinkedHashMap(tabs)
                val selected = entries.optString("selected", "main")
                if (selected in tabs) activeTabs[profile] = selected
            }
        }
        runCatching {
            val savedGroups = JSONArray(store.getString("tab_groups_v1", "[]") ?: "[]")
            for (i in 0 until minOf(savedGroups.length(), 18000)) runCatching {
                val group = savedGroups.getJSONObject(i)
                val profile = group.getString("profile")
                require(profile in profileUrls)
                val members = group.optJSONArray("tabs") ?: JSONArray()
                tabGroups.restore(TabGroups.Group(group.getString("id"), profile, group.getString("name"),
                    group.getInt("color"), LinkedHashSet((0 until members.length()).map { members.getString(it) })),
                    tabIds(profile))
            }
        }
        closedMainProfiles.retainAll(profileUrls.keys)
        closedMainProfiles.toList().forEach { profile ->
            if (tabUrls[profile].isNullOrEmpty()) closedMainProfiles.remove(profile)
            else if (selectedTab(profile) == "main") activeTabs[profile] = tabUrls[profile]!!.keys.first()
        }
    }

    private fun validProfile(id: String) = id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,30}"))
    private fun notifyUi() {
        onStateChanged?.invoke()
        val current = selectedLoadedSession() ?: return
        val document = identity(current).documentId ?: return
        val key = tabKey(activeProfile, if (popupSessions[activeProfile] === current) "popup" else selectedTab())
        if (view == null || !bridgeReady(current) || previewDocuments[key] == document || previewTask != null) return
        val task = Runnable {
            previewTask = null
            if (view != null && selectedLoadedSession() === current && bridgeReady(current) && captureInFlight == null) {
                requestTabPreview()
            }
        }
        previewTask = task
        ui.postDelayed(task, 900L)
    }
    internal fun requestTabPreview(done: () -> Unit = {}) {
        if (captureInFlight?.isDone == false || view?.isAttachedToWindow != true) { done(); return }
        captureAsync().whenComplete { _, _ -> ui.post { done() } }
    }
    private fun tabIds(profile: String): Set<String> = buildSet {
        if (profile !in closedMainProfiles) add("main")
        addAll(tabUrls[profile]?.keys.orEmpty())
    }
    internal fun createTabGroup(name: String, color: Int, members: Collection<String>, profile: String = activeProfile): TabGroups.Group {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        val group = tabGroups.create(profile, name, color, members, tabIds(profile))
        persist(); notifyUi(); return group
    }
    internal fun renameTabGroup(id: String, name: String, color: Int, profile: String = activeProfile) {
        assertMain(); tabGroups.rename(profile, id, name, color); persist(); notifyUi()
    }
    internal fun moveTabsToGroup(members: Collection<String>, group: String?, profile: String = activeProfile) {
        assertMain(); tabGroups.move(profile, members, group, tabIds(profile)); persist(); notifyUi()
    }
    internal fun ungroupTabs(id: String, profile: String = activeProfile) {
        assertMain(); tabGroups.ungroup(profile, id); persist(); notifyUi()
    }
    private fun groupsJson(profile: String? = null) = JSONArray().apply {
        (profile?.let { listOf(it) } ?: profileUrls.keys.toList()).forEach { owner ->
            tabGroups.all(owner).forEach { group -> put(JSONObject().put("id", group.id)
                .put("profile", owner).put("name", group.name).put("color", group.color)
                .put("tabs", JSONArray(group.tabs.toList()))) }
        }
    }
    fun tabGroupsInfo(profile: String = activeProfile): JSONObject {
        assertMain(); require(profile in profileUrls) { "Unknown profile" }
        return JSONObject().put("profile", profile).put("groups", groupsJson(profile))
    }
    fun editTabGroups(action: String, input: JSONObject): JSONObject {
        assertMain()
        val allowed = setOf("profile", "tab", "expectedUrl", "documentId") + when (action) {
            "create" -> setOf("name", "color", "tabs")
            "update" -> setOf("id", "name", "color")
            "move" -> setOf("id", "tabs")
            "ungroup" -> setOf("id")
            else -> emptySet()
        }
        input.keys().forEach { key ->
            val value = input.get(key)
            val valid = key in allowed && when (key) {
                "tabs" -> value is JSONArray
                "color" -> value is Number && value.toDouble() in 0.0..5.0 && value.toDouble() == value.toInt().toDouble()
                "id" -> value is String || (action == "move" && value === JSONObject.NULL)
                else -> value is String
            }
            if (!valid) throw BrowserOperationException("BAD_ARGUMENTS", "Invalid group argument: $key")
        }
        val profile = profileForRequest(input)
        if (profile in manualProfiles) throw BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active")
        if (action in setOf("update", "ungroup")) {
            require(input.has("id")) { "id is required" }
            tabGroups.group(profile, input.getString("id"))
        }
        val rawMembers = if (input.has("tabs")) input.getJSONArray("tabs") else JSONArray()
        require(rawMembers.length() <= 121) { "Too many tab IDs" }
        val members = (0 until rawMembers.length()).map { rawMembers.getString(it) }
        var created: String? = null
        when (action) {
            "create" -> created = createTabGroup(input.getString("name"), input.optInt("color", 0), members, profile).id
            "update" -> renameTabGroup(input.getString("id"), input.getString("name"), input.getInt("color"), profile)
            "move" -> {
                require(input.has("tabs")) { "tabs is required" }
                moveTabsToGroup(members, if (input.isNull("id")) null else input.getString("id"), profile)
            }
            "ungroup" -> ungroupTabs(input.getString("id"), profile)
            else -> throw IllegalArgumentException("Unknown group action")
        }
        return tabGroupsInfo(profile).put("createdGroupId", created ?: JSONObject.NULL)
    }
    private fun assertMain() = check(Looper.myLooper() == Looper.getMainLooper())
    /**
     * Mozilla native anti-fingerprinting setting is GeckoRuntime-wide.
     * Do not misrepresent it as a unique per-profile browser fingerprint.
     */
    fun fingerprintProtectionEnabled(): Boolean =
        app.getSharedPreferences("abwr_settings", 0).getBoolean("fingerprint_protection", false)

    fun setFingerprintProtection(enabled: Boolean): JSONObject {
        assertMain()
        app.getSharedPreferences("abwr_settings", 0).edit()
            .putBoolean("fingerprint_protection", enabled).apply()
        runtime?.settings?.setFingerprintingProtection(enabled)
        notifyUi()
        return privacyReport()
    }

    fun privacyReport(profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        return JSONObject()
            .put("profile", profile)
            .put("storageIsolation", "GeckoView contextId")
            .put("cookieAndLocalStorageIsolated", true)
            .put("desktopMode", isDesktopMode(profile))
            .put("fingerprintingProtectionEnabled", fingerprintProtectionEnabled())
            .put("fingerprintingProtectionScope", "shared_gecko_runtime")
            .put("independentPerProfileWebFingerprint", false)
            .put("fingerprintRotation", false)
            .put("globalPrivacyControl", true)
            .put("note", "Containers isolate site storage, not device/browser fingerprint; protection is runtime-wide.")
    }

    fun trackingProtectionEnabled(): Boolean =
        app.getSharedPreferences("abwr_settings", 0).getBoolean("tracking_protection", true)

    fun setTrackingProtection(enabled: Boolean) {
        assertMain()
        app.getSharedPreferences("abwr_settings", 0).edit()
            .putBoolean("tracking_protection", enabled).apply()
        runtime?.settings?.contentBlocking?.setEnhancedTrackingProtectionLevel(
            if (enabled) ContentBlocking.EtpLevel.DEFAULT else ContentBlocking.EtpLevel.NONE)
        notifyUi()
    }

    private fun requireWebUrl(raw: String): String {
        require(raw.length in 1..4096) { "URL length out of range" }
        val value = URI(raw)
        require(value.scheme?.lowercase() in setOf("https", "http") && !value.host.isNullOrBlank()) { "Only absolute HTTP(S) URLs supported" }
        require(value.userInfo == null) { "Credentials in URL not allowed" }
        return raw
    }
    private fun recordVisit(profile: String, url: String) {
        if (profile in privateProfiles) return
        if (runCatching { requireWebUrl(url) }.isFailure) return
        val history = historyUrls.getOrPut(profile) { mutableListOf() }
        if (history.lastOrNull() == url) return
        history.remove(url)
        history.add(url)
        if (history.size > 150) history.removeAt(0)
        persist()
    }
    fun removeHistory(url: String, profile: String = activeProfile) {
        assertMain(); require(profile in profileUrls) { "Unknown profile" }
        historyUrls[profile]?.removeAll { it == url }; persist(); notifyUi()
    }
    fun clearHistory(profile: String = activeProfile) {
        assertMain(); require(profile in profileUrls) { "Unknown profile" }
        historyUrls.remove(profile); recentlyClosed.clear(profile)
        (sessions.filterKeys { it == profile }.values + tabSessions.filterKeys { it.startsWith("$profile/") }.values)
            .forEach { it.purgeHistory() }
        persist(); notifyUi()
    }
    fun isPrivate(profile: String = activeProfile) = profile in privateProfiles
    fun createPrivateProfile(): JSONObject {
        assertMain()
        val id = "Private_" + UUID.randomUUID().toString().take(8)
        privateProfiles.add(id)
        try { createProfile(id) } catch (error: Throwable) { privateProfiles.remove(id); throw error }
        switchProfile(id)
        return status(id)
    }
    fun closePrivateProfiles() {
        assertMain()
        val targets = privateProfiles.toList()
        require(targets.all { (inFlightByProfile[it] ?: 0) == 0 && downloads.activeCount(it) == 0 && http.activeCount(it) == 0 }) {
            "無痕容器仍有操作或下載，請完成後再關閉"
        }
        targets.forEach { manualProfiles.remove(it); removeProfile(it) }
    }
    fun reopenClosedTab(profile: String = activeProfile, fromUser: Boolean = false): JSONObject {
        assertMain()
        val entry = recentlyClosed.peek(profile) ?: throw IllegalArgumentException("目前沒有可恢復的分頁")
        val result = createTab(entry.url, profile, fromUser)
        recentlyClosed.remove(entry)
        return result
    }
    fun closedTabCount(profile: String = activeProfile) = recentlyClosed.list(profile).size
    fun exportBookmarks(profile: String = activeProfile): String = BrowserBookmarks.export(
        bookmarkUrls[profile].orEmpty().map { BrowserBookmarks.Entry(it, it) })
    fun importBookmarks(html: String, profile: String = activeProfile): Int {
        assertMain(); require(profile in profileUrls) { "Unknown profile" }
        val imported = BrowserBookmarks.parse(html)
        require(imported.isNotEmpty()) { "檔案沒有可匯入的 HTTP(S) 書籤" }
        val items = bookmarkUrls.getOrPut(profile) { linkedSetOf() }
        val incoming = imported.map { it.url }.filter { it !in items }
        require(items.size + incoming.size <= 250) { "書籤總數超過 250 筆，請先整理現有書籤" }
        items.addAll(incoming); persist(); notifyUi(); return incoming.size
    }
    fun setTextScale(percent: Int) {
        assertMain(); require(percent in 50..200) { "文字比例必須介於 50–200%" }
        app.getSharedPreferences("abwr_settings", 0).edit().putInt("text_scale", percent).apply()
        runtime?.settings?.setAutomaticFontSizeAdjustment(false)
        runtime?.settings?.setFontSizeFactor(percent / 100f)
        humanReload()
    }
    fun setForceZoom(enabled: Boolean) {
        assertMain()
        app.getSharedPreferences("abwr_settings", 0).edit().putBoolean("force_zoom", enabled).apply()
        runtime?.settings?.setForceUserScalableEnabled(enabled)
    }
    fun humanStop() {
        assertMain(); val session = currentSession()
        startupNavigations.remove(session); initialLoads.remove(session); startingSessions.remove(session)
        val page = identity(session)
        val priorUrl = page.url.takeIf { page.navigating && page.documentId != null }
        session.stop(); page.navigationFailed()
        if (priorUrl != null) commitDocumentLocation(session, priorUrl)
        navigationWaiters.entries.filter { it.value.session === session }.map { it.key }.forEach {
            navigationWaiters.remove(it)?.future?.completeExceptionally(BrowserOperationException("NAVIGATION_CANCELLED", "載入已由使用者停止"))
        }
        pageLoading[activeProfile] = false; tabLoading[tabKey(activeProfile, selectedTab())] = false; notifyUi()
    }
    fun exitFullscreen() { assertMain(); selectedLoadedSession()?.exitFullScreen() }
    fun savePdfAsync(): CompletableFuture<java.io.InputStream> {
        assertMain()
        val result = CompletableFuture<java.io.InputStream>()
        currentSession().saveAsPdf().accept({ stream ->
            if (stream == null) result.completeExceptionally(IllegalStateException("網頁無法產生 PDF")) else result.complete(stream)
        }, { result.completeExceptionally(it) })
        return result
    }
    fun siteInfo(): JSONObject {
        assertMain()
        val state = status()
        return JSONObject().put("url", state.optString("url")).put("title", state.optString("title"))
            .put("profile", activeProfile).put("private", isPrivate())
            .put("security", selectedLoadedSession()?.let { securityInfo[it] } ?: JSONObject.NULL)
    }
    fun sitePermissionList() = selectedLoadedSession()?.let { currentPermissions[it] }.orEmpty()
    fun sitePermissionsAsync(): CompletableFuture<List<GeckoSession.PermissionDelegate.ContentPermission>> {
        assertMain()
        val result = CompletableFuture<List<GeckoSession.PermissionDelegate.ContentPermission>>()
        val session = selectedLoadedSession() ?: return CompletableFuture.failedFuture(IllegalStateException("頁面尚未就緒"))
        val profile = activeProfile; val url = status().optString("url")
        runtime!!.storageController.getPermissions(url, profileContexts[profile], isPrivate(profile)).accept({ permissions ->
            if (selectedLoadedSession() !== session || status().optString("url") != url) result.completeExceptionally(IllegalStateException("網站已改變"))
            else { val list = permissions.orEmpty(); currentPermissions[session] = list; result.complete(list) }
        }, { result.completeExceptionally(it) })
        return result
    }
    fun changeSitePermission(permission: GeckoSession.PermissionDelegate.ContentPermission, value: Int) {
        assertMain()
        require(sitePermissionList().any { it === permission }) { "網站已改變，請重新開啟權限頁" }
        require(value in setOf(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW,
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY, GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT))
        runtime?.storageController?.setPermission(permission, value)
        notifyUi()
    }
    fun bookmarks(profile: String = activeProfile): JSONArray =
        JSONArray(bookmarkUrls[profile]?.toList() ?: emptyList<String>())
    fun history(profile: String = activeProfile): JSONArray =
        JSONArray(historyUrls[profile]?.asReversed() ?: emptyList<String>())
    fun bookmark(url: String, add: Boolean = true, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        requireWebUrl(url)
        val items = bookmarkUrls.getOrPut(profile) { linkedSetOf() }
        if (add) {
            require(items.size < 250 || url in items) { "Bookmark capacity reached" }
            items.add(url)
        } else items.remove(url)
        persist()
        return JSONObject().put("saved", url in items).put("url", url).put("profile", profile)
    }
    private fun persist(sync: Boolean = false): Boolean {
        val arr = JSONArray()
        profileUrls.filterKeys { it !in privateProfiles }.forEach { (id, url) -> arr.put(JSONObject().put("id", id).put("url", url)
            .put("title", pageTitles[id]?.take(250) ?: JSONObject.NULL)) }
        val tabs = JSONObject()
        tabUrls.filterKeys { it !in privateProfiles }.forEach { (profile, urls) ->
            val items = JSONArray()
            urls.forEach { (id, url) -> items.put(JSONObject().put("id", id).put("url", url)
                .put("title", tabTitles[tabKey(profile, id)]?.take(250) ?: JSONObject.NULL)) }
            tabs.put(profile, JSONObject().put("selected", selectedTab(profile)).put("items", items))
        }
        val collections = JSONObject()
        profileUrls.keys.filter { it !in privateProfiles }.forEach { profile ->
            collections.put(profile, JSONObject()
                .put("bookmarks", JSONArray(bookmarkUrls[profile]?.toList() ?: emptyList<String>()))
                .put("history", JSONArray(historyUrls[profile]?.toList() ?: emptyList<String>())))
        }
        val persistentActive = activeProfile.takeIf { it !in privateProfiles } ?: profileUrls.keys.first { it !in privateProfiles }
        val edit = store.edit().putString("profiles", arr.toString()).putString("active", persistentActive)
            .putString("profile_contexts_v1", JSONObject(profileContexts.filterKeys { it !in privateProfiles } as Map<*, *>).toString())
            .putString("tabs_v1", tabs.toString())
            .putString("tab_groups_v1", JSONArray().apply {
                val groups = groupsJson()
                for (i in 0 until groups.length()) if (groups.getJSONObject(i).optString("profile") !in privateProfiles) put(groups.getJSONObject(i))
            }.toString())
            .putLong("tab_group_next_id_v1", tabGroups.nextSequence)
            .putStringSet("closed_main_profiles_v1", closedMainProfiles.filter { it !in privateProfiles }.toSet())
            .putString("collections_v1", collections.toString())
            .putStringSet("desktop_profiles_v1", desktopProfiles.filter { it !in privateProfiles }.toSet())
            .putStringSet("retiring_profiles_v1", retiringProfiles.toSet())
        return if (sync) edit.commit() else { edit.apply(); true }
    }

    /** Called on main thread. The runtime is process-scoped, not Activity-scoped. */
    fun initialize() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (runtime != null) return
        try {
            val etp = if (app.getSharedPreferences("abwr_settings", 0)
                    .getBoolean("tracking_protection", true))
                ContentBlocking.EtpLevel.DEFAULT else ContentBlocking.EtpLevel.NONE
            val blocking = ContentBlocking.Settings.Builder()
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .enhancedTrackingProtectionLevel(etp)
                .queryParameterStrippingEnabled(true)
                .build()
            runtime = GeckoRuntime.create(app, GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true).contentBlocking(blocking)
                .consoleOutput(BuildConfig.DEBUG && android.os.Build.HARDWARE in setOf("ranchu", "goldfish")).build())
            http.initialize(runtime!!)
            extensions.proxyPolicies = proxyPolicies()
            extensions.initialize(runtime!!)
            extensions.syncScripts(userScripts.enabledJson())
            runtime!!.settings.setGlobalPrivacyControl(true)
            runtime!!.settings.setFingerprintingProtection(fingerprintProtectionEnabled())
            runtime!!.settings.setAutomaticFontSizeAdjustment(false)
            runtime!!.settings.setFontSizeFactor(app.getSharedPreferences("abwr_settings", 0).getInt("text_scale", 100).coerceIn(50, 200) / 100f)
            runtime!!.settings.setForceUserScalableEnabled(app.getSharedPreferences("abwr_settings", 0).getBoolean("force_zoom", false))
            // Migrate v0.11's permanent public-name quarantine. New profiles use
            // opaque context IDs, so a reused display name cannot recover the
            // retired context's cookies even if Gecko is still clearing it.
            retiringProfiles.toList().forEach { clearContext(it) }
            if (retiringProfiles.isNotEmpty()) {
                retiringProfiles.clear()
                store.edit().remove("retiring_profiles_v1").apply()
            }
            runtime!!.webExtensionController.ensureBuiltIn(
                "resource://android/assets/agentbridge/", "agentbridge@local.browser"
            ).accept({ ext ->
                fun ready(value: WebExtension) {
                    extensions.proxyPolicies = proxyPolicies()
                    extension = value; extensions.attachBuiltin(value)
                    val deadline = SystemClock.elapsedRealtime() + 5000L
                    val bootstrap = object : Runnable {
                        override fun run() {
                            if (!extensions.proxyState.optBoolean("initialized")) {
                                if (SystemClock.elapsedRealtime() < deadline) { ui.postDelayed(this, 50L); return }
                                extensionError = "Network policy broker did not initialize; browser requests remain blocked"
                                notifyUi(); return
                            }
                            engineReady = true
                            initialLoads.toMap().forEach { (session, url) ->
                                if (session.isOpen) { installSessionDelegate(session); identity(session).beginNavigation(url); session.loadUri(url) }
                                initialLoads.remove(session)
                            }
                            (sessions.values + popupSessions.values + tabSessions.values).forEach { installSessionDelegate(it) }
                            notifyUi()
                        }
                    }
                    bootstrap.run()
                }
                if (ext == null) { extensionError = "內建擴充未就緒"; notifyUi() }
                else runtime!!.webExtensionController.setAllowedInPrivateBrowsing(ext, true).accept(
                    { allowed -> ready(allowed ?: ext) }, { error -> extensionError = error?.message ?: "無痕控制擴充啟用失敗"; notifyUi() })
            }, { err ->
                extensionError = err?.message ?: "Extension initialization failed"
                Log.e("AIBrowser", "Failed to install GeckoView extension", err)
                notifyUi()
            })
            openProfile(activeProfile)
        } catch (e: Exception) {
            extensionError = e.message ?: "Gecko runtime failed"
            Log.e("AIBrowser", "Runtime initialization failed", e)
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val sourceSession = port.sender.session ?: run {
                port.setDelegate(object : WebExtension.PortDelegate {})
                retireBridgePort(port); return
            }
            bridgeConnections.connected(port)
            if (!sourceSession.isOpen) retireBridgePort(port)
            bridgeLastErrors.remove(sourceSession)
            val topLevel = port.sender.isTopLevel
            var frameId: String? = null
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    if (!bridgeConnections.writable(port) || !sourceSession.isOpen) return
                    val json = message as? JSONObject ?: return
                    if (json.optBoolean("tabIdentity")) {
                        if (topLevel && ports[sourceSession] === port && identity(sourceSession).documentId == json.optString("documentId") && !identity(sourceSession).navigating) {
                            if (json.optInt("geckoTabId", -1) >= 0) browserTabIds[sourceSession] = json.getInt("geckoTabId")
                            val profile = profileOf(sourceSession)
                            val cookieStore = json.optString("cookieStoreId").takeIf { it.isNotBlank() && it != "null" }
                            if (profile != null && cookieStore != null && cookieStoreIdentities.optString(profile) != cookieStore) {
                                cookieStoreIdentities.put(profile, cookieStore)
                                if (!isPrivate(profile)) persistProxyIdentities()
                                extensions.proxyPolicies = proxyPolicies()
                                extensions.syncScripts(userScripts.enabledJson())
                            }
                        }
                        return
                    }
                    if (json.has("httpStreamId")) {
                        if (topLevel && ports[sourceSession] === port) http.receive(sourceSession, json)
                        return
                    }
                    if (json.optBoolean("ready")) {
                        val documentId = json.optString("documentId")
                        val url = json.optString("url")
                        if (documentId.isBlank()) { retireBridgePort(port); return }
                        if (topLevel) {
                            val previousDocument = identity(sourceSession).documentId
                            if (!identity(sourceSession).acceptDocument(documentId, url)) {
                                if (ports[sourceSession] !== port) retireBridgePort(port)
                                return
                            }
                            if (previousDocument != documentId) {
                                val epoch = navigationEpochs[sourceSession] ?: 0L
                                framePorts[sourceSession]?.let { frames ->
                                    frames.entries.filter { it.value.epoch < epoch }.map { it.key }.forEach { key ->
                                        frames.remove(key)?.let {
                                            bridgeRequests.failPort(it.port, BrowserOperationException("PAGE_CHANGED", "Frame document changed"))
                                            retireBridgePort(it.port)
                                        }
                                    }
                                }
                            }
                            commitDocumentLocation(sourceSession, url)
                            val previous = ports.put(sourceSession, port)
                            if (previous != null && previous !== port) {
                                bridgeRequests.failPort(previous, BrowserOperationException("PAGE_CHANGED", "Document bridge replaced"))
                                retireBridgePort(previous)
                            }
                        } else {
                            val frames = framePorts.getOrPut(sourceSession) { LinkedHashMap() }
                            if (frames.size >= 24 && !frames.containsKey(documentId)) { retireBridgePort(port); return }
                            frameId = documentId
                            frames.put(documentId, FrameBridge(port, documentId, url, navigationEpochs[sourceSession] ?: 0L))?.takeIf { it.port !== port }?.let {
                                bridgeRequests.failPort(it.port, BrowserOperationException("PAGE_CHANGED", "Frame bridge replaced"))
                                retireBridgePort(it.port)
                            }
                        }
                        if (topLevel) {
                            bridgeVersions[sourceSession] = json.optString("bridgeVersion", "legacy")
                        }
                        runCatching { postBridge(port, JSONObject().put("bridgeReady", true)) }
                        if (topLevel) finishNavigationDocument(sourceSession, json)
                        notifyUi(); return
                    }
                    if (json.optBoolean("pageState") && topLevel && ports[sourceSession] === port) {
                        if (identity(sourceSession).updateSameDocument(json.optString("documentId"), json.optString("url")))
                            commitDocumentLocation(sourceSession, json.optString("url"))
                        finishNavigationDocument(sourceSession, json)
                        notifyUi(); return
                    }
                    val id = json.optString("id", "")
                    if (topLevel && ports[sourceSession] === port && !identity(sourceSession).navigating) {
                        json.optJSONObject("result")?.let { data ->
                            if (data.optString("documentId") == identity(sourceSession).documentId && data.has("url")) {
                                identity(sourceSession).updateSameDocument(data.optString("documentId"), data.getString("url"))
                                commitDocumentLocation(sourceSession, data.getString("url"))
                            }
                        }
                    }
                    if (id.isNotBlank()) bridgeRequests.reply(id, sourceSession, port, json)
                }
                override fun onDisconnect(port: WebExtension.Port) {
                    bridgeConnections.closed(port)
                    if (ports[sourceSession] === port) { ports.remove(sourceSession); bridgeVersions.remove(sourceSession) }
                    frameId?.let { id ->
                        if (framePorts[sourceSession]?.get(id)?.port === port) framePorts[sourceSession]?.remove(id)
                        if (framePorts[sourceSession]?.isEmpty() == true) framePorts.remove(sourceSession)
                    }
                    bridgeRequests.failPort(port, BrowserOperationException("BRIDGE_DISCONNECTED", "Page bridge disconnected; wait for automatic reconnect, then refresh the snapshot"))
                    notifyUi()
                }
            })
            notifyUi()
        }
    }

    interface SitePromptHost {
        fun showSitePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BasePrompt,
            reply: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
        ): Boolean
        fun showContentPermission(
            session: GeckoSession,
            permission: GeckoSession.PermissionDelegate.ContentPermission,
            reply: GeckoResult<Int>
        ): Boolean
        fun showAndroidPermissions(
            session: GeckoSession,
            permissions: Array<String>,
            callback: GeckoSession.PermissionDelegate.Callback
        ): Boolean
        fun dismissResolvedPermissions(session: GeckoSession)
        fun dismissResolvedPrompt(prompt: GeckoSession.PromptDelegate.BasePrompt)
        fun dismissPromptsFor(session: GeckoSession)
        fun showDownload(session: GeckoSession, response: WebResponse): Boolean
        fun showContextMenu(session: GeckoSession, element: GeckoSession.ContentDelegate.ContextElement)
        fun showFullscreen(session: GeckoSession, enabled: Boolean)
        fun showExternalUri(session: GeckoSession, uri: String)
        fun showMediaPermission(session: GeckoSession, uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback): Boolean
    }
    var sitePromptHost: SitePromptHost? = null

    fun isVisibleSession(session: GeckoSession) = backgroundHost == null &&
        view?.isAttachedToWindow == true && view?.session === session && sitePromptHost != null

    private fun profileOf(session: GeckoSession): String? =
        popupSessions.entries.firstOrNull { it.value === session }?.key ?:
        sessions.entries.firstOrNull { it.value === session }?.key ?:
        tabSessions.entries.firstOrNull { it.value === session }?.key?.substringBefore('/')

    private fun externalResponse(session: GeckoSession, response: WebResponse) {
        // An attachment does not commit a replacement webpage. Restore the
        // existing document so its reconnecting port, URL and controls survive.
        val page = identity(session)
        if (page.navigating && page.documentId != null && page.url != null) {
            val priorUrl = requireNotNull(page.url)
            page.navigationFailed()
            commitDocumentLocation(session, priorUrl)
            profileOf(session)?.let { profile ->
                when {
                    sessions[profile] === session -> pageLoading[profile] = false
                    popupSessions[profile] === session -> popupLoading[profile] = false
                    else -> tabSessions.entries.firstOrNull { it.value === session }?.key?.let { tabLoading[it] = false }
                }
                historyUrls[profile]?.let { history ->
                    if (history.lastOrNull() == response.uri) history.removeAt(history.lastIndex)
                }
                navigationWaiters[profile]?.takeIf { it.session === session }?.let {
                    navigationWaiters.remove(profile)
                    it.future.completeExceptionally(BrowserOperationException("DOWNLOAD_RESPONSE",
                        "The URI returned an attachment, not a replacement webpage"))
                }
            }
            notifyUi()
        }
        val id = try { registerInteraction(session, "download", { input ->
            if (input.getString("decision") == "accept") JSONObject().put("downloadId", startDownload(session, response))
            else { discardPendingDownload(response); JSONObject().put("cancelled", true) }
        }, { discardPendingDownload(response) }, { if (isVisibleSession(session)) sitePromptHost?.showDownload(session, response) }) } catch (_: Exception) { downloads.discard(response); return }
        pendingDownloadIds[response] = id
        if (isVisibleSession(session)) sitePromptHost?.showDownload(session, response)
    }
    internal fun discardPendingDownload(response: WebResponse) {
        pendingDownloadIds.remove(response)?.let { pendingInteractions.remove(it) }
        downloads.discard(response)
    }
    internal fun startDownload(session: GeckoSession, response: WebResponse): String {
        assertMain()
        pendingDownloadIds.remove(response)?.let { pendingInteractions.remove(it) }
        val profile = profileOf(session) ?: error("原分頁已關閉，請重新下載")
        downloadPins[session] = (downloadPins[session] ?: 0) + 1
        try {
            return downloads.start(response, profile) {
                val count = (downloadPins[session] ?: 1) - 1
                if (count <= 0) downloadPins.remove(session) else downloadPins[session] = count
                pruneSessions()
                pruneTabSessions()
                notifyUi()
            }
        } catch (error: Exception) {
            val count = (downloadPins[session] ?: 1) - 1
            if (count <= 0) downloadPins.remove(session) else downloadPins[session] = count
            downloads.discard(response)
            throw error
        }
    }

    private val sitePrompts = object : GeckoSession.PromptDelegate {
        private fun dispatch(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BasePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val reply = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            if (!promptBroker.register(session, prompt, reply)) reply.complete(prompt.dismiss())
            else if (isVisibleSession(session)) sitePromptHost?.showSitePrompt(session, prompt, reply)
            return reply
        }
        override fun onAlertPrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt) = dispatch(session, prompt)
        override fun onButtonPrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt) = dispatch(session, prompt)
        override fun onTextPrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt) = dispatch(session, prompt)
        override fun onAuthPrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AuthPrompt) = dispatch(session, prompt)
        override fun onPopupPrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt) = dispatch(session, prompt)
        override fun onBeforeUnloadPrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt) = dispatch(session, prompt)
        override fun onRepostConfirmPrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt) = dispatch(session, prompt)
        override fun onFilePrompt(session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt) = dispatch(session, prompt)
    }

    private val sitePermissions = object : GeckoSession.PermissionDelegate {
        override fun onMediaPermissionRequest(session: GeckoSession, uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback) {
            val id = try { registerInteraction(session, "media_permission", { input ->
                val allow = input.getString("decision") == "accept"
                val videoSource = input.optString("videoId").takeIf { it.isNotBlank() }?.let { wanted -> video?.firstOrNull { it.id == wanted } ?: throw IllegalArgumentException("Unknown video source") } ?: video?.firstOrNull()
                val audioSource = input.optString("audioId").takeIf { it.isNotBlank() }?.let { wanted -> audio?.firstOrNull { it.id == wanted } ?: throw IllegalArgumentException("Unknown audio source") } ?: audio?.firstOrNull()
                val allowed = (videoSource == null || app.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
                    (audioSource == null || app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                if (allow && !allowed) throw BrowserOperationException("SYSTEM_PERMISSION_REQUIRED", "Android has not granted the required camera/microphone permission")
                finishMediaPermission(callback, videoSource, audioSource, allow, false)
                JSONObject().put("dispatched", true)
            }, { finishMediaPermission(callback, null, null, false, false) }, {
                if (isVisibleSession(session)) sitePromptHost?.showMediaPermission(session, uri, video, audio, callback)
            }) } catch (_: Exception) { callback.reject(); return }
            mediaInteractionIds[callback] = id
            pendingInteractions[id]?.scope?.put("videoSources", JSONArray(video.orEmpty().map { JSONObject().put("id", it.id).put("name", it.name) }))
                ?.put("audioSources", JSONArray(audio.orEmpty().map { JSONObject().put("id", it.id).put("name", it.name) }))
            if (isVisibleSession(session)) sitePromptHost?.showMediaPermission(session, uri, video, audio, callback)

        }
        override fun onContentPermissionRequest(session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
            val result = GeckoResult<Int>()
            val id = try { registerInteraction(session, "site_permission", { input ->
                result.complete(if (input.getString("decision") == "accept") GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                JSONObject().put("dispatched", true)
            }, { result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }, { if (isVisibleSession(session)) sitePromptHost?.showContentPermission(session, perm, result) }) } catch (_: Exception) {
                result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY); return result
            }
            pendingInteractions[id]?.scope?.put("permission", perm.permission)?.put("requestOrigin", perm.uri)?.put("thirdPartyOrigin", perm.thirdPartyOrigin ?: JSONObject.NULL)
            result.accept({ _ -> pendingInteractions.remove(id); sitePromptHost?.dismissResolvedPermissions(session) }, { _ -> pendingInteractions.remove(id) })
            if (isVisibleSession(session)) sitePromptHost?.showContentPermission(session, perm, result)
            return result
        }
        override fun onAndroidPermissionsRequest(session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback) {
            if (permissions.isNullOrEmpty() || !isVisibleSession(session) ||
                sitePromptHost?.showAndroidPermissions(session, permissions, callback) != true) {
                blockedSiteInteraction(session, "android_permission")
                callback.reject()
            }
        }
    }
    private fun externalLoad(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
        val scheme = runCatching { java.net.URI(request.uri).scheme?.lowercase() }.getOrNull()
        if (scheme !in setOf("mailto", "tel", "sms", "geo", "market", "intent")) return null
        if (isVisibleSession(session) && request.hasUserGesture) sitePromptHost?.showExternalUri(session, request.uri)
        return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
    }

    private fun installSessionDelegate(session: GeckoSession) {
        session.promptDelegate = sitePrompts
        session.permissionDelegate = sitePermissions
        extensions.attachSession(session)
        extension?.let { session.webExtensionController.setMessageDelegate(it, messageDelegate, "aibrowser") }
    }

    private fun newPopupSession(profile: String): GeckoResult<GeckoSession> {
        // Gecko opens the returned session itself. Popup ownership stays within
        // the same container's contextId, regardless of its source tab.
        val previous = popupSessions.remove(profile)
        if (previous != null) {
            failSessionRequests(previous, "Popup replaced")
            if (view?.session === previous) view?.releaseSession()
            ports.remove(previous)?.let(::retireBridgePort)
            previous.close()
        }
        invalidateScreenshot(profile)
        popupUrls.remove(profile)
        popupTitles.remove(profile)
        popupLoading[profile] = true
        val child = GeckoSession(sessionSettings(profile))
        popupSessions[profile] = child
        child.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                observeLoadRequest(session, request)
                return externalLoad(session, request)
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError): GeckoResult<String>? {
                if (uri != null) navigationLoadError(session, uri)
                return null
            }
            override fun onLocationChange(session: GeckoSession, url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean) {
                currentPermissions[session] = perms
                if (popupSessions[profile] === session && url != null) {
                    observeRequestedLocation(session, url)
                    identity(session).updateSameDocument(identity(session).documentId.orEmpty(), url)
                    if (popupUrls[profile] != url) invalidateScreenshot(profile)
                    popupUrls[profile] = url
                }
                notifyUi()
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackMap[session] = canGoBack
                notifyUi()
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canGoForwardMap[session] = canGoForward
                notifyUi()
            }
        }
        child.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                if (popupSessions[profile] === session) closePopup(profile, fromUser = true)
            }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) = externalResponse(session, response)
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                if (isVisibleSession(session)) sitePromptHost?.showContextMenu(session, element)
            }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (isVisibleSession(session)) sitePromptHost?.showFullscreen(session, fullScreen)
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (popupSessions[profile] === session) popupTitles[profile] = title ?: ""
                notifyUi()
            }
        }
        child.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onSecurityChange(session: GeckoSession, security: GeckoSession.ProgressDelegate.SecurityInformation) {
                securityInfo[session] = JSONObject().put("secure", security.isSecure).put("host", security.host)
                    .put("issuer", security.certificate?.issuerX500Principal?.name ?: JSONObject.NULL)
                notifyUi()
            }
            override fun onPageStart(session: GeckoSession, url: String) {
                sessionStarted(session)
                beginPageNavigation(session, url)
                observeRequestedLocation(session, url)
                if (popupSessions[profile] === session) {
                    popupUrls[profile] = url
                    popupLoading[profile] = true
                    popupTitles.remove(profile)
                    invalidateScreenshot(profile)
                }
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success && !hasPendingNavigation(session)) identity(session).navigationFailed()
                if (popupSessions[profile] === session) popupLoading[profile] = false
                notifyUi()
            }
        }
        installSessionDelegate(child)
        if (profile == activeProfile) ui.post { attachActive() }
        return GeckoResult.fromValue(child)
    }

    private fun openProfile(id: String): GeckoSession {
        sessions[id]?.let { return it }
        require(profileUrls.containsKey(id)) { "Unknown profile: $id" }
        // Context ID partitions persistent Gecko cookies and localStorage.
        val session = GeckoSession(sessionSettings(id))
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                observeLoadRequest(session, request)
                return externalLoad(session, request)
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError): GeckoResult<String>? {
                if (uri != null) navigationLoadError(session, uri)
                return null
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> =
                newPopupSession(id)
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackMap[session] = canGoBack
                notifyUi()
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canGoForwardMap[session] = canGoForward
                notifyUi()
            }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                currentPermissions[session] = perms
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    observeRequestedLocation(session, url)
                    identity(session).updateSameDocument(identity(session).documentId.orEmpty(), url)
                    if (profileUrls[id] != url) invalidateScreenshot(id)
                    profileUrls[id] = url
                    recordVisit(id, url)
                }
                notifyUi()
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) = externalResponse(session, response)
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                if (isVisibleSession(session)) sitePromptHost?.showContextMenu(session, element)
            }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (isVisibleSession(session)) sitePromptHost?.showFullscreen(session, fullScreen)
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageTitles[id] = title.orEmpty().take(250)
                persist()
                notifyUi()
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onSecurityChange(session: GeckoSession, security: GeckoSession.ProgressDelegate.SecurityInformation) {
                securityInfo[session] = JSONObject().put("secure", security.isSecure).put("host", security.host)
                    .put("issuer", security.certificate?.issuerX500Principal?.name ?: JSONObject.NULL)
                notifyUi()
            }
            override fun onPageStart(session: GeckoSession, url: String) {
                sessionStarted(session)
                beginPageNavigation(session, url)
                observeRequestedLocation(session, url)
                pageLoading[id] = true
                invalidateScreenshot(id)
                pageTitles.remove(id)
                profileUrls[id] = url
                recordVisit(id, url)
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success && !hasPendingNavigation(session)) identity(session).navigationFailed()
                pageLoading[id] = false
                notifyUi()
            }
        }
        installSessionDelegate(session)
        session.open(runtime ?: error("Runtime not ready"))
        sessions[id] = session
        installSessionDelegate(session)
        val initialUrl = profileUrls[id] ?: "https://example.org/"
        identity(session).beginNavigation(initialUrl)
        pageLoading[id] = true
        startingSessions[session] = true
        if (engineReady) session.loadUri(initialUrl) else initialLoads[session] = initialUrl
        pruneSessions(preserveId = id)
        return session
    }

    private fun openExtraTab(profile: String, id: String): GeckoSession {
        assertMain()
        val key = tabKey(profile, id)
        tabSessions[key]?.let { return it }
        val url = tabUrls[profile]?.get(id) ?: throw IllegalArgumentException("Unknown tab")
        val tab = GeckoSession(sessionSettings(profile))
        tab.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                observeLoadRequest(session, request)
                return externalLoad(session, request)
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError): GeckoResult<String>? {
                if (uri != null) navigationLoadError(session, uri)
                return null
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> =
                newPopupSession(profile)
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackMap[session] = canGoBack
                notifyUi()
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canGoForwardMap[session] = canGoForward
                notifyUi()
            }
            override fun onLocationChange(session: GeckoSession, url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                currentPermissions[session] = perms
                if (url != null && runCatching { requireWebUrl(url) }.isSuccess) {
                    observeRequestedLocation(session, url)
                    identity(session).updateSameDocument(identity(session).documentId.orEmpty(), url)
                    if (tabUrls[profile]?.get(id) != url) invalidateScreenshot(profile)
                    tabUrls[profile]?.set(id, url)
                    recordVisit(profile, url)
                }
                notifyUi()
            }
        }
        tab.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) = externalResponse(session, response)
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                if (isVisibleSession(session)) sitePromptHost?.showContextMenu(session, element)
            }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (isVisibleSession(session)) sitePromptHost?.showFullscreen(session, fullScreen)
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tabTitles[key] = title.orEmpty().take(250)
                persist()
                notifyUi()
            }
        }
        tab.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onSecurityChange(session: GeckoSession, security: GeckoSession.ProgressDelegate.SecurityInformation) {
                securityInfo[session] = JSONObject().put("secure", security.isSecure).put("host", security.host)
                    .put("issuer", security.certificate?.issuerX500Principal?.name ?: JSONObject.NULL)
                notifyUi()
            }
            override fun onPageStart(session: GeckoSession, url: String) {
                sessionStarted(session)
                beginPageNavigation(session, url)
                observeRequestedLocation(session, url)
                tabUrls[profile]?.set(id, url)
                tabLoading[key] = true
                tabTitles.remove(key)
                invalidateScreenshot(profile)
                recordVisit(profile, url)
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success && !hasPendingNavigation(session)) identity(session).navigationFailed()
                tabLoading[key] = false
                notifyUi()
            }
        }
        tab.open(runtime ?: error("Runtime not ready"))
        tabSessions[key] = tab
        installSessionDelegate(tab)
        identity(tab).beginNavigation(url)
        tabLoading[key] = true
        startingSessions[tab] = true
        if (engineReady) tab.loadUri(url) else initialLoads[tab] = url
        pruneTabSessions(preserveKey = key)
        return tab
    }

    private fun pruneTabSessions(preserveKey: String? = null) {
        // Bound browser tab rendering while keeping tab URLs and Gecko profile storage.
        while (tabSessions.size > 8) {
            val victim = tabSessions.keys.firstOrNull { key ->
                val owner = key.substringBefore('/')
                val id = key.substringAfter('/')
                key != preserveKey && (selectedTab(owner) != id || owner != activeProfile) &&
                    (inFlightByProfile[owner] ?: 0) == 0 &&
                    tabSessions[key]?.let { !hasSessionWork(it) } == true
            } ?: break
            val old = tabSessions.remove(victim) ?: break
            if (view?.session === old) view?.releaseSession()
            failSessionRequests(old, "Tab evicted due to memory limit")
            ports.remove(old)?.let(::retireBridgePort)
            old.close()
        }
    }

    fun createTab(url: String = "https://example.org/", profile: String = activeProfile,
        fromUser: Boolean = false): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles || fromUser) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        val list = tabUrls.getOrPut(profile) { LinkedHashMap() }
        require(list.size < 120) { "Maximum 120 extra tabs per profile" }
        val id = "T" + nextTabId++
        list[id] = url
        activeTabs[profile] = id
        invalidateScreenshot(profile)
        if (profile == activeProfile) attachActive()
        else openExtraTab(profile, id)
        persist()
        notifyUi()
        return JSONObject().put("id", id).put("profile", profile).put("url", url)
    }
    fun selectTab(id: String, profile: String = activeProfile,
        fromUser: Boolean = false): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles || fromUser) { "Manual takeover active for requested profile" }
        require((id == "main" && profile !in closedMainProfiles) || tabUrls[profile]?.containsKey(id) == true) { "Unknown tab" }
        activeTabs[profile] = id
        invalidateScreenshot(profile)
        persist()
        invalidateScreenshot(profile)
        if (profile == activeProfile) attachActive()
        notifyUi()
        return tabs(profile)
    }
    fun closeTab(id: String, profile: String = activeProfile,
        fromUser: Boolean = false): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles || fromUser) { "Manual takeover active for requested profile" }
        if (id == "main") {
            require(profile !in closedMainProfiles) { "Unknown tab" }
            val old = sessions[profile]
            if (!fromUser && old != null && hasSessionWork(old)) throw BrowserOperationException("BUSY", "Tab has active operations; retry after they finish")
            recentlyClosed.remember(profile, profileUrls[profile].orEmpty(), pageTitles[profile].orEmpty(), isPrivate(profile))
            if (old != null) {
                sessions.remove(profile)
                failSessionRequests(old, "Tab closed")
                if (view?.session === old) view?.releaseSession()
                if (backgroundHost?.session === old) { backgroundHost?.close(); backgroundHost = null }
                ports.remove(old)?.let(::retireBridgePort); old.close()
            }
            tabGroups.removeTab(profile, id); previews.remove(profile, id)
            previewDocuments.remove(tabKey(profile, id))
            pageTitles.remove(profile)
            profileUrls[profile] = BrowserSearch.home(app.getSharedPreferences("abwr_settings", 0)
                .getString("search_engine", "google") ?: "google")
            val next = tabUrls[profile]?.keys?.firstOrNull()
            if (next != null) { closedMainProfiles.add(profile); activeTabs[profile] = next }
            else { closedMainProfiles.remove(profile); activeTabs[profile] = "main" }
            invalidateScreenshot(profile); persist()
            if (profile == activeProfile) attachActive()
            notifyUi(); return tabs(profile)
        }
        require(tabUrls[profile]?.containsKey(id) == true) { "Unknown tab" }
        val key = tabKey(profile, id)
        val session = tabSessions[key]
        if (!fromUser && session != null) {
            if (hasSessionWork(session)) throw BrowserOperationException("BUSY", "Tab has active operations; retry after they finish")
        }
        // Validate ownership/in-flight work before making any persistent mutation.
        recentlyClosed.remember(profile, tabUrls[profile]?.get(id).orEmpty(), tabTitles[key].orEmpty(), isPrivate(profile))
        tabUrls[profile]?.remove(id)
        tabGroups.removeTab(profile, id); previews.remove(profile, id); previewDocuments.remove(key)
        if (session != null) {
            tabSessions.remove(key)
            failSessionRequests(session, "Tab closed")
            if (view?.session === session) view?.releaseSession()
            if (backgroundHost?.session === session) { backgroundHost?.close(); backgroundHost = null }
            ports.remove(session)?.let(::retireBridgePort)
            session.close()
        }
        tabTitles.remove(key)
        tabLoading.remove(key)
        if (selectedTab(profile) == id) {
            val next = if (profile in closedMainProfiles) tabUrls[profile]?.keys?.firstOrNull() else null
            activeTabs[profile] = next ?: "main"
            if (next == null) closedMainProfiles.remove(profile)
        }
        invalidateScreenshot(profile)
        persist()
        if (profile == activeProfile) attachActive()
        notifyUi()
        return tabs(profile)
    }
    fun tabs(profile: String = activeProfile): JSONObject {
        require(profile in profileUrls) { "Unknown profile" }
        val list = JSONArray()
        if (profile !in closedMainProfiles) list.put(JSONObject()
            .put("id", "main").put("url", profileUrls[profile] ?: "")
            .put("title", pageTitles[profile] ?: "")
            .put("active", selectedTab(profile) == "main" && !popupSessions.containsKey(profile)))
        tabUrls[profile]?.forEach { (id, url) ->
            list.put(JSONObject().put("id", id).put("url", url)
                .put("title", tabTitles[tabKey(profile,id)] ?: "")
                .put("active", selectedTab(profile) == id && !popupSessions.containsKey(profile)))
        }
        if (popupSessions.containsKey(profile)) list.put(JSONObject()
            .put("id", "popup").put("active", true).put("popup", true)
            .put("url", popupUrls[profile] ?: "")
            .put("title", popupTitles[profile] ?: "")
            .put("loading", popupLoading[profile] ?: false))
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            item.put("groupId", tabGroups.membership(profile, item.getString("id")) ?: JSONObject.NULL)
        }
        return JSONObject().put("profile", profile).put("groups", groupsJson(profile))
            .put("previewRevision", previews.revision)
            .put("popupActive", popupSessions.containsKey(profile))
            .put("selectedTab", selectedTab(profile)).put("tabs", list)
    }

    private fun pruneSessions(preserveId: String? = null) {
        // Profiles and their Gecko context IDs remain persisted when an idle session is evicted.
        // The active UI session, the new requested session and in-flight command sessions stay pinned.
        // Permit a temporary overflow rather than interrupt an outstanding Agent operation.
        while (sessions.size > 3) {
            val evictId = sessions.keys.firstOrNull {
                it != activeProfile && it != preserveId && (inFlightByProfile[it] ?: 0) == 0 &&
                    sessions[it]?.let { session -> !hasSessionWork(session) } == true &&
                    popupSessions[it]?.let { popup -> !hasSessionWork(popup) } != false
            } ?: return
            val old = sessions.remove(evictId) ?: return
            popupSessions.remove(evictId)?.let { popup ->
                failSessionRequests(popup, "Popup evicted with profile")
                popupUrls.remove(evictId)
                popupTitles.remove(evictId)
                popupLoading.remove(evictId)
                if (view?.session === popup) view?.releaseSession()
                ports.remove(popup)?.let(::retireBridgePort)
                popup.close()
            }
            failSessionRequests(old, "Profile session evicted due to memory limit")
            ports.remove(old)?.let(::retireBridgePort)
            if (view?.session === old) view?.releaseSession()
            old.close()
        }
    }

    /** Transfer the selected session to a service-owned compositor Surface. */
    fun enterBackgroundSurface() {
        assertMain()
        if (view != null || backgroundHost != null || pendingBackgroundAttach != null) return
        if (!backgroundRenderingEnabled()) return
        // Ignore short onStop/onStart transitions, e.g. opening system settings or rotation.
        val task = object : Runnable {
            override fun run() {
                pendingBackgroundAttach = null
                if (view != null || backgroundHost != null) return
                val host = OffscreenBrowserDisplay(backgroundWidth, backgroundHeight)
                try {
                    host.attach(currentSession())
                    backgroundHost = host
                    surfaceGeneration++
                    backgroundSurfaceStartedElapsedMs = SystemClock.elapsedRealtime()
                    backgroundSurfaceError = null
                    notifyUi()
                } catch (error: Throwable) {
                    Log.e("Abwr", "Private background render surface failed", error)
                    backgroundSurfaceError = error.message ?: error.javaClass.simpleName
                    view = null
                    runCatching { host.close() }
                    backgroundHost = null
                }
            }
        }
        pendingBackgroundAttach = task
        ui.postDelayed(task, 750L)
    }
    fun bindView(browserView: GeckoView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        pendingBackgroundAttach?.let { ui.removeCallbacks(it) }
        pendingBackgroundAttach = null
        initialize()
        if (view !== browserView && backgroundHost != null) {
            surfaceGeneration++
            backgroundHost?.close()
            backgroundHost = null
            backgroundSurfaceStartedElapsedMs = 0L
        }
        if (view !== browserView) {
            surfaceGeneration++
            view?.releaseSession()
            view = browserView
        }
        attachActive()
    }
    fun unbindView(browserView: GeckoView) {
        if (view === browserView) {
            if (browserView.width > 0 && browserView.height > 0) {
                backgroundWidth = browserView.width
                backgroundHeight = browserView.height
            }
            surfaceGeneration++
            browserView.releaseSession()
            view = null
        }
    }
    private fun attachActive() {
        val current = currentSession()
        extensions.activateSession(current)
        if (captureAuxHost?.session === current) {
            captureAuxHost?.close(); captureAuxHost = null
            captureInFlight?.completeExceptionally(BrowserOperationException("PAGE_CHANGED", "Capture target became visible; capture again"))
        }
        if (backgroundHost != null) {
            backgroundHost!!.attach(current)
            surfaceGeneration++
            notifyUi(); return
        }
        var target = view ?: run { enterBackgroundSurface(); return }
        if (target.session !== current) {
            target.session?.let { sitePromptHost?.dismissPromptsFor(it) }
            surfaceGeneration++
            target.releaseSession()
            replaceBrowserView?.let { replace -> target = replace(target); view = target }
            target.setSession(current)
        }
        notifyUi()
    }

    fun profiles(): JSONArray = JSONArray().apply {
        profileUrls.forEach { (id, url) ->
            val selectedUrl = popupUrls[id] ?: tabUrls[id]?.get(selectedTab(id)) ?: url
            put(JSONObject().put("id", id).put("url", selectedUrl).put("active", id == activeProfile)
                .put("loaded", sessions.containsKey(id)).put("manual", id in manualProfiles)
                .put("storageIsolated", true).put("private", isPrivate(id))
                .put("fingerprintProtectionScope", "shared_gecko_runtime"))
        }
    }
    fun syncUserScripts() {
        assertMain()
        extensions.syncScripts(userScripts.enabledJson())
        notifyUi()
    }
    fun status(profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        return JSONObject().put("profile", profile).put("private", isPrivate(profile)).put("recentlyClosedTabCount", closedTabCount(profile))
            .put("ready", engineReady)
            .put("extensionError", extensionError)
            .put("activeProfile", activeProfile)
            .put("profileCount", profileUrls.size)
            .put("retiringProfiles", JSONArray(retiringProfiles.toList()))
            .put("reservedDeletedProfileIds", JSONArray(retiringProfiles.toList()))
            .put("residentSessions", sessions.size + tabSessions.size)
            .put("trackingProtectionEnabled", trackingProtectionEnabled())
            .put("fingerprintingProtectionEnabled", fingerprintProtectionEnabled())
            .put("fingerprintingProtectionScope", "shared_gecko_runtime")
            .put("independentPerProfileWebFingerprint", false)
            .put("desktopMode", profile in desktopProfiles)
            .put("inFlightProfiles", JSONArray(inFlightByProfile.keys.toList()))
            .put("pendingNavigations", JSONArray(navigationWaiters.keys.toList()))
            .put("pendingBridgeCommands", bridgeRequests.size)
            .put("pendingDownloads", downloads.activeCount(profile))
            .put("lastBlockedSiteInteraction", lastBlockedSiteInteraction ?: JSONObject.NULL)
            .put("screenshotInFlight", captureInFlight?.isDone == false)
            .put("manualMode", profile in manualProfiles)
            .put("manualProfiles", JSONArray(manualProfiles.toList()))
            .put("residentLimit", 3)
            .put("profileCapacity", 300)
            .put("backgroundScreenshotGuaranteed", false)
            .put("backgroundRendererPhase", if (profile == activeProfile) backgroundRenderPhase() else "no_surface")
            .put("viewAttached", profile == activeProfile && view != null)
            .put("extensionPortReady", selectedLoadedSession(profile)?.let { bridgeReady(it) } ?: false)
            .put("bridgeStartAttempts", selectedLoadedSession(profile)?.let { bridgeStartAttempts[it] ?: 0 } ?: 0)
            .put("lastBridgeError", selectedLoadedSession(profile)?.let { bridgeLastErrors[it] } ?: JSONObject.NULL)
            .put("contentBridgeVersion", selectedLoadedSession(profile)?.takeIf { bridgeReady(it) }?.let { bridgeVersions[it] } ?: JSONObject.NULL)
            .put("bridgeSchemaVersion", 4)
            .put("documentId", selectedLoadedSession(profile)?.let {
                if (bridgeReady(it)) identity(it).documentId else null
            } ?: JSONObject.NULL)
            .put("pendingUrl", selectedLoadedSession(profile)?.let { identity(it).pendingUrl } ?: JSONObject.NULL)
            .put("documentTransition", selectedLoadedSession(profile)?.let { identity(it).navigating } ?: false)
            .put("frameCount", selectedLoadedSession(profile)?.let { framePorts[it]?.size ?: 0 } ?: 0)
            .put("popupActive", popupSessions.containsKey(profile))
            .put("activeTab", if (popupSessions.containsKey(profile)) "popup" else selectedTab(profile))
            .put("canGoBack", selectedLoadedSession(profile)?.let { canGoBackMap[it] } ?: false)
            .put("canGoForward", selectedLoadedSession(profile)?.let { canGoForwardMap[it] } ?: false)
            .put("tabCount", tabs(profile).getJSONArray("tabs").length())
            .put("url", if (popupSessions.containsKey(profile)) popupUrls[profile] ?: ""
            else if (selectedTab(profile) == "main") profileUrls[profile] ?: ""
            else tabUrls[profile]?.get(selectedTab(profile)) ?: "")
            .put("title", if (popupSessions.containsKey(profile)) popupTitles[profile] ?: ""
            else if (selectedTab(profile) == "main") pageTitles[profile] ?: ""
            else tabTitles[tabKey(profile, selectedTab(profile))] ?: "")
            .put("loading", if (popupSessions.containsKey(profile)) popupLoading[profile] ?: false
            else if (selectedTab(profile) == "main") pageLoading[profile] ?: false
            else tabLoading[tabKey(profile, selectedTab(profile))] ?: false)
            .put("httpStatusAvailable", false).put("networkObservationAvailable", true)
            .put("liveBackgroundScreenshotSupported", backgroundRenderingEnabled())
            .put("backgroundScreenshotOnDemand", backgroundRenderingEnabled())
            .put("tabGroupCount", tabGroups.all(profile).size)
            .put("experimentalBackgroundRendererActive", profile == activeProfile && backgroundHost != null)
            .put("experimentalBackgroundSurfaceAttached",
            profile == activeProfile && backgroundHost?.attached == true)
            .put("experimentalBackgroundFramesReceived", if (profile == activeProfile) backgroundHost?.framesReceived ?: 0L else 0L)
            .put("experimentalBackgroundLastFrameAgeMs", backgroundHost?.lastFrameElapsedMs?.takeIf { profile == activeProfile && it > 0L }
            ?.let { SystemClock.elapsedRealtime() - it } ?: JSONObject.NULL)
            .put("experimentalBackgroundSurfaceError", if (profile == activeProfile) backgroundSurfaceError ?: JSONObject.NULL else JSONObject.NULL)
            .put("cachedScreenshotAvailable", lastShots.containsKey(profile))
            .put("lastScreenshotAtMs", if (lastScreenshotAt == 0L) JSONObject.NULL else lastScreenshotAt)
            .put("lastScreenshotError", lastScreenshotError ?: JSONObject.NULL)
            .put("screenshotSurfaceAttached", profile == activeProfile &&
                (view?.isAttachedToWindow == true || backgroundHost?.attached == true))
            .put("screenshotViewport", JSONObject().put("width", view?.width ?: backgroundHost?.width ?: 0)
                .put("height", view?.height ?: backgroundHost?.height ?: 0))
            .put("backgroundRendererImplementation", "gecko_display_image_reader")
            .put("proxyMode", if (proxyConfigurations.has(profile)) "per_profile" else "direct").put("proxy", proxyInfo(profile))
    }

    fun switchProfile(id: String): JSONObject {
        assertMain()
        require(profileUrls.containsKey(id)) { "Unknown profile" }
        activeProfile = id
        persist()
        currentSession(id)
        attachActive()
        return status()
    }
    fun createProfile(id: String): JSONObject {
        assertMain()
        require(validProfile(id)) { "Profile IDs must be 1–31 letters, digits, _ or - and start with a letter" }
        require(!profileUrls.containsKey(id)) { "Profile already exists" }
        require(profileUrls.size < 300) { "Profile metadata limit reached" }
        // A newly-created container must not contact a website before the
        // owner configures its route or explicitly navigates it.
        profileUrls[id] = "about:blank"
        profileContexts[id] = "abwr_" + UUID.randomUUID().toString().replace("-", "")
        check(persist(sync = true)) { "Cannot persist new container identity" }
        extensions.proxyPolicies = proxyPolicies(); extensions.syncScripts(userScripts.enabledJson())
        notifyUi()
        return JSONObject().put("id", id)
    }
    fun setManual(enabled: Boolean, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        if (enabled) manualProfiles.add(profile) else manualProfiles.remove(profile)
        automationAudit.record(if (enabled) "takeover_enter" else "takeover_return", profile, selectedTab(profile),
            selectedLoadedSession(profile)?.let { identity(it).documentId }.orEmpty(), private = isPrivate(profile))
        // A system process restart must not silently return control to the Agent.
        val persisted = store.edit().putStringSet("manual_profiles_v1", manualProfiles.filter { it !in privateProfiles }.toSet()).commit()
        notifyUi()
        return status(profile).put("takeoverProfile", profile).put("takeoverEnabled", enabled)
            .put("takeoverStatePersisted", persisted)
    }
    private fun clearContext(contextId: String) {
        assertMain()
        val storage = runtime?.storageController ?: return
        // GeckoView 157 returns Unit here, NOT a result/future acknowledging
        // data erasure. Retire the opaque context identity permanently; a new
        // public profile with the same name receives a different context.
        try {
            storage.clearDataForSessionContext(contextId)
        } catch (error: Exception) {
            Log.e("Abwr", "Unable to request storage cleanup for retired context", error)
        }
    }

    fun removeProfile(id: String): JSONObject {
        assertMain()
        require(id in profileUrls) { "Unknown profile" }
        require(profileUrls.size > 1) { "Cannot remove the final profile" }
        require(id in privateProfiles || profileUrls.keys.count { it !in privateProfiles } > 1) { "Cannot remove the final ordinary profile" }
        require((inFlightByProfile[id] ?: 0) == 0 &&
            navigationWaiters[id]?.future?.isDone != false && downloads.activeCount(id) == 0 && http.activeCount(id) == 0) {
            "Profile has active Agent operations; retry later"
        }
        require(!proxyChangeInFlight) { "Proxy policy update in progress" }
        require(id !in manualProfiles) { "Cannot delete a profile during manual takeover" }
        requireNotNull(runtime?.storageController) {
            "Gecko runtime must be ready before clearing profile storage"
        }
        proxyConfigurations.remove(id); cookieStoreIdentities.remove(id)
        proxyPrefs.edit().putString("configurations", proxyConfigurations.toString()).putString("cookie_stores", cookieStoreIdentities.toString()).commit()
        extensions.proxyPolicies = proxyPolicies(); extensions.syncScripts(userScripts.enabledJson())
        val retiredContext = profileContexts[id] ?: id
        tabIds(id).forEach { previews.remove(id, it); previewDocuments.remove(tabKey(id, it)) }
        tabGroups.removeProfile(id)
        popupSessions.remove(id)?.let { popup ->
            failSessionRequests(popup, "Profile deleted")
            if (view?.session === popup) view?.releaseSession()
            ports.remove(popup)?.let(::retireBridgePort)
            popup.close()
        }
        val session = sessions.remove(id)
        if (session != null) {
            failSessionRequests(session, "Profile deleted")
            if (view?.session === session) view?.releaseSession()
            ports.remove(session)?.let(::retireBridgePort)
            session.close()
        }
        popupUrls.remove(id)
        popupTitles.remove(id)
        popupLoading.remove(id)
        profileUrls.remove(id)
        profileContexts.remove(id)
        privateProfiles.remove(id)
        recentlyClosed.clear(id)
        desktopProfiles.remove(id)
        bookmarkUrls.remove(id)
        historyUrls.remove(id)
        tabUrls.remove(id)
        activeTabs.remove(id)
        closedMainProfiles.remove(id)
        tabSessions.keys.filter { it.startsWith("$id/") }.toList().forEach { key ->
            val tab = tabSessions.remove(key) ?: return@forEach
            failSessionRequests(tab, "Profile deleted")
            if (view?.session === tab) view?.releaseSession()
            ports.remove(tab)?.let(::retireBridgePort)
            tab.close()
        }
        pageLoading.remove(id)
        tabLoading.keys.removeAll { it.startsWith("$id/") }
        pageTitles.remove(id)
        lastShots.remove(id)
        navigationWaiters.remove(id)?.future?.completeExceptionally(IllegalStateException("Profile deleted"))
        manualProfiles.remove(id)
        if (activeProfile == id) activeProfile = profileUrls.keys.firstOrNull { it !in privateProfiles } ?: profileUrls.keys.first()
        check(persist(sync = true)) { "Cannot persist deleted container; storage cleanup was not requested" }
        clearContext(retiredContext)
        attachActive()
        // Gecko does not acknowledge erasure; never imply that it did.
        return JSONObject().put("removed", id)
            .put("storageClearRequested", true).put("idReserved", false)
            .put("reusableWithFreshContext", true)
            .put("activeProfile", activeProfile)
    }
    fun profileForRequest(input: JSONObject): String = input.optString("profile", activeProfile).also { profile ->
        require(profile in profileUrls) { "Unknown profile" }
        val currentTab = if (popupSessions.containsKey(profile)) "popup" else selectedTab(profile)
        if (input.has("tab") && input.getString("tab") != currentTab)
            throw BrowserOperationException("PAGE_CHANGED", "Selected tab changed; inspect status and refresh the snapshot")
        if (input.has("expectedUrl")) {
            val currentUrl = if (currentTab == "popup") popupUrls[profile] ?: ""
                else if (currentTab == "main") profileUrls[profile] ?: ""
                else tabUrls[profile]?.get(currentTab) ?: ""
            if (!PageIdentity.sameUrl(input.getString("expectedUrl"), currentUrl))
                throw BrowserOperationException("PAGE_CHANGED", "Page URL changed; inspect status and refresh the snapshot")
        }
        if (input.has("documentId") && input.getString("documentId") !=
            selectedLoadedSession(profile)?.let { identity(it).documentId })
            throw BrowserOperationException("PAGE_CHANGED", "Document changed; refresh the snapshot")
    }
    fun ensureTarget(profile: String) {
        assertMain()
        if (profile != activeProfile) switchProfile(profile)
    }
    private fun recordNavigationTarget(session: GeckoSession, profile: String, url: String) {
        invalidateScreenshot(profile)
        beginPageNavigation(session, url)
        notifyUi()
    }
    private fun sessionStarted(session: GeckoSession) {
        if (startingSessions.remove(session) == null) return
        val request = startupNavigations.remove(session) ?: return
        // Gecko's initial-window load can otherwise overtake a loadUri sent
        // before that window exists. Issue the requested URI exactly once.
        ui.post {
            if (!session.isOpen || request.completion?.isDone == true) return@post
            if (!request.fromUser && request.profile in manualProfiles) {
                request.completion?.completeExceptionally(BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active"))
                return@post
            }
            recordNavigationTarget(session, request.profile, request.url)
            session.stop(); session.loadUri(request.url)
        }
    }
    private fun loadRequestedUri(session: GeckoSession, profile: String, url: String,
        fromUser: Boolean = false, completion: CompletableFuture<JSONObject>? = null) {
        if (!engineReady || initialLoads.containsKey(session)) {
            initialLoads[session] = url; startingSessions[session] = true; return
        }
        if (startingSessions.containsKey(session) && identity(session).documentId == null) {
            val old = startupNavigations.put(session, StartupNavigation(profile, url, fromUser, completion))
            if (old?.completion != null && old.completion !== completion) old.completion.completeExceptionally(
                BrowserOperationException("NAVIGATION_CANCELLED", "A newer navigation replaced the startup destination"))
            completion?.whenComplete { _, _ -> ui.post {
                if (startupNavigations[session]?.completion === completion) startupNavigations.remove(session)
            } }
            return
        }
        session.stop(); session.loadUri(url)
    }
    fun navigate(url: String, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        val destination = currentSession(profile)
        activateForWork(destination)
        if (NavigationReadiness.joinsInitialLoad(identity(destination).documentId, identity(destination).pendingUrl, url))
            return JSONObject().put("navigating", url).put("profile", profile).put("joinedInitialLoad", true)
        recordNavigationTarget(destination, profile, url)
        loadRequestedUri(destination, profile, url)
        return JSONObject().put("navigating", url).put("profile", profile)
    }
    /** Waits for a fresh document's real completed load state, never an old page callback. */
    fun navigateAndWaitAsync(url: String, profile: String, timeoutMs: Long): CompletableFuture<JSONObject> {
        assertMain()
        require(timeoutMs in 250L..14000L) { "timeoutMs must be 250-14000" }
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        check(navigationWaiters[profile] == null) { "Navigation wait already active for profile" }
        val destination = currentSession(profile)
        activateForWork(destination)
        val joinedInitialLoad = NavigationReadiness.joinsInitialLoad(
            identity(destination).documentId, identity(destination).pendingUrl, url)
        val future = CompletableFuture<JSONObject>()
        val pending = PendingNavigation(url, destination, future, NavigationReadiness(url,
            identity(destination).documentId, identity(destination).url ?: status(profile).optString("url")), joinedInitialLoad)
        navigationWaiters[profile] = pending
        val timeout = Runnable {
            if (!future.isDone) future.completeExceptionally(
                java.util.concurrent.TimeoutException("Navigation did not finish within timeoutMs"))
        }
        ui.postDelayed(timeout, timeoutMs)
        future.whenComplete { _, _ ->
            ui.post {
                ui.removeCallbacks(timeout)
                if (navigationWaiters[profile] === pending) navigationWaiters.remove(profile)
                pruneSessions()
                pruneTabSessions()
            }
        }
        try {
            // Navigate precisely the session pinned above, avoiding a tab/popup
            // selection change between waiter creation and issuing loadUri().
            if (joinedInitialLoad) {
                // A fresh tab already loading this exact URL has no old document
                // to reload. Issuing it twice can accept the first document just
                // before the second replaces it, invalidating the returned UUID.
                pending.readiness.observe(url)
            } else {
                recordNavigationTarget(destination, profile, url)
                loadRequestedUri(destination, profile, url, completion = future)
            }
        } catch (e: Exception) { future.completeExceptionally(e) }
        return future
    }

    fun diagnostics(): JSONObject {
        assertMain()
        val heap = Runtime.getRuntime()
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject()
            .put("installedApplication", installedApplication())
            .put("version", BuildConfig.VERSION_NAME).put("apiVersion", BuildConfig.VERSION_NAME)
            .put("sourceRevision", BuildConfig.SOURCE_REVISION).put("schemaVersion", 4)
            .put("contentBridgeVersion", selectedLoadedSession(activeProfile)?.let { bridgeVersions[it] } ?: JSONObject.NULL)
            .put("pendingBridgeCommands", bridgeRequests.size).put("activeInputProfiles", JSONArray(activeInputProfiles.toList()))
            .put("uptimeMs", SystemClock.elapsedRealtime() - controllerCreatedAt)
            .put("memoryHeapUsedBytes", heap.totalMemory() - heap.freeMemory())
            .put("memoryHeapAllocatedBytes", heap.totalMemory())
            .put("memoryHeapMaxBytes", heap.maxMemory())
            .put("processPssBytes", memory.totalPss.toLong() * 1024L)
            .put("processPrivateDirtyBytes", memory.totalPrivateDirty.toLong() * 1024L)
            .put("processCpuTimeMs", android.os.Process.getElapsedCpuTime())
            .put("memoryScope", "current_android_process_only_gecko_child_processes_excluded")
            .put("batteryOptimizationExempt", power.isIgnoringBatteryOptimizations(app.packageName))
            .put("screenInteractive", power.isInteractive)
            .put("apiWakeLeases", workLease.active.get())
            .put("apiWakeLeasePolicy", "operation_only_with_timeout_and_finally_release")
            .put("bridgeReadyWaitMs", 3500)
            .put("waitingForBridge", preparingSessions.values.sum())
            .put("temporaryActiveSessions", activeWorkSessions.size)
            .put("backgroundRendererEnabled", backgroundRenderingEnabled())
            .put("backgroundRendererActive", backgroundHost != null)
            .put("backgroundRendererPhase", backgroundRenderPhase())
            .put("backgroundRecoveryAttempts", backgroundRecoveryAttempts)
            .put("backgroundRecoveryCooldownMs", if (lastBackgroundRecoveryElapsedMs == 0L)
                0L else (20000L - (SystemClock.elapsedRealtime() -
                lastBackgroundRecoveryElapsedMs)).coerceAtLeast(0L))
            .put("backgroundDisplayFrames", backgroundHost?.framesReceived ?: 0L)
            .put("backgroundSuccessfulPixelCaptures", backgroundHost?.successfulPixelCaptures ?: 0L)
            .put("backgroundLastPixelCaptureAgeMs", backgroundHost?.lastPixelCaptureElapsedMs?.takeIf { it > 0L }
                ?.let { SystemClock.elapsedRealtime() - it } ?: JSONObject.NULL)
            .put("backgroundLastFrameAgeMs", backgroundHost?.lastFrameElapsedMs?.takeIf { it > 0L }
                ?.let { SystemClock.elapsedRealtime() - it } ?: JSONObject.NULL)
            .put("backgroundSurfaceError", backgroundSurfaceError ?: JSONObject.NULL)
            .put("lastBlockedSiteInteraction", lastBlockedSiteInteraction ?: JSONObject.NULL)
            .put("residentProfiles", JSONArray(sessions.keys.toList()))
            .put("residentTabSessions", tabSessions.size)
            .put("inFlightProfiles", JSONArray(inFlightByProfile.keys.toList()))
            .put("lastScreenshotAtMs", if (lastScreenshotAt == 0L) JSONObject.NULL else lastScreenshotAt)
            .put("lastScreenshotError", lastScreenshotError ?: JSONObject.NULL)
    }
    private fun installedApplication(): JSONObject {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName,
            if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val certificates = if (android.os.Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        return JSONObject().put("applicationId", app.packageName).put("versionName", info.versionName)
            .put("sourceRevision", BuildConfig.SOURCE_REVISION)
            .put("versionCode", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info))
            .put("certificateSha256", JSONArray(certificates.map { signature ->
                java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }))
    }

    fun humanNavigate(url: String) {
        if (runCatching { requireWebUrl(url) }.isSuccess) {
            val destination = currentSession(activeProfile)
            recordNavigationTarget(destination, activeProfile, url)
            loadRequestedUri(destination, activeProfile, url, fromUser = true)
        }
    }
    fun back(profile: String = activeProfile) {
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        currentSession(profile).let { if (canGoBackMap[it] == true) { beginPageNavigation(it); it.goBack() } }
    }
    fun forward(profile: String = activeProfile) {
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        currentSession(profile).let { if (canGoForwardMap[it] == true) { beginPageNavigation(it); it.goForward() } }
    }
    fun reload(profile: String = activeProfile) {
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        currentSession(profile).let { beginPageNavigation(it); it.reload() }
    }
    // User-driven activity controls intentionally remain functional during manual takeover.
    fun humanBack() { currentSession().let { if (canGoBackMap[it] == true) { beginPageNavigation(it); it.goBack() } } }
    fun humanForward() { currentSession().let { if (canGoForwardMap[it] == true) { beginPageNavigation(it); it.goForward() } } }
    fun humanReload() { currentSession().let { beginPageNavigation(it); it.reload() } }
    fun cachedScreenshot(profile: String = activeProfile): ByteArray {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        return lastShots[profile]?.jpeg?.copyOf()
            ?: throw IllegalStateException("No cached screenshot yet; capture with Abwr visible first")
    }
    fun screenshotMeta(profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        val cached = lastShots[profile]
        return JSONObject()
            .put("profile", profile)
            .put("liveBackgroundScreenshotSupported", backgroundRenderingEnabled())
            .put("cachedAvailable", cached != null)
            .put("capturedAtMs", cached?.capturedAt ?: JSONObject.NULL)
            .put("ageMs", cached?.let { System.currentTimeMillis() - it.capturedAt } ?: JSONObject.NULL)
            .put("bytes", cached?.jpeg?.size ?: 0)
            .put("width", cached?.width ?: 0).put("height", cached?.height ?: 0)
            .put("url", cached?.url ?: JSONObject.NULL)
            .put("tab", cached?.tab ?: JSONObject.NULL)
            .put("documentId", cached?.documentId ?: JSONObject.NULL)
            .put("stale", true)
            .put("note", "Cached screenshot is an old frame, not live background pixels")
    }

    fun closePopup(profile: String = activeProfile, fromUser: Boolean = false): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles || fromUser) { "Manual takeover active for requested profile" }
        invalidateScreenshot(profile)
        popupSessions.remove(profile)?.let { popup ->
            failSessionRequests(popup, "Popup closed")
            if (view?.session === popup) view?.releaseSession()
            ports.remove(popup)?.let(::retireBridgePort)
            popup.close()
        }
        popupUrls.remove(profile)
        popupTitles.remove(profile)
        popupLoading.remove(profile)
        if (profile == activeProfile) attachActive()
        notifyUi()
        return tabs(profile)
    }

    /** Dispatches a DOM action asynchronously via a GeckoView native messaging port. */
    private fun releaseInFlight(profile: String) {
        assertMain()
        val count = (inFlightByProfile[profile] ?: 0) - 1
        if (count <= 0) inFlightByProfile.remove(profile)
        else inFlightByProfile[profile] = count
        pruneSessions()
        pruneTabSessions()
    }

    fun validateCommand(input: JSONObject) {
        if (input.optString("action") == "http_request") validateHttpRequest(input)
        else {
            fun value(raw: Any?): Any? = when (raw) {
                is JSONArray -> (0 until raw.length()).map { value(raw.opt(it)) }
                is JSONObject -> raw.keys().asSequence().associateWith { value(raw.opt(it)) }
                else -> raw
            }
            @Suppress("UNCHECKED_CAST")
            CommandArguments.validate(value(input) as Map<String, Any?>)
        }
    }

    private fun dispatchPage(profile: String, session: GeckoSession, port: WebExtension.Port,
        input: JSONObject, frame: String, documentId: String): CompletableFuture<JSONObject> {
        inFlightByProfile[profile] = (inFlightByProfile[profile] ?: 0) + 1
        val id = UUID.randomUUID().toString()
        val request = JSONObject(input.toString()).put("id", id).put("expectedDocumentId", documentId)
        if (frame != "main") {
            request.remove("expectedUrl")
            input.optString("expectedFrameUrl").takeIf { it.isNotBlank() }?.let { request.put("expectedUrl", it) }
        }
        val future = CompletableFuture<JSONObject>()
        bridgeRequests.add(id, session, port, future)
        val timeout = Runnable {
            bridgeRequests.fail(id, BrowserOperationException("BRIDGE_TIMEOUT", "Page bridge response timed out"))
        }
        ui.postDelayed(timeout, if (input.optString("action") == "http_request") input.optLong("timeoutMs", 15000L).coerceIn(1000L, 20000L) + 500L else 11_000L)
        future.whenComplete { _, _ ->
            ui.post {
                ui.removeCallbacks(timeout)
                bridgeRequests.remove(id, future)
                releaseInFlight(profile)
            }
        }
        try { postBridge(port, request) }
        catch (error: Exception) { bridgeRequests.fail(id, error) }
        return future.thenApply { reply ->
            if (!reply.optBoolean("ok", true)) throw BrowserOperationException(reply.optString("code", "PAGE_ACTION_FAILED"), reply.optString("error", "Page action failed"))
            reply
        }
    }

    fun commandAsync(input: JSONObject): CompletableFuture<JSONObject> {
        if (input.optString("action") == "http_request") return httpRequestAsync(input)
        assertMain()
        validateCommand(input)
        val profile = profileForRequest(input)
        val normalized = JSONObject(input.toString())
        if (input.optString("action") == "scroll" && !input.has("y")) {
            val sign = if (input.optString("direction", "down") == "up") -1 else 1
            normalized.put("y", sign * input.optDouble("pixels", 600.0))
            normalized.remove("direction"); normalized.remove("pixels")
        }
        val writes = setOf("pointer_path", "key_sequence", "click", "type", "scroll", "scrollTo", "select", "press", "keyboard", "key",
            "click_point", "drag", "swipe", "focus", "clear", "toggle")
        if (profile in manualProfiles && input.optString("action") in writes)
            return CompletableFuture.failedFuture(BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active"))
        if (profile in activeInputProfiles && input.optString("action") in writes)
            return CompletableFuture.failedFuture(BrowserOperationException("BUSY", "Native input sequence in progress"))
        val session = currentSession(profile)
        activateForWork(session)
        if (input.optString("action") == "network_observe") return networkObserveAsync(input, profile)
        if (input.optString("action") == "wait" && input.optString("condition") !in setOf("", "selector", "text")) return automationWaitAsync(input, profile, session)
        if (input.optString("action") == "readonly_probe") automationAudit.record("readonly_probe", profile, selectedTab(profile), identity(session).documentId.orEmpty(), outcome = "requested", private = isPrivate(profile))
        val generation = screenshotGeneration[profile] ?: 0L
        val ready = awaitBridge(profile, session) {
            profileForRequest(input)
            if ((screenshotGeneration[profile] ?: 0L) != generation)
                throw BrowserOperationException("PAGE_CHANGED", "Page changed while waiting for the bridge; refresh the snapshot")
        }
        val result = CompletableFuture<JSONObject>()
        var dispatched: CompletableFuture<JSONObject>? = null
        ready.whenComplete { _, error ->
            if (!result.isDone) {
                if (error != null) result.completeExceptionally(error)
                else try {
                    profileForRequest(input)
                    dispatched = commandReadyAsync(normalized, profile, session)
                    dispatched!!.whenComplete { reply, failure ->
                        if (failure != null) result.completeExceptionally(failure) else result.complete(reply)
                    }
                } catch (failure: Exception) { result.completeExceptionally(failure) }
            }
        }
        result.whenComplete { _, _ -> if (result.isCancelled) {
            ready.cancel(false); dispatched?.cancel(false)
        } }
        return result
    }

    /** Waits only before dispatch. Never retries a click/type or replays an old response. */
    private fun validateHttpRequest(input: JSONObject) {
        val values = input.keys().asSequence().associateWith { key ->
            val value = input.opt(key)
            if (value is JSONObject) value.keys().asSequence().associateWith { value.opt(it) } else value
        }
        HttpRequestSpec.validate(values)
    }
    fun httpRequestAsync(input: JSONObject, fromUser: Boolean = false): CompletableFuture<JSONObject> {
        assertMain(); validateHttpRequest(input)
        val profile = profileForRequest(input)
        val method = input.optString("method", "GET").uppercase()
        if (!fromUser && profile in manualProfiles && method !in setOf("GET", "HEAD"))
            return CompletableFuture.failedFuture(BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active"))
        val url = input.getString("url")
        var loaded = selectedLoadedSession(profile)
        val pageUrl = loaded?.let { identity(it).url } ?: status(profile).optString("url")
        val requestedTransport = input.optString("transport", "auto")
        val usePage = requestedTransport == "page" || (requestedTransport == "auto" && HttpRequestSpec.sameOrigin(pageUrl, url))
        if (!usePage) {
            require(input.optString("credentials", "omit") == "omit") { "Native HTTP cannot inherit profile cookies; navigate the profile to the target origin and use page transport" }
            if (proxyConfigurations.has(profile)) return CompletableFuture.failedFuture(BrowserOperationException("PROXY_SCOPE_UNSUPPORTED", "Configured profile proxy requires same-origin page transport; anonymous native HTTP is not routed by profile"))
            return http.native(input, profile)
        }
        if (loaded == null) loaded = currentSession(profile)
        val pageSession = loaded
        require(pageSession != null && HttpRequestSpec.sameOrigin(pageUrl, url)) { "Page transport requires a loaded page on the target origin; navigate this profile normally first" }
        activateForWork(pageSession)
        val result = CompletableFuture<JSONObject>()
        val ready = awaitBridge(profile, pageSession) { if (selectedLoadedSession(profile) !== pageSession || (bridgeReady(pageSession) && !HttpRequestSpec.sameOrigin(identity(pageSession).url.orEmpty(), url)))
            throw BrowserOperationException("PAGE_CHANGED", "HTTP document changed") }
        var job: BrowserHttp.Job? = null
        var dispatched: CompletableFuture<JSONObject>? = null
        ready.whenComplete { _, error ->
            if (result.isDone) return@whenComplete
            if (error != null) result.completeExceptionally(error)
            else try {
                profileForRequest(input)
                if (!fromUser && profile in manualProfiles && method !in setOf("GET", "HEAD")) throw BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active")
                val created = http.create(input, profile, pageSession, "page"); job = created
                val port = ports[pageSession] ?: error("HTTP page bridge disconnected")
                created.cancel = { ui.post { if (ports[pageSession] === port && !identity(pageSession).navigating && bridgeConnections.writable(port)) runCatching { postBridge(port, JSONObject().put("cancelHttpStream", created.id)) } } }
                val request = JSONObject(input.toString()).put("httpStreamId", created.id)
                dispatched = dispatchPage(profile, pageSession, port, request, "main", identity(pageSession).documentId!!)
                dispatched!!.whenComplete { reply, failure ->
                    if (failure != null) http.finish(created, failure)
                    else if (!created.streaming) {
                        val payload = reply.optJSONObject("result")
                        if (payload == null || reply.optBoolean("ok") != true) http.finish(created, BrowserOperationException("HTTP_NETWORK_ERROR", "HTTP page request failed"))
                        else { val bytes = android.util.Base64.decode(payload.optString("body"), android.util.Base64.DEFAULT)
                            payload.remove("body"); payload.remove("bodyEncoding"); http.headers(created, payload); http.finish(created, body = bytes) }
                    }
                }
                created.reply.whenComplete { reply, failure -> if (failure != null) result.completeExceptionally(failure) else result.complete(reply) }
            } catch (failure: Exception) { job?.let { http.finish(it, failure) }; result.completeExceptionally(failure) }
        }
        result.whenComplete { _, _ -> if (result.isCancelled) { ready.cancel(false); dispatched?.cancel(false); job?.let { http.finish(it, BrowserOperationException("HTTP_CANCELLED", "HTTP caller cancelled")) } } }
        return result
    }

    private fun awaitBridge(profile: String, session: GeckoSession, guard: () -> Unit): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val deadline = SystemClock.elapsedRealtime() + 3500L
        var nextDelegateRetry = SystemClock.elapsedRealtime()
        preparingSessions[session] = (preparingSessions[session] ?: 0) + 1
        val task = object : Runnable {
            override fun run() {
                if (result.isDone) return
                try {
                    if (selectedLoadedSession(profile) !== session)
                        throw BrowserOperationException("PAGE_CHANGED", "Selected tab changed while waiting for the bridge")
                    guard()
                    if (bridgeReady(session)) result.complete(Unit)
                    else if (SystemClock.elapsedRealtime() >= deadline) {
                        bridgeLastErrors[session] = "Bridge did not connect after delegate retries"
                        result.completeExceptionally(BrowserOperationException("BRIDGE_NOT_READY", "Bridge did not become ready within 3.5 seconds after delegate retries; inspect status and request a fresh snapshot"))
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (now >= nextDelegateRetry) {
                            bridgeStartAttempts[session] = (bridgeStartAttempts[session] ?: 0) + 1
                            try {
                                installSessionDelegate(session)
                                bridgeLastErrors.remove(session)
                            } catch (error: Exception) {
                                bridgeLastErrors[session] = error.message ?: error.javaClass.simpleName
                            }
                            nextDelegateRetry = now + 640L
                        }
                        ui.postDelayed(this, 80L)
                    }
                } catch (error: Exception) {
                    bridgeLastErrors[session] = error.message ?: error.javaClass.simpleName
                    result.completeExceptionally(error)
                }
            }
        }
        result.whenComplete { _, _ ->
            ui.post {
                ui.removeCallbacks(task)
                val remaining = (preparingSessions[session] ?: 1) - 1
                if (remaining <= 0) preparingSessions.remove(session) else preparingSessions[session] = remaining
                pruneSessions(); pruneTabSessions()
            }
        }
        task.run()
        return result
    }

    private fun commandReadyAsync(input: JSONObject, profile: String, session: GeckoSession): CompletableFuture<JSONObject> {
        val action = input.optString("action")
        val writes = setOf("pointer_path", "key_sequence", "click", "type", "scroll", "scrollTo", "select", "press", "keyboard", "key",
            "click_point", "drag", "swipe", "focus", "clear", "toggle")
        if (profile in manualProfiles && action in writes)
            return CompletableFuture.failedFuture(BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active"))
        if (action == "pointer_path") return pointerPathAsync(input, profile)
        if (action == "key_sequence") return keySequenceAsync(input, profile, session)
        if (action == "click_point") return clickPointAsync(input, profile)
        if (action in setOf("drag", "swipe")) return dragAsync(input, profile)
        if (action !in setOf("geometry", "readonly_probe", "snapshot", "dom", "find", "click", "type", "scroll", "scrollTo", "frames",
            "status", "cookie_metadata", "storage_inspect", "devtools", "inspect_element", "compatibility", "read", "wait",
            "select", "press", "keyboard", "key", "focus", "clear", "toggle", "links"))
            return CompletableFuture.failedFuture(IllegalArgumentException("Unsupported action"))
        if (!bridgeReady(session)) return CompletableFuture.failedFuture(BrowserOperationException("BRIDGE_NOT_READY",
            "Document is loading or bridge is reconnecting. Inspect status, then request a fresh snapshot."))
        val pageId = identity(session).documentId!!
        val frame = input.optString("frame", "main")
        val child = if (frame == "main") null else (framePorts[session]?.get(frame)
            ?: return CompletableFuture.failedFuture(BrowserOperationException("FRAME_NOT_FOUND", "Frame document was removed; refresh the snapshot")))
        val port = child?.port ?: ports[session]!!
        val frameDocument = child?.documentId ?: pageId
        fun stillCurrent() = selectedLoadedSession(profile) === session && bridgeReady(session) && identity(session).documentId == pageId
        if (action == "frames") return CompletableFuture.completedFuture(JSONObject().put("ok", true)
            .put("result", JSONObject().put("documentId", pageId).put("frames", frameMetadata(session))))
        if (action == "key") {
            val prepared = if (input.has("target") || input.has("selector")) dispatchPage(profile, session, port,
                JSONObject(input.toString()).put("action", "focus"), frame, frameDocument)
                else CompletableFuture.completedFuture(JSONObject().put("ok", true))
            return prepared.thenApply { reply ->
                if (!reply.optBoolean("ok")) reply else {
                    if (!stillCurrent()) throw BrowserOperationException("PAGE_CHANGED", "Page changed before native keyboard input")
                    nativeKey(session, input).also { it.getJSONObject("result").put("profile", profile).put("tab", selectedTab(profile)).put("documentId", identity(session).documentId).put("url", identity(session).url).put("siteCompleted", false) }
                }
            }
        }
        val top = dispatchPage(profile, session, port, input, frame, frameDocument)
        val children = if (frame == "main" && input.optBoolean("includeFrames", true) &&
            action in setOf("snapshot", "read", "dom", "links", "compatibility"))
            framePorts[session]?.toList().orEmpty().map { (id, bridge) ->
                id to dispatchPage(profile, session, bridge.port, input, id, bridge.documentId)
                    .handle { value, error -> value ?: JSONObject().put("ok", false)
                        .put("code", "FRAME_UNAVAILABLE").put("error", "Frame changed or did not respond") }
            } else emptyList()
        val result = CompletableFuture<JSONObject>()
        CompletableFuture.allOf(top, *children.map { it.second }.toTypedArray()).whenComplete { _, error ->
            if (error != null) result.completeExceptionally(error)
            else if (!stillCurrent() && action !in writes) result.completeExceptionally(
                BrowserOperationException("PAGE_CHANGED", "Document or tab changed while reading; refresh the snapshot"))
            else {
                val reply = top.getNow(null)
                reply.optJSONObject("result")?.let { data ->
                    data.put("profile", profile).put("tab", if (popupSessions[profile] === session) "popup" else selectedTab(profile))
                        .put("frame", frame).put("pageDocumentId", pageId)
                    if (action in writes) data.put("dispatched", true).put("siteCompleted", false)
                        .put("sourceDocumentId", pageId).put("afterDocumentId", identity(session).documentId ?: JSONObject.NULL)
                        .put("afterUrl", identity(session).url ?: JSONObject.NULL)
                    val frames = frameMetadata(session)
                    for ((id, value) in children) {
                        val item = (0 until frames.length()).map { frames.getJSONObject(it) }.firstOrNull { it.optString("frame") == id }
                        if (item != null) item.put("response", value.getNow(null))
                    }
                    if (frame == "main") data.put("frames", frames)
                }
                result.complete(reply)
            }
        }
        result.whenComplete { _, _ -> if (result.isCancelled) {
            top.cancel(false); children.forEach { it.second.cancel(false) }
        } }
        return result
    }

    private fun frameMetadata(session: GeckoSession) = JSONArray().apply {
        framePorts[session]?.forEach { (id, frame) ->
            put(JSONObject().put("frame", id).put("documentId", frame.documentId).put("url", frame.url))
        }
    }

    private fun persistProxyIdentities() {
        val saved = JSONObject(cookieStoreIdentities.toString())
        privateProfiles.forEach { saved.remove(it) }
        proxyPrefs.edit().putString("cookie_stores", saved.toString()).apply()
    }
    private fun proxyPolicies(): JSONArray = JSONArray().apply {
        profileUrls.keys.forEach { profile ->
            val config = proxyConfigurations.optJSONObject(profile)
            val policy = if (config != null) JSONObject(config.toString()) else JSONObject().put("type", "direct")
            if (!proxyInfo(profile).optBoolean("contextUnique") && config != null) policy.put("type", "unavailable")
            // GeckoView tabs use the context supplied to GeckoSessionSettings.
            // Seed it before a new context's first document, and retain the
            // observed alias for runtimes that transform its public identifier.
            listOfNotNull(profileContexts[profile], cookieStoreIdentities.optString(profile).takeIf { it.isNotBlank() })
                .distinct().forEach { storeId -> put(JSONObject(policy.toString()).put("cookieStoreId", storeId)) }
        }
    }
    fun proxyInfo(profile: String = activeProfile): JSONObject {
        require(profile in profileUrls) { "Unknown profile" }
        val config = proxyConfigurations.optJSONObject(profile)
        val context = profileContexts[profile] ?: profile
        val observed = cookieStoreIdentities.optString(profile).takeIf { it.isNotBlank() }
        val unique = profileUrls.keys.filter { it != profile }.none { other ->
            (profileContexts[other] ?: other) == context || (observed != null && cookieStoreIdentities.optString(other) == observed)
        }
        return JSONObject().put("profile", profile).put("mode", if (config == null) "direct" else "per_profile")
            .put("server", config?.optString("server") ?: JSONObject.NULL).put("hasCredentials", !config?.optString("username").isNullOrEmpty())
            .put("supported", extensions.proxyState.optBoolean("supported")).put("initialized", extensions.proxyState.optBoolean("initialized"))
            .put("identityKnown", observed != null).put("identityVerified", observed != null && unique).put("contextUnique", unique).put("routingIdentity", "tabs_cookie_store").put("directFallback", false)
            .put("nativeAnonymousHttpUsesProxy", false).put("transportScope", "Gecko_web_requests")
    }
    fun setProxyAsync(input: JSONObject): CompletableFuture<JSONObject> {
        val profile = profileForRequest(input)
        if (profile in manualProfiles) throw BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active")
        if (input.optBoolean("enabled", true) && !proxyInfo(profile).optBoolean("contextUnique"))
            throw BrowserOperationException("NOT_IMPLEMENTED", "Gecko did not expose a unique cookieStoreId for this container; refusing an ambiguous proxy route")
        if (proxyChangeInFlight) throw BrowserOperationException("BUSY", "Proxy policy update in progress")
        val config = if (input.optBoolean("enabled", true)) {
            val spec = ProxySpec.parse(input.getString("server"), input.optString("username"), input.optString("password"))
            JSONObject().put("server", spec.server).put("type", spec.type).put("host", spec.host).put("port", spec.port)
                .put("username", spec.username).put("password", spec.password).put("revision", UUID.randomUUID().toString())
        } else null
        val old = proxyConfigurations.optJSONObject(profile)
        if (config == null) proxyConfigurations.remove(profile) else proxyConfigurations.put(profile, config)
        val policies = proxyPolicies()
        // Keep the old config on broker failure; never pretend an unapplied route succeeded.
        val result = CompletableFuture<JSONObject>()
        proxyChangeInFlight = true
        extensions.requestTools(JSONObject().put("command", "proxy").put("policies", policies)).whenComplete { reply, error ->
            proxyChangeInFlight = false
            if (error != null) {
                if (old == null) proxyConfigurations.remove(profile) else proxyConfigurations.put(profile, old)
                result.completeExceptionally(error)
            } else {
                extensions.proxyPolicies = policies
                val saved = JSONObject(proxyConfigurations.toString())
                privateProfiles.forEach { saved.remove(it) }
                proxyPrefs.edit().putString("configurations", saved.toString()).commit()
                automationAudit.record("proxy_change", profile, selectedTab(profile), private = isPrivate(profile))
                result.complete(proxyInfo(profile).put("dispatched", true).put("verifiedConnectivity", false))
            }
        }
        return result
    }
    fun finishMediaPermission(callback: GeckoSession.PermissionDelegate.MediaCallback,
        video: GeckoSession.PermissionDelegate.MediaSource?, audio: GeckoSession.PermissionDelegate.MediaSource?, allow: Boolean, fromUser: Boolean) {
        val id = mediaInteractionIds.remove(callback) ?: return
        val pending = pendingInteractions.remove(id)
        if (allow) callback.grant(video, audio) else callback.reject()
        pending?.let { sitePromptHost?.dismissResolvedPermissions(it.session)
            val profile = it.scope.optString("profile")
            automationAudit.record("media_permission", profile, it.scope.optString("tab"), it.scope.optString("documentId"),
                actor = if (fromUser) "ui" else "api", outcome = if (allow) "allow" else "deny", private = isPrivate(profile)) }
    }
    fun presentPendingInteractions() {
        promptBroker.present { session, prompt, reply -> if (isVisibleSession(session)) sitePromptHost?.showSitePrompt(session, prompt, reply) }
        pendingInteractions.values.toList().forEach { if (isVisibleSession(it.session)) it.present() }
    }
    fun pendingPrompts(profile: String = activeProfile): JSONObject {
        val result = promptBroker.list(profile)
        pendingInteractions.values.filter { it.scope.optString("profile") == profile }.forEach { result.getJSONArray("prompts").put(JSONObject(it.scope.toString())) }
        return result
    }
    fun resolvePromptAsync(input: JSONObject): CompletableFuture<JSONObject> {
        val profile = profileForRequest(input)
        if (profile in manualProfiles) throw BrowserOperationException("MANUAL_TAKEOVER", "Return control before API prompt input")
        if (pendingInteractions.containsKey(input.optString("id"))) return CompletableFuture.completedFuture(resolvePrompt(input))
        return promptBroker.resolveAsync(input) {
            profileForRequest(input)
            if (profile in manualProfiles) throw BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active")
        }
    }
    private fun resolvePrompt(input: JSONObject): JSONObject {
        val profile = profileForRequest(input)
        if (profile in manualProfiles) throw BrowserOperationException("MANUAL_TAKEOVER", "Return control before API prompt input")
        val pending = pendingInteractions[input.optString("id")]
        if (pending != null) {
            val now = automationScope(pending.session)
            for (key in listOf("profile", "tab", "scopeToken")) if (input.optString(key) != pending.scope.optString(key))
                throw BrowserOperationException("PAGE_CHANGED", "Interaction scope mismatch")
            for (key in listOf("profile", "tab", "documentId", "url")) if (now.opt(key)?.toString() != pending.scope.opt(key)?.toString())
                throw BrowserOperationException("PAGE_CHANGED", "Interaction document changed")
            require(input.optString("decision") in setOf("accept", "dismiss")) { "decision must be accept/dismiss" }
            val result = pending.resolve(input)
            pendingInteractions.remove(input.getString("id"))
            automationAudit.record("interaction_" + pending.scope.optString("kind"), profile, pending.scope.optString("tab"), pending.scope.optString("documentId"), private = isPrivate(profile))
            return result.put("dispatched", true).put("siteCompleted", false).put("profile", profile).put("tab", pending.scope.optString("tab"))
        }
        return promptBroker.resolve(input)
    }
    fun importAutomationFile(input: JSONObject): JSONObject {
        val name = input.getString("name")
        require(name.length in 1..100 && name.none { it < ' ' || it == '/' || it == '\\' } && name !in setOf(".", "..")) { "Invalid file name" }
        val bytes = android.util.Base64.decode(input.getString("dataBase64"), android.util.Base64.DEFAULT)
        require(bytes.size <= 65536) { "File import limit is 64 KiB; use an existing readable content URI for larger files" }
        val directory = java.io.File(app.filesDir, "automation_uploads").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3600000L }?.forEach { it.delete() }
        if ((directory.listFiles()?.size ?: 0) >= 16) throw BrowserOperationException("BUSY", "File import capacity reached")
        val file = java.io.File(directory, UUID.randomUUID().toString() + "-" + name)
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".downloads", file, name)
        return JSONObject().put("uri", uri.toString()).put("bytes", bytes.size).put("expiresAfterMs", 3600000L)
    }
    fun downloadAction(input: JSONObject): JSONObject {
        val profile = profileForRequest(input)
        if (profile in manualProfiles && input.optString("operation") != "export") throw BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active")
        val id = input.getString("id")
        require(downloads.list(profile).any { it.id == id }) { "Download does not belong to profile" }
        if (input.getString("operation") == "export") {
            val file = downloads.savedFile(id)
            val offset = input.optLong("offset", 0); val maximum = input.optInt("maxBytes", 65536)
            require(offset in 0L..file.length() && maximum in 1..65536) { "Invalid download export range" }
            val data = java.io.RandomAccessFile(file, "r").use { reader ->
                reader.seek(offset); val bytes = ByteArray(minOf(maximum.toLong(), file.length() - offset).toInt())
                reader.readFully(bytes); bytes
            }
            automationAudit.record("download_export", profile, private = isPrivate(profile))
            return JSONObject().put("profile", profile).put("id", id).put("offset", offset).put("totalBytes", file.length())
                .put("nextOffset", offset + data.size).put("done", offset + data.size >= file.length())
                .put("dataBase64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
        }
        when (input.getString("operation")) { "cancel" -> downloads.cancel(id); "remove" -> downloads.remove(id); else -> throw IllegalArgumentException("Unsupported download action") }
        automationAudit.record("download_" + input.getString("operation"), profile, private = isPrivate(profile))
        return JSONObject().put("dispatched", true).put("profile", profile).put("id", id)
    }
    fun storageExportAsync(input: JSONObject): CompletableFuture<JSONObject> {
        val profile = profileForRequest(input)
        require(input.has("tab") && input.has("documentId") && input.optBoolean("consented")) { "Explicit consent, tab and documentId required" }
        automationAudit.record("storage_debug_export", profile, input.getString("tab"), input.getString("documentId"), private = isPrivate(profile))
        return commandAsync(JSONObject().put("action", "storage_inspect").put("profile", profile).put("tab", input.getString("tab"))
            .put("documentId", input.getString("documentId"))).thenApply { it.put("redacted", true).put("rawCredentialsIncluded", false) }
    }
    fun auditEvents(profile: String?) = automationAudit.read(profile)
    fun networkObserveAsync(input: JSONObject, profile: String = profileForRequest(input)): CompletableFuture<JSONObject> {
        assertMain()
        val session = selectedLoadedSession(profile) ?: return CompletableFuture.failedFuture(BrowserOperationException("BRIDGE_NOT_READY", "No loaded tab to observe"))
        val tab = if (popupSessions[profile] === session) "popup" else selectedTab(profile)
        val future = CompletableFuture<JSONObject>(); val deadline = SystemClock.elapsedRealtime() + 3500L
        val task = object : Runnable {
            override fun run() {
                if (future.isDone) return
                if (selectedLoadedSession(profile) !== session) { future.completeExceptionally(BrowserOperationException("PAGE_CHANGED", "Observed tab changed")); return }
                val id = browserTabIds[session]
                if (id != null) extensions.requestTools(JSONObject(input.toString()).put("command", "network").put("tabId", id))
                    .whenComplete { value, error -> if (error != null) future.completeExceptionally(error) else future.complete(value.put("profile", profile).put("tab", tab)) }
                else if (SystemClock.elapsedRealtime() >= deadline) future.completeExceptionally(BrowserOperationException("BRIDGE_NOT_READY", "Browser tab identity not connected"))
                else ui.postDelayed(this, 50L)
            }
        }
        future.whenComplete { _, _ -> ui.post { ui.removeCallbacks(task) } }
        task.run(); return future
    }
    private fun automationWaitAsync(input: JSONObject, profile: String, session: GeckoSession): CompletableFuture<JSONObject> {
        val condition = input.getString("condition")
        val timeout = input.optLong("timeoutMs", 10000L).coerceIn(100L, 20000L)
        val duration = input.optLong("durationMs", 0L)
        require(duration in 0..20000L && (condition != "delay" || duration <= timeout)) { "Invalid wait duration" }
        if (condition == "frame_ready") require(input.has("frame") && input.has("expectedFrameUrl")) { "frame and expectedFrameUrl required" }
        val future = CompletableFuture<JSONObject>()
        val start = SystemClock.elapsedRealtime()
        preparingSessions[session] = (preparingSessions[session] ?: 0) + 1
        val originalDocument = identity(session).documentId
        var networkPending = false
        fun finish() { future.complete(JSONObject().put("ok", true).put("result", JSONObject().put("matched", true)
            .put("condition", condition).put("profile", profile).put("tab", selectedTab(profile))
            .put("documentId", identity(session).documentId ?: JSONObject.NULL).put("url", identity(session).url ?: JSONObject.NULL)
            .put("elapsedMs", SystemClock.elapsedRealtime() - start))) }
        val task = object : Runnable {
            override fun run() {
                if (future.isDone) return
                try {
                    if (selectedLoadedSession(profile) !== session) throw BrowserOperationException("PAGE_CHANGED", "Wait's tab was changed or closed")
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val id = identity(session)
                    if (condition !in setOf("document_changed", "url_changed", "load", "delay") && originalDocument != id.documentId)
                        throw BrowserOperationException("PAGE_CHANGED", "Document changed during wait")
                    if (input.has("expectedUrl") && condition != "url_changed" && id.url != input.getString("expectedUrl"))
                        throw BrowserOperationException("PAGE_CHANGED", "URL changed during wait")
                    val matched = when (condition) {
                        "delay" -> elapsed >= duration
                        "document_changed" -> bridgeReady(session) && id.documentId != input.getString("previousDocumentId")
                        "url_changed" -> bridgeReady(session) && id.url != input.getString("previousUrl")
                        "load" -> bridgeReady(session)
                        "frame_ready" -> framePorts[session]?.get(input.getString("frame"))?.let { it.url == input.getString("expectedFrameUrl") && bridgeConnections.writable(it.port) } == true
                        "network_idle" -> false
                        else -> throw BrowserOperationException("BAD_ARGUMENTS", "Unknown wait condition")
                    }
                    if (matched) { finish(); return }
                    if (elapsed >= timeout) throw BrowserOperationException("WAIT_TIMEOUT", "Wait condition did not match within timeoutMs")
                    if (condition == "network_idle" && !networkPending) {
                        networkPending = true
                        networkObserveAsync(JSONObject().put("profile", profile), profile).whenComplete { state, error ->
                            networkPending = false
                            if (!future.isDone) {
                                if (error != null) future.completeExceptionally(error)
                                else if (!state.optBoolean("recording")) future.completeExceptionally(BrowserOperationException("OBSERVATION_DISABLED", "Enable network_observe before network_idle"))
                                else if (selectedLoadedSession(profile) === session && identity(session).documentId == originalDocument &&
                                    state.optInt("pendingRequests") == 0 && System.currentTimeMillis() - state.optLong("lastActivityMs", Long.MAX_VALUE) >= input.optLong("idleMs", 500)) finish()
                            }
                        }
                    }
                    ui.postDelayed(this, 50L)
                } catch (error: Exception) { future.completeExceptionally(error) }
            }
        }
        future.whenComplete { _, _ -> ui.post {
            ui.removeCallbacks(task)
            val n = (preparingSessions[session] ?: 1) - 1
            if (n <= 0) preparingSessions.remove(session) else preparingSessions[session] = n
            pruneSessions(); pruneTabSessions()
        } }
        task.run()
        return future
    }
    private fun inputGuard(input: JSONObject, profile: String, session: GeckoSession, pageId: String?, generation: Long) {
        if (profile in manualProfiles) throw BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active")
        profileForRequest(input)
        if (selectedLoadedSession(profile) !== session || identity(session).documentId != pageId || identity(session).navigating || surfaceGeneration != generation)
            throw BrowserOperationException("PAGE_CHANGED", "Document or surface changed during native input")
    }
    private fun pointerPathAsync(input: JSONObject, profile: String): CompletableFuture<JSONObject> {
        require(profile == activeProfile && input.optString("frame", "main") == "main") { "Native pointer uses the active rendered viewport" }
        val session = currentSession(profile)
        val host = backgroundHost?.takeIf { it.session === session && it.attached }
        val foreground = view?.takeIf { it.session === session && it.isAttachedToWindow }
        if (host == null && foreground == null) throw BrowserOperationException("SCREENSHOT_SURFACE_UNAVAILABLE", "No native input surface; bring Abwr to the foreground")
        val width = host?.width ?: foreground!!.width; val height = host?.height ?: foreground!!.height
        var scaleX = 1.0; var scaleY = 1.0
        if (input.has("imageWidth") || input.has("imageHeight")) {
            val iw = input.getInt("imageWidth"); val ih = input.getInt("imageHeight")
            val shot = lastShots[profile]
            require(iw > 0 && ih > 0 && shot?.width == iw && shot.height == ih && shot.documentId == identity(session).documentId) { "Screenshot dimensions/document changed" }
            scaleX = width.toDouble() / iw; scaleY = height.toDouble() / ih
        }
        val raw = input.getJSONArray("steps")
        val steps = (0 until raw.length()).map { val step = raw.getJSONObject(it)
            PointerStep(step.getString("kind"), step.getDouble("x") * scaleX, step.getDouble("y") * scaleY, step.getLong("atMs")) }
        PointerPath.validate(steps, width, height)
        if (!activeInputProfiles.add(profile)) throw BrowserOperationException("BUSY", "Native input already active")
        inFlightByProfile[profile] = (inFlightByProfile[profile] ?: 0) + 1
        val future = CompletableFuture<JSONObject>(); val start = SystemClock.uptimeMillis()
        val pageId = identity(session).documentId; val generation = surfaceGeneration
        var index = 0; var downTime = start; var pressed = false; var last = steps.first()
        val timings = JSONArray()
        fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, last.x.toFloat(), last.y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { session.panZoomController.onTouchEvent(event) } finally { event.recycle() }
        }
        val task = object : Runnable {
            override fun run() {
                if (future.isDone) return
                try {
                    inputGuard(input, profile, session, pageId, generation)
                    last = steps[index]
                    when (last.kind) {
                        "down" -> { downTime = SystemClock.uptimeMillis(); pressed = true; send(MotionEvent.ACTION_DOWN) }
                        "up" -> { send(MotionEvent.ACTION_UP); pressed = false }
                        "move" -> if (pressed) send(MotionEvent.ACTION_MOVE)
                    }
                    timings.put(JSONObject().put("kind", last.kind).put("scheduledAtMs", last.atMs).put("actualAtMs", SystemClock.uptimeMillis() - start))
                    index++
                    if (index == steps.size) future.complete(JSONObject().put("ok", true).put("result", JSONObject().put("dispatched", true)
                        .put("siteCompleted", false).put("native", true).put("profile", profile).put("tab", selectedTab(profile))
                        .put("sourceDocumentId", pageId).put("documentId", identity(session).documentId ?: JSONObject.NULL).put("url", identity(session).url ?: JSONObject.NULL)
                        .put("steps", timings).put("pointerType", "touch").put("preDownMove", "position_only")))
                    else ui.postDelayed(this, maxOf(0L, start + steps[index].atMs - SystemClock.uptimeMillis()))
                } catch (error: Exception) { if (pressed) runCatching { send(MotionEvent.ACTION_CANCEL) }; pressed = false; future.completeExceptionally(error) }
            }
        }
        future.whenComplete { _, _ -> ui.post { ui.removeCallbacks(task); if (pressed) runCatching { send(MotionEvent.ACTION_CANCEL) }; activeInputProfiles.remove(profile); releaseInFlight(profile) } }
        ui.postDelayed(task, steps.first().atMs)
        return future
    }
    private fun keySequenceAsync(input: JSONObject, profile: String, session: GeckoSession): CompletableFuture<JSONObject> {
        val names = input.getJSONArray("keys")
        val keys = (0 until names.length()).map { names.getString(it) }
        val bases = setOf("Enter", "Tab", "Escape", "Backspace", "Delete", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Home", "End", "PageUp", "PageDown", "Space")
        keys.forEach { key -> val parts = key.split('+'); require(parts.last() in bases || parts.last().matches(Regex("[a-zA-Z0-9]"))) { "Unsupported key" }
            require(parts.dropLast(1).all { it.lowercase() in setOf("control", "ctrl", "alt", "shift", "meta") }) { "Unsupported modifier" } }
        if (!activeInputProfiles.add(profile)) throw BrowserOperationException("BUSY", "Native input already active")
        inFlightByProfile[profile] = (inFlightByProfile[profile] ?: 0) + 1
        val future = CompletableFuture<JSONObject>(); val pageId = identity(session).documentId; val generation = surfaceGeneration
        val start = SystemClock.uptimeMillis(); val interval = input.optLong("intervalMs", 50); var index = 0
        val task = object : Runnable {
            override fun run() {
                if (future.isDone) return
                try {
                    inputGuard(input, profile, session, pageId, generation)
                    nativeKey(session, JSONObject().put("key", keys[index++]));
                    if (index == keys.size) future.complete(JSONObject().put("ok", true).put("result", JSONObject().put("dispatched", true).put("siteCompleted", false)
                        .put("keyCount", index).put("elapsedMs", SystemClock.uptimeMillis() - start).put("profile", profile).put("tab", selectedTab(profile))
                        .put("documentId", identity(session).documentId ?: JSONObject.NULL).put("url", identity(session).url ?: JSONObject.NULL)))
                    else ui.postDelayed(this, interval)
                } catch (error: Exception) { future.completeExceptionally(error) }
            }
        }
        future.whenComplete { _, _ -> ui.post { ui.removeCallbacks(task); activeInputProfiles.remove(profile); releaseInFlight(profile) } }
        task.run(); return future
    }

    private fun nativeKey(session: GeckoSession, input: JSONObject): JSONObject {
        val parts = input.getString("key").split('+')
        val base = parts.last()
        val modifiers = parts.dropLast(1).map { it.lowercase() }.toSet()
        require(modifiers.all { it in setOf("control", "ctrl", "alt", "shift", "meta") }) { "Unsupported key modifier" }
        val codes = mapOf("Enter" to KeyEvent.KEYCODE_ENTER, "Tab" to KeyEvent.KEYCODE_TAB,
            "Escape" to KeyEvent.KEYCODE_ESCAPE, "Backspace" to KeyEvent.KEYCODE_DEL, "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "ArrowUp" to KeyEvent.KEYCODE_DPAD_UP, "ArrowDown" to KeyEvent.KEYCODE_DPAD_DOWN,
            "ArrowLeft" to KeyEvent.KEYCODE_DPAD_LEFT, "ArrowRight" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
            "PageUp" to KeyEvent.KEYCODE_PAGE_UP, "PageDown" to KeyEvent.KEYCODE_PAGE_DOWN,
            "Space" to KeyEvent.KEYCODE_SPACE)
        val code = codes[base] ?: if (base.matches(Regex("[a-zA-Z0-9]")))
            KeyEvent.keyCodeFromString("KEYCODE_" + base.uppercase()) else KeyEvent.KEYCODE_UNKNOWN
        require(code != KeyEvent.KEYCODE_UNKNOWN) { "Unsupported keyboard key" }
        val meta = (if (base.matches(Regex("[A-Z]")) && modifiers.none { it in setOf("ctrl", "control", "alt", "meta") }) KeyEvent.META_SHIFT_ON else 0) or
            (if ("ctrl" in modifiers || "control" in modifiers) KeyEvent.META_CTRL_ON else 0) or
            (if ("alt" in modifiers) KeyEvent.META_ALT_ON else 0) or
            (if ("shift" in modifiers) KeyEvent.META_SHIFT_ON else 0) or
            (if ("meta" in modifiers) KeyEvent.META_META_ON else 0)
        val now = SystemClock.uptimeMillis()
        val down = session.textInput.onKeyDown(code, KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        val up = session.textInput.onKeyUp(code, KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        return JSONObject().put("ok", true).put("result", JSONObject().put("key", input.getString("key"))
            .put("dispatched", true).put("native", true).put("downHandled", down).put("upHandled", up)
            .put("note", "Input was dispatched to Gecko; inspect the resulting page to confirm the website action."))
    }
    private fun dragAsync(input: JSONObject, profile: String): CompletableFuture<JSONObject> {
        require(profile == activeProfile && input.optString("frame", "main") == "main") { "Native gestures require the active rendered viewport" }
        val session = currentSession(profile)
        val host = backgroundHost
        val foreground = view
        check((host?.session === session && host.attached) || (foreground?.session === session && foreground.isAttachedToWindow)) { "No ready browser surface" }
        val gesture = NativeGesture(input.getDouble("fromX"), input.getDouble("fromY"), input.getDouble("toX"), input.getDouble("toY"), input.optLong("durationMs", 500))
        gesture.validate(host?.width ?: foreground!!.width, host?.height ?: foreground!!.height)
        val result = CompletableFuture<JSONObject>()
        val generation = surfaceGeneration
        val pageId = identity(session).documentId
        val start = SystemClock.uptimeMillis()
        var last = gesture.point(0)
        fun send(action: Int) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, last.first, last.second, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { session.panZoomController.onTouchEvent(event) } finally { event.recycle() }
        }
        val task = object : Runnable {
            override fun run() {
                if (result.isDone) return
                try {
                    if (profile in manualProfiles) throw BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active")
                    profileForRequest(input)
                    if (generation != surfaceGeneration || selectedLoadedSession(profile) !== session || identity(session).documentId != pageId)
                        throw BrowserOperationException("PAGE_CHANGED", "Document or surface changed during gesture")
                    val elapsed = SystemClock.uptimeMillis() - start
                    last = gesture.point(elapsed)
                    if (elapsed >= gesture.durationMs) {
                        send(MotionEvent.ACTION_UP)
                        result.complete(JSONObject().put("ok", true).put("result", JSONObject().put("native", true).put("dispatched", true)
                            .put("documentId", identity(session).documentId).put("url", identity(session).url).put("profile", profile).put("tab", selectedTab(profile)).put("siteCompleted", false).put("durationMs", elapsed).put("note", "Inspect the resulting page to confirm the action")))
                    } else { send(MotionEvent.ACTION_MOVE); ui.postDelayed(this, minOf(16L, gesture.durationMs - elapsed)) }
                } catch (error: Exception) { runCatching { send(MotionEvent.ACTION_CANCEL) }; result.completeExceptionally(error) }
            }
        }
        result.whenComplete { _, _ -> ui.removeCallbacks(task); if (result.isCancelled) ui.post { runCatching { send(MotionEvent.ACTION_CANCEL) } } }
        send(MotionEvent.ACTION_DOWN); ui.postDelayed(task, 16L)
        return result
    }

    private fun clickPointAsync(input: JSONObject, profile: String): CompletableFuture<JSONObject> {
        require(profile == activeProfile) { "Coordinate input requires the active rendered profile" }
        require(input.optString("frame", "main") == "main") { "Coordinates refer to the whole rendered viewport" }
        val session = currentSession(profile)
        if (identity(session).navigating) throw BrowserOperationException("BRIDGE_NOT_READY", "Page is navigating")
        val host = backgroundHost
        val foreground = view
        check((host?.session === session && host.attached) ||
            (foreground?.session === session && foreground.isAttachedToWindow)) { "No ready browser surface" }
        val width = host?.width ?: foreground!!.width
        val height = host?.height ?: foreground!!.height
        require(width > 0 && height > 0) { "Browser viewport has not been measured" }
        var x = input.getDouble("x"); var y = input.getDouble("y")
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite" }
        if (input.has("imageWidth") || input.has("imageHeight")) {
            val iw = input.getInt("imageWidth"); val ih = input.getInt("imageHeight")
            require(iw > 0 && ih > 0 && x >= 0 && y >= 0 && x < iw && y < ih) { "Point outside screenshot bounds" }
            val shot = lastShots[profile]
            require(shot != null && shot.width == iw && shot.height == ih &&
                shot.documentId == identity(session).documentId) { "Screenshot dimensions or document changed; capture again" }
            x *= width.toDouble() / iw; y *= height.toDouble() / ih
        }
        require(x >= 0 && y >= 0 && x < width && y < height) { "Point outside browser viewport" }
        val generation = surfaceGeneration
        val hostGeneration = host?.generation
        val pageId = identity(session).documentId
        val result = CompletableFuture<JSONObject>()
        val now = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { session.panZoomController.onTouchEvent(event) } finally { event.recycle() }
        }
        try { send(MotionEvent.ACTION_DOWN) } catch (error: Exception) {
            result.completeExceptionally(error)
            return result
        }
        ui.postDelayed({
            try {
              if (result.isDone || selectedLoadedSession(profile) !== session || surfaceGeneration != generation ||
                host?.generation != hostGeneration || identity(session).documentId != pageId || identity(session).navigating) {
                runCatching { send(MotionEvent.ACTION_CANCEL) }
                result.completeExceptionally(BrowserOperationException("PAGE_CHANGED", "Page or surface changed during touch input"))
              } else {
                send(MotionEvent.ACTION_UP)
                result.complete(JSONObject().put("ok", true).put("result", JSONObject().put("dispatched", true)
                    .put("documentId", identity(session).documentId).put("url", identity(session).url).put("profile", profile).put("tab", selectedTab(profile)).put("siteCompleted", false)
                    .put("native", true).put("x", x).put("y", y).put("viewportWidth", width).put("viewportHeight", height)))
              }
            } catch (error: Exception) {
                runCatching { send(MotionEvent.ACTION_CANCEL) }
                result.completeExceptionally(error)
            }
        }, 32L)
        return result
    }

    data class ScreenshotEvidence(val bytes: ByteArray, val metadata: JSONObject)
    fun captureEvidenceAsync(profile: String = activeProfile): CompletableFuture<ScreenshotEvidence> =
        captureAsync(profile = profile).thenApply { bytes ->
            ScreenshotEvidence(bytes, screenshotMeta(profile).put("stale", false).put("live", true).put("mimeType", "image/jpeg"))
        }

    fun captureAsync(full: Boolean = false, profile: String = activeProfile): CompletableFuture<ByteArray> {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        if (full) return CompletableFuture.failedFuture(UnsupportedOperationException("Full-page screenshot requires a separate Gecko API integration"))
        val session = currentSession(profile)
        activateForWork(session)
        captureInFlight?.takeIf { !it.isDone }?.let { pending ->
            // A thumbnail and an API request for the same document may share real pixels.
            return if (captureSession === session) pending.thenApply { it.copyOf() }
                else CompletableFuture.failedFuture(BrowserOperationException("BUSY", "Another screenshot is in progress; retry shortly"))
        }
        val result = CompletableFuture<ByteArray>()
        captureInFlight = result; captureSession = session
        val imageGeneration = screenshotGeneration[profile] ?: 0L
        val deadline = SystemClock.elapsedRealtime() + 12000L
        var host: OffscreenBrowserDisplay? = null
        var originView: GeckoView? = null
        var ownsHost = false
        var originSurfaceGeneration = 0L
        var capturedDocument: String? = null
        var capturedUrl = ""
        var capturedTab = ""
        var attemptToken = 0
        var retried = false
        // Gecko may return the previous display's final buffer immediately after
        // a tab/profile Surface transfer. Never expose that first unproven frame.
        var discardedWarmupFrame = false
        var attemptTimeout: Runnable? = null
        var bridgeWait: CompletableFuture<Unit>? = null
        var paintWait: CompletableFuture<JSONObject>? = null
        var delayedAttempt: Runnable? = null
        val timeout = Runnable {
            lastScreenshotError = "Fresh screenshot did not finish within 12 seconds"
            result.completeExceptionally(BrowserOperationException("SCREENSHOT_TIMEOUT", lastScreenshotError!!))
        }
        ui.postDelayed(timeout, 12000L)
        result.whenComplete { _, _ ->
            val cleanup = Runnable {
                ui.removeCallbacks(timeout)
                attemptTimeout?.let { ui.removeCallbacks(it) }
                delayedAttempt?.let { ui.removeCallbacks(it) }
                bridgeWait?.takeIf { !it.isDone }?.cancel(false)
                paintWait?.takeIf { !it.isDone }?.cancel(false)
                if (ownsHost) {
                    if (captureAuxHost === host) captureAuxHost = null
                    runCatching { host?.close() }
                }
                if (captureInFlight === result) { captureInFlight = null; captureSession = null }
                pruneSessions(); pruneTabSessions()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else ui.post(cleanup)
        }
        fun targetCurrent(): Boolean = selectedLoadedSession(profile) === session &&
            (screenshotGeneration[profile] ?: 0L) == imageGeneration &&
            identity(session).documentId == capturedDocument && !identity(session).navigating &&
            (if (ownsHost) captureAuxHost === host && host?.session === session
             else activeProfile == profile && view === originView && backgroundHost === host &&
                 surfaceGeneration == originSurfaceGeneration)
        fun fail(error: Throwable) {
            lastScreenshotError = error.message ?: error.javaClass.simpleName
            result.completeExceptionally(error)
        }
        lateinit var capturePixels: () -> Unit
        fun retrySurface(error: Throwable) {
            if (result.isDone) return
            attemptTimeout?.let { ui.removeCallbacks(it) }
            if (!targetCurrent()) {
                fail(BrowserOperationException("PAGE_CHANGED", "Page or display changed during capture; request a new screenshot"))
            } else if (host != null && !retried && SystemClock.elapsedRealtime() + 500L < deadline) {
                // Recreate only our compositor Surface, once. Never reload the page.
                retried = true; attemptToken++; discardedWarmupFrame = false
                try {
                    host!!.recreateSurface()
                    delayedAttempt = Runnable { if (!result.isDone) capturePixels() }
                    ui.postDelayed(delayedAttempt!!, 120L)
                } catch (failure: Exception) { fail(failure) }
            } else fail(error)
        }
        capturePixels = capture@{
            if (result.isDone) return@capture
            if (!targetCurrent()) {
                fail(BrowserOperationException("PAGE_CHANGED", "Capture target changed before pixels were available"))
                return@capture
            }
            val ticket = ++attemptToken
            val hostGeneration = host?.generation
            val width = host?.width ?: originView!!.width
            val height = host?.height ?: originView!!.height
            if (host != null && !retried) {
                attemptTimeout = Runnable {
                    if (ticket == attemptToken && !result.isDone)
                        retrySurface(BrowserOperationException("SCREENSHOT_TIMEOUT", "Background compositor did not return pixels"))
                }
                ui.postDelayed(attemptTimeout!!, 3500L)
            }
            val pixels = try { host?.capturePixels() ?: originView!!.capturePixels() }
            catch (error: Exception) { retrySurface(error); return@capture }
            pixels.accept({ bitmap: Bitmap? ->
                if (result.isDone || ticket != attemptToken) { bitmap?.recycle(); return@accept }
                attemptTimeout?.let { ui.removeCallbacks(it) }
                if (bitmap == null) {
                    retrySurface(IllegalStateException("Compositor returned no screenshot bitmap")); return@accept
                }
                if (!discardedWarmupFrame) {
                    discardedWarmupFrame = true
                    bitmap.recycle()
                    delayedAttempt = Runnable { if (!result.isDone) capturePixels() }
                    ui.postDelayed(delayedAttempt!!, 120L)
                    return@accept
                }
                if (host?.generation != hostGeneration || bitmap.width != width || bitmap.height != height) {
                    bitmap.recycle()
                    retrySurface(BrowserOperationException("SCREENSHOT_SIZE_MISMATCH", "Compositor surface changed or returned incorrect dimensions"))
                    return@accept
                }
                imageEncoder.execute {
                    var scaled: Bitmap? = null
                    var encodedWidth = bitmap.width
                    var encodedHeight = bitmap.height
                    val encoded = runCatching {
                        scaled = if (bitmap.width.toLong() * bitmap.height > 8_000_000L) {
                            val ratio = kotlin.math.sqrt(8_000_000.0 / (bitmap.width.toDouble() * bitmap.height))
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                                (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                        } else bitmap
                        encodedWidth = scaled!!.width; encodedHeight = scaled!!.height
                        val bytes = ByteArrayOutputStream()
                        check(scaled!!.compress(Bitmap.CompressFormat.JPEG, 78, bytes)) { "JPEG encoding failed" }
                        bytes.toByteArray()
                    }
                    if (scaled !== bitmap) scaled?.recycle()
                    bitmap.recycle()
                    ui.post {
                        if (result.isDone || ticket != attemptToken) return@post
                        if (!targetCurrent()) {
                            fail(BrowserOperationException("PAGE_CHANGED", "Document or tab changed during screenshot"))
                        } else if (host?.generation != hostGeneration) {
                            retrySurface(BrowserOperationException("PAGE_CHANGED", "Compositor surface was replaced during screenshot"))
                        } else if (encoded.isFailure) fail(encoded.exceptionOrNull()!!)
                        else {
                            val snapshot = encoded.getOrThrow()
                            val now = System.currentTimeMillis()
                            lastScreenshotAt = now; lastScreenshotError = null
                            host?.recordPixelCapture()
                            if (snapshot.size <= 4 * 1024 * 1024) {
                                lastShots[profile] = CachedShot(snapshot, now, encodedWidth, encodedHeight,
                                    capturedUrl, capturedTab, capturedDocument)
                                while (lastShots.size > 3 || lastShots.values.sumOf { it.jpeg.size } > 4 * 1024 * 1024)
                                    lastShots.remove(lastShots.keys.first())
                            }
                            if (!isPrivate(profile)) previews.save(profile, capturedTab, snapshot)
                            capturedDocument?.let { previewDocuments[tabKey(profile, capturedTab)] = it }
                            result.complete(snapshot)
                        }
                    }
                }
            }, { error ->
                if (ticket == attemptToken) retrySurface(error ?: IllegalStateException("Compositor capture failed"))
            })
        }
        bridgeWait = awaitBridge(profile, session) {
            if ((screenshotGeneration[profile] ?: 0L) != imageGeneration)
                throw BrowserOperationException("PAGE_CHANGED", "Document changed while preparing screenshot")
        }
        bridgeWait!!.whenComplete { _, error ->
            if (error != null) fail(error)
            else {
                val prepare = object : Runnable {
                    override fun run() {
                        if (result.isDone) return
                        try {
                            if (selectedLoadedSession(profile) !== session || identity(session).navigating ||
                                (screenshotGeneration[profile] ?: 0L) != imageGeneration)
                                throw BrowserOperationException("PAGE_CHANGED", "Page changed while preparing capture")
                            if (profile == activeProfile) {
                                if (view == null && backgroundHost == null && backgroundRenderingEnabled()) {
                                    enterBackgroundSurface()
                                    pendingBackgroundAttach?.let { ui.removeCallbacks(it); it.run() }
                                }
                                originView = view; host = backgroundHost
                                val waitingForLayout = originView?.session === session && originView!!.isAttachedToWindow &&
                                    (originView!!.width <= 0 || originView!!.height <= 0)
                                if (waitingForLayout && SystemClock.elapsedRealtime() + 100L < deadline) {
                                    delayedAttempt = this; ui.postDelayed(this, 16L); return
                                }
                                check((originView?.session === session && originView!!.isAttachedToWindow &&
                                    originView!!.width > 0 && originView!!.height > 0) ||
                                    (host?.session === session && host!!.attached)) { "No ready browser compositor surface" }
                            } else {
                                check(backgroundRenderingEnabled()) { "Enable background rendering to capture an inactive profile" }
                                host = OffscreenBrowserDisplay(backgroundWidth, backgroundHeight)
                                ownsHost = true; captureAuxHost = host
                                host!!.attach(session, focused = false)
                            }
                            originSurfaceGeneration = surfaceGeneration
                            capturedDocument = identity(session).documentId
                            capturedUrl = status(profile).optString("url")
                            capturedTab = status(profile).optString("activeTab")
                            val port = ports[session] ?: throw BrowserOperationException("BRIDGE_NOT_READY", "Bridge disconnected before paint synchronization")
                            paintWait = dispatchPage(profile, session, port, JSONObject().put("action", "render_ready")
                                .put("documentId", capturedDocument).put("expectedUrl", capturedUrl), "main", capturedDocument!!)
                            paintWait!!.whenComplete { _, paintError ->
                                if (!result.isDone) {
                                    if (paintError != null) fail(paintError)
                                    else if (!targetCurrent()) fail(BrowserOperationException("PAGE_CHANGED", "Page changed during paint synchronization"))
                                    else {
                                        delayedAttempt = Runnable { if (!result.isDone) capturePixels() }
                                        ui.postDelayed(delayedAttempt!!, 32L)
                                    }
                                }
                            }
                        } catch (failure: Exception) { fail(failure) }
                    }
                }
                prepare.run()
            }
        }
        return result
    }

    /** Blocking helper for HTTP worker threads. Never invoke from UI thread. */
    fun <T> uiCall(timeoutSeconds: Long = 12, operation: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Cannot block UI thread" }
        val lease = workLease.acquire(timeoutSeconds * 1000L + 1000L)
        val future = CompletableFuture<T>()
        val task = Runnable {
            if (!future.isDone) try { future.complete(operation()) }
            catch (e: Exception) { future.completeExceptionally(e) }
        }
        ui.post(task)
        try { return future.get(timeoutSeconds, TimeUnit.SECONDS) }
        finally {
            ui.removeCallbacks(task)
            if (!future.isDone) future.cancel(false)
            lease.close()
        }
    }
    fun <T> uiAsync(timeoutSeconds: Long = 15, operation: () -> CompletableFuture<T>): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        val lease = workLease.acquire(timeoutSeconds * 1000L + 1000L)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val promise = CompletableFuture<CompletableFuture<T>>()
        val task = Runnable {
            if (!promise.isDone) try {
                val nested = operation()
                if (!promise.complete(nested)) nested.cancel(false)
            } catch (e: Exception) { promise.completeExceptionally(e) }
        }
        ui.post(task)
        var nested: CompletableFuture<T>? = null
        try {
            nested = promise.get((deadline - System.nanoTime()).coerceAtLeast(1L), TimeUnit.NANOSECONDS)
            return nested.get((deadline - System.nanoTime()).coerceAtLeast(1L), TimeUnit.NANOSECONDS)
        } finally {
            ui.removeCallbacks(task)
            val started = nested ?: if (promise.isDone && !promise.isCompletedExceptionally && !promise.isCancelled)
                promise.getNow(null) else null
            if (!promise.isDone) promise.cancel(false)
            if (started?.isDone == false) started.cancel(false)
            lease.close()
        }
    }
}
