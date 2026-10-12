package dev.local.aibrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.widget.LinearLayout
import android.widget.SeekBar
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.io.ByteArrayInputStream
import java.io.InputStream

/** User-facing everyday browser actions, kept out of the control/debugging UI. */
internal class BrowserTools(private val activity: Activity, private val ctrl: BrowserController,
    private val dark: Boolean, private val toast: (String) -> Unit,
    private val save: (InputStream, String, String) -> Unit, private val pickBookmarks: (String) -> Unit) {
    fun siteInfo() {
        val info = ctrl.siteInfo()
        val url = info.optString("url")
        val page = BrowserPage(activity, "網站資訊", Uri.parse(url).host.orEmpty(), dark)
        val security = info.optJSONObject("security")
        page.card { box ->
            page.item(box, "shield", when {
                security?.optBoolean("secure") == true -> "連線已加密"
                url.startsWith("http:") -> "連線未加密"
                else -> "等待連線安全資訊"
            }, url)
            security?.optString("issuer")?.takeIf { it.isNotBlank() }?.let { page.item(box, "info", "憑證簽發者", it) }
            page.item(box, "container", if (ctrl.isPrivate()) "無痕瀏覽" else "容器 ${info.optString("profile")}",
                if (ctrl.isPrivate()) "不保存歷史、分頁與縮圖；主動下載或另存的檔案仍會保留。" else "網站資料由目前容器保存。")
        }
        page.section("網站權限")
        val permissions = page.column(); page.body.addView(permissions)
        fun load() {
            permissions.removeAllViews()
            permissions.addView(page.text("讀取權限…", 13f, page.subtle))
            ctrl.sitePermissionsAsync().whenComplete { list, error -> activity.runOnUiThread {
                if (!page.dialog.isShowing) return@runOnUiThread
                permissions.removeAllViews()
                if (error != null) { permissions.addView(page.text(error.message ?: "無法讀取網站權限")); return@runOnUiThread }
                if (list.isEmpty()) page.item(permissions, "shield", "尚無儲存的網站權限", "網站提出要求時會再詢問。")
                list.forEach { permission ->
                    val state = when (permission.value) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW -> "允許"
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY -> "拒絕"
                        else -> "詢問"
                    }
                    page.item(permissions, "shield", permissionName(permission.permission), state) {
                        AlertDialog.Builder(activity).setTitle(permissionName(permission.permission))
                            .setItems(arrayOf("詢問", "允許", "拒絕")) { _, index ->
                                val values = intArrayOf(GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT,
                                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                                runCatching { ctrl.changeSitePermission(permission, values[index]); load() }
                                    .onFailure { toast(it.message ?: "網站已改變") }
                            }.setNegativeButton("取消", null).show()
                    }
                }
            } }
        }
        page.show(); load()
    }
    fun reading() {
        val source = ctrl.status()
        val request = JSONObject().put("action", "read").put("profile", source.getString("profile"))
            .put("expectedUrl", source.optString("url")).put("maxChars", 60000)
        if (!source.isNull("documentId")) request.put("documentId", source.getString("documentId"))
        ctrl.commandAsync(request).whenComplete { result, error -> activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            if (error != null || result?.optBoolean("ok") != true) { toast(error?.message ?: "頁面尚未就緒"); return@runOnUiThread }
            val article = result.getJSONObject("result")
            val page = BrowserPage(activity, article.optString("title", "閱讀檢視"), Uri.parse(source.optString("url")).host.orEmpty(), dark)
            page.body.addView(page.text(article.optString("text"), 19f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.55f) })
            page.show()
        } }
    }
    fun savePage() {
        val state = ctrl.status()
        AlertDialog.Builder(activity).setTitle("另存網頁").setItems(arrayOf("PDF 文件", "目前畫面 JPEG", "頁面文字 TXT")) { _, choice ->
            when (choice) {
                0 -> { toast("正在產生 PDF…"); ctrl.savePdfAsync().whenComplete { stream, error -> activity.runOnUiThread {
                    if (activity.isDestroyed) { stream?.close(); return@runOnUiThread }
                    if (error != null) toast(error.message ?: "無法產生 PDF") else save(stream, "Abwr-page.pdf", "application/pdf")
                } } }
                1 -> ctrl.captureAsync().whenComplete { bytes, error -> activity.runOnUiThread {
                    if (activity.isDestroyed) return@runOnUiThread
                    if (error != null) toast(error.message ?: "截圖失敗") else save(ByteArrayInputStream(bytes), "Abwr-page.jpg", "image/jpeg")
                } }
                2 -> ctrl.commandAsync(JSONObject().put("action", "read").put("expectedUrl", state.optString("url")).put("maxChars", 100000))
                    .whenComplete { result, error -> activity.runOnUiThread {
                        if (activity.isDestroyed) return@runOnUiThread
                        if (error != null || result?.optBoolean("ok") != true) toast(error?.message ?: "無法讀取頁面")
                        else save(ByteArrayInputStream((state.optString("title") + "\n" + state.optString("url") + "\n\n" + result.getJSONObject("result").optString("text")).toByteArray(Charsets.UTF_8)), "Abwr-page.txt", "text/plain")
                    } }
            }
        }.setNegativeButton("取消", null).show()
    }
    fun bookmarkFiles() {
        val profile = ctrl.status().getString("profile")
        AlertDialog.Builder(activity).setTitle("書籤匯入與匯出").setItems(arrayOf("匯入 Firefox／Chrome HTML 書籤", "匯出目前容器的 HTML 書籤")) { _, choice ->
            if (choice == 0) pickBookmarks(profile)
            else save(ByteArrayInputStream(ctrl.exportBookmarks(profile).toByteArray(Charsets.UTF_8)), "Abwr-bookmarks.html", "text/html")
        }.setNegativeButton("取消", null).show()
    }
    fun clearHistory() {
        val profile = ctrl.status().getString("profile")
        AlertDialog.Builder(activity).setTitle("清除目前容器的歷史？")
            .setMessage("會清除容器 $profile 的瀏覽歷史與分頁返回紀錄。書籤與網站登入會保留。")
            .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> ctrl.clearHistory(profile); toast("已清除歷史") }.show()
    }
    fun textSize() {
        val prefs = activity.getSharedPreferences("abwr_settings", 0)
        val page = BrowserPage(activity, "文字與縮放", "套用於所有一般與無痕容器", dark)
        val value = page.text("${prefs.getInt("text_scale", 100)}%", 24f)
        var percent = prefs.getInt("text_scale", 100).coerceIn(50, 200)
        page.body.addView(value)
        page.body.addView(SeekBar(activity).apply {
            max = 150; progress = percent - 50; contentDescription = "文字大小"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { percent = progress + 50; value.text = "$percent%" }
            })
        }, LinearLayout.LayoutParams(-1, page.dp(60)))
        page.card { box -> page.toggle(box, "search", "允許手指縮放", "也允許縮放網站原先限制的頁面", prefs.getBoolean("force_zoom", false)) { ctrl.setForceZoom(it) } }
        page.body.addView(page.button("套用並重新載入目前頁面", true) { ctrl.setTextScale(percent); page.dialog.dismiss() })
        page.show()
    }
    fun addHomeShortcut() {
        val state = ctrl.status(); val url = state.optString("url")
        if (ctrl.isPrivate()) { toast("請切回一般容器再加入主畫面"); return }
        if (!BrowserBookmarks.validUrl(url)) { toast("目前頁面不能建立捷徑"); return }
        val shortcuts = activity.getSystemService(ShortcutManager::class.java)
        if (!shortcuts.isRequestPinShortcutSupported) { toast("此主畫面不支援新增捷徑"); return }
        val title = state.optString("title").ifBlank { Uri.parse(url).host ?: "網頁" }.take(40)
        val item = ShortcutInfo.Builder(activity, "web_" + java.util.UUID.randomUUID())
            .setShortLabel(title).setLongLabel(title).setIcon(Icon.createWithResource(activity, R.drawable.ic_abwr))
            .setIntent(Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))).build()
        shortcuts.requestPinShortcut(item, null)
    }
    fun contextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
        val link = element.linkUri?.takeIf { BrowserBookmarks.validUrl(it) }
        val source = element.srcUri?.takeIf { BrowserBookmarks.validUrl(it) }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        fun copy(url: String) { (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("網址", url)); toast("已複製網址") }
        fun share(url: String) { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "分享")) }
        if (link != null) {
            options += "在新分頁開啟連結" to { ctrl.createTab(link, fromUser = true); Unit }
            options += "在無痕容器開啟" to { ctrl.createPrivateProfile(); ctrl.humanNavigate(link) }
            options += "複製連結" to { copy(link) }
            options += "分享連結" to { share(link) }
            options += "收藏連結" to { ctrl.bookmark(link); toast("已加入書籤") }
            options += "另存連結檔案" to { saveResource(link) }
        }
        if (source != null) {
            options += "在新分頁開啟圖片／媒體" to { ctrl.createTab(source, fromUser = true); Unit }
            options += "複製圖片／媒體網址" to { copy(source) }
            options += "分享圖片／媒體網址" to { share(source) }
            options += "另存圖片／媒體" to { saveResource(source) }
        }
        if (options.isNotEmpty()) AlertDialog.Builder(activity).setTitle(element.linkText?.takeIf { it.isNotBlank() } ?: element.altText ?: "網頁選項")
            .setItems(options.map { it.first }.toTypedArray()) { _, index -> runCatching { options[index].second() }.onFailure { toast(it.message ?: "操作失敗") } }
            .setNegativeButton("取消", null).show()
    }
    private fun saveResource(url: String) {
        toast("正在讀取檔案…")
        ctrl.httpRequestAsync(JSONObject().put("url", url).put("responseEncoding", "base64").put("maxBytes", 2_000_000), fromUser = true)
            .whenComplete { reply, error -> activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (error != null) { toast(error.message ?: "無法讀取檔案"); return@runOnUiThread }
                val response = reply?.optJSONObject("result")
                if (response == null || response.optInt("status") !in 200..299) { toast("下載未成功，請檢查網站回應"); return@runOnUiThread }
                val headers = response.optJSONObject("headers")
                val mime = headers?.optString("content-type")?.substringBefore(';')?.takeIf { it.contains('/') } ?: "application/octet-stream"
                val name = Uri.parse(url).lastPathSegment?.take(100)?.takeIf { it.isNotBlank() } ?: "Abwr-file"
                save(ByteArrayInputStream(android.util.Base64.decode(response.getString("body"), android.util.Base64.DEFAULT)), name, mime)
            } }
    }
    companion object {
        fun permissionName(value: Int) = when (value) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "定位"
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "通知"
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "跨網站儲存"
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "永久網站儲存"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> "有聲自動播放"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "靜音自動播放"
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "受保護媒體播放"
            else -> "網站權限 ($value)"
        }
    }
}
