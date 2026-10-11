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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Binds strictly to 127.0.0.1. Never expose to 0.0.0.0 without authentication/TLS. */
class LocalApiServer(private val context: Context, private val controller: BrowserController) {
    private val defaultPort = 8765
    private val workers = Executors.newFixedThreadPool(3)
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
                    workers.submit { socket.use { process(it) } }
                } catch (e: Exception) {
                    if (running) Log.w("AIBrowser", "API socket error", e)
                }
            }
        }, "browser-api-listener").apply { isDaemon = true; start() }
    }
    fun stop() {
        running = false
        runCatching { listener?.close() }
        workers.shutdownNow()
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
    private fun send(socket: Socket, status: Int, body: ByteArray, mime: String = "application/json; charset=utf-8") {
        val desc = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized";
            404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 504 -> "Gateway Timeout";
            501 -> "Not Implemented"; 503 -> "Service Unavailable"; else -> "Internal Server Error" }
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
        socket.soTimeout = 22000
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
                send(socket, 401, json(JSONObject().put("error", "Invalid API token")))
                return
            }
            val body = if (req.body.isEmpty()) JSONObject() else JSONObject(String(req.body, StandardCharsets.UTF_8))
            val response: ByteArray
            var mime = "application/json; charset=utf-8"
            when (req.method to req.path) {
                "GET" to "/v1/status" -> response = json(controller.uiCall { controller.status() })
                "GET" to "/v1/diagnostics" -> response = json(controller.uiCall { controller.diagnostics() })
                "POST" to "/v1/background/recover" -> response = json(controller.uiCall {
                    controller.recoverBackgroundSurface()
                })
                "GET" to "/v1/capabilities" -> response = json(JSONObject()
                    .put("apiVersion", "0.7.1")
                    .put("engine", "Android GeckoView")
                    .put("mcpTransport", "stdio via bridge/mcp_stdio.py")
                    .put("snapshotRefs", true)
                    .put("requiresApiToken", true)
                    .put("maxResidentIdleSessions", 3)
                    .put("maxProfileMetadata", 300)
                    .put("supportedCommands", org.json.JSONArray(
                        listOf("snapshot", "read", "dom", "find", "links", "devtools",
                          "storage_inspect", "compatibility", "click", "type",
                          "clear", "toggle", "select", "scroll", "scrollTo",
                          "keyboard", "focus", "wait", "status", "cookie_metadata",
                          "navigate", "back", "forward", "reload")))
                    .put("supportedFeatures", org.json.JSONArray(
                        listOf("isolated_profiles", "manual_takeover", "multi_tab",
                          "bookmarks", "history", "native_fingerprint_protection_runtime_wide",
                          "gecko_context_storage_partitioning", "global_privacy_control",
                          "site_prompt_dialogs", "file_picker_user_driven",
                          "site_permission_dialogs", "oauth_compatibility_diagnostics", "find_in_page",
                          "desktop_mode_by_profile", "search_engine_selection",
                          "android_app_links", "tracking_protection", "visible_screenshot",
                          "storage_inspector_redacted", "page_diagnostics",
                          "screenshot_cache_with_age", "navigate_wait_for_load",
                          "diagnostics")))
                    .put("experimentalFeatures", org.json.JSONArray(
                        listOf("virtual_display_background_renderer")))
                    .put("unsupportedFeatures", org.json.JSONArray(
                        listOf("full_page_screenshot", "reliable_headless_screenshot",
                          "generic_javascript_evaluation", "file_upload",
                          "download_manager", "full_devtools_network_log",
                          "raw_cookie_export", "per_profile_proxy",
                          "independent_per_profile_fingerprint", "fingerprint_rotation",
                          "captcha_bypass"))))
                "GET" to "/v1/tabs" -> response = json(controller.uiCall {
                    controller.tabs(req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile"))
                })
                "POST" to "/v1/tabs/new" -> response = json(controller.uiCall {
                    controller.createTab(body.optString("url", "https://www.google.com/"),
                        controller.profileForRequest(body))
                })
                "POST" to "/v1/tabs/select" -> response = json(controller.uiCall {
                    controller.selectTab(body.getString("id"), controller.profileForRequest(body))
                })
                "POST" to "/v1/tabs/close" -> response = json(controller.uiCall {
                    controller.closeTab(body.getString("id"), controller.profileForRequest(body))
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
                    controller.setDesktopMode(body.getBoolean("enabled"), controller.profileForRequest(body))
                })
                "POST" to "/v1/find-in-page" -> {
                    val backwards = body.optBoolean("backwards", false)
                    val search = if (body.has("query")) body.getString("query") else null
                    response = json(controller.uiAsync {
                        controller.findInPageAsync(search, backwards, controller.profileForRequest(body))
                    })
                }
                "POST" to "/v1/find-in-page/clear" -> response = json(controller.uiCall {
                    controller.clearFind(controller.profileForRequest(body))
                    JSONObject().put("cleared", true)
                })
                "POST" to "/v1/settings/tracking" -> response = json(controller.uiCall {
                    controller.setTrackingProtection(body.getBoolean("enabled"))
                    JSONObject().put("trackingProtection", controller.trackingProtectionEnabled())
                })
                "POST" to "/v1/tabs/close_popup" -> response = json(controller.uiCall {
                    controller.closePopup(controller.profileForRequest(body))
                })
                "POST" to "/v1/back" -> response = json(controller.uiCall { controller.back(controller.profileForRequest(body)); controller.status() })
                "POST" to "/v1/forward" -> response = json(controller.uiCall { controller.forward(controller.profileForRequest(body)); controller.status() })
                "POST" to "/v1/reload" -> response = json(controller.uiCall { controller.reload(controller.profileForRequest(body)); controller.status() })
                "GET" to "/v1/profiles" -> response = json(JSONObject().put("profiles", controller.uiCall { controller.profiles() }))
                "GET" to "/v1/bookmarks" -> response = json(controller.uiCall {
                    JSONObject().put("bookmarks", controller.bookmarks(
                        req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")))
                })
                "GET" to "/v1/history" -> response = json(controller.uiCall {
                    JSONObject().put("history", controller.history(
                        req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")))
                })
                "POST" to "/v1/bookmarks/add" -> response = json(controller.uiCall {
                    controller.bookmark(body.getString("url"), true, controller.profileForRequest(body))
                })
                "POST" to "/v1/bookmarks/remove" -> response = json(controller.uiCall {
                    controller.bookmark(body.getString("url"), false, controller.profileForRequest(body))
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
                            body.getString("url"), controller.profileForRequest(body),
                            body.optLong("timeoutMs", 12000L))
                    })
                } else {
                    json(controller.uiCall {
                        controller.navigate(body.getString("url"), controller.profileForRequest(body))
                    })
                }
                "POST" to "/v1/takeover" -> response = json(controller.uiCall {
                    controller.setManual(body.getBoolean("manual"), controller.profileForRequest(body))
                })
                "POST" to "/v1/command" -> {
                    // One documented entry point for native navigation and page DOM actions.
                    val action = body.optString("action")
                    response = if (action in setOf("navigate", "goto", "open", "load") &&
                            body.optBoolean("waitForLoad", false)) {
                        val result = controller.uiAsync {
                            controller.navigateAndWaitAsync(body.getString("url"),
                                controller.profileForRequest(body), body.optLong("timeoutMs", 12000L))
                        }
                        json(JSONObject().put("ok", true).put("result", result))
                    } else if (action in setOf("navigate", "goto", "open", "load", "back", "forward", "reload")) {
                        json(controller.uiCall {
                            val profile = controller.profileForRequest(body)
                            val result = when (action) {
                                "navigate", "goto", "open", "load" ->
                                    controller.navigate(body.getString("url"), profile)
                                "back" -> { controller.back(profile); controller.status() }
                                "forward" -> { controller.forward(profile); controller.status() }
                                else -> { controller.reload(profile); controller.status() }
                            }
                            JSONObject().put("ok", true).put("result", result)
                        })
                    } else {
                        json(controller.uiAsync { controller.commandAsync(body) })
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
        } catch (e: Exception) {
            var root: Throwable = e
            while (root.cause != null && root !== root.cause) root = root.cause!!
            val status = when {
                e is ApiException -> e.status
                root is IllegalArgumentException || root is org.json.JSONException -> 400
                root is UnsupportedOperationException -> 501
                root is IllegalStateException -> 409
                root is java.util.concurrent.TimeoutException -> 504
                else -> 500
            }
            val safeMsg = root.message?.take(300) ?: "Request failed"
            val code = when (status) {
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
                    .put("error", safeMsg)
                    .put("hint", if (status == 409) "Verify profile, takeover and GeckoView surface status" else "Check the API reference")))
            }
            catch (_: Exception) {}
        }
    }
}