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
        while (head.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) throw ApiException(400, "Unexpected connection end")
            head.write(b)
            val h = head.toByteArray()
            if (h.size >= 4 && h.copyOfRange(h.size - 4, h.size).contentEquals(tail)) break
        }
        if (head.size() >= 16 * 1024) throw ApiException(413, "Headers too large")
        val lines = String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n")
        val first = lines.first().split(" ")
        if (first.size < 3) throw ApiException(400, "Malformed request line")
        val headers = lines.drop(1).filter { it.contains(":") }.associate {
            it.substringBefore(":").lowercase() to it.substringAfter(":").trim()
        }
        val size = headers["content-length"]?.toIntOrNull() ?: 0
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
            501 -> "Not Implemented"; else -> "Internal Server Error" }
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
            if (req.path == "/health") {
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
                "GET" to "/v1/tabs" -> response = json(controller.uiCall { controller.tabs() })
                "POST" to "/v1/tabs/close_popup" -> response = json(controller.uiCall {
                    controller.closePopup(controller.profileForRequest(body))
                })
                "POST" to "/v1/back" -> response = json(controller.uiCall { controller.back(); controller.status() })
                "POST" to "/v1/forward" -> response = json(controller.uiCall { controller.forward(); controller.status() })
                "POST" to "/v1/reload" -> response = json(controller.uiCall { controller.reload(); controller.status() })
                "GET" to "/v1/profiles" -> response = json(JSONObject().put("profiles", controller.uiCall { controller.profiles() }))
                "POST" to "/v1/profile/create" -> response = json(controller.uiCall {
                    controller.createProfile(body.getString("id"))
                })
                "POST" to "/v1/profile/delete" -> response = json(controller.uiCall {
                    controller.removeProfile(body.getString("id"))
                })
                "POST" to "/v1/profile/switch" -> response = json(controller.uiCall {
                    controller.switchProfile(body.getString("id"))
                })
                "POST" to "/v1/navigate" -> response = json(controller.uiCall {
                    controller.navigate(body.getString("url"), controller.profileForRequest(body))
                })
                "POST" to "/v1/takeover" -> response = json(controller.uiCall {
                    controller.setManual(body.getBoolean("manual"), controller.profileForRequest(body))
                })
                "POST" to "/v1/command" -> {
                    val result = controller.uiAsync { controller.commandAsync(body) }
                    response = json(result)
                }
                "GET" to "/v1/screenshot", "GET" to "/v1/screenshot/full" -> {
                    response = controller.uiAsync { controller.captureAsync(
                        full = req.path == "/v1/screenshot/full",
                        profile = req.headers["x-browser-profile"] ?: controller.status().getString("activeProfile")
                    ) }
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
                root is IllegalStateException -> 409
                root is java.util.concurrent.TimeoutException -> 504
                else -> 500
            }
            val safeMsg = root.message?.take(300) ?: "Request failed"
            try { send(socket, status, json(JSONObject().put("error", safeMsg))) }
            catch (_: Exception) {}
        }
    }
}