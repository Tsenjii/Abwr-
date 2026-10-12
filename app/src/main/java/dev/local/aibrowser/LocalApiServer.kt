package dev.local.aibrowser

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Binds strictly to 127.0.0.1. Never expose to 0.0.0.0 without authentication/TLS. */
class LocalApiServer(private val context: Context, private val controller: BrowserController) {
    private val defaultPort = 8765
    private val work = ApiWorkQueue()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var listener: ServerSocket? = null
    val token: String = getOrCreateToken(context)
    companion object {
        /**
         * Rotate only on an explicit user action. Existing Agent clients
         * must obtain the replacement locally; do not print credentials in logs.
         */
        fun rotateToken(context: Context) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val value = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            context.getSharedPreferences("local_api", 0).edit()
                .putString("token", value).commit()
        }
        fun getOrCreateToken(context: Context): String {
            val prefs = context.getSharedPreferences("local_api", 0)
            return prefs.getString("token", null) ?: run {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        prefs.edit().putString("token", hex).commit()
        hex
            }
        }
    }
    @Volatile private var running = false

    fun start() {
        if (running) return
        listener = ServerSocket(defaultPort, 16, InetAddress.getByName("127.0.0.1"))
        running = true
        Thread({
            while (running) {
                try {
                    val socket = listener?.accept() ?: break
                    sockets.add(socket)
                    if (!work.control { process(socket) }) {
                        sendBusy(socket)
                        closeSocket(socket)
                    }
                } catch (e: Exception) {
                    if (running) Log.w("AIBrowser", "API socket error", e)
                }
            }
        }, "browser-api-listener").apply { isDaemon = true; start() }
    }
    fun stop() {
        running = false
        runCatching { listener?.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
        work.stop()
    }

    private fun closeSocket(socket: Socket) {
        sockets.remove(socket)
        runCatching { socket.close() }
    }
    private fun sendBusy(socket: Socket) {
        runCatching { send(socket, 503, json(JSONObject().put("ok", false)
            .put("code", "BUSY").put("retryable", true)
            .put("message", "Local API busy; retry shortly"))) }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private class ApiException(val status: Int, message: String) : Exception(message)
    private fun readRequest(input: InputStream): Request {
        val head = ByteArrayOutputStream()
        val tail = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (head.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) throw ApiException(400, "Unexpected connection end")
            head.write(b)
            // Rolling CRLFCRLF matcher; avoid allocating/copying the entire buffer per byte.
            matched = if (b == tail[matched].toInt()) matched + 1
                      else if (b == tail[0].toInt()) 1 else 0
            if (matched == 4) break
        }
        if (head.size() >= 16 * 1024) throw ApiException(413, "Headers too large")
        val lines = String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n")
        val first = lines.first().split(" ")
        if (first.size < 3) throw ApiException(400, "Malformed request line")
        val headers = lines.drop(1).filter { it.contains(":") }.associate {
            it.substringBefore(":").lowercase() to it.substringAfter(":").trim()
        }
        if (headers.containsKey("transfer-encoding"))
            throw ApiException(400, "Transfer-Encoding not supported")
        val size = headers["content-length"]?.let {
            it.toIntOrNull() ?: throw ApiException(400, "Invalid Content-Length")
        } ?: 0
        if (size < 0 || size > 128 * 1024) throw ApiException(413, "Body too large")
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(body, offset, size - offset)
            if (n <= 0) throw ApiException(400, "Body truncated")
            offset += n
        }
        return Request(first[0], first[1].substringBefore("?"), headers, body)
    }
    private fun json(obj: JSONObject) = obj.toString().toByteArray(StandardCharsets.UTF_8)
    private fun scopedBody(req: Request, body: JSONObject): JSONObject {
        val header = req.headers["x-browser-profile"]?.takeIf { it.isNotBlank() } ?: return body
        if (body.has("profile") && body.optString("profile") != header)
            throw BrowserOperationException("BAD_ARGUMENTS",
                "X-Browser-Profile and body profile must match")
        return JSONObject(body.toString()).put("profile", header)
    }
    private fun send(socket: Socket, status: Int, body: ByteArray, mime: String = "application/json; charset=utf-8") {
        val desc = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized";
            404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 504 -> "Gateway Timeout";
            502 -> "Bad Gateway"; 501 -> "Not Implemented"; 503 -> "Service Unavailable"; else -> "Internal Server Error" }
        val head = "HTTP/1.1 $status $desc\r\nContent-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }
    private fun process(socket: Socket) {
        var handedOff = false
        socket.soTimeout = 5000
        try {
            val req = readRequest(socket.getInputStream())
            if (req.method == "GET" && req.path == "/health") {
                send(socket, 200, json(JSONObject().put("ok", true).put("name", "AI Browser Server")))
                return
            }
            val headerToken = req.headers["authorization"]?.removePrefix("Bearer ")
                ?: req.headers["x-api-key"]
            // Constant-time comparison to avoid timing leaks across app clients.
            if (headerToken == null || !java.security.MessageDigest.isEqual(
                    token.toByteArray(), headerToken.toByteArray())) {
                send(socket, 401, json(JSONObject().put("ok", false).put("code", "UNAUTHORIZED")
                    .put("retryable", false).put("error", "Invalid API token")))
                return
            }
            val body = if (req.body.isEmpty()) JSONObject() else JSONObject(String(req.body, StandardCharsets.UTF_8))
            val events = req.method == "GET" && req.path.matches(Regex("/v1/http/streams/http_[a-f0-9]{32}/events"))
            val slow = req.path in setOf("/v1/command", "/v1/http/request", "/v1/find-in-page",
                "/v1/screenshot", "/v1/screenshot/full") ||
                (req.path == "/v1/navigate" && body.optBoolean("waitForLoad", false))
            if (events) {
                handedOff = work.stream { try { respond(socket, req, body) } finally { closeSocket(socket) } }
                if (!handedOff) sendBusy(socket)
            } else if (slow) {
                handedOff = work.operation {
                    try { respond(socket, req, body) }
                    finally { closeSocket(socket) }
                }
                if (!handedOff) sendBusy(socket)
            } else respond(socket, req, body)
        } catch (e: Exception) { sendFailure(socket, e) }
        finally { if (!handedOff) closeSocket(socket) }
    }

    private fun respond(socket: Socket, req: Request, body: JSONObject) {
        try {
            val scoped = scopedBody(req, body)
            if (req.method == "DELETE" && req.path.matches(Regex("/v1/profiles/[A-Za-z][A-Za-z0-9_-]{0,30}"))) {
                val removed = controller.uiCall { controller.removeProfile(req.path.substringAfterLast('/')) }
                send(socket, 200, json(removed)); return
            }
            if (req.path.matches(Regex("/v1/http/streams/http_[a-f0-9]{32}/(?:events|poll|cancel)"))) {
                val parts = req.path.split("/"); val id = parts[4]
                val profile = req.headers["x-browser-profile"]
                val state = controller.http.poll(id, 0)
                if (profile != null && profile != state.getString("profile")) throw BrowserOperationException("BAD_ARGUMENTS", "HTTP stream belongs to a different profile")
                if (parts.last() == "events" && req.method == "GET") {
                    sendHttpEvents(socket, id, req.headers["last-event-id"]?.toIntOrNull() ?: 0); return
                }
                if (parts.last() == "poll" && req.method == "POST") {
                    send(socket, 200, json(controller.http.poll(id, body.optInt("afterSeq", 0)))); return
                }
                if (parts.last() == "cancel" && req.method == "POST") {
                    send(socket, 200, json(controller.http.cancel(id))); return
                }
                throw ApiException(405, "Unsupported HTTP stream method")
            }
            val response: ByteArray
            var mime = "application/json; charset=utf-8"
            when (req.method to req.path) {
                "GET" to "/v1/status" -> response = json(controller.uiCall {
                    controller.status(req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile"))
                })
                "POST" to "/v1/profiles/private" -> response = json(controller.uiCall { controller.createPrivateProfile() })
                "POST" to "/v1/tabs/reopen" -> response = json(controller.uiCall { controller.reopenClosedTab(controller.profileForRequest(scoped)) })
                "POST" to "/v1/history/clear" -> response = json(controller.uiCall {
                    val profile = controller.profileForRequest(scoped); controller.clearHistory(profile)
                    JSONObject().put("profile", profile).put("cleared", true)
                })
                "POST" to "/v1/http/request" -> response = json(controller.uiAsync(
                    timeoutSeconds = (scoped.optLong("timeoutMs", 15000L).coerceIn(1000L, 20000L) / 1000L) + 4L) {
                    controller.httpRequestAsync(scoped.put("action", "http_request"))
                })
                "GET" to "/v1/extensions" -> response = json(controller.uiCall {
                    JSONObject().put("extensions", org.json.JSONArray(controller.extensions.list().map {
                        JSONObject().put("id", it.id).put("name", controller.extensions.name(it)).put("version", it.metaData?.version)
                            .put("enabled", it.metaData?.enabled).put("hasOptions", !it.metaData?.optionsPageUrl.isNullOrBlank())
                    })).put("loading", controller.extensions.loading).put("error", controller.extensions.error ?: JSONObject.NULL)
                })
                "GET" to "/v1/userscripts" -> response = json(controller.uiCall {
                    JSONObject().put("scripts", org.json.JSONArray(controller.userScripts.list().map { it.json(false) }))
                        .put("runtime", controller.extensions.scriptStatus)
                })
                "GET" to "/v1/diagnostics" -> response = json(controller.uiCall { controller.diagnostics() })
                "POST" to "/v1/background/recover" -> response = json(controller.uiCall {
                    controller.recoverBackgroundSurface()
                })
                "GET" to "/v1/capabilities" -> response = json(JSONObject()
                    .put("apiVersion", "0.13.0")
                    .put("bridgeSchemaVersion", 3)
                    .put("bridgeReadyWaitMs", 3500)
                    .put("screenshotTimeoutMs", 12000)
                    .put("screenshotSurfaceRetries", 1)
                    .put("legacyScrollArguments", org.json.JSONArray(listOf("direction", "pixels")))
                    .put("pageOperationWorkers", 2).put("pageOperationQueueCapacity", 8)
                    .put("controlWorkers", 3).put("controlQueueCapacity", 12)
                    .put("pagePreconditions", org.json.JSONArray(listOf("tab", "expectedUrl", "documentId")))
                    .put("frameRouting", "frame_document_id")
                    .put("snapshotMaxElementsPerFrame", 300)
                    .put("engine", "Android GeckoView")
                    .put("mcpTransport", "stdio via bridge/mcp_stdio.py")
                    .put("snapshotRefs", true)
                    .put("requiresApiToken", true)
                    .put("maxResidentIdleSessions", 3)
                    .put("maxProfileMetadata", 300)
                    .put("supportedCommands", org.json.JSONArray(
                        listOf("snapshot", "read", "dom", "find", "links", "devtools", "inspect_element", "http_request",
                          "storage_inspect", "compatibility", "click", "type",
                          "clear", "toggle", "select", "scroll", "scrollTo",
                          "keyboard", "key", "click_point", "drag", "swipe", "frames", "focus", "wait", "status", "cookie_metadata",
                          "navigate", "back", "forward", "reload")))
                    .put("supportedFeatures", org.json.JSONArray(
                        listOf("private_browsing_ephemeral", "reopen_closed_tabs", "native_drag", "html_media_fullscreen", "site_permissions_manager",
                          "bookmark_html_import_export", "page_pdf_save_user_driven", "page_text_save_user_driven", "reading_view", "home_screen_shortcuts", "text_scale", "force_pinch_zoom",
                          "signed_extension_store", "sandboxed_userscripts", "devtools_console_network", "http_request", "http_incremental_stream", "http_binary_base64", "isolated_profiles", "manual_takeover", "multi_tab", "persistent_tab_groups", "tab_thumbnail_overview",
                          "bookmarks", "history", "native_fingerprint_protection_runtime_wide",
                          "gecko_context_storage_partitioning", "global_privacy_control",
                          "site_prompt_dialogs", "file_picker_user_driven",
                          "http_auth_user_prompt", "download_manager_local",
                          "download_export_user_driven", "browser_appearance",
                          "site_permission_dialogs", "oauth_compatibility_diagnostics", "find_in_page",
                          "desktop_mode_by_profile", "search_engine_selection",
                          "android_app_links", "tracking_protection", "visible_screenshot",
                          "storage_inspector_redacted", "page_diagnostics",
                          "screenshot_cache_with_age", "navigate_wait_for_load",
                          "diagnostics", "bounded_bridge_reconnect",
                          "page_operation_preconditions", "responsive_control_lane", "plain_contenteditable_input",
                          "iframe_document_routing", "open_shadow_root_inspection", "snapshot_pagination",
                          "native_touch_input", "native_keyboard_input", "strict_command_arguments",
                          "custom_checkbox_label_proxy", "bounded_bridge_handshake", "bounded_api_wake_lease",
                          "background_compositor_screenshot", "inactive_profile_screenshot_on_demand")))
                    .put("experimentalFeatures", org.json.JSONArray(
                        listOf("service_owned_gecko_compositor_surface")))
                    .put("unsupportedFeatures", org.json.JSONArray(
                        listOf("full_page_screenshot", "guaranteed_execution_after_android_force_stop",
                          "generic_javascript_evaluation", "file_upload",
                          "automatic_download_resume", "full_devtools_network_log",
                          "raw_cookie_export", "per_profile_proxy",
                          "independent_per_profile_fingerprint", "fingerprint_rotation",
                          "captcha_bypass"))))
                "GET" to "/v1/tabs" -> response = json(controller.uiCall {
                    controller.tabs(req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile"))
                })
                "GET" to "/v1/tab-groups" -> response = json(controller.uiCall {
                    controller.tabGroupsInfo(req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile"))
                })
                "POST" to "/v1/tab-groups/create", "POST" to "/v1/tab-groups/update",
                "POST" to "/v1/tab-groups/move", "POST" to "/v1/tab-groups/ungroup" -> response = json(controller.uiCall {
                    controller.editTabGroups(req.path.substringAfterLast('/'), scoped)
                })
                "POST" to "/v1/tabs/new" -> response = json(controller.uiCall {
                    controller.createTab(scoped.optString("url", "https://www.google.com/"),
                        controller.profileForRequest(scoped))
                })
                "POST" to "/v1/tabs/select" -> response = json(controller.uiCall {
                    controller.selectTab(scoped.getString("id"), controller.profileForRequest(scoped))
                })
                "POST" to "/v1/tabs/close" -> response = json(controller.uiCall {
                    controller.closeTab(scoped.getString("id"), controller.profileForRequest(scoped))
                })
                "GET" to "/v1/settings" -> response = json(controller.uiCall {
                    val prefs = context.getSharedPreferences("abwr_settings", 0)
                    JSONObject().put("trackingProtection", controller.trackingProtectionEnabled())
                        .put("fingerprintingProtection", controller.fingerprintProtectionEnabled())
                        .put("fingerprintingProtectionScope", "shared_gecko_runtime")
                        .put("trackingProtectionEngine", "GeckoView Enhanced Tracking Protection")
                        .put("desktopMode", controller.isDesktopMode())
                        .put("searchEngine", BrowserSearch.normalizeProvider(
                            prefs.getString("search_engine", "google") ?: "google"))
                        .put("availableSearchEngines", org.json.JSONArray(BrowserSearch.keys()))
                        .put("dataScope", "Abwr profiles only")
                        .put("credentialExport", false)
                })
                "GET" to "/v1/privacy" -> response = json(controller.uiCall {
                    controller.privacyReport(
                        req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile"))
                })
                "POST" to "/v1/settings/fingerprint-protection" -> response = json(controller.uiCall {
                    controller.setFingerprintProtection(body.getBoolean("enabled"))
                })
                "POST" to "/v1/settings/search" -> {
                    val engine = body.getString("engine")
                    if (engine !in BrowserSearch.keys()) throw IllegalArgumentException("Unknown search engine")
                    context.getSharedPreferences("abwr_settings", 0).edit()
                        .putString("search_engine", engine).apply()
                    response = json(JSONObject().put("engine", engine))
                }
                "POST" to "/v1/settings/desktop" -> response = json(controller.uiCall {
                    controller.setDesktopMode(scoped.getBoolean("enabled"), controller.profileForRequest(scoped))
                })
                "POST" to "/v1/find-in-page" -> {
                    val backwards = body.optBoolean("backwards", false)
                    val search = when { body.has("query") -> body.getString("query")
                        body.has("text") -> body.getString("text") else -> null }
                    response = json(controller.uiAsync {
                        controller.findInPageAsync(search, backwards, controller.profileForRequest(scoped))
                    })
                }
                "POST" to "/v1/find-in-page/clear" -> response = json(controller.uiCall {
                    controller.clearFind(controller.profileForRequest(scoped))
                    JSONObject().put("cleared", true)
                })
                "POST" to "/v1/settings/tracking" -> response = json(controller.uiCall {
                    controller.setTrackingProtection(body.getBoolean("enabled"))
                    JSONObject().put("trackingProtection", controller.trackingProtectionEnabled())
                })
                "POST" to "/v1/tabs/close_popup" -> response = json(controller.uiCall {
                    controller.closePopup(controller.profileForRequest(scoped))
                })
                "POST" to "/v1/back" -> response = json(controller.uiCall { val profile = controller.profileForRequest(scoped); controller.back(profile); controller.status(profile) })
                "POST" to "/v1/forward" -> response = json(controller.uiCall { val profile = controller.profileForRequest(scoped); controller.forward(profile); controller.status(profile) })
                "POST" to "/v1/reload" -> response = json(controller.uiCall { val profile = controller.profileForRequest(scoped); controller.reload(profile); controller.status(profile) })
                "GET" to "/v1/profiles" -> response = json(JSONObject().put("profiles", controller.uiCall { controller.profiles() }))
                "GET" to "/v1/bookmarks" -> response = json(controller.uiCall {
                    JSONObject().put("profile", req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")).put("bookmarks", controller.bookmarks(
                        req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")))
                })
                "GET" to "/v1/history" -> response = json(controller.uiCall {
                    JSONObject().put("profile", req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")).put("history", controller.history(
                        req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")))
                })
                "GET" to "/v1/downloads" -> response = json(controller.uiCall {
                    val profile = req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")
                    // Metadata only: no download URLs, cookies or file-content export.
                    controller.profileForRequest(JSONObject().put("profile", profile))
                    JSONObject().put("profile", profile)
                        .put("downloads", org.json.JSONArray(controller.downloads.list(profile).map { it.json() }))
                })
                "POST" to "/v1/bookmarks/add", "POST" to "/v1/bookmarks" -> response = json(controller.uiCall {
                    controller.bookmark(scoped.getString("url"), true, controller.profileForRequest(scoped))
                })
                "POST" to "/v1/bookmarks/remove" -> response = json(controller.uiCall {
                    controller.bookmark(scoped.getString("url"), false, controller.profileForRequest(scoped))
                })
                "POST" to "/v1/profile/create", "POST" to "/v1/profiles/create",
                "POST" to "/v1/profiles" -> response = json(controller.uiCall {
                    controller.createProfile(body.getString("id"))
                })
                "POST" to "/v1/profile/delete", "POST" to "/v1/profiles/delete" -> response = json(controller.uiCall {
                    controller.removeProfile(body.getString("id"))
                })
                "POST" to "/v1/profile/switch", "POST" to "/v1/profiles/switch" -> response = json(controller.uiCall {
                    controller.switchProfile(body.getString("id"))
                })
                "POST" to "/v1/navigate" -> response = if (body.optBoolean("waitForLoad", false)) {
                    json(controller.uiAsync {
                        controller.navigateAndWaitAsync(
                            scoped.getString("url"), controller.profileForRequest(scoped),
                            scoped.optLong("timeoutMs", 12000L))
                    })
                } else {
                    json(controller.uiCall {
                        controller.navigate(scoped.getString("url"), controller.profileForRequest(scoped))
                    })
                }
                "POST" to "/v1/takeover" -> response = json(controller.uiCall {
                    controller.setManual(scoped.getBoolean("manual"), controller.profileForRequest(scoped))
                })
                "POST" to "/v1/command" -> {
                    controller.validateCommand(scoped)
                    // One documented entry point for native navigation and page DOM actions.
                    val action = body.optString("action")
                    response = if (action in setOf("navigate", "goto", "open", "load") &&
                            body.optBoolean("waitForLoad", false)) {
                        val result = controller.uiAsync {
                            controller.navigateAndWaitAsync(body.getString("url"),
                                controller.profileForRequest(scoped), scoped.optLong("timeoutMs", 12000L))
                        }
                        json(JSONObject().put("ok", true).put("result", result))
                    } else if (action in setOf("navigate", "goto", "open", "load", "back", "forward", "reload")) {
                        json(controller.uiCall {
                            val profile = controller.profileForRequest(scoped)
                            val result = when (action) {
                                "navigate", "goto", "open", "load" ->
                                    controller.navigate(body.getString("url"), profile)
                                "back" -> { controller.back(profile); controller.status(profile) }
                                "forward" -> { controller.forward(profile); controller.status(profile) }
                                else -> { controller.reload(profile); controller.status(profile) }
                            }
                            JSONObject().put("ok", true).put("result", result)
                        })
                    } else {
                        json(controller.uiAsync(timeoutSeconds = if (scoped.optString("action") == "http_request") scoped.optLong("timeoutMs", 15000L).coerceIn(1000L, 20000L) / 1000L + 4L else 15L) { controller.commandAsync(scoped) })
                    }
                }
                "GET" to "/v1/screenshot", "GET" to "/v1/screenshot/full" -> {
                    response = controller.uiAsync { controller.captureAsync(
                        full = req.path == "/v1/screenshot/full",
                        profile = req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")
                    ) }
                    mime = "image/jpeg"
                }
                "GET" to "/v1/screenshot/meta" -> response = json(controller.uiCall {
                    controller.screenshotMeta(req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile"))
                })
                "GET" to "/v1/screenshot/last" -> {
                    response = controller.uiCall {
                        controller.cachedScreenshot(req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile"))
                    }
                    mime = "image/jpeg"
                }
                "GET" to "/v1/proxy" -> response = json(JSONObject()
                    .put("mode", "direct").put("supported", false)
                    .put("message", "Optional proxy is planned but not connected in this MVP"))
                "POST" to "/v1/proxy" -> throw ApiException(501, "Proxy is not implemented yet")
                else -> throw ApiException(404, "Unknown endpoint")
            }
            send(socket, 200, response, mime)
        } catch (e: Exception) { sendFailure(socket, e) }
    }

    private fun sendHttpEvents(socket: Socket, id: String, after: Int) {
        var cursor = after
        controller.http.poll(id, cursor) // Validate the cursor before sending HTTP 200.
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(65)
        while (running && System.nanoTime() < deadline && !socket.isClosed) {
            val polled = controller.http.poll(id, cursor)
            val events = polled.getJSONArray("events")
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i); cursor = event.getInt("seq")
                val text = "id: $cursor\nevent: ${event.getString("type")}\ndata: $event\n\n"
                output.write(text.toByteArray(StandardCharsets.UTF_8)); output.flush()
            }
            if (polled.getBoolean("done") && events.length() == 0) break
            if (events.length() == 0) { output.write(": waiting\n\n".toByteArray()); output.flush(); Thread.sleep(200L) }
        }
    }

    private fun sendFailure(socket: Socket, e: Exception) {
        var root: Throwable = e
        while (root.cause != null && root !== root.cause) root = root.cause!!
        val status = when {
            e is ApiException -> e.status
            root is BrowserOperationException && root.code in setOf("BAD_ARGUMENTS", "INVALID_SELECTOR", "OPTION_NOT_FOUND") -> 400
            root is IllegalArgumentException || root is org.json.JSONException -> 400
            root is UnsupportedOperationException -> 501
            root is BrowserOperationException && root.code == "HTTP_NETWORK_ERROR" -> 502
            root is BrowserOperationException && root.code == "HTTP_TIMEOUT" -> 504
            root is BrowserOperationException && root.code == "HTTP_STREAM_NOT_FOUND" -> 404
            root is IllegalStateException -> 409
            root is java.util.concurrent.TimeoutException -> 504
            else -> 500
        }
        val safeMsg = root.message?.take(300) ?: "Request failed"
        val code = if (root is BrowserOperationException) root.code else when (status) {
            400 -> "BAD_REQUEST"
            401 -> "UNAUTHORIZED"
            404 -> "NOT_FOUND"
            409 -> "NOT_READY"
            501 -> "NOT_IMPLEMENTED"
            504 -> "TIMEOUT"
            else -> "INTERNAL_ERROR"
        }
        try {
            send(socket, status, json(JSONObject()
                .put("ok", false).put("code", code).put("message", safeMsg)
                .put("retryable", status == 503)
                .put("error", safeMsg)
                .put("hint", if (status == 409) "Verify profile, takeover and GeckoView surface status" else "Check the API reference")))
        }
        catch (_: Exception) {}
    }
}
