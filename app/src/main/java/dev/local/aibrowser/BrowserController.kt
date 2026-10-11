package dev.local.aibrowser

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
    internal val downloads = BrowserDownloads(app).apply { onChanged = { notifyUi() } }
    private val downloadPins = mutableMapOf<GeckoSession, Int>()
    private fun hasSessionWork(session: GeckoSession): Boolean =
        hasPendingNavigation(session) || bridgeRequests.hasSession(session) ||
            (downloadPins[session] ?: 0) > 0
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
    private data class CachedShot(val jpeg: ByteArray, val capturedAt: Long)
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
            .put("reason", "Source page is not visible; request was dismissed")
        notifyUi()
    }

    private fun backgroundRenderPhase(): String {
        val host = backgroundHost
        if (host != null) {
            if (view?.isAttachedToWindow != true) return "surface_detached"
            if (host.framesReceived == 0L) return "attached_no_frames_yet"
            val age = SystemClock.elapsedRealtime() - host.lastFrameElapsedMs
            return if (age <= 15000L) "frames_observed" else "no_recent_frames"
        }
        if (pendingBackgroundAttach != null) return "pending"
        if (view != null) return "foreground"
        if (backgroundSurfaceError != null) return "error"
        return if (app.getSharedPreferences("abwr_settings", 0)
            .getBoolean("experimental_background_renderer", false)) "no_surface" else "disabled"
    }

    /**
     * Explicit, rate-limited best-effort recovery for the app's OWN offscreen
     * display. Never starts an Activity, captures other apps, or promises that
     * Android/OEM will keep the process alive.
     */
    fun recoverBackgroundSurface(): JSONObject {
        assertMain()
        require(app.getSharedPreferences("abwr_settings", 0)
            .getBoolean("experimental_background_renderer", false)) {
            "Enable experimental background rendering in Abwr settings first"
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
            view?.releaseSession()
            view = null
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
                    if (id.matches(Regex("T[0-9]+")) && tabs.size < 12 &&
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
    }

    private fun validProfile(id: String) = id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,30}"))
    private fun notifyUi() { onStateChanged?.invoke() }
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
            val previous = ports.put(sourceSession, port)
            if (previous != null && previous !== port) {
                bridgeRequests.failPort(previous, IllegalStateException("Page bridge replaced; refresh the page snapshot"))
                previous.disconnect()
            }
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val json = message as? JSONObject ?: return
                    val id = json.optString("id", "")
                    if (id.isNotBlank()) bridgeRequests.reply(id, sourceSession, port, json)
                }
                override fun onDisconnect(port: WebExtension.Port) {
                    if (ports[sourceSession] === port) ports.remove(sourceSession)
                    bridgeRequests.failPort(port, IllegalStateException("Page bridge disconnected; wait for load and retry"))
                }
            })
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
        require(list.size < 12) { "Maximum 12 extra tabs per profile" }
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
        require(id == "main" || tabUrls[profile]?.containsKey(id) == true) { "Unknown tab" }
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
        require(id != "main") { "The main tab cannot be closed" }
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
        if (session != null) {
            tabSessions.remove(key)
            failSessionRequests(session, "Tab closed")
            if (view?.session === session) view?.releaseSession()
            ports.remove(session)?.disconnect()
            session.close()
        }
        tabTitles.remove(key)
        tabLoading.remove(key)
        if (selectedTab(profile) == id) activeTabs[profile] = "main"
        invalidateScreenshot(profile)
        persist()
        if (profile == activeProfile) attachActive()
        notifyUi()
        return tabs(profile)
    }
    fun tabs(profile: String = activeProfile): JSONObject {
        require(profile in profileUrls) { "Unknown profile" }
        val list = JSONArray().put(JSONObject()
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
        return JSONObject().put("profile", profile)
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

    /**
     * On Activity background, the current session is reattached to an optional
     * private VirtualDisplay.  A successful virtual-display creation does NOT
     * guarantee compositor screenshot success on every Android device.
     */
    fun enterBackgroundSurface() {
        assertMain()
        if (view != null || backgroundHost != null || pendingBackgroundAttach != null) return
        if (!app.getSharedPreferences("abwr_settings", 0)
                .getBoolean("experimental_background_renderer", false)) return
        // Ignore short onStop/onStart transitions, e.g. opening system settings or rotation.
        val task = object : Runnable {
            override fun run() {
                pendingBackgroundAttach = null
                if (view != null || backgroundHost != null) return
                val host = OffscreenBrowserDisplay(app, backgroundWidth, backgroundHeight)
                try {
                    val surfaceView = host.start()
                    backgroundHost = host
                    view = surfaceView
                    surfaceGeneration++
                    backgroundSurfaceStartedElapsedMs = SystemClock.elapsedRealtime()
                    backgroundSurfaceError = null
                    attachActive()
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
            view?.releaseSession()
            view = null
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
        val target = view ?: return
        val current = currentSession()
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
            put(JSONObject().put("id", id).put("url", url).put("active", id == activeProfile)
                .put("loaded", sessions.containsKey(id)).put("manual", id in manualProfiles)
                .put("storageIsolated", true)
                .put("fingerprintProtectionScope", "shared_gecko_runtime"))
        }
    }
    fun status(): JSONObject = JSONObject()
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
        .put("desktopMode", activeProfile in desktopProfiles)
        .put("inFlightProfiles", JSONArray(inFlightByProfile.keys.toList()))
        .put("pendingNavigations", JSONArray(navigationWaiters.keys.toList()))
        .put("pendingBridgeCommands", bridgeRequests.size)
        .put("pendingDownloads", downloads.activeCount(activeProfile))
        .put("lastBlockedSiteInteraction", lastBlockedSiteInteraction ?: JSONObject.NULL)
        .put("screenshotInFlight", captureInFlight?.isDone == false)
        .put("manualMode", manualMode)
        .put("manualProfiles", JSONArray(manualProfiles.toList()))
        .put("residentLimit", 3)
        .put("profileCapacity", 300)
        .put("backgroundScreenshotGuaranteed", false)
        .put("backgroundRendererPhase", backgroundRenderPhase())
        .put("viewAttached", view != null)
        .put("extensionPortReady", selectedLoadedSession()?.let { ports.containsKey(it) } ?: false)
        .put("popupActive", popupSessions.containsKey(activeProfile))
        .put("activeTab", if (popupSessions.containsKey(activeProfile)) "popup" else selectedTab())
        .put("canGoBack", canGoBack())
        .put("canGoForward", canGoForward())
        .put("tabCount", 1 + (tabUrls[activeProfile]?.size ?: 0))
        .put("url", if (popupSessions.containsKey(activeProfile)) popupUrls[activeProfile] ?: ""
            else if (selectedTab() == "main") profileUrls[activeProfile] ?: ""
            else tabUrls[activeProfile]?.get(selectedTab()) ?: "")
        .put("title", if (popupSessions.containsKey(activeProfile)) popupTitles[activeProfile] ?: ""
            else if (selectedTab() == "main") pageTitles[activeProfile] ?: ""
            else tabTitles[tabKey(activeProfile, selectedTab())] ?: "")
        .put("loading", if (popupSessions.containsKey(activeProfile)) popupLoading[activeProfile] ?: false
            else if (selectedTab() == "main") pageLoading[activeProfile] ?: false
            else tabLoading[tabKey(activeProfile, selectedTab())] ?: false)
        .put("httpStatusAvailable", false)
        .put("liveBackgroundScreenshotSupported", false)
        .put("experimentalBackgroundRendererActive", backgroundHost != null)
        .put("experimentalBackgroundSurfaceAttached",
            backgroundHost != null && view?.isAttachedToWindow == true)
        .put("experimentalBackgroundFramesReceived", backgroundHost?.framesReceived ?: 0L)
        .put("experimentalBackgroundLastFrameAgeMs", backgroundHost?.lastFrameElapsedMs?.takeIf { it > 0L }
            ?.let { SystemClock.elapsedRealtime() - it } ?: JSONObject.NULL)
        .put("experimentalBackgroundSurfaceError", backgroundSurfaceError ?: JSONObject.NULL)
        .put("cachedScreenshotAvailable", lastShots.containsKey(activeProfile))
        .put("lastScreenshotAtMs", if (lastScreenshotAt == 0L) JSONObject.NULL else lastScreenshotAt)
        .put("lastScreenshotError", lastScreenshotError ?: JSONObject.NULL)
        .put("screenshotSurfaceAttached", view?.isAttachedToWindow == true)
        .put("proxyMode", "direct")

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
        notifyUi()
        return status().put("takeoverProfile", profile).put("takeoverEnabled", enabled)
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
    fun profileForRequest(input: JSONObject): String = input.optString("profile", activeProfile).also {
        require(it in profileUrls) { "Unknown profile" }
    }
    fun ensureTarget(profile: String) {
        assertMain()
        if (profile != activeProfile) switchProfile(profile)
    }
    private fun recordNavigationTarget(session: GeckoSession, profile: String, url: String) {
        invalidateScreenshot(profile)
        if (session === popupSessions[profile]) popupUrls[profile] = url
        else if (session === sessions[profile]) profileUrls[profile] = url
        else tabSessions.entries.firstOrNull { it.value === session }?.key
            ?.substringAfter('/')?.let { tabUrls[profile]?.set(it, url) }
        persist()
    }
    fun navigate(url: String, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        val destination = currentSession(profile)
        destination.loadUri(url)
        recordNavigationTarget(destination, profile, url)
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
            destination.loadUri(url)
            recordNavigationTarget(destination, profile, url)
        } catch (e: Exception) { future.completeExceptionally(e) }
        return future
    }

    fun diagnostics(): JSONObject {
        assertMain()
        val heap = Runtime.getRuntime()
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("uptimeMs", SystemClock.elapsedRealtime() - controllerCreatedAt)
            .put("memoryHeapUsedBytes", heap.totalMemory() - heap.freeMemory())
            .put("memoryHeapAllocatedBytes", heap.totalMemory())
            .put("memoryHeapMaxBytes", heap.maxMemory())
            .put("batteryOptimizationExempt", power.isIgnoringBatteryOptimizations(app.packageName))
            .put("backgroundRendererEnabled", app.getSharedPreferences("abwr_settings", 0)
                .getBoolean("experimental_background_renderer", false))
            .put("backgroundRendererActive", backgroundHost != null)
            .put("backgroundRendererPhase", backgroundRenderPhase())
            .put("backgroundRecoveryAttempts", backgroundRecoveryAttempts)
            .put("backgroundRecoveryCooldownMs", if (lastBackgroundRecoveryElapsedMs == 0L)
                0L else (20000L - (SystemClock.elapsedRealtime() -
                lastBackgroundRecoveryElapsedMs)).coerceAtLeast(0L))
            .put("backgroundDisplayFrames", backgroundHost?.framesReceived ?: 0L)
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

    fun humanNavigate(url: String) {
        if (runCatching { requireWebUrl(url) }.isSuccess) {
            val destination = currentSession(activeProfile)
            destination.loadUri(url)
            recordNavigationTarget(destination, activeProfile, url)
        }
    }
    fun back(profile: String = activeProfile) {
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        currentSession(profile).goBack()
    }
    fun forward(profile: String = activeProfile) {
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        currentSession(profile).goForward()
    }
    fun reload(profile: String = activeProfile) {
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        currentSession(profile).reload()
    }
    // User-driven activity controls intentionally remain functional during manual takeover.
    fun humanBack() { currentSession().goBack() }
    fun humanForward() { currentSession().goForward() }
    fun humanReload() { currentSession().reload() }
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
            .put("liveBackgroundScreenshotSupported", false)
            .put("cachedAvailable", cached != null)
            .put("capturedAtMs", cached?.capturedAt ?: JSONObject.NULL)
            .put("ageMs", cached?.let { System.currentTimeMillis() - it.capturedAt } ?: JSONObject.NULL)
            .put("bytes", cached?.jpeg?.size ?: 0)
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

    fun commandAsync(input: JSONObject): CompletableFuture<JSONObject> {
        val profile = profileForRequest(input)
        val action = input.optString("action")
        if (profile in manualProfiles && action in setOf("click", "type", "scroll", "scrollTo", "select", "press", "keyboard", "focus", "clear", "toggle")) {
            return CompletableFuture.failedFuture(IllegalStateException("Manual takeover active"))
        }
        if (action !in setOf("snapshot", "dom", "find", "click", "type", "scroll", "scrollTo",
            "status", "cookie_metadata", "storage_inspect", "devtools", "compatibility", "read", "wait",
            "select", "press", "keyboard", "focus", "clear", "toggle", "links")) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Unsupported action"))
        }
        inFlightByProfile[profile] = (inFlightByProfile[profile] ?: 0) + 1
        val session = try { currentSession(profile) } catch (e: Exception) {
            releaseInFlight(profile)
            return CompletableFuture.failedFuture(e)
        }
        val port = ports[session] ?: run {
            releaseInFlight(profile)
            return CompletableFuture.failedFuture(
                IllegalStateException("Page bridge not ready. Wait for page load and retry."))
        }
        val id = UUID.randomUUID().toString()
        val request = JSONObject(input.toString()).put("id", id)
        val future = CompletableFuture<JSONObject>()
        bridgeRequests.add(id, session, port, future)
        val timeout = Runnable {
            bridgeRequests.fail(id, java.util.concurrent.TimeoutException("Page bridge response timed out"))
        }
        ui.postDelayed(timeout, 14_000L)
        future.whenComplete { _, _ ->
            ui.post {
                ui.removeCallbacks(timeout)
                bridgeRequests.remove(id, future)
                releaseInFlight(profile)
            }
        }
        try { port.postMessage(request) }
        catch (e: Exception) { bridgeRequests.fail(id, e) }
        return future
    }

    fun captureAsync(full: Boolean = false, profile: String = activeProfile): CompletableFuture<ByteArray> {
        assertMain()
        val result = CompletableFuture<ByteArray>()
        if (profile != activeProfile) {
            result.completeExceptionally(IllegalStateException("Background profile has no display surface; open the profile in the app to capture pixels"))
            return result
        }
        val session = currentSession()
        if (view?.session !== session || view?.isAttachedToWindow != true) {
            result.completeExceptionally(IllegalStateException("Screenshot needs a visible and ready GeckoView surface"))
            return result
        }
        if (full) {
            result.completeExceptionally(UnsupportedOperationException("Full-page screenshot requires a separate Gecko API integration"))
            return result
        }
        if (captureInFlight?.isDone == false) {
            result.completeExceptionally(IllegalStateException("Screenshot already in progress; retry shortly"))
            return result
        }
        val host = backgroundHost
        if (host != null && host.framesReceived == 0L &&
            backgroundSurfaceStartedElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - backgroundSurfaceStartedElapsedMs >= 7000L) {
            result.completeExceptionally(IllegalStateException(
                "Background display is attached but has produced no frame; " +
                "check /v1/diagnostics and try /v1/background/recover or return to foreground"))
            return result
        }
        val imageGeneration = screenshotGeneration[profile] ?: 0L
        val originSession = session
        val originView = view!!
        val originSurfaceGeneration = surfaceGeneration
        captureInFlight = result
        captureSession = session
        val timeout = Runnable {
            if (!result.isDone) {
                lastScreenshotError = "Screenshot did not finish within 12 seconds"
                result.completeExceptionally(java.util.concurrent.TimeoutException(lastScreenshotError))
            }
        }
        ui.postDelayed(timeout, 12000L)
        result.whenComplete { _, _ ->
            ui.post {
                ui.removeCallbacks(timeout)
                if (captureInFlight === result) { captureInFlight = null; captureSession = null }
            }
        }
        val pixels = try { originView.capturePixels() } catch (error: Exception) {
            result.completeExceptionally(error)
            return result
        }
        pixels.accept({ bitmap: Bitmap? ->
            if (bitmap == null) {
                result.completeExceptionally(IllegalStateException("GeckoView returned no screenshot bitmap"))
                return@accept
            }
            if (result.isDone) { bitmap.recycle(); return@accept }
            imageEncoder.execute {
                var scaled: Bitmap? = null
                val encoded = runCatching {
                    scaled = if (bitmap.width.toLong() * bitmap.height > 8_000_000L) {
                        val ratio = kotlin.math.sqrt(8_000_000.0 / (bitmap.width.toDouble() * bitmap.height))
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                            (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                    } else bitmap
                    val bytes = ByteArrayOutputStream()
                    check(scaled!!.compress(Bitmap.CompressFormat.JPEG, 78, bytes)) { "JPEG encoding failed" }
                    bytes.toByteArray()
                }
                if (scaled !== bitmap) scaled?.recycle()
                bitmap.recycle()
                ui.post {
                    if (result.isDone) return@post
                    try {
                        check(activeProfile == profile && view === originView &&
                            surfaceGeneration == originSurfaceGeneration &&
                            (screenshotGeneration[profile] ?: 0L) == imageGeneration &&
                            selectedLoadedSession(profile) === originSession) {
                            "Page or display surface changed during screenshot; request a new capture"
                        }
                        val snapshot = encoded.getOrThrow()
                        val now = System.currentTimeMillis()
                        lastScreenshotAt = now
                        lastScreenshotError = null
                        if (snapshot.size <= 4 * 1024 * 1024) {
                            lastShots[profile] = CachedShot(snapshot, now)
                            while (lastShots.size > 3 || lastShots.values.sumOf { it.jpeg.size } > 4 * 1024 * 1024) {
                                lastShots.remove(lastShots.keys.first())
                            }
                        }
                        result.complete(snapshot)
                    } catch (error: Exception) {
                        lastScreenshotError = error.message ?: error.javaClass.simpleName
                        result.completeExceptionally(error)
                    }
                }
            }
        }, { error ->
            lastScreenshotError = error?.message ?: "GeckoView capture failed"
            result.completeExceptionally(error ?: IllegalStateException("GeckoView capture failed"))
        })
        return result
    }

    /** Blocking helper for HTTP worker threads. Never invoke from UI thread. */
    fun <T> uiCall(timeoutSeconds: Long = 12, operation: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Cannot block UI thread" }
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
        }
    }
    fun <T> uiAsync(timeoutSeconds: Long = 15, operation: () -> CompletableFuture<T>): T {
        check(Looper.myLooper() != Looper.getMainLooper())
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
        }
    }
}
