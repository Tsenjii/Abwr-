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
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.UUID
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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
    // Each browser tab is a distinct GeckoSession, within the same profile contextId.
    private val tabUrls = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val bookmarkUrls = linkedMapOf<String, LinkedHashSet<String>>()
    private val historyUrls = linkedMapOf<String, MutableList<String>>()
    private val tabTitles = mutableMapOf<String, String>()
    private val tabSessions = LinkedHashMap<String, GeckoSession>(8, .75f, true)
    private val activeTabs = mutableMapOf<String, String>()
    private var nextTabId: Long = 1L
    private fun tabKey(profile: String, tabId: String) = "$profile/$tabId"
    private fun selectedTab(profile: String = activeProfile) = activeTabs[profile] ?: "main"
    private fun currentSession(profile: String = activeProfile): GeckoSession =
        popupSessions[profile] ?: selectedTab(profile).takeIf { it != "main" }
            ?.let { openExtraTab(profile, it) } ?: openProfile(profile)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    // UI-thread-owned bookkeeping: do not evict a GeckoSession that is serving an Agent request.
    private val inFlightByProfile = mutableMapOf<String, Int>()
    private data class PendingNavigation(
        val expectedUrl: String, val future: CompletableFuture<JSONObject>,
        var sawRequestedPageStart: Boolean = false
    )
    private val navigationWaiters = mutableMapOf<String, PendingNavigation>()
    private val controllerCreatedAt = SystemClock.elapsedRealtime()
    private val profileUrls = linkedMapOf<String, String>()
    private var activeProfile = "G001"
    private val pageLoading = mutableMapOf<String, Boolean>()
    private val pageTitles = mutableMapOf<String, String>()
    private data class CachedShot(val jpeg: ByteArray, val capturedAt: Long)
    // Bounded JPEG cache: at most 3 images and 4 MiB across all profiles.
    private val lastShots = LinkedHashMap<String, CachedShot>(4, .75f, true)
    private var lastScreenshotError: String? = null
    private var lastScreenshotAt: Long = 0L
    private var view: GeckoView? = null
    private var backgroundHost: OffscreenBrowserDisplay? = null
    private var backgroundSurfaceError: String? = null
    private var pendingBackgroundAttach: Runnable? = null
    private val manualProfiles = mutableSetOf<String>()
    val manualMode: Boolean get() = activeProfile in manualProfiles
    @Volatile var engineReady = false
        private set
    @Volatile var extensionError: String? = null
        private set
    var onStateChanged: (() -> Unit)? = null

    init {
        val saved = store.getString("profiles", null)
        if (saved != null) runCatching {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                if (validProfile(id)) profileUrls[id] = obj.optString("url", "https://example.org/")
            }
        }
        if (profileUrls.isEmpty()) profileUrls["G001"] = "https://example.org/"
        activeProfile = store.getString("active", "G001")!!.takeIf { profileUrls.containsKey(it) } ?: profileUrls.keys.first()
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
            .putString("collections_v1", collections.toString()).apply()
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
            ports[sourceSession] = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val json = message as? JSONObject ?: return
                    val id = json.optString("id", "")
                    if (id.isNotBlank()) pending.remove(id)?.complete(json)
                }
                override fun onDisconnect(port: WebExtension.Port) {
                    if (ports[sourceSession] === port) ports.remove(sourceSession)
                }
            })
        }
    }

    private fun installSessionDelegate(session: GeckoSession) {
        extension?.let { session.webExtensionController.setMessageDelegate(it, messageDelegate, "aibrowser") }
    }

    private fun openProfile(id: String): GeckoSession {
        sessions[id]?.let { return it }
        require(profileUrls.containsKey(id)) { "Unknown profile: $id" }
        // Context ID partitions persistent Gecko cookies and localStorage.
        val session = GeckoSession(GeckoSessionSettings.Builder().contextId(id).build())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Gecko opens the returned NEW, unopened session. Never call open() here.
                val previous = popupSessions.remove(id)
                if (previous != null) {
                    if (view?.session === previous) view?.releaseSession()
                    ports.remove(previous)?.disconnect()
                    previous.close()
                }
                val child = GeckoSession(GeckoSessionSettings.Builder().contextId(id).build())
                popupSessions[id] = child
                // NavigationDelegate for a popup will close it when the web page finishes.
                child.contentDelegate = object : GeckoSession.ContentDelegate {
                    override fun onCloseRequest(session: GeckoSession) { closePopup(id) }
                }
                child.progressDelegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(session: GeckoSession, url: String) { notifyUi() }
                    override fun onPageStop(session: GeckoSession, success: Boolean) { notifyUi() }
                }
                installSessionDelegate(child)
                if (id == activeProfile) ui.post { attachActive() }
                return GeckoResult.fromValue(child)
            }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    profileUrls[id] = url
                    recordVisit(id, url)
                }
                notifyUi()
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageTitles[id] = title ?: ""
                notifyUi()
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                navigationWaiters[id]?.let { pending ->
                    if (url.trimEnd('/') == pending.expectedUrl.trimEnd('/'))
                        pending.sawRequestedPageStart = true
                }
                pageLoading[id] = true
                pageTitles.remove(id)
                profileUrls[id] = url
                recordVisit(id, url)
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                pageLoading[id] = false
                navigationWaiters[id]?.takeIf { it.sawRequestedPageStart }?.let { waiter ->
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
        val tab = GeckoSession(GeckoSessionSettings.Builder().contextId(profile).build())
        tab.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null && runCatching { requireWebUrl(url) }.isSuccess) {
                    tabUrls[profile]?.set(id, url)
                    recordVisit(profile, url)
                }
                notifyUi()
            }
        }
        tab.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tabTitles[key] = title ?: ""
                notifyUi()
            }
        }
        tab.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                navigationWaiters[profile]?.let { pending ->
                    if (url.trimEnd('/') == pending.expectedUrl.trimEnd('/'))
                        pending.sawRequestedPageStart = true
                }
                tabUrls[profile]?.set(id, url)
                pageLoading[profile] = true
                recordVisit(profile, url)
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                pageLoading[profile] = false
                navigationWaiters[profile]?.takeIf { it.sawRequestedPageStart }?.let { waiter ->
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
        pruneTabSessions()
        return tab
    }

    private fun pruneTabSessions() {
        // Bound browser tab rendering while keeping tab URLs and Gecko profile storage.
        while (tabSessions.size > 8) {
            val victim = tabSessions.keys.firstOrNull { key ->
                val owner = key.substringBefore('/')
                val id = key.substringAfter('/')
                (selectedTab(owner) != id || owner != activeProfile) &&
                    (inFlightByProfile[owner] ?: 0) == 0
            } ?: break
            val old = tabSessions.remove(victim) ?: break
            if (view?.session === old) view?.releaseSession()
            ports.remove(old)?.disconnect()
            old.close()
        }
    }

    fun createTab(url: String = "https://example.org/", profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        val list = tabUrls.getOrPut(profile) { LinkedHashMap() }
        require(list.size < 12) { "Maximum 12 extra tabs per profile" }
        val id = "T" + nextTabId++
        list[id] = url
        activeTabs[profile] = id
        if (profile == activeProfile) attachActive()
        else openExtraTab(profile, id)
        persist()
        notifyUi()
        return JSONObject().put("id", id).put("profile", profile).put("url", url)
    }
    fun selectTab(id: String, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(id == "main" || tabUrls[profile]?.containsKey(id) == true) { "Unknown tab" }
        activeTabs[profile] = id
        persist()
        if (profile == activeProfile) attachActive()
        notifyUi()
        return tabs(profile)
    }
    fun closeTab(id: String, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(id != "main") { "The main tab cannot be closed" }
        require(tabUrls[profile]?.remove(id) != null) { "Unknown tab" }
        val key = tabKey(profile, id)
        val session = tabSessions.remove(key)
        if (session != null) {
            if (view?.session === session) view?.releaseSession()
            ports.remove(session)?.disconnect()
            session.close()
        }
        tabTitles.remove(key)
        if (selectedTab(profile) == id) activeTabs[profile] = "main"
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
            .put("id", "popup").put("active", true).put("popup", true))
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
                it != activeProfile && it != preserveId && (inFlightByProfile[it] ?: 0) == 0
            } ?: return
            val old = sessions.remove(evictId) ?: return
            popupSessions.remove(evictId)?.let { popup ->
                if (view?.session === popup) view?.releaseSession()
                ports.remove(popup)?.disconnect()
                popup.close()
            }
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
                val host = OffscreenBrowserDisplay(app)
                try {
                    val surfaceView = host.start()
                    backgroundHost = host
                    view = surfaceView
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
            view?.releaseSession()
            view = null
            backgroundHost?.close()
            backgroundHost = null
        }
        if (view !== browserView) {
            view?.releaseSession()
            view = browserView
        }
        attachActive()
    }
    fun unbindView(browserView: GeckoView) {
        if (view === browserView) {
            browserView.releaseSession()
            view = null
        }
    }
    private fun attachActive() {
        val target = view ?: return
        val current = currentSession()
        if (target.session !== current) {
            target.releaseSession()
            target.setSession(current)
        }
        notifyUi()
    }

    fun profiles(): JSONArray = JSONArray().apply {
        profileUrls.forEach { (id, url) ->
            put(JSONObject().put("id", id).put("url", url).put("active", id == activeProfile)
                .put("loaded", sessions.containsKey(id)).put("manual", id in manualProfiles))
        }
    }
    fun status(): JSONObject = JSONObject()
        .put("ready", engineReady)
        .put("extensionError", extensionError)
        .put("activeProfile", activeProfile)
        .put("profileCount", profileUrls.size)
        .put("residentSessions", sessions.size + tabSessions.size)
        .put("trackingProtectionEnabled", trackingProtectionEnabled())
        .put("inFlightProfiles", JSONArray(inFlightByProfile.keys.toList()))
        .put("manualMode", manualMode)
        .put("manualProfiles", JSONArray(manualProfiles.toList()))
        .put("residentLimit", 3)
        .put("profileCapacity", 300)
        .put("backgroundScreenshotGuaranteed", false)
        .put("viewAttached", view != null)
        .put("extensionPortReady", ports.containsKey(currentSession()))
        .put("popupActive", popupSessions.containsKey(activeProfile))
        .put("activeTab", selectedTab())
        .put("tabCount", 1 + (tabUrls[activeProfile]?.size ?: 0))
        .put("url", if (selectedTab() == "main") profileUrls[activeProfile] ?: ""
            else tabUrls[activeProfile]?.get(selectedTab()) ?: "")
        .put("title", if (selectedTab() == "main") pageTitles[activeProfile] ?: ""
            else tabTitles[tabKey(activeProfile, selectedTab())] ?: "")
        .put("loading", pageLoading[activeProfile] ?: false)
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
        openProfile(id)
        attachActive()
        return status()
    }
    fun createProfile(id: String): JSONObject {
        assertMain()
        require(validProfile(id)) { "Profile IDs must be 1–31 letters, digits, _ or - and start with a letter" }
        require(!profileUrls.containsKey(id)) { "Profile already exists" }
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
    fun removeProfile(id: String): JSONObject {
        assertMain()
        require(id in profileUrls) { "Unknown profile" }
        require(profileUrls.size > 1) { "Cannot remove the final profile" }
        popupSessions.remove(id)?.let { popup ->
            if (view?.session === popup) view?.releaseSession()
            ports.remove(popup)?.disconnect()
            popup.close()
        }
        val session = sessions.remove(id)
        if (session != null) {
            if (view?.session === session) view?.releaseSession()
            ports.remove(session)?.disconnect()
            session.close()
        }
        profileUrls.remove(id)
        bookmarkUrls.remove(id)
        historyUrls.remove(id)
        tabUrls.remove(id)
        activeTabs.remove(id)
        tabSessions.keys.filter { it.startsWith("$id/") }.toList().forEach { key ->
            val tab = tabSessions.remove(key) ?: return@forEach
            if (view?.session === tab) view?.releaseSession()
            ports.remove(tab)?.disconnect()
            tab.close()
        }
        pageLoading.remove(id)
        pageTitles.remove(id)
        lastShots.remove(id)
        navigationWaiters.remove(id)?.future?.completeExceptionally(IllegalStateException("Profile deleted"))
        runtime?.storageController?.clearDataForSessionContext(id)
        manualProfiles.remove(id)
        if (activeProfile == id) activeProfile = profileUrls.keys.first()
        persist()
        attachActive()
        // Data clear is requested after all GeckoSessions sharing this context are closed.
        return JSONObject().put("removed", id).put("activeProfile", activeProfile)
    }
    fun profileForRequest(input: JSONObject): String = input.optString("profile", activeProfile).also {
        require(it in profileUrls) { "Unknown profile" }
    }
    fun ensureTarget(profile: String) {
        assertMain()
        if (profile != activeProfile) switchProfile(profile)
    }
    fun navigate(url: String, profile: String = activeProfile): JSONObject {
        assertMain()
        require(profile in profileUrls) { "Unknown profile" }
        require(profile !in manualProfiles) { "Manual takeover active for requested profile" }
        requireWebUrl(url)
        currentSession(profile).loadUri(url)
        if (selectedTab(profile) == "main") profileUrls[profile] = url
        else tabUrls[profile]?.set(selectedTab(profile), url)
        persist()
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
        val future = CompletableFuture<JSONObject>()
        val pending = PendingNavigation(url, future)
        navigationWaiters[profile] = pending
        ui.postDelayed({
            if (!future.isDone) future.completeExceptionally(
                java.util.concurrent.TimeoutException("Navigation did not finish within timeoutMs"))
        }, timeoutMs)
        future.whenComplete { _, _ ->
            ui.post {
                if (navigationWaiters[profile] === pending) navigationWaiters.remove(profile)
            }
        }
        try { navigate(url, profile) }
        catch (e: Exception) { future.completeExceptionally(e) }
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
            .put("backgroundDisplayFrames", backgroundHost?.framesReceived ?: 0L)
            .put("backgroundLastFrameAgeMs", backgroundHost?.lastFrameElapsedMs?.takeIf { it > 0L }
                ?.let { SystemClock.elapsedRealtime() - it } ?: JSONObject.NULL)
            .put("backgroundSurfaceError", backgroundSurfaceError ?: JSONObject.NULL)
            .put("residentProfiles", JSONArray(sessions.keys.toList()))
            .put("residentTabSessions", tabSessions.size)
            .put("inFlightProfiles", JSONArray(inFlightByProfile.keys.toList()))
            .put("lastScreenshotAtMs", if (lastScreenshotAt == 0L) JSONObject.NULL else lastScreenshotAt)
            .put("lastScreenshotError", lastScreenshotError ?: JSONObject.NULL)
    }

    fun humanNavigate(url: String) {
        if (runCatching { requireWebUrl(url) }.isSuccess) {
            currentSession(activeProfile).loadUri(url)
            if (selectedTab() == "main") profileUrls[activeProfile] = url
            else tabUrls[activeProfile]?.set(selectedTab(), url)
            persist()
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

    fun closePopup(profile: String = activeProfile): JSONObject {
        require(profile in profileUrls) { "Unknown profile" }
        popupSessions.remove(profile)?.let { popup ->
            if (view?.session === popup) view?.releaseSession()
            ports.remove(popup)?.disconnect()
            popup.close()
        }
        if (profile == activeProfile) attachActive()
        return tabs()
    }

    /** Dispatches a DOM action asynchronously via a GeckoView native messaging port. */
    private fun releaseInFlight(profile: String) {
        assertMain()
        val count = (inFlightByProfile[profile] ?: 0) - 1
        if (count <= 0) inFlightByProfile.remove(profile)
        else inFlightByProfile[profile] = count
        pruneSessions()
    }

    fun commandAsync(input: JSONObject): CompletableFuture<JSONObject> {
        val profile = profileForRequest(input)
        val action = input.optString("action")
        if (profile in manualProfiles && action in setOf("click", "type", "scroll", "scrollTo", "select", "press", "keyboard", "focus", "clear", "toggle")) {
            return CompletableFuture.failedFuture(IllegalStateException("Manual takeover active"))
        }
        if (action !in setOf("snapshot", "dom", "find", "click", "type", "scroll", "scrollTo",
            "status", "cookie_metadata", "storage_inspect", "devtools", "wait",
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
        pending[id] = future
        ui.postDelayed({
            if (pending.remove(id, future)) {
                future.completeExceptionally(java.util.concurrent.TimeoutException("Page bridge response timed out"))
            }
        }, 14_000L)
        try { port.postMessage(request) }
        catch (e: Exception) { pending.remove(id); future.completeExceptionally(e) }
        future.whenComplete { _, _ ->
            pending.remove(id)
            ui.post { releaseInFlight(profile) }
        }
        return future
    }

    fun captureAsync(full: Boolean = false, profile: String = activeProfile): CompletableFuture<ByteArray> {
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
        val pixels = view!!.capturePixels()
        pixels.accept({ bitmap: Bitmap? ->
            try {
                requireNotNull(bitmap) { "GeckoView returned no screenshot bitmap" }
                val bytes = ByteArrayOutputStream()
                // Guard against excessive image allocations from very long documents.
                val scaled = if (bitmap.width.toLong() * bitmap.height > 8_000_000L) {
                    val ratio = kotlin.math.sqrt(8_000_000.0 / (bitmap.width.toDouble() * bitmap.height)).toFloat()
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                } else bitmap
                scaled.compress(Bitmap.CompressFormat.JPEG, 78, bytes)
                if (scaled !== bitmap) scaled.recycle()
                val snapshot = bytes.toByteArray()
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
            } catch (e: Exception) {
                lastScreenshotError = e.message ?: e.javaClass.simpleName
                result.completeExceptionally(e)
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
        ui.post { try { future.complete(operation()) } catch (e: Exception) { future.completeExceptionally(e) } }
        return future.get(timeoutSeconds, TimeUnit.SECONDS)
    }
    fun <T> uiAsync(timeoutSeconds: Long = 15, operation: () -> CompletableFuture<T>): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        val promise = CompletableFuture<CompletableFuture<T>>()
        ui.post { try { promise.complete(operation()) } catch (e: Exception) { promise.completeExceptionally(e) } }
        val nested = promise.get(timeoutSeconds, TimeUnit.SECONDS)
        return nested.get(timeoutSeconds, TimeUnit.SECONDS)
    }
}