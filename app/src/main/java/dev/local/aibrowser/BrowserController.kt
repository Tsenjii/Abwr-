package dev.local.aibrowser

import android.app.Application
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
    private fun currentSession(profile: String = activeProfile): GeckoSession = popupSessions[profile] ?: openProfile(profile)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val profileUrls = linkedMapOf<String, String>()
    private var activeProfile = "G001"
    private var view: GeckoView? = null
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
    }

    private fun validProfile(id: String) = id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,30}"))
    private fun notifyUi() { onStateChanged?.invoke() }
    private fun assertMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun requireWebUrl(raw: String): String {
        require(raw.length in 1..4096) { "URL length out of range" }
        val value = URI(raw)
        require(value.scheme?.lowercase() in setOf("https", "http") && !value.host.isNullOrBlank()) { "Only absolute HTTP(S) URLs supported" }
        require(value.userInfo == null) { "Credentials in URL not allowed" }
        return raw
    }
    private fun persist() {
        val arr = JSONArray()
        profileUrls.forEach { (id, url) -> arr.put(JSONObject().put("id", id).put("url", url)) }
        store.edit().putString("profiles", arr.toString()).putString("active", activeProfile).apply()
    }

    /** Called on main thread. The runtime is process-scoped, not Activity-scoped. */
    fun initialize() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (runtime != null) return
        try {
            runtime = GeckoRuntime.create(app, GeckoRuntimeSettings.Builder().javaScriptEnabled(true).build())
            runtime!!.webExtensionController.ensureBuiltIn(
                "resource://android/assets/agentbridge/", "agentbridge@local.browser"
            ).accept({ ext ->
                extension = ext
                engineReady = true
                (sessions.values + popupSessions.values).forEach { installSessionDelegate(it) }
                notifyUi()
            }, { err ->
                extensionError = err.message ?: "Extension initialization failed"
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
                    persist()
                }
                notifyUi()
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {}
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                profileUrls[id] = url
                persist()
                notifyUi()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) { notifyUi() }
        }
        session.open(runtime ?: error("Runtime not ready"))
        sessions[id] = session
        installSessionDelegate(session)
        session.loadUri(profileUrls[id] ?: "https://example.org/")
        pruneSessions()
        return session
    }

    private fun pruneSessions() {
        // Profiles remain persisted even when sessions are evicted from RAM.
        while (sessions.size > 3) {
            val evictId = sessions.keys.firstOrNull { it != activeProfile } ?: return
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

    fun bindView(browserView: GeckoView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        initialize()
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
        .put("residentSessions", sessions.size)
        .put("manualMode", manualMode)
        .put("manualProfiles", JSONArray(manualProfiles.toList()))
        .put("residentLimit", 3)
        .put("profileCapacity", 300)
        .put("backgroundScreenshotGuaranteed", false)
        .put("viewAttached", view != null)
        .put("extensionPortReady", ports.containsKey(currentSession()))
        .put("popupActive", popupSessions.containsKey(activeProfile))
        .put("url", profileUrls[activeProfile] ?: "")
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
        profileUrls[profile] = url
        persist()
        return JSONObject().put("navigating", url).put("profile", profile)
    }
    fun humanNavigate(url: String) {
        if (runCatching { requireWebUrl(url) }.isSuccess) {
            openProfile(activeProfile).loadUri(url)
            profileUrls[activeProfile] = url
            persist()
        }
    }
    fun back() { currentSession().goBack() }
    fun forward() { currentSession().goForward() }
    fun reload() { currentSession().reload() }
    fun tabs(): JSONObject = JSONObject().put("profile", activeProfile)
        .put("popupActive", popupSessions.containsKey(activeProfile))
        .put("tabs", JSONArray().put(JSONObject().put("id", "main").put("active", !popupSessions.containsKey(activeProfile)))
            .apply { if (popupSessions.containsKey(activeProfile)) put(JSONObject().put("id", "popup").put("active", true)) })
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
    fun commandAsync(input: JSONObject): CompletableFuture<JSONObject> {
        val profile = profileForRequest(input)
        val action = input.optString("action")
        if (profile in manualProfiles && action in setOf("click", "type", "scroll", "select", "press", "focus")) {
            return CompletableFuture.failedFuture(IllegalStateException("Manual takeover active"))
        }
        if (action !in setOf("dom", "find", "click", "type", "scroll", "status", "cookie_metadata", "wait", "select", "press", "focus", "links")) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Unsupported action"))
        }
        val session = currentSession(profile)
        val port = ports[session] ?: return CompletableFuture.failedFuture(
            IllegalStateException("Page bridge not ready. Wait for page load and retry."))
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
        future.whenComplete { _, _ -> pending.remove(id) }
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
        val pixels = if (full) view!!.captureFullPage() else view!!.capturePixels()
        pixels.accept({ bitmap: Bitmap ->
            try {
                val bytes = ByteArrayOutputStream()
                // Guard against excessive image allocations from very long documents.
                val scaled = if (bitmap.width.toLong() * bitmap.height > 8_000_000L) {
                    val ratio = kotlin.math.sqrt(8_000_000.0 / (bitmap.width.toDouble() * bitmap.height)).toFloat()
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                } else bitmap
                scaled.compress(Bitmap.CompressFormat.JPEG, 78, bytes)
                if (scaled !== bitmap) scaled.recycle()
                result.complete(bytes.toByteArray())
            } catch (e: Exception) { result.completeExceptionally(e) }
        }, { error -> result.completeExceptionally(error) })
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