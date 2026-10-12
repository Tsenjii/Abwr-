package dev.local.aibrowser

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Gecko owns installation, signature verification and persistence. Never installs an unsigned add-on. */
internal class BrowserExtensions(private val changed: () -> Unit) {
    interface Host {
        fun confirmExtension(title: String, details: String, done: (Boolean) -> Unit)
        fun openExtensionPage(extension: WebExtension, session: GeckoSession, open: () -> Unit)
    }
    var host: Host? = null
    private lateinit var runtime: GeckoRuntime
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val installed = linkedMapOf<String, WebExtension>()
    private val sessions = java.util.WeakHashMap<GeckoSession, Boolean>()
    private var activeSession: GeckoSession? = null
    private val defaults = mutableMapOf<String, WebExtension.Action>()
    private val overrides = java.util.WeakHashMap<GeckoSession, MutableMap<String, WebExtension.Action>>()
    private val pageDefaults = mutableMapOf<String, WebExtension.Action>()
    private val pageOverrides = java.util.WeakHashMap<GeckoSession, MutableMap<String, WebExtension.Action>>()
    private val actionEvents = mutableMapOf<String, String>()
    private var toolsPort: WebExtension.Port? = null
    private val toolConnections = BridgeConnections<WebExtension.Port>()
    private var scripts = JSONArray()
    var scriptStatus = JSONObject().put("connected", false).put("active", 0)
        private set
    var error: String? = null
        private set
    var loading = false
        private set
    var busy = false
        private set
    private val builtinId = "agentbridge@local.browser"

    fun initialize(engine: GeckoRuntime) {
        runtime = engine
        runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(extension: WebExtension, permissions: Array<String>,
                origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<WebExtension.PermissionPromptResponse> {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                confirm("安裝 ${name(extension)}？", permissions, origins, dataCollectionPermissions) {
                    result.complete(WebExtension.PermissionPromptResponse(it, false, false))
                }
                return result
            }
            override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<String>,
                newOrigins: Array<String>, newDataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny> =
                permission("${name(extension)} 需要新增權限", newPermissions, newOrigins, newDataCollectionPermissions)
            override fun onOptionalPrompt(extension: WebExtension, permissions: Array<String>,
                origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny> =
                permission("${name(extension)} 請求權限", permissions, origins, dataCollectionPermissions)
        })
        runtime.webExtensionController.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onReady(extension: WebExtension) { register(extension) }
        })
        refresh()
    }
    private fun confirm(title: String, permissions: Array<String>, origins: Array<String>, data: Array<String>, done: (Boolean) -> Unit) {
        val message = buildString {
            append("網站存取\n").append(origins.joinToString("\n").ifEmpty { "無新增網站" })
            append("\n\n功能權限\n").append(permissions.joinToString("、").ifEmpty { "無新增權限" })
            if (data.isNotEmpty()) append("\n\n資料使用聲明\n").append(data.joinToString("、"))
            append("\n\n擴充功能會套用到符合權限的所有容器。")
        }
        host?.confirmExtension(title, message, done) ?: done(false)
    }
    private fun permission(title: String, p: Array<String>, o: Array<String>, d: Array<String>): GeckoResult<AllowOrDeny> {
        val reply = GeckoResult<AllowOrDeny>()
        confirm(title, p, o, d) { reply.complete(if (it) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
        return reply
    }
    fun name(extension: WebExtension) = extension.metaData?.name?.takeIf { it.isNotBlank() } ?: extension.id
    fun list() = installed.values.toList()
    fun refresh(done: (() -> Unit)? = null) {
        if (!::runtime.isInitialized) return
        loading = true; error = null; changed()
        runtime.webExtensionController.list().accept({ items ->
            installed.clear()
            items?.forEach(::register)
            loading = false; changed(); done?.invoke()
        }, { failure -> loading = false; error = failure?.message ?: "無法讀取擴充功能"; changed(); done?.invoke() })
    }
    private fun register(extension: WebExtension) {
        if (extension.id == builtinId) { attachBuiltin(extension); return }
        installed[extension.id] = extension
        extension.setActionDelegate(actions)
        extension.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? = newPage(source, null, false)
            override fun onOpenOptionsPage(source: WebExtension) { options(source) }
        })
        sessions.keys.toList().forEach { it.webExtensionController.setActionDelegate(extension, actions) }
        changed()
    }
    fun attachSession(session: GeckoSession) {
        sessions[session] = true
        installed.values.forEach { session.webExtensionController.setActionDelegate(it, actions) }
    }
    fun activateSession(session: GeckoSession, restore: Boolean = false) {
        if (!::runtime.isInitialized || !session.isOpen || (!restore && activeSession === session)) return
        if (activeSession !== session) activeSession?.takeIf { it.isOpen }?.let { runtime.webExtensionController.setTabActive(it, false) }
        runtime.webExtensionController.setTabActive(session, true)
        activeSession = session
    }
    fun attachBuiltin(extension: WebExtension) {
        extension.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val active = toolConnections.connected(port)
                // Only the built-in extension's background page may receive script source.
                if (!active || port.sender.session != null || port.sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION) {
                    toolConnections.retire(port); port.setDelegate(object : WebExtension.PortDelegate {}); return
                }
                toolsPort?.takeIf { it !== port }?.let(toolConnections::retire)
                toolsPort = port
                scriptStatus.put("connected", true)
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        if (toolsPort !== port || !toolConnections.writable(port)) return
                        val json = message as? JSONObject ?: return
                        if (json.optBoolean("toolsReady")) syncScripts(scripts)
                        if (json.optBoolean("scriptsStatus")) {
                            scriptStatus = json.put("connected", true); changed()
                        }
                    }
                    override fun onDisconnect(port: WebExtension.Port) {
                        toolConnections.closed(port)
                        if (toolsPort === port) {
                            toolsPort = null; scriptStatus.put("connected", false); changed()
                        }
                    }
                })
                syncScripts(scripts)
            }
        }, "abwr_tools")
    }
    fun syncScripts(value: JSONArray) {
        scripts = value
        runCatching { toolsPort?.takeIf(toolConnections::writable)?.postMessage(JSONObject().put("scripts", scripts)) }
            .onFailure { scriptStatus.put("connected", false).put("error", it.message ?: "Script broker disconnected"); changed() }
    }
    fun install(url: String, done: (String?) -> Unit) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host?.lowercase() !in setOf("addons.mozilla.org", "addons.cdn.mozilla.net")) {
            done("請從 Mozilla 擴充商店選擇已簽章的擴充功能"); return
        }
        operation(done) { complete -> runtime.webExtensionController.install(url).accept({ extension ->
            extension?.takeIf { it.id !in installed }?.let(::register)
            // onReady may already have delivered complete URLs. Do not overwrite
            // it with the earlier install result's provisional metadata.
            refresh { complete(error) }
        }, { complete(it?.message ?: "安裝失敗") }) }
    }
    fun enable(extension: WebExtension, enabled: Boolean, done: (String?) -> Unit) = operation(done) { complete ->
        val result = if (enabled) runtime.webExtensionController.enable(extension, WebExtensionController.EnableSource.USER)
            else runtime.webExtensionController.disable(extension, WebExtensionController.EnableSource.USER)
        result.accept({ updated -> updated?.let(::register); complete(null) }, { complete(it?.message ?: "無法更新啟用狀態") })
    }
    fun update(extension: WebExtension, done: (String?) -> Unit) = operation(done) { complete ->
        runtime.webExtensionController.update(extension).accept({ updated -> updated?.let(::register); complete(null) },
            { complete(it?.message ?: "更新失敗") })
    }
    fun uninstall(extension: WebExtension, done: (String?) -> Unit) = operation(done) { complete ->
        runtime.webExtensionController.uninstall(extension).accept({
            installed.remove(extension.id); defaults.remove(extension.id); pageDefaults.remove(extension.id); actionEvents.remove(extension.id)
            overrides.values.forEach { it.remove(extension.id) }; pageOverrides.values.forEach { it.remove(extension.id) }; complete(null)
        }, { complete(it?.message ?: "移除失敗") })
    }
    private fun operation(done: (String?) -> Unit, run: ((String?) -> Unit) -> Unit) {
        if (busy) { done("另一項擴充操作尚未完成"); return }
        busy = true; error = null; changed()
        run { problem -> busy = false; error = problem; changed(); done(problem) }
    }
    fun options(extension: WebExtension) {
        val current = installed[extension.id] ?: extension
        val url = current.metaData?.optionsPageUrl?.takeIf { it.isNotBlank() } ?: return
        actionEvents[extension.id] = "options"
        newPage(current, url, true)
    }
    private fun resolvedAction(extension: WebExtension, session: GeckoSession?, page: Boolean = false): WebExtension.Action? {
        val fallback = (if (page) pageDefaults else defaults)[extension.id]
        val specific = session?.let { (if (page) pageOverrides else overrides)[it]?.get(extension.id) }
        val action = if (specific != null && fallback != null) specific.withDefault(fallback) else specific ?: fallback
        return action?.takeIf { it.enabled != false }
    }
    fun actionInfo(extension: WebExtension, session: GeckoSession?) = JSONObject()
        .put("browserActionReady", resolvedAction(extension, session) != null)
        .put("pageActionReady", resolvedAction(extension, session, true) != null)
        .put("lastEvent", actionEvents[extension.id] ?: JSONObject.NULL)
    fun openAction(extension: WebExtension, session: GeckoSession?): Boolean {
        session?.let { activateSession(it, restore = true) }
        val action = resolvedAction(extension, session) ?: resolvedAction(extension, session, true) ?: return false
        actionEvents[extension.id] = "click"
        action.click(); return true
    }
    private val actions = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (session == null) defaults[extension.id] = action
            else overrides.getOrPut(session) { mutableMapOf() }[extension.id] = action
        }
        override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (session == null) pageDefaults[extension.id] = action
            else pageOverrides.getOrPut(session) { mutableMapOf() }[extension.id] = action
        }
        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
            actionEvents[extension.id] = "toggle-popup"; return newPage(extension, null, true)
        }
        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
            actionEvents[extension.id] = "open-popup"; return newPage(extension, null, true)
        }
    }
    private fun newPage(extension: WebExtension, url: String?, open: Boolean): GeckoResult<GeckoSession>? {
        val owner = host ?: return null
        val session = GeckoSession()
        owner.openExtensionPage(extension, session) {
            if (open) { session.open(runtime); url?.let(session::loadUri) }
        }
        return GeckoResult.fromValue(session)
    }
    data class Listing(val id: String, val name: String, val summary: String, val author: String,
        val version: String, val rating: String, val url: String, val xpi: String)
    fun search(query: String, done: (List<Listing>?, String?) -> Unit) {
        val term = query.trim().take(160)
        worker.execute {
            val result = runCatching {
                val endpoint = "https://addons.mozilla.org/api/v5/addons/search/?app=android&type=extension&page_size=20&lang=zh-TW&q=" + URLEncoder.encode(term, "UTF-8")
                val connection = URL(endpoint).openConnection() as javax.net.ssl.HttpsURLConnection
                try {
                    connection.connectTimeout = 15000; connection.readTimeout = 15000
                    connection.setRequestProperty("Accept", "application/json")
                    require(connection.responseCode == 200) { "商店連線失敗（${connection.responseCode}）" }
                    val bytes = connection.inputStream.use { it.readBytesLimited(2_000_000) }
                    val rows = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("results")
                    (0 until rows.length()).mapNotNull { index ->
                        val item = rows.getJSONObject(index)
                        val version = item.optJSONObject("current_version") ?: return@mapNotNull null
                        val xpi = version.optJSONObject("file")?.optString("url").orEmpty()
                        if (xpi.isBlank()) return@mapNotNull null
                        fun local(key: String): String {
                            val value = item.opt(key)
                            if (value is JSONObject) return (listOf("zh-TW", "en-US") + value.keys().asSequence().toList())
                                .asSequence().mapNotNull { value.opt(it) as? String }.firstOrNull { it.isNotBlank() }.orEmpty()
                            return if (value is String) value else ""
                        }
                        Listing(item.optString("guid"), local("name"), local("summary").replace(Regex("<[^>]*>"), "").take(500),
                            item.optJSONArray("authors")?.optJSONObject(0)?.optString("name").orEmpty(),
                            version.optString("version"), item.optJSONObject("ratings")?.optDouble("average", 0.0)?.let { "%.1f".format(it) }.orEmpty(),
                            item.optString("url"), xpi)
                    }
                } finally { connection.disconnect() }
            }
            ui.post { done(result.getOrNull(), result.exceptionOrNull()?.message) }
        }
    }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        require(output.size() + n <= limit) { "檔案超過大小上限" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
