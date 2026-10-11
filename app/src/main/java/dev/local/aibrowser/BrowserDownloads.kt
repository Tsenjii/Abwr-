package dev.local.aibrowser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Streams the already-authenticated Gecko response; never refetches or exports cookies. */
internal class BrowserDownloads(private val context: Context) {
    data class Item(val id: String, val profile: String, val name: String, val mime: String,
        val sourceHost: String, val state: String, val bytes: Long, val total: Long,
        val createdAt: Long, val error: String = "") {
        fun json() = JSONObject().put("id", id).put("profile", profile).put("name", name)
            .put("mime", mime).put("sourceHost", sourceHost).put("state", state)
            .put("bytes", bytes).put("total", total).put("createdAtMs", createdAt)
            .put("error", error)
    }
    private val prefs = context.getSharedPreferences("browser_downloads", 0)
    private val root = File(context.filesDir, "downloads").apply { mkdirs() }
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val items = linkedMapOf<String, Item>()
    private val streams = ConcurrentHashMap<String, InputStream>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    var onChanged: (() -> Unit)? = null

    init {
        runCatching {
            val saved = JSONArray(prefs.getString("items", "[]"))
            for (index in 0 until minOf(saved.length(), 200)) {
                val value = saved.getJSONObject(index)
                val id = value.getString("id")
                if (!id.matches(Regex("[a-f0-9-]{36}"))) continue
                val name = DownloadIO.filename("", "filename=\"${value.getString("name")}\"")
                var item = Item(id, value.getString("profile"), name,
                    value.optString("mime", "application/octet-stream"), value.optString("sourceHost"),
                    value.getString("state"), value.optLong("bytes"), value.optLong("total", -1),
                    value.optLong("createdAtMs"), value.optString("error"))
                if (item.state in listOf("queued", "downloading")) {
                    item = item.copy(state = "failed", error = "App 已停止，請從原網站重新下載")
                    partialFile(id).delete()
                }
                if (item.state == "complete" && !file(item).isFile)
                    item = item.copy(state = "failed", error = "找不到下載檔案")
                items[id] = item
            }
            persist()
        }
    }
    private fun file(item: Item) = File(root, item.id + "-" + item.name)
    private fun partialFile(id: String) = File(root, id + ".part")
    @Synchronized fun list(profile: String): List<Item> = items.values
        .filter { it.profile == profile }.reversed().map { it.copy() }
    @Synchronized fun activeCount(profile: String) = items.values.count {
        it.profile == profile && it.state in listOf("queued", "downloading")
    }
    @Synchronized fun savedFile(id: String): File {
        val item = items[id] ?: error("找不到下載紀錄")
        check(item.state == "complete") { "下載尚未完成" }
        return file(item).also { check(it.isFile) { "找不到下載檔案" } }
    }
    @Synchronized private fun persist() {
        prefs.edit().putString("items", JSONArray(items.values.map { it.json() }).toString()).apply()
    }
    @Synchronized private fun update(id: String, change: (Item) -> Item, save: Boolean = true) {
        items[id]?.let { items[id] = change(it) }
        if (save) persist()
        ui.post { onChanged?.invoke() }
    }
    fun discard(response: WebResponse) {
        response.body?.let { stream -> io.execute { runCatching { stream.close() } } }
    }
    fun start(response: WebResponse, profile: String, finished: () -> Unit): String {
        val body = response.body ?: error("網站沒有提供可下載的檔案內容")
        response.setReadTimeoutMillis(30000L)
        val headers = response.headers.entries.associate { it.key.lowercase() to it.value }
        val name = DownloadIO.filename(response.uri, headers["content-disposition"])
        val mime = headers["content-type"]?.substringBefore(';')?.trim()
            ?.takeIf { it.matches(Regex("[\\w.+-]+/[\\w.+-]+")) } ?: "application/octet-stream"
        val total = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
        // Content-Length can describe compressed wire bytes rather than the decoded stream.
        val expected = if (headers["content-encoding"].orEmpty().lowercase() in listOf("", "identity")) total else -1L
        val id = UUID.randomUUID().toString()
        synchronized(this) {
            check(items.size < 200) { "下載紀錄已達上限，請先移除舊紀錄" }
            check(items.values.count { it.state in listOf("queued", "downloading") } < 4) {
                "同時最多四個下載，請稍後再試"
            }
            check(response.statusCode in 200..299) { "網站回傳 HTTP ${response.statusCode}" }
            items[id] = Item(id, profile, name, mime, Uri.parse(response.uri).host.orEmpty(),
                "queued", 0, total, System.currentTimeMillis())
            persist()
        }
        streams[id] = body
        io.execute {
            val partial = partialFile(id)
            try {
                update(id, { it.copy(state = "downloading") })
                var lastProgress = SystemClock.elapsedRealtime()
                val bytes = body.use { input ->
                    partial.outputStream().use { output ->
                        DownloadIO.copy(input, output, expected, { id in cancelled }) { bytes ->
                            if (SystemClock.elapsedRealtime() - lastProgress >= 500L) {
                                lastProgress = SystemClock.elapsedRealtime()
                                update(id, { it.copy(bytes = bytes) }, save = false)
                            }
                        }
                    }
                }
                synchronized(this) {
                    if (id in cancelled) throw CancellationException()
                    val item = items[id] ?: error("下載紀錄已移除")
                    check(partial.renameTo(file(item))) { "無法儲存下載檔案" }
                    update(id, { it.copy(state = "complete", bytes = bytes) })
                }
            } catch (error: Exception) {
                partial.delete()
                update(id, { it.copy(state = if (id in cancelled || error is CancellationException)
                    "cancelled" else "failed", error = if (id in cancelled) "" else
                    "下載中斷或無法儲存，請從原網站重新下載") })
            } finally {
                streams.remove(id)
                cancelled.remove(id)
                runCatching { body.close() }
                ui.post { finished() }
            }
        }
        return id
    }
    fun cancel(id: String) {
        synchronized(this) {
            val item = items[id] ?: return
            if (item.state !in listOf("queued", "downloading")) return
            if (!cancelled.add(id)) return
        }
        // Closing the stream unblocks its worker without blocking the Android UI.
        streams[id]?.let { stream -> Thread({ runCatching { stream.close() } }, "Abwr-cancel-download").start() }
    }
    @Synchronized fun remove(id: String) {
        val item = items[id] ?: return
        check(item.state !in listOf("queued", "downloading")) { "請先取消下載" }
        val saved = file(item)
        check(!saved.exists() || saved.delete()) { "無法刪除下載檔案" }
        partialFile(id).delete()
        items.remove(id)
        persist()
        ui.post { onChanged?.invoke() }
    }
    fun export(id: String, target: Uri, completed: (Boolean) -> Unit) {
        val source = runCatching { savedFile(id) }.getOrNull()
        if (source == null) { completed(false); return }
        io.execute {
            val ok = runCatching {
                source.inputStream().use { input ->
                    context.contentResolver.openOutputStream(target, "w")?.use { output ->
                        DownloadIO.copy(input, output, source.length())
                    } ?: error("無法開啟儲存位置")
                }
            }.isSuccess
            ui.post { completed(ok) }
        }
    }
}
