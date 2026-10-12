package dev.local.aibrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Shared bounded stream broker. Responses never fall back to another container's cookie jar. */
internal class BrowserHttp(context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private val leases = BrowserWorkLease(context)
    private lateinit var executor: GeckoWebExecutor
    private val jobs = linkedMapOf<String, Job>()
    internal class Job(val id: String, val profile: String, val owner: GeckoSession?, val timeoutMs: Long,
        val maxBytes: Int, val streaming: Boolean, val encoding: String, val transport: String,
        val lease: AutoCloseable) {
        val reply = CompletableFuture<JSONObject>()
        val events = mutableListOf<JSONObject>()
        var bytes = 0
        @Volatile var done = false
        var timeout: Runnable? = null
        var status: JSONObject? = null
        var cancel: (() -> Unit)? = null
    }
    fun initialize(runtime: GeckoRuntime) { executor = GeckoWebExecutor(runtime) }
    @Synchronized fun activeCount(profile: String) = jobs.values.count { it.profile == profile && !it.done }
    @Synchronized fun create(input: JSONObject, profile: String, owner: GeckoSession?, transport: String): Job {
        check(jobs.values.count { !it.done } < 4) { "Too many HTTP operations; retry later" }
        val streaming = input.optBoolean("stream", false)
        val id = "http_" + UUID.randomUUID().toString().replace("-", "")
        val job = Job(id, profile, owner, input.optLong("timeoutMs", if (streaming) 60000L else 15000L),
            input.optInt("maxBytes", 1_000_000), streaming, input.optString("responseEncoding", "auto"), transport,
            leases.acquire(input.optLong("timeoutMs", if (streaming) 60000L else 15000L)))
        while (jobs.size >= 12) { val old = jobs.values.firstOrNull { it.done } ?: break; jobs.remove(old.id) }
        jobs[id] = job
        val timeout = Runnable { finish(job, BrowserOperationException("HTTP_TIMEOUT", "HTTP operation timed out; the server may already have accepted the request")) }
        job.timeout = timeout; ui.postDelayed(timeout, job.timeoutMs)
        job.reply.whenComplete { _, _ -> if (job.reply.isCancelled) finish(job, BrowserOperationException("HTTP_CANCELLED", "HTTP caller cancelled the operation")) }
        return job
    }
    @Synchronized private fun event(job: Job, data: JSONObject) {
        if (job.done) return
        data.put("seq", job.events.size + 1)
        job.events.add(data)
    }
    fun headers(job: Job, data: JSONObject) {
        synchronized(this) {
            if (job.done) return
            job.status = data.put("profile", job.profile).put("transport", job.transport)
                .put("cookieScope", data.optString("cookieScope", if (job.transport == "page") "selected_profile_document" else "none"))
            event(job, JSONObject().put("type", "headers").put("response", data))
            if (job.streaming) job.reply.complete(JSONObject().put("ok", true).put("result", JSONObject(data.toString())
                .put("streamId", job.id).put("streaming", true).put("streamEncoding", "base64")
                .put("eventsPath", "/v1/http/streams/${job.id}/events")))
        }
    }
    fun chunk(job: Job, bytes: ByteArray): Boolean = synchronized(this) {
        if (job.done) return false
        if (job.bytes + bytes.size > job.maxBytes || job.events.size >= 4096) {
            finish(job, BrowserOperationException("RESPONSE_TOO_LARGE", "HTTP response exceeded the configured byte or event limit")); return false
        }
        job.bytes += bytes.size
        event(job, JSONObject().put("type", "chunk").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("encoding", "base64").put("bytes", bytes.size))
        true
    }
    fun finish(job: Job, error: Throwable? = null, body: ByteArray? = null) {
        val cancel: (() -> Unit)?
        synchronized(this) {
            if (job.done) return
            val terminal = JSONObject().put("type", if (error == null) "complete" else "error").put("bytes", job.bytes)
            if (error != null) terminal.put("code", (error as? BrowserOperationException)?.code ?: "HTTP_NETWORK_ERROR")
                .put("message", if (error is BrowserOperationException) error.message else "HTTP connection failed")
            event(job, terminal); job.done = true
            job.timeout?.let { ui.removeCallbacks(it) }; job.timeout = null
            cancel = job.cancel; job.cancel = null
            if (!job.reply.isDone) {
                if (error != null) job.reply.completeExceptionally(error)
                else {
                    val metadata = job.status ?: JSONObject()
                    val raw = body ?: ByteArray(0)
                    val mime = metadata.optJSONObject("headers")?.optString("content-type").orEmpty()
                    val text = job.encoding == "text" || (job.encoding == "auto" && (mime.startsWith("text/") || Regex("json|xml|javascript|event-stream").containsMatchIn(mime)))
                    metadata.put("bodyEncoding", if (text) "text" else "base64").put("bytes", raw.size)
                        .put("body", if (text) raw.toString(Charsets.UTF_8) else Base64.encodeToString(raw, Base64.NO_WRAP)).put("truncated", false)
                    job.reply.complete(JSONObject().put("ok", true).put("result", metadata))
                }
            }
            job.lease.close()
        }
        runCatching { cancel?.invoke() }
        if (job.streaming) ui.postDelayed({ synchronized(this) { jobs.remove(job.id) } }, 90000L)
        else synchronized(this) { jobs.remove(job.id) }
    }
    @Synchronized fun poll(id: String, after: Int = 0): JSONObject {
        val job = jobs[id] ?: throw BrowserOperationException("HTTP_STREAM_NOT_FOUND", "HTTP stream is unknown or expired")
        require(after >= 0 && after <= job.events.size) { "Invalid stream cursor" }
        return JSONObject().put("streamId", id).put("profile", job.profile).put("done", job.done)
            .put("events", JSONArray(job.events.drop(after).take(32))).put("nextSeq", minOf(job.events.size, after + 32))
    }
    @Synchronized fun cancel(id: String): JSONObject {
        val job = jobs[id] ?: throw BrowserOperationException("HTTP_STREAM_NOT_FOUND", "HTTP stream is unknown or expired")
        finish(job, BrowserOperationException("HTTP_CANCELLED", "HTTP stream cancelled"))
        return JSONObject().put("streamId", id).put("cancelled", true)
    }
    @Synchronized fun cancelSession(session: GeckoSession) {
        jobs.values.filter { !it.done && it.owner === session }.toList().forEach { finish(it, BrowserOperationException("PAGE_CHANGED", "HTTP document changed")) }
    }
    @Synchronized fun receive(session: GeckoSession, json: JSONObject): Boolean {
        val id = json.optString("httpStreamId")
        val job = jobs[id] ?: return false
        if (job.owner !== session || job.done) return false
        when (json.optString("type")) {
            "headers" -> headers(job, json.getJSONObject("response"))
            "chunk" -> chunk(job, Base64.decode(json.getString("data"), Base64.DEFAULT))
            "complete" -> finish(job)
            "error" -> finish(job, BrowserOperationException(json.optString("code", "HTTP_NETWORK_ERROR"), json.optString("message", "Page HTTP request failed").take(250)))
        }
        return true
    }
    fun native(input: JSONObject, profile: String): CompletableFuture<JSONObject> {
        val job = create(input, profile, null, "native")
        val builder = WebRequest.Builder(input.getString("url")).method(input.optString("method", "GET").uppercase())
        input.optJSONObject("headers")?.let { h -> h.keys().forEach { builder.header(it, h.getString(it)) } }
        input.optString("body").takeIf { input.has("body") }?.let { builder.body(it) }
        if (input.has("bodyBase64")) {
            val bytes = Base64.decode(input.getString("bodyBase64"), Base64.DEFAULT)
            require(bytes.size <= 256_000) { "Request body exceeds 256000 bytes" }
            builder.body(ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() })
        }
        val flags = GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS or if (input.optBoolean("followRedirects", false)) 0 else GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS
        try {
            executor.fetch(builder.build(), flags).accept({ response ->
                if (response == null) { finish(job, BrowserOperationException("HTTP_NETWORK_ERROR", "No HTTP response")); return@accept }
                response.setReadTimeoutMillis(job.timeoutMs)
                io.execute {
                    val stream = response.body
                    if (job.done) { runCatching { stream?.close() }; return@execute }
                    job.cancel = { io.execute { runCatching { stream?.close() } } }
                    val safeHeaders = JSONObject()
                    response.headers.forEach { (name, value) -> safeHeaders.put(name.lowercase(), if (Regex("cookie|authorization", RegexOption.IGNORE_CASE).containsMatchIn(name)) "[redacted]" else value) }
                    headers(job, JSONObject().put("url", response.uri).put("status", response.statusCode).put("headers", safeHeaders).put("redirected", response.redirected))
                    val collected = ByteArrayOutputStream()
                    try {
                        stream?.use { source ->
                            val buffer = ByteArray(16384)
                            while (!job.done) {
                                val n = source.read(buffer); if (n < 0) break
                                if (job.streaming) { if (!chunk(job, buffer.copyOf(n))) break }
                                else { require(collected.size() + n <= job.maxBytes) { "HTTP response exceeds maxBytes" }; collected.write(buffer, 0, n) }
                            }
                        }
                        finish(job, body = if (job.streaming) null else collected.toByteArray())
                    } catch (error: Exception) { finish(job, if (error is IllegalArgumentException) BrowserOperationException("RESPONSE_TOO_LARGE", "HTTP response exceeds maxBytes") else error) }
                }
            }, { finish(job, it ?: BrowserOperationException("HTTP_NETWORK_ERROR", "HTTP request failed")) })
        } catch (error: Exception) { finish(job, error) }
        return job.reply
    }
}
