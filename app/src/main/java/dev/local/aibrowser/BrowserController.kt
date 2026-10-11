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
    private var extension: WebExtension? = null
    private val sessions = LinkedHashMap<String, GeckoSession>(8, .75f, true)
    private val ports = mutableMapOf<GeckoSession, WebExtension.Port>()
    private val documents = java.util.WeakHashMap<GeckoSession, PageIdentity>()
    private val bridgeVersions = java.util.WeakHashMap<GeckoSession, String>()
    private fun identity(session: GeckoSession) = documents.getOrPut(session) { PageIdentity() }
    private data class FrameBridge(val port: WebExtension.Port, val documentId: String, var url: String)
    private val framePorts = mutableMapOf<GeckoSession, LinkedHashMap<String, FrameBridge>>()
    private fun bridgeReady(session: GeckoSession): Boolean = ports.containsKey(session) &&
        identity(session).documentId != null && !identity(session).navigating
    private fun discardFramePorts(session: GeckoSession) {
        framePorts.remove(session)?.values?.toList()?.forEach {
            bridgeRequests.failPort(it.port, BrowserOperationException("PAGE_CHANGED", "Frame document changed"))
            it.port.disconnect()
        }
    }
    private fun beginPageNavigation(session: GeckoSession, target: String? = null) {
        identity(session).beginNavigation(target)
        bridgeVersions.remove(session)
        ports.remove(session)?.let {
            bridgeRequests.failPort(it, BrowserOperationException("PAGE_CHANGED", "Page is navigating; inspect the new document"))
            it.disconnect()
        }
        discardFramePorts(session)
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
    internal val tabGroups = TabGroups()
    internal val previews = TabPreviews(app)
    private val previewDocuments = mutableMapOf<String, String>()
    private var previewTask: Runnable? = null
    private var nextTabId: Long = 1L
    private fun tabKey(profile: String, tabId: String) = "$profile/$tabId"
    private fun sessionSettings(profile: String): GeckoSessionSettings {
        val desktop = profile in desktopProfiles
        return GeckoSessionSettings.Builder().contextId(profile)
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
    private val workLease = BrowserWorkLease(app)
    internal val downloads = BrowserDownloads(app).apply { onChanged = { notifyUi() } }
    private val downloadPins = mutableMapOf<GeckoSession, Int>()
    private fun hasSessionWork(session: GeckoSession): Boolean =
        hasPendingNavigation(session) || bridgeRequests.hasSession(session) ||
            (downloadPins[session] ?: 0) > 0 || (preparingSessions[session] ?: 0) > 0 ||
            (captureSession === session && captureInFlight?.isDone == false)
    // UI-thread-owned bookkeeping: do not evict a GeckoSession that is serving an Agent request.
    private val inFlightByProfile = mutableMapOf<String, Int>()
    private data class PendingNavigation(
        val expectedUrl: String,
        val session: GeckoSession,
        val future: CompletableFuture<JSONObject>,
        var sawRequestedPageStart: Boolean = false
    )
    private val navigationWaiters = mutableMapOf<String, PendingNavigation>()
    private fun hasPendingNavigation(session: GeckoSession): Boolean =
        navigationWaiters.values.any { it.session === session && !it.future.isDone }

    private fun failSessionRequests(session: GeckoSession, reason: String) {
        assertMain()
        val error = IllegalStateException(reason)
        bridgeRequests.failSession(session, error)
        discardFramePorts(session)
        documents.remove(session)
        bridgeVersions.remove(session)
        if (backgroundHost?.session === session) {
            backgroundHost?.close(); backgroundHost = null; surfaceGeneration++
        }
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
                if (validProfile(id) && id !in retiringProfiles) profileUrls[id] = obj.optString("url", "https://example.org/")
            }
        }
        if (profileUrls.isEmpty()) {
            val first = (1..300).map { "G%03d".format(it) }.first { it !in retiringProfiles }
            profileUrls[first] = "https://example.org/"
        }
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
        if (runCatching { requireWebUrl(url) }.isFailure) return
        val history = historyUrls.getOrPut(profile) { mutableListOf() }
        if (history.lastOrNull() == url) return
        history.remove(url)
        history.add(url)
        if (history.size > 150) history.removeAt(0)
        persist()
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
    private fun persist() {
        val arr = JSONArray()
        profileUrls.forEach { (id, url) -> arr.put(JSONObject().put("id", id).put("url", url)) }
        val tabs = JSONObject()
        tabUrls.forEach { (profile, urls) ->
            val items = JSONArray()
            urls.forEach { (id, url) -> items.put(JSONObject().put("id", id).put("url", url)) }
            tabs.put(profile, JSONObject().put("selected", selectedTab(profile)).put("items", items))
        }
        val collections = JSONObject()
        profileUrls.keys.forEach { profile ->
            collections.put(profile, JSONObject()
                .put("bookmarks", JSONArray(bookmarkUrls[profile]?.toList() ?: emptyList<String>()))
                .put("history", JSONArray(historyUrls[profile]?.toList() ?: emptyList<String>())))
        }
        store.edit().putString("profiles", arr.toString()).putString("active", activeProfile)
            .putString("tabs_v1", tabs.toString())
            .putString("tab_groups_v1", groupsJson().toString())
            .putStringSet("closed_main_profiles_v1", closedMainProfiles.toSet())
            .putString("collections_v1", collections.toString())
            .putStringSet("desktop_profiles_v1", desktopProfiles.toSet())
            .putStringSet("retiring_profiles_v1", retiringProfiles.toSet()).apply()
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
                .javaScriptEnabled(true).contentBlocking(blocking).build())
            runtime!!.settings.setGlobalPrivacyControl(true)
            runtime!!.settings.setFingerprintingProtection(fingerprintProtectionEnabled())
            // Resume any interrupted storage clean-ups from a previous process.
            retiringProfiles.toList().forEach { clearRetiringContext(it) }
            runtime!!.webExtensionController.ensureBuiltIn(
                "resource://android/assets/agentbridge/", "agentbridge@local.browser"
            ).accept({ ext ->
                extension = ext
                engineReady = true
                (sessions.values + popupSessions.values + tabSessions.values).forEach { installSessionDelegate(it) }
                notifyUi()
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
            val sourceSession = port.sender.session ?: return
            val topLevel = port.sender.isTopLevel
            var frameId: String? = null
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val json = message as? JSONObject ?: return
                    if (json.optBoolean("ready")) {
                        val documentId = json.optString("documentId")
                        val url = json.optString("url")
                        if (documentId.isBlank()) { port.disconnect(); return }
                        if (topLevel) {
                            if (!identity(sourceSession).acceptDocument(documentId, url)) { port.disconnect(); return }
                            commitDocumentLocation(sourceSession, url)
                            val previous = ports.put(sourceSession, port)
                            if (previous != null && previous !== port) {
                                bridgeRequests.failPort(previous, BrowserOperationException("PAGE_CHANGED", "Document bridge replaced"))
                                previous.disconnect()
                            }
                        } else {
                            val frames = framePorts.getOrPut(sourceSession) { LinkedHashMap() }
                            if (frames.size >= 24 && !frames.containsKey(documentId)) { port.disconnect(); return }
                            frameId = documentId
                            frames.put(documentId, FrameBridge(port, documentId, url))?.takeIf { it.port !== port }?.let {
                                bridgeRequests.failPort(it.port, BrowserOperationException("PAGE_CHANGED", "Frame bridge replaced"))
                                it.port.disconnect()
                            }
                        }
                        if (topLevel) bridgeVersions[sourceSession] = json.optString("bridgeVersion", "legacy")
                        runCatching { port.postMessage(JSONObject().put("bridgeReady", true)) }
                        notifyUi(); return
                    }
                    if (json.optBoolean("pageState") && topLevel && ports[sourceSession] === port) {
                        if (identity(sourceSession).updateSameDocument(json.optString("documentId"), json.optString("url")))
                            commitDocumentLocation(sourceSession, json.optString("url"))
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
                    if (ports[sourceSession] === port) ports.remove(sourceSession)
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
        fun dismissPromptsFor(session: GeckoSession)
        fun showDownload(session: GeckoSession, response: WebResponse): Boolean
    }
    var sitePromptHost: SitePromptHost? = null

    fun isVisibleSession(session: GeckoSession) = backgroundHost == null &&
        view?.isAttachedToWindow == true && view?.session === session && sitePromptHost != null

    private fun profileOf(session: GeckoSession): String? =
        popupSessions.entries.firstOrNull { it.value === session }?.key ?:
        sessions.entries.firstOrNull { it.value === session }?.key ?:
        tabSessions.entries.firstOrNull { it.value === session }?.key?.substringBefore('/')

    private fun externalResponse(session: GeckoSession, response: WebResponse) {
        if (!isVisibleSession(session) || sitePromptHost?.showDownload(session, response) != true) {
            blockedSiteInteraction(session, "download_confirmation")
            downloads.discard(response)
        }
    }
    internal fun startDownload(session: GeckoSession, response: WebResponse): String {
        assertMain()
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
            val accepted = isVisibleSession(session) &&
                sitePromptHost?.showSitePrompt(session, prompt, reply) == true
            if (!accepted) {
                blockedSiteInteraction(session, "site_prompt")
                reply.complete(prompt.dismiss())
            }
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
        override fun onContentPermissionRequest(session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
            val result = GeckoResult<Int>()
            if (!isVisibleSession(session) || sitePromptHost?.showContentPermission(session, perm, result) != true) {
                blockedSiteInteraction(session, "site_permission")
                result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
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

    private fun installSessionDelegate(session: GeckoSession) {
        session.promptDelegate = sitePrompts
        session.permissionDelegate = sitePermissions
        extension?.let { session.webExtensionController.setMessageDelegate(it, messageDelegate, "aibrowser") }
    }

    private fun newPopupSession(profile: String): GeckoResult<GeckoSession> {
        // Gecko opens the returned session itself. Popup ownership stays within
        // the same container's contextId, regardless of its source tab.
        val previous = popupSessions.remove(profile)
        if (previous != null) {
            failSessionRequests(previous, "Popup replaced")
            if (view?.session === previous) view?.releaseSession()
            ports.remove(previous)?.disconnect()
            previous.close()
        }
        invalidateScreenshot(profile)
        popupUrls.remove(profile)
        popupTitles.remove(profile)
        popupLoading[profile] = true
        val child = GeckoSession(sessionSettings(profile))
        popupSessions[profile] = child
        child.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean) {
                if (popupSessions[profile] === session && url != null) {
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
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (popupSessions[profile] === session) popupTitles[profile] = title ?: ""
                notifyUi()
            }
        }
        child.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                beginPageNavigation(session, url)
                navigationWaiters[profile]?.let { pending ->
                    if (pending.session === session && url.trimEnd('/') == pending.expectedUrl.trimEnd('/'))
                        pending.sawRequestedPageStart = true
                }
                if (popupSessions[profile] === session) {
                    popupUrls[profile] = url
                    popupLoading[profile] = true
                    popupTitles.remove(profile)
                    invalidateScreenshot(profile)
                }
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) identity(session).navigationFailed()
                if (popupSessions[profile] === session) popupLoading[profile] = false
                navigationWaiters[profile]?.takeIf {
                    it.session === session && it.sawRequestedPageStart
                }?.let { waiter ->
                    navigationWaiters.remove(profile)
                    waiter.future.complete(JSONObject().put("profile", profile)
                        .put("url", popupUrls[profile] ?: "").put("title", popupTitles[profile] ?: "")
                        .put("navigationCompleted", true).put("loadSucceeded", success)
                        .put("httpStatusAvailable", false))
                }
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
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
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
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageTitles[id] = title ?: ""
                notifyUi()
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                beginPageNavigation(session, url)
                navigationWaiters[id]?.let { pending ->
                    if (pending.session === session &&
                        url.trimEnd('/') == pending.expectedUrl.trimEnd('/'))
                        pending.sawRequestedPageStart = true
                }
                pageLoading[id] = true
                invalidateScreenshot(id)
                pageTitles.remove(id)
                profileUrls[id] = url
                recordVisit(id, url)
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) identity(session).navigationFailed()
                pageLoading[id] = false
                navigationWaiters[id]?.takeIf {
                    it.session === session && it.sawRequestedPageStart
                }?.let { waiter ->
                    navigationWaiters.remove(id)
                    waiter.future.complete(JSONObject()
                        .put("profile", id).put("url", profileUrls[id] ?: "")
                        .put("title", pageTitles[id] ?: "")
                        .put("navigationCompleted", true)
                        .put("loadSucceeded", success)
                        .put("httpStatusAvailable", false))
                }
                notifyUi()
            }
        }
        session.open(runtime ?: error("Runtime not ready"))
        sessions[id] = session
        installSessionDelegate(session)
        session.loadUri(profileUrls[id] ?: "https://example.org/")
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
                if (url != null && runCatching { requireWebUrl(url) }.isSuccess) {
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
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tabTitles[key] = title ?: ""
                notifyUi()
            }
        }
        tab.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                beginPageNavigation(session, url)
                navigationWaiters[profile]?.let { pending ->
                    if (pending.session === session &&
                        url.trimEnd('/') == pending.expectedUrl.trimEnd('/'))
                        pending.sawRequestedPageStart = true
                }
                tabUrls[profile]?.set(id, url)
                tabLoading[key] = true
                tabTitles.remove(key)
                invalidateScreenshot(profile)
                recordVisit(profile, url)
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) identity(session).navigationFailed()
                tabLoading[key] = false
                navigationWaiters[profile]?.takeIf {
                    it.session === session && it.sawRequestedPageStart
                }?.let { waiter ->
                    navigationWaiters.remove(profile)
                    waiter.future.complete(JSONObject().put("profile", profile)
                        .put("url", tabUrls[profile]?.get(id) ?: "")
                        .put("title", tabTitles[tabKey(profile, id)] ?: "")
                        .put("navigationCompleted", true)
                        .put("loadSucceeded", success)
                        .put("httpStatusAvailable", false))
                }
                notifyUi()
            }
        }
        tab.open(runtime ?: error("Runtime not ready"))
        tabSessions[key] = tab
        installSessionDelegate(tab)
        tab.loadUri(url)
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
            ports.remove(old)?.disconnect()
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
            require(fromUser || old == null || !hasSessionWork(old)) { "Tab has active Agent operations; retry later" }
            if (old != null) {
                sessions.remove(profile)
                failSessionRequests(old, "Tab closed")
                if (view?.session === old) view?.releaseSession()
                if (backgroundHost?.session === old) { backgroundHost?.close(); backgroundHost = null }
                ports.remove(old)?.disconnect(); old.close()
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
            require(!hasSessionWork(session)) {
                "Tab has active Agent operations; retry later"
            }
        }
        // Validate ownership/in-flight work before making any persistent mutation.
        tabUrls[profile]?.remove(id)
        tabGroups.removeTab(profile, id); previews.remove(profile, id); previewDocuments.remove(key)
        if (session != null) {
            tabSessions.remove(key)
            failSessionRequests(session, "Tab closed")
            if (view?.session === session) view?.releaseSession()
            if (backgroundHost?.session === session) { backgroundHost?.close(); backgroundHost = null }
            ports.remove(session)?.disconnect()
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
                ports.remove(popup)?.disconnect()
                popup.close()
            }
            failSessionRequests(old, "Profile session evicted due to memory limit")
            ports.remove(old)?.disconnect()
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
        if (captureAuxHost?.session === current) {
            captureAuxHost?.close(); captureAuxHost = null
            captureInFlight?.completeExceptionally(BrowserOperationException("PAGE_CHANGED", "Capture target became visible; capture again"))
        }
        if (backgroundHost != null) {
            backgroundHost!!.attach(current)
            surfaceGeneration++
            notifyUi(); return
        }
        val target = view ?: run { enterBackgroundSurface(); return }
        if (target.session !== current) {
            target.session?.let { sitePromptHost?.dismissPromptsFor(it) }
            surfaceGeneration++
            target.releaseSession()
            target.setSession(current)
        }
        notifyUi()
    }

    fun profiles(): JSONArray = JSONArray().apply {
        profileUrls.forEach { (id, url) ->
            val selectedUrl = popupUrls[id] ?: tabUrls[id]?.get(selectedTab(id)) ?: url
            put(JSONObject().put("id", id).put("url", selectedUrl).put("active", id == activeProfile)
                .put("loaded", sessions.containsKey(id)).put("manual", id in manualProfiles)
                .put("storageIsolated", true)
                .put("fingerprintProtectionScope", "shared_gecko_runtime"))
        }
    }
    fun status(profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        return JSONObject().put("profile", profile)
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
            .put("contentBridgeVersion", selectedLoadedSession(profile)?.takeIf { bridgeReady(it) }?.let { bridgeVersions[it] } ?: JSONObject.NULL)
            .put("bridgeSchemaVersion", 3)
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
            .put("httpStatusAvailable", false)
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
            .put("proxyMode", "direct")
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
        require(id !in retiringProfiles) { "Profile storage is still being cleared; retry later" }
        require(profileUrls.size < 300) { "Profile metadata limit reached" }
        profileUrls[id] = "https://example.org/"
        persist()
        notifyUi()
        return JSONObject().put("id", id)
    }
    fun setManual(enabled: Boolean, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        if (enabled) manualProfiles.add(profile) else manualProfiles.remove(profile)
        // A system process restart must not silently return control to the Agent.
        val persisted = store.edit().putStringSet("manual_profiles_v1", manualProfiles.toSet()).commit()
        notifyUi()
        return status(profile).put("takeoverProfile", profile).put("takeoverEnabled", enabled)
            .put("takeoverStatePersisted", persisted)
    }
    private fun clearRetiringContext(id: String) {
        assertMain()
        val storage = runtime?.storageController ?: return
        // GeckoView 157 returns Unit here, NOT a result/future acknowledging
        // data erasure. Permanently reserve this contextId rather than silently
        // reusing an identity whose cookies may not have finished clearing.
        try {
            storage.clearDataForSessionContext(id)
        } catch (error: Exception) {
            Log.e("Abwr", "Unable to request storage cleanup for retired profile " + id, error)
        }
    }

    fun removeProfile(id: String): JSONObject {
        assertMain()
        require(id in profileUrls) { "Unknown profile" }
        require(profileUrls.size > 1) { "Cannot remove the final profile" }
        require((inFlightByProfile[id] ?: 0) == 0 &&
            navigationWaiters[id]?.future?.isDone != false && downloads.activeCount(id) == 0) {
            "Profile has active Agent operations; retry later"
        }
        require(id !in manualProfiles) { "Cannot delete a profile during manual takeover" }
        requireNotNull(runtime?.storageController) {
            "Gecko runtime must be ready before clearing profile storage"
        }
        // Commit the ID quarantine before touching profile metadata. If Android
        // terminates this process mid-delete, the ID stays protected at startup.
        check(store.edit().putStringSet("retiring_profiles_v1",
            (retiringProfiles + id).toSet()).commit()) {
            "Unable to persist profile deletion reservation"
        }
        tabIds(id).forEach { previews.remove(id, it); previewDocuments.remove(tabKey(id, it)) }
        tabGroups.removeProfile(id)
        retiringProfiles.add(id)
        popupSessions.remove(id)?.let { popup ->
            failSessionRequests(popup, "Profile deleted")
            if (view?.session === popup) view?.releaseSession()
            ports.remove(popup)?.disconnect()
            popup.close()
        }
        val session = sessions.remove(id)
        if (session != null) {
            failSessionRequests(session, "Profile deleted")
            if (view?.session === session) view?.releaseSession()
            ports.remove(session)?.disconnect()
            session.close()
        }
        popupUrls.remove(id)
        popupTitles.remove(id)
        popupLoading.remove(id)
        profileUrls.remove(id)
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
            ports.remove(tab)?.disconnect()
            tab.close()
        }
        pageLoading.remove(id)
        tabLoading.keys.removeAll { it.startsWith("$id/") }
        pageTitles.remove(id)
        lastShots.remove(id)
        navigationWaiters.remove(id)?.future?.completeExceptionally(IllegalStateException("Profile deleted"))
        manualProfiles.remove(id)
        if (activeProfile == id) activeProfile = profileUrls.keys.first()
        persist()
        clearRetiringContext(id)
        attachActive()
        // Gecko does not acknowledge erasure; never imply that it did.
        return JSONObject().put("removed", id)
            .put("storageClearRequested", true).put("idReserved", true)
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
    fun navigate(url: String, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        val destination = currentSession(profile)
        recordNavigationTarget(destination, profile, url)
        destination.loadUri(url)
        return JSONObject().put("navigating", url).put("profile", profile)
    }
    /** Waits for GeckoSession's real onPageStop signal, not a guessed sleep duration. */
    fun navigateAndWaitAsync(url: String, profile: String, timeoutMs: Long): CompletableFuture<JSONObject> {
        assertMain()
        require(timeoutMs in 250L..14000L) { "timeoutMs must be 250-14000" }
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        check(navigationWaiters[profile] == null) { "Navigation wait already active for profile" }
        val destination = currentSession(profile)
        val future = CompletableFuture<JSONObject>()
        val pending = PendingNavigation(url, destination, future)
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
            recordNavigationTarget(destination, profile, url)
            destination.loadUri(url)
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
            destination.loadUri(url)
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
            ports.remove(popup)?.disconnect()
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
        CommandArguments.validate(input.keys().asSequence().associateWith { input.opt(it) })
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
        ui.postDelayed(timeout, 11_000L)
        future.whenComplete { _, _ ->
            ui.post {
                ui.removeCallbacks(timeout)
                bridgeRequests.remove(id, future)
                releaseInFlight(profile)
            }
        }
        try { port.postMessage(request) }
        catch (error: Exception) { bridgeRequests.fail(id, error) }
        return future
    }

    fun commandAsync(input: JSONObject): CompletableFuture<JSONObject> {
        assertMain()
        validateCommand(input)
        val profile = profileForRequest(input)
        val normalized = JSONObject(input.toString())
        if (input.optString("action") == "scroll" && !input.has("y")) {
            val sign = if (input.optString("direction", "down") == "up") -1 else 1
            normalized.put("y", sign * input.optDouble("pixels", 600.0))
            normalized.remove("direction"); normalized.remove("pixels")
        }
        val writes = setOf("click", "type", "scroll", "scrollTo", "select", "press", "keyboard", "key",
            "click_point", "focus", "clear", "toggle")
        if (profile in manualProfiles && input.optString("action") in writes)
            return CompletableFuture.failedFuture(BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active"))
        val session = currentSession(profile)
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
    private fun awaitBridge(profile: String, session: GeckoSession, guard: () -> Unit): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val deadline = SystemClock.elapsedRealtime() + 3500L
        preparingSessions[session] = (preparingSessions[session] ?: 0) + 1
        val task = object : Runnable {
            override fun run() {
                if (result.isDone) return
                try {
                    if (selectedLoadedSession(profile) !== session)
                        throw BrowserOperationException("PAGE_CHANGED", "Selected tab changed while waiting for the bridge")
                    guard()
                    if (bridgeReady(session)) result.complete(Unit)
                    else if (SystemClock.elapsedRealtime() >= deadline)
                        result.completeExceptionally(BrowserOperationException("BRIDGE_NOT_READY", "Bridge did not become ready within 3.5 seconds; inspect status and request a fresh snapshot"))
                    else ui.postDelayed(this, 80L)
                } catch (error: Exception) { result.completeExceptionally(error) }
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
        val writes = setOf("click", "type", "scroll", "scrollTo", "select", "press", "keyboard", "key",
            "click_point", "focus", "clear", "toggle")
        if (profile in manualProfiles && action in writes)
            return CompletableFuture.failedFuture(BrowserOperationException("MANUAL_TAKEOVER", "Manual takeover active"))
        if (action == "click_point") return clickPointAsync(input, profile)
        if (action !in setOf("snapshot", "dom", "find", "click", "type", "scroll", "scrollTo", "frames",
            "status", "cookie_metadata", "storage_inspect", "devtools", "compatibility", "read", "wait",
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
                    nativeKey(session, input)
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
        val meta = (if ("ctrl" in modifiers || "control" in modifiers) KeyEvent.META_CTRL_ON else 0) or
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
                    .put("native", true).put("x", x).put("y", y).put("viewportWidth", width).put("viewportHeight", height)))
              }
            } catch (error: Exception) {
                runCatching { send(MotionEvent.ACTION_CANCEL) }
                result.completeExceptionally(error)
            }
        }, 32L)
        return result
    }

    fun captureAsync(full: Boolean = false, profile: String = activeProfile): CompletableFuture<ByteArray> {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        if (full) return CompletableFuture.failedFuture(UnsupportedOperationException("Full-page screenshot requires a separate Gecko API integration"))
        val session = currentSession(profile)
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
        var attemptTimeout: Runnable? = null
        var bridgeWait: CompletableFuture<Unit>? = null
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
                retried = true; attemptToken++
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
                            previews.save(profile, capturedTab, snapshot)
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
            if (!result.isDone) {
                if (error != null) fail(error)
                else try {
                    if (profile == activeProfile) {
                        if (view == null && backgroundHost == null && backgroundRenderingEnabled()) {
                            enterBackgroundSurface()
                            pendingBackgroundAttach?.let { ui.removeCallbacks(it); it.run() }
                        }
                        originView = view; host = backgroundHost
                        check((originView?.session === session && originView!!.isAttachedToWindow &&
                            originView!!.width > 0 && originView!!.height > 0) ||
                            (host?.session === session && host!!.attached)) { "No ready browser compositor surface" }
                    } else {
                        check(backgroundRenderingEnabled()) { "Enable background rendering to capture an inactive profile" }
                        host = OffscreenBrowserDisplay(backgroundWidth, backgroundHeight)
                        ownsHost = true; captureAuxHost = host
                        host!!.attach(session)
                    }
                    originSurfaceGeneration = surfaceGeneration
                    capturedDocument = identity(session).documentId
                    capturedUrl = status(profile).optString("url")
                    capturedTab = status(profile).optString("activeTab")
                    // Allow the new service display one compositor turn before capturing.
                    delayedAttempt = Runnable { if (!result.isDone) capturePixels() }
                    ui.postDelayed(delayedAttempt!!, if (host != null) 120L else 0L)
                } catch (failure: Exception) { fail(failure) }
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
