package dev.local.aibrowser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import org.json.JSONObject
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/**
 * A normal mobile-browser shell first; advanced Agent controls live in the menu.
 * Nothing here reads the user's Chrome/Brave profile or exports auth cookies.
 */
class MainActivity : Activity(), BrowserController.SitePromptHost {
    private data class PromptUi(val session: GeckoSession,
        val reply: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
        var dialog: AlertDialog? = null)
    private val promptUi = mutableMapOf<GeckoSession.PromptDelegate.BasePrompt, PromptUi>()
    private val permissionDialogs = mutableMapOf<AlertDialog, GeckoSession>()
    private val downloadDialogs = mutableMapOf<AlertDialog, GeckoSession>()
    private var androidPermissionSession: GeckoSession? = null
    private var pendingFilePrompt: Pair<GeckoSession.PromptDelegate.FilePrompt,
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse>>? = null
    private val scriptRequestCode = 4204
    private val extensionDialogs = mutableSetOf<AlertDialog>()
    private val extensionWindows = mutableMapOf<Dialog, GeckoSession>()
    private lateinit var workbench: BrowserWorkbench
    private lateinit var everyday: BrowserTools
    private var siteInfoButton: ImageButton? = null
    private val bookmarkImportCode = 4205
    private val documentSaveCode = 4206
    private var bookmarkImportProfile: String? = null
    private var documentStream: java.io.InputStream? = null
    private var fullscreen = false
    private val browserChrome = mutableListOf<View>()
    private var browserRoot: LinearLayout? = null
    private val fileRequestCode = 4201
    private val permissionsRequestCode = 4302
    private var pendingAndroidPermission: GeckoSession.PermissionDelegate.Callback? = null
    private val exportRequestCode = 4203
    private var pendingExportId: String? = null
    private val fileImports = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun finishPrompt(prompt: GeckoSession.PromptDelegate.BasePrompt,
        response: () -> GeckoSession.PromptDelegate.PromptResponse) {
        val item = promptUi.remove(prompt) ?: return
        if (!prompt.isComplete) {
            val result = try { response() } catch (error: Exception) {
                if (prompt.isComplete) { item.dialog?.dismiss(); return }
                prompt.dismiss()
            }
            item.reply.complete(result)
        }
        item.dialog?.dismiss()
    }
    override fun dismissResolvedPermissions(session: GeckoSession) {
        permissionDialogs.filterValues { it === session }.keys.toList().forEach { permissionDialogs.remove(it); it.dismiss() }
    }
    override fun dismissResolvedPrompt(prompt: GeckoSession.PromptDelegate.BasePrompt) {
        if (!prompt.isComplete) return
        if (pendingFilePrompt?.first === prompt) {
            pendingFilePrompt = null
            // The API completed the same request as the native picker. Return
            // to the browser instead of leaving an already-answered picker open.
            finishActivity(fileRequestCode)
        }
        promptUi.remove(prompt)?.dialog?.dismiss()
    }
    override fun dismissPromptsFor(session: GeckoSession) {
        promptUi.filterValues { it.session === session }.keys.toList().forEach { prompt ->
            if (pendingFilePrompt?.first === prompt) pendingFilePrompt = null
            finishPrompt(prompt) { prompt.dismiss() }
        }
        permissionDialogs.filterValues { it === session }.keys.toList().forEach { it.cancel() }
        downloadDialogs.filterValues { it === session }.keys.toList().forEach { it.cancel() }
        if (androidPermissionSession === session) {
            pendingAndroidPermission?.reject()
            pendingAndroidPermission = null
            androidPermissionSession = null
        }
    }
    override fun showDownload(session: GeckoSession, response: WebResponse): Boolean {
        if (isFinishing || isDestroyed || downloadDialogs.size >= 4) return false
        val headers = response.headers.entries.associate { it.key.lowercase() to it.value }
        val name = DownloadIO.filename(response.uri, headers["content-disposition"])
        val host = android.net.Uri.parse(response.uri).host ?: "目前網站"
        var decided = false
        lateinit var dialog: AlertDialog
        fun choose(save: Boolean) {
            if (decided) return
            decided = true
            downloadDialogs.remove(dialog)
            if (save) runCatching { ctrl.startDownload(session, response) }
                .onSuccess { toast("下載已開始，可從選單查看進度") }
                .onFailure { toast(it.message ?: "無法開始下載") }
            else ctrl.discardPendingDownload(response)
        }
        dialog = AlertDialog.Builder(this).setTitle("下載檔案")
            .setMessage("$name\n來源：$host\n儲存後可從下載紀錄開啟或另存。")
            .setPositiveButton("下載") { _, _ -> choose(true) }
            .setNegativeButton("取消") { _, _ -> choose(false) }
            .setOnCancelListener { choose(false) }.show()
        downloadDialogs[dialog] = session
        return true
    }

    private fun showDownloads(showAll: Boolean = false) {
        val profile = if (showAll) null else ctrl.status().getString("activeProfile")
        val page = BrowserPage(this, "下載", if (showAll) "全部容器" else "容器 · $profile", dark)
        page.toolbar.addView(page.icon("container", if (showAll) "只顯示目前容器" else "顯示全部容器") {
            page.dialog.dismiss(); showDownloads(!showAll)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        var last = ""
        fun update() {
            val entries = ctrl.downloads.list(profile)
            val signature = entries.joinToString { "${it.id}:${it.state}:${it.bytes}:${it.error}" }
            if (last == signature && page.body.childCount > 0) return
            last = signature; page.body.removeAllViews()
            if (entries.isEmpty()) page.empty("下載的檔案會放在這裡", "完成後可開啟、分享，或另存到手機其他位置。")
            entries.forEach { item -> page.card { box ->
                val state = when (item.state) {
                    "queued" -> "等待下載"; "downloading" -> "下載中"; "complete" -> "完成"; "cancelled" -> "已取消"; else -> "失敗"
                }
                val size = android.text.format.Formatter.formatShortFileSize(this, item.bytes)
                val total = if (item.total >= 0L) " / " + android.text.format.Formatter.formatShortFileSize(this, item.total) else ""
                page.item(box, "download", item.name, (if (showAll) "${item.profile} · " else "") + "$state · $size$total") { showDownloadActions(item) }
                if (item.state in listOf("queued", "downloading")) box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = item.total <= 0; max = 100
                    if (item.total > 0) progress = ((item.bytes * 100L) / item.total).toInt().coerceIn(0, 100)
                    progressTintList = ColorStateList.valueOf(primary); indeterminateTintList = ColorStateList.valueOf(primary)
                }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(10); bottomMargin = dp(8) })
                if (item.error.isNotBlank()) box.addView(page.text(item.error, 12f, primary))
            } }
        }
        val handler = Handler(Looper.getMainLooper())
        val refresh = object : Runnable { override fun run() {
            if (!page.dialog.isShowing || isDestroyed) return
            update(); handler.postDelayed(this, 1000L)
        } }
        page.onDismiss = { handler.removeCallbacks(refresh) }
        update(); page.show(); handler.post(refresh)
    }
    private fun showDownloadActions(item: BrowserDownloads.Item) {
        if (item.state in listOf("queued", "downloading")) {
            AlertDialog.Builder(this).setTitle("取消下載？").setMessage(item.name)
                .setNegativeButton("繼續下載", null)
                .setPositiveButton("取消下載") { _, _ -> ctrl.downloads.cancel(item.id) }.show()
            return
        }
        val choices = if (item.state == "complete") arrayOf("開啟", "分享", "另存到…", "移除檔案與紀錄")
                      else arrayOf("移除紀錄")
        AlertDialog.Builder(this).setTitle(item.name).setItems(choices) { _, which ->
            runCatching {
                if (item.state != "complete" || which == 3) {
                    AlertDialog.Builder(this).setTitle("移除此下載？")
                        .setMessage("移除 Abwr 儲存的檔案和紀錄。已另存到其他位置的副本會保留。")
                        .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                            runCatching { ctrl.downloads.remove(item.id) }
                                .onFailure { toast(it.message ?: "無法移除") }
                        }.show()
                } else if (which == 2) {
                    check(pendingExportId == null) { "請先完成目前的另存操作" }
                    val export = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = item.mime
                        putExtra(Intent.EXTRA_TITLE, item.name)
                    }
                    startActivityForResult(export, exportRequestCode)
                    pendingExportId = item.id
                } else {
                    val uri = FileProvider.getUriForFile(this, "$packageName.downloads", ctrl.downloads.savedFile(item.id))
                    val open = if (which == 0) Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                        else Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    open.clipData = ClipData.newUri(contentResolver, item.name, uri)
                    startActivity(Intent.createChooser(open, if (which == 0) "開啟檔案" else "分享檔案"))
                }
            }.onFailure { toast(it.message ?: "無法處理下載檔案") }
        }.show()
    }

    override fun showContentPermission(
        session: GeckoSession,
        permission: GeckoSession.PermissionDelegate.ContentPermission,
        reply: GeckoResult<Int>
    ): Boolean {
        if (isFinishing || isDestroyed) return false
        val title = when (permission.permission) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "使用你的定位"
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "傳送通知"
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "使用跨網站登入 Cookie"
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "儲存網站資料"
            else -> "使用網站權限"
        }
        val source = android.net.Uri.parse(permission.uri).host ?: "目前網站"
        val extra = permission.thirdPartyOrigin?.let { "\n第三方網站：$it" } ?: ""
        var answered = false
        lateinit var dialog: AlertDialog
        fun finish(value: Int) {
            if (answered) return
            answered = true
            permissionDialogs.remove(dialog)
            reply.complete(value)
        }
        dialog = AlertDialog.Builder(this).setTitle(title)
            .setMessage("$source 要求這項權限。$extra\n只在你信任這個網站時允許。")
            .setPositiveButton("允許") { _, _ ->
                finish(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            .setNegativeButton("拒絕") { _, _ ->
                finish(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            .setOnCancelListener {
                finish(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }.show()
        permissionDialogs[dialog] = session
        permission.notifyShown()
        return dialog.isShowing
    }

    override fun showAndroidPermissions(session: GeckoSession, permissions: Array<String>,
        callback: GeckoSession.PermissionDelegate.Callback): Boolean {
        if (isFinishing || isDestroyed || pendingAndroidPermission != null) return false
        val requested = permissions.filter {
            it in listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO)
        }
        if (requested.isEmpty() || requested.size != permissions.size) return false
        if (requested.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            callback.grant()
            return true
        }
        pendingAndroidPermission = callback
        androidPermissionSession = session
        requestPermissions(requested.toTypedArray(), permissionsRequestCode)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int,
        permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionsRequestCode) return
        val callback = pendingAndroidPermission ?: return
        pendingAndroidPermission = null
        val samePage = androidPermissionSession?.let { ctrl.isVisibleSession(it) } == true
        androidPermissionSession = null
        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED } && samePage) callback.grant()
        else callback.reject()
    }
    override fun showMediaPermission(session: GeckoSession, uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback): Boolean {
        if (isFinishing || isDestroyed || !ctrl.isVisibleSession(session)) return false
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun sourcePicker(title: String, sources: Array<GeckoSession.PermissionDelegate.MediaSource>?): android.widget.Spinner? {
            if (sources.isNullOrEmpty()) return null
            fields.addView(TextView(this).apply { text = title })
            return android.widget.Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    sources.mapIndexed { index, source -> source.name ?: "$title ${index + 1}" })
                fields.addView(this)
            }
        }
        val videoPicker = sourcePicker("攝影機", video)
        val audioPicker = sourcePicker("麥克風", audio)
        var answered = false
        lateinit var dialog: AlertDialog
        fun choose(allow: Boolean) {
            if (answered) return
            answered = true; permissionDialogs.remove(dialog)
            val appAllowed = (video.isNullOrEmpty() || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) &&
                (audio.isNullOrEmpty() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            ctrl.finishMediaPermission(callback, video?.getOrNull(videoPicker?.selectedItemPosition ?: 0),
                audio?.getOrNull(audioPicker?.selectedItemPosition ?: 0), allow && appAllowed && ctrl.isVisibleSession(session), true)
        }
        dialog = AlertDialog.Builder(this).setTitle("允許 ${android.net.Uri.parse(uri).host ?: "網站"} 使用媒體？")
            .setView(fields).setPositiveButton("允許") { _, _ -> choose(true) }.setNegativeButton("拒絕") { _, _ -> choose(false) }
            .setOnCancelListener { choose(false) }.create()
        permissionDialogs[dialog] = session; dialog.show(); return true
    }
    override fun showContextMenu(session: GeckoSession, element: GeckoSession.ContentDelegate.ContextElement) {
        if (ctrl.isVisibleSession(session) && ::everyday.isInitialized) everyday.contextMenu(element)
    }
    override fun showExternalUri(session: GeckoSession, uri: String) {
        if (!ctrl.isVisibleSession(session)) return
        AlertDialog.Builder(this).setTitle("開啟其他應用程式？")
            .setMessage("目前網站要求開啟 ${android.net.Uri.parse(uri).scheme} 連結。")
            .setNegativeButton("取消", null).setPositiveButton("開啟") { _, _ ->
                if (!ctrl.isVisibleSession(session)) return@setPositiveButton
                runCatching {
                    val outgoing = if (uri.startsWith("intent:", true)) {
                        val parsed = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                        val target = requireNotNull(parsed.data) { "無效的 App 連結" }
                        require(target.scheme?.lowercase() !in setOf("file", "content", "javascript", "data", "blob", "intent")) { "不支援的 App 連結" }
                        Intent(Intent.ACTION_VIEW, target).setPackage(parsed.`package`).addCategory(Intent.CATEGORY_BROWSABLE)
                    } else Intent(if (uri.startsWith("mailto:", true) || uri.startsWith("sms:", true)) Intent.ACTION_SENDTO else Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    startActivity(outgoing)
                }.onFailure { toast("找不到可開啟此連結的應用程式") }
            }.show()
    }
    override fun showFullscreen(session: GeckoSession, enabled: Boolean) { setBrowserFullscreen(enabled) }
    private fun setBrowserFullscreen(enabled: Boolean) {
        fullscreen = enabled
        browserChrome.forEach { it.visibility = if (enabled) View.GONE else View.VISIBLE }
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        browserRoot?.let { ViewCompat.requestApplyInsets(it) }
    }
    // Website JS / popup / file dialogs require a visible user-facing Activity.
    override fun showSitePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.BasePrompt,
        reply: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>): Boolean {
        if (isFinishing || isDestroyed) return false
        if (pendingFilePrompt?.first === prompt || promptUi[prompt]?.dialog?.isShowing == true) return true
        if (promptUi.size >= 8) return false
        val item = PromptUi(session, reply)
        promptUi[prompt] = item
        // Keep this completion listener while our own document picker is on
        // top; the foreground-only host is detached during onStop.
        reply.accept({ _ -> runOnUiThread { if (!isDestroyed) dismissResolvedPrompt(prompt) } },
            { _ -> runOnUiThread { if (!isDestroyed) dismissResolvedPrompt(prompt) } })
        prompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                val stale = promptUi.remove(prompt) ?: return
                if (pendingFilePrompt?.first === prompt) pendingFilePrompt = null
                stale.dialog?.dismiss()
            }
        })
        val heading = prompt.title?.take(150) ?: "網站要求確認"
        when (prompt) {
            is GeckoSession.PromptDelegate.AlertPrompt -> {
                item.dialog = AlertDialog.Builder(this).setTitle(heading)
                    .setMessage(prompt.message?.take(1000) ?: "")
                    .setPositiveButton("知道了") { _, _ -> finishPrompt(prompt) { prompt.dismiss() } }
                    .setOnCancelListener { finishPrompt(prompt) { prompt.dismiss() } }.show()
            }
            is GeckoSession.PromptDelegate.ButtonPrompt -> {
                item.dialog = AlertDialog.Builder(this).setTitle(heading)
                    .setMessage(prompt.message?.take(1000) ?: "")
                    .setPositiveButton("確認") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE) }
                    }.setNegativeButton("取消") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE) }
                    }.setOnCancelListener { finishPrompt(prompt) { prompt.dismiss() } }.show()
            }
            is GeckoSession.PromptDelegate.TextPrompt -> {
                val entry = EditText(this).apply {
                    setSingleLine()
                    setText(prompt.defaultValue ?: "")
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                item.dialog = AlertDialog.Builder(this).setTitle(heading)
                    .setMessage(prompt.message?.take(1000) ?: "")
                    .setView(entry)
                    .setPositiveButton("確定") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(entry.text.toString()) }
                    }.setNegativeButton("取消") { _, _ ->
                        finishPrompt(prompt) { prompt.dismiss() }
                    }.setOnCancelListener { finishPrompt(prompt) { prompt.dismiss() } }.show()
            }
            is GeckoSession.PromptDelegate.AuthPrompt -> {
                val options = prompt.authOptions
                val passwordOnly = options.flags and
                    GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
                val fields = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                }
                val username = EditText(this).apply {
                    hint = "帳號"; setSingleLine(); setText(options.username.orEmpty())
                }
                val password = EditText(this).apply {
                    hint = "密碼"; setSingleLine()
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                if (!passwordOnly) fields.addView(username)
                fields.addView(password)
                val host = android.net.Uri.parse(options.uri.orEmpty()).host ?: "網站"
                item.dialog = AlertDialog.Builder(this).setTitle("網站登入 · $host")
                    .setView(fields).setPositiveButton("登入") { _, _ ->
                        finishPrompt(prompt) {
                            if (passwordOnly) prompt.confirm(password.text.toString())
                            else prompt.confirm(username.text.toString(), password.text.toString())
                        }
                        password.text.clear()
                    }.setNegativeButton("取消") { _, _ -> finishPrompt(prompt) { prompt.dismiss() } }
                    .setOnCancelListener { finishPrompt(prompt) { prompt.dismiss() } }.show()
            }
            is GeckoSession.PromptDelegate.PopupPrompt -> {
                val target = prompt.targetUri?.take(250) ?: "這個網站"
                item.dialog = AlertDialog.Builder(this).setTitle("允許開啟彈出視窗？")
                    .setMessage(target + "\n\n用於網站登入與授權時，請確認是你正在操作的網站。")
                    .setPositiveButton("允許") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                    }.setNegativeButton("封鎖") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(AllowOrDeny.DENY) }
                    }.setOnCancelListener { finishPrompt(prompt) { prompt.dismiss() } }.show()
            }
            is GeckoSession.PromptDelegate.BeforeUnloadPrompt -> {
                item.dialog = AlertDialog.Builder(this).setTitle("離開此頁？")
                    .setMessage("這個網站可能有尚未儲存的內容。")
                    .setPositiveButton("離開") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                    }.setNegativeButton("留在此頁") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(AllowOrDeny.DENY) }
                    }.setOnCancelListener { finishPrompt(prompt) { prompt.dismiss() } }.show()
            }
            is GeckoSession.PromptDelegate.RepostConfirmPrompt -> {
                item.dialog = AlertDialog.Builder(this).setTitle("重新提交表單？")
                    .setMessage("重新送出可能造成重複操作，請確認。")
                    .setPositiveButton("重新提交") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                    }.setNegativeButton("取消") { _, _ ->
                        finishPrompt(prompt) { prompt.confirm(AllowOrDeny.DENY) }
                    }.setOnCancelListener { finishPrompt(prompt) { prompt.dismiss() } }.show()
            }
            is GeckoSession.PromptDelegate.FilePrompt -> {
                if (pendingFilePrompt != null) {
                    finishPrompt(prompt) { prompt.dismiss() }
                    return true
                }
                pendingFilePrompt = prompt to reply
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        val types = prompt.mimeTypes.orEmpty().mapNotNull { value ->
                            if (value.startsWith('.')) android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(value.drop(1).lowercase())
                            else value.takeIf { it.matches(Regex("[\\w.*+-]+/[\\w.*+-]+")) }
                        }.distinct()
                        type = types.singleOrNull() ?: "*/*"
                        if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivityForResult(intent, fileRequestCode)
                } catch (error: Exception) {
                    pendingFilePrompt = null
                    finishPrompt(prompt) { prompt.dismiss() }
                    toast("無法開啟檔案選擇器")
                }
            }
            else -> { promptUi.remove(prompt); return false }
        }
        return true
    }
    @Deprecated("Uses the platform document picker, no third-party ActivityResult dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == bookmarkImportCode) {
            val profile = bookmarkImportProfile; bookmarkImportProfile = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && profile != null) fileImports.execute {
                val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytesLimited(1_000_000).toString(Charsets.UTF_8) } ?: error("無法讀取書籤") }
                runOnUiThread { if (!isDestroyed) text.onSuccess {
                    runCatching { ctrl.importBookmarks(it, profile) }.onSuccess { count -> toast("已匯入 $count 筆書籤") }.onFailure { failure -> toast(failure.message ?: "匯入失敗") }
                }.onFailure { toast(it.message ?: "匯入失敗") } }
            }
            return
        }
        if (requestCode == documentSaveCode) {
            val stream = documentStream; documentStream = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { stream?.close(); return }
            if (stream == null) { toast("儲存已中斷，請重新選擇另存"); return }
            fileImports.execute {
                val result = runCatching { stream.use { input -> contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                        check(total <= 100L * 1024 * 1024) { "文件超過 100 MB" }; output.write(buffer, 0, count) }
                } ?: error("無法寫入選擇的位置") } }
                runOnUiThread { if (!isDestroyed) toast(if (result.isSuccess) "已儲存文件" else result.exceptionOrNull()?.message ?: "儲存失敗") }
            }
            return
        }
        if (requestCode == scriptRequestCode) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                fileImports.execute {
                    val result = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytesLimited(100_000).toString(Charsets.UTF_8) }
                        ?: error("無法讀取腳本檔案") }
                    runOnUiThread { if (!isDestroyed) result.onSuccess { workbench.importScript(it) }.onFailure { toast(it.message ?: "匯入失敗") } }
                }
            }
            return
        }
        if (requestCode == exportRequestCode) {
            val id = pendingExportId
            pendingExportId = null
            val target = data?.data
            if (id != null && resultCode == RESULT_OK && target != null) {
                ctrl.downloads.export(id, target) { ok ->
                    toast(if (ok) "已另存下載檔案" else "另存失敗，原下載檔案仍保留")
                }
            }
            return
        }
        if (requestCode != fileRequestCode) return
        val item = pendingFilePrompt ?: return
        pendingFilePrompt = null
        val (prompt, reply) = item
        if (prompt.isComplete || prompt !in promptUi) return
        if (resultCode != RESULT_OK) {
            finishPrompt(prompt) { prompt.dismiss() }
            return
        }
        val uris = mutableListOf<android.net.Uri>()
        data?.data?.let { uris.add(it) }
        data?.clipData?.let { clip ->
            for (i in 0 until minOf(clip.itemCount, 12))
                clip.getItemAt(i).uri?.let { if (it !in uris) uris.add(it) }
        }
        try {
            if (uris.isEmpty()) finishPrompt(prompt) { prompt.dismiss() }
            else if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                finishPrompt(prompt) { prompt.confirm(this, uris.toTypedArray()) }
            else finishPrompt(prompt) { prompt.confirm(this, uris.first()) }
        } catch (error: Exception) {
            finishPrompt(prompt) { prompt.dismiss() }
            toast("此網站無法接收所選檔案")
        }
    }
    private lateinit var ctrl: BrowserController
    private lateinit var browser: GeckoView
    private lateinit var address: EditText
    private lateinit var tabButton: ImageButton
    private lateinit var profileButton: Button
    private lateinit var reloadButton: ImageButton
    private lateinit var loadingBar: ProgressBar
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton

    private val dark: Boolean get() = when (getSharedPreferences("abwr_settings", 0).getString("appearance", "system")) {
        "dark" -> true; "light" -> false
        else -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    private val bg get() = if (dark) Color.rgb(25, 19, 18) else Color.rgb(255, 250, 247)
    private val card get() = if (dark) Color.rgb(60, 49, 48) else Color.rgb(238, 227, 223)
    private val ink get() = if (dark) Color.rgb(241, 231, 229) else Color.rgb(45, 32, 31)
    private val subtle get() = if (dark) Color.rgb(199, 183, 180) else Color.rgb(114, 94, 90)
    private val primary get() = if (dark) Color.rgb(255, 181, 174) else Color.rgb(146, 65, 60)
    private var fullAddress = ""

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 13): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun ripple(color: Int = Color.TRANSPARENT, radius: Int = 16) = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x24FFFFFF else 0x18007352),
        rounded(color, radius), rounded(Color.WHITE, radius))

    private fun iconButton(icon: String, label: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            contentDescription = label; tooltipText = label
            minimumWidth = dp(48); minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple()
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(BrowserIcon(icon, ink))
            setOnClickListener { action() }
        }

    private fun label(text: String, size: Float = 15f, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }

    /** Native bottom panels keep navigation and account selection within thumb reach. */
    private fun sheet(title: String, subtitle: String, height: Int = 540,
        build: (LinearLayout, Dialog) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bg, 28)
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        root.addView(View(this).apply { background = rounded(subtle, 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(14) })
        val header = horizontal().apply { setPadding(dp(8), 0, 0, 0); isBaselineAligned = false }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label(title, 24f).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = true
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        })
        if (subtitle.isNotBlank()) heading.addView(label(subtitle, 12f, subtle).apply {
            setPadding(0, dp(6), 0, dp(12)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("close", "關閉面板") { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        build(body, dialog)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.3f }
            navigationBarColor = bg
        }
        dialog.show()
        val usable = window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(height), (usable * .84f).toInt()))
        if (android.animation.ValueAnimator.areAnimatorsEnabled() && !getSharedPreferences("abwr_settings", 0).getBoolean("reduce_motion", false)) {
            root.translationY = dp(32).toFloat(); root.alpha = 0f
            root.animate().translationY(0f).alpha(1f).setDuration(220L).start()
        }
    }

    private fun panelItem(parent: LinearLayout, icon: String, title: String,
        detail: String = "", selected: Boolean = false, action: () -> Unit) {
        val row = horizontal().apply {
            minimumHeight = dp(64); setPadding(dp(14), dp(9), dp(14), dp(9))
            background = ripple(if (selected) card else Color.TRANSPARENT)
            isFocusable = true; contentDescription = listOf(title, detail).filter { it.isNotBlank() }.joinToString("，")
            setOnClickListener { action() }
        }
        row.addView(android.widget.ImageView(this).apply {
            setImageDrawable(BrowserIcon(icon, if (selected) primary else subtle))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(18) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, if (selected) primary else ink))
        if (detail.isNotBlank()) texts.addView(label(detail, 12f, subtle).apply {
            setPadding(0, dp(4), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(ink)
        background = rounded(card)
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener { action() }
    }
    private fun horizontal(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(5), dp(8), dp(5))
    }
    private fun flexible(row: LinearLayout, item: View, weight: Float = 1f) {
        row.addView(item, LinearLayout.LayoutParams(0, dp(48), weight).apply {
            marginEnd = dp(4)
        })
    }
    private fun compact(row: LinearLayout, item: View) {
        row.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(4) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pendingExportId = savedInstanceState?.getString("pendingExportId")
        ctrl = (application as BrowserApplication).controller
        ctrl.initialize()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                handleBrowserBack()
            }
        }
        workbench = BrowserWorkbench(this, ctrl, dark, ::toast) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, scriptRequestCode)
        }
        everyday = BrowserTools(this, ctrl, dark, ::toast, ::saveDocument) { profile ->
            bookmarkImportProfile = profile
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, bookmarkImportCode)
        }
        ctrl.extensions.host = extensionHost
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = if (dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        browserRoot = root
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(if (fullscreen) 0 else bars.left, if (fullscreen) 0 else bars.top,
                if (fullscreen) 0 else bars.right, if (fullscreen) 0 else maxOf(bars.bottom, ime.bottom))
            insets
        }
        browser = GeckoView(this).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(primary)
            visibility = View.INVISIBLE
        }
        root.addView(loadingBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val navigation = horizontal().apply { setPadding(dp(12), dp(8), dp(12), dp(2)) }
        val addressGroup = horizontal().apply {
            setPadding(dp(2), 0, dp(2), 0); background = rounded(card, 24)
        }
        profileButton = button("G1") { showProfiles() }.apply {
            background = ripple(); minWidth = 0; minimumWidth = 0
            textSize = 12f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(primary)
        }
        addressGroup.addView(profileButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        siteInfoButton = iconButton("info", "網站資訊與權限") { everyday.siteInfo() }
        addressGroup.addView(siteInfoButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        address = EditText(this).apply {
            hint = "搜尋或輸入網址"
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(subtle)
            setSingleLine(true)
            setSelectAllOnFocus(true)
            background = null
            setPadding(dp(2), 0, dp(2), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnFocusChangeListener { _, focused ->
                if (focused) { setText(fullAddress); selectAll() }
                else refresh()
            }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    go(); true
                } else false
            }
        }
        addressGroup.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        reloadButton = iconButton("reload", "重新載入") {
            if (address.hasFocus()) go() else if (ctrl.status().optBoolean("loading")) ctrl.humanStop() else ctrl.humanReload()
        }
        addressGroup.addView(reloadButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        navigation.addView(addressGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(navigation)
        val bottom = horizontal().apply { setPadding(dp(8), dp(2), dp(8), dp(2)) }
        backButton = iconButton("back", "上一頁") { ctrl.humanBack() }
        forwardButton = iconButton("forward", "下一頁") { ctrl.humanForward() }
        flexible(bottom, backButton)
        flexible(bottom, forwardButton)
        flexible(bottom, iconButton("plus", "新增分頁") {
            runCatching { ctrl.createTab(BrowserSearch.home(searchEngine()), fromUser = true) }
                .onFailure { toast(it.message ?: "無法新增分頁") }
            refresh()
        })
        tabButton = iconButton("tabs", "分頁") { showTabs() }
        flexible(bottom, tabButton)
        flexible(bottom, iconButton("menu", "選單") { showMenu() })
        root.addView(bottom)
        browserChrome.add(navigation); browserChrome.add(bottom)
        root.isFocusableInTouchMode = true
        root.requestFocus()
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        ctrl.onStateChanged = { runOnUiThread { refresh() } }
        ctrl.sitePromptHost = this
        ctrl.replaceBrowserView = ::replaceBrowserSurface
        ctrl.bindView(browser)
        refresh()
        handleExternalLink(intent)
        startApiService()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    private fun replaceBrowserSurface(previous: GeckoView): GeckoView {
        val parent = previous.parent as? ViewGroup ?: return previous
        val position = parent.indexOfChild(previous)
        val params = previous.layoutParams
        val fresh = GeckoView(this).apply {
            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            coverUntilFirstPaint(bg)
        }
        parent.removeView(previous)
        parent.addView(fresh, position, params)
        browser = fresh
        return fresh
    }

    private fun searchEngine(): String =
        BrowserSearch.normalizeProvider(getSharedPreferences("abwr_settings", 0)
            .getString("search_engine", "google") ?: "google")

    private fun showSearchEnginePicker() {
        val engines = BrowserSearch.names().toTypedArray()
        val selected = BrowserSearch.keys().indexOf(searchEngine()).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("預設搜尋引擎")
            .setSingleChoiceItems(engines, selected) { dialog, which ->
                val engine = BrowserSearch.keys()[which]
                getSharedPreferences("abwr_settings", 0).edit()
                    .putString("search_engine", engine).apply()
                toast("搜尋引擎：${BrowserSearch.label(engine)}")
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun showFindInPage() {
        val profile = ctrl.status().getString("activeProfile")
        sheet("在頁面中尋找", "輸入文字，使用箭頭切換結果", 270) { body, dialog ->
            val field = EditText(this).apply {
                hint = "尋找文字"; setSingleLine(); textSize = 16f; setTextColor(ink); setHintTextColor(subtle)
                background = rounded(card, 16); setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            val count = label("", 13f, subtle).apply { setPadding(dp(4), dp(10), 0, dp(10)) }
            body.addView(field, LinearLayout.LayoutParams(-1, dp(52))); body.addView(count)
            var previous = ""; var generation = 0
            fun find(backwards: Boolean) {
                val query = field.text.toString()
                if (query.isBlank()) { ctrl.clearFind(profile); count.text = ""; return }
                val sequence = ++generation
                ctrl.findInPageAsync(if (query == previous) null else query, backwards, profile).whenComplete { result, error ->
                    runOnUiThread { if (dialog.isShowing && sequence == generation) {
                        count.text = if (error != null) error.message ?: "搜尋失敗" else if (result.optBoolean("found"))
                            "${result.optInt("current")} / ${result.optInt("total")}" else "找不到「$query」"
                    } }
                }
                previous = query
            }
            val row = horizontal()
            row.addView(iconButton("back", "上一個結果") { find(true) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(iconButton("forward", "下一個結果") { find(false) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(button("完成") { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(row)
            field.imeOptions = EditorInfo.IME_ACTION_SEARCH
            field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { find(false); true } else false }
            dialog.setOnDismissListener { ctrl.clearFind(profile) }
        }
    }

    private fun handleExternalLink(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type == "text/plain") {
            val text = incoming.getStringExtra(Intent.EXTRA_TEXT)?.take(4096)?.trim().orEmpty()
            if (text.isNotBlank()) runCatching { ctrl.createTab(BrowserSearch.resolve(text, searchEngine()), fromUser = true) }
                .onFailure { toast(it.message ?: "無法開啟分享的內容") }
            return
        }
        if (incoming?.action != Intent.ACTION_VIEW) return
        val url = incoming.dataString ?: return
        val scheme = android.net.Uri.parse(url).scheme?.lowercase()
        if (scheme !in setOf("https", "http")) return
        runCatching { ctrl.createTab(url, fromUser = true) }
            .onFailure { toast(it.message ?: "無法從其他 App 開啟連結") }
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalLink(intent)
    }

    private fun go() {
        val raw = address.text.toString().trim()
        if (raw.isBlank()) return
        val url = BrowserSearch.resolve(raw, searchEngine())
        runCatching { ctrl.humanNavigate(url) }
            .onFailure { toast(it.message ?: "無法開啟網址"); return }
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
        refresh()
    }

    private var tabOverview: TabOverview? = null
    private fun showTabs() {
        if (tabOverview?.isShowing == true) return
        tabOverview = TabOverview(this, ctrl, dark) { refresh() }.also { panel ->
            panel.setOnDismissListener { tabOverview = null; refresh() }
            panel.show()
        }
    }

    private fun showProfiles() {
        val arr = ctrl.profiles()
        val names = (0 until arr.length()).map {
            arr.getJSONObject(it).getString("id")
        }.toTypedArray()
        sheet("瀏覽容器", "每個容器分開保存網站登入與資料", 540) { body, dialog ->
            panelItem(body, "plus", "新增容器", "建立新的獨立瀏覽空間") {
                val retired = ctrl.status().getJSONArray("reservedDeletedProfileIds")
                val used = names.toSet() + (0 until retired.length()).map { retired.getString(it) }
                val next = (1..300).map { "G%03d".format(it) }.firstOrNull { it !in used }
                if (next == null) toast("容器已達上限")
                else runCatching { ctrl.createProfile(next); ctrl.switchProfile(next); dialog.dismiss(); refresh() }
                    .onFailure { toast(it.message ?: "無法新增容器") }
            }
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val id = item.getString("id")
                val mode = if (item.optBoolean("manual")) "人工操作" else "AI 可操作"
                panelItem(body, "container", id,
                    (if (item.optBoolean("active")) "目前使用 · " else "") + mode + "\n" + displayHost(item.optString("url")),
                    selected = item.optBoolean("active")) {
                    runCatching { ctrl.switchProfile(id); dialog.dismiss(); refresh() }
                        .onFailure { toast(it.message ?: "無法切換容器") }
                }
            }
        }
    }

    private fun toggleTakeover() {
        val next = !ctrl.manualMode
        ctrl.setManual(next)
        toast(if (next) "已切換人工操作" else "已交還 AI")
        refresh()
    }

    private fun showMenu() {
        val state = ctrl.status()
        sheet("Abwr", displayHost(state.optString("url")), 610) { body, dialog ->
            fun perform(action: () -> Unit) { dialog.dismiss(); runCatching { action(); refresh() }.onFailure { toast(it.message ?: "無法完成操作") } }
            val quick = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(card, 22); setPadding(dp(6), dp(8), dp(6), dp(8)) }
            val actions = listOf(
                Triple("home", "首頁", { ctrl.humanNavigate(BrowserSearch.home(searchEngine())) }),
                Triple("bookmark", "收藏", { ctrl.bookmark(state.optString("url")); toast("已加入書籤") }),
                Triple("search", "尋找", { showFindInPage() }),
                Triple("share", "分享", { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.optString("url")), "分享網頁")) }),
                Triple("download", "下載", { showDownloads() }),
                Triple("desktop", "桌面版", { ctrl.setDesktopMode(!ctrl.isDesktopMode()); Unit }),
                Triple("copy", "複製網址", { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("網址", state.optString("url"))); toast("已複製網址") }),
                Triple("external", "其他瀏覽器", { openExternalBrowser() }))
            actions.chunked(4).forEach { group ->
                val row = horizontal()
                group.forEach { (icon, title, action) ->
                    val tile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(80); background = ripple(); contentDescription = title
                        setOnClickListener { perform(action) } }
                    tile.addView(ImageView(this).apply { setImageDrawable(BrowserIcon(icon, primary)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24), dp(24)))
                    tile.addView(label(title, 11f).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, 0) })
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(80), 1f))
                }; quick.addView(row)
            }
            body.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            fun add(icon: String, title: String, detail: String = "", action: () -> Unit) { panelItem(body, icon, title, detail) { perform(action) } }
            add("container", "容器 · " + state.optString("activeProfile"), "切換登入空間") { showProfiles() }
            add("shield", "新增無痕瀏覽", "不保存歷史與分頁") { ctrl.createPrivateProfile() }
            if (ctrl.isPrivate()) add("close", "關閉所有無痕容器") { ctrl.closePrivateProfiles() }
            if (ctrl.closedTabCount() > 0) add("reload", "重新開啟關閉的分頁", "目前容器") { ctrl.reopenClosedTab(fromUser = true) }
            add("info", "網站資訊與權限") { everyday.siteInfo() }
            add("search", "閱讀檢視") { everyday.reading() }
            add("download", "另存網頁", "PDF、截圖或文字") { everyday.savePage() }
            add("home", "加入主畫面") { everyday.addHomeShortcut() }
            add("agent", if (ctrl.manualMode) "交還 AI" else "人工接管", if (ctrl.manualMode) "目前已暫停 AI 寫入" else "點此暫停目前容器的 AI 操作") { toggleTakeover() }
            add("bookmark", "書籤") { showUrlCollection("書籤", ctrl.bookmarks(), false) }
            add("history", "歷史紀錄") { showUrlCollection("歷史紀錄", ctrl.history(), true) }
            add("extension", "擴充功能", "商店與已安裝擴充") { workbench.extensions() }
            add("code", "使用者腳本") { workbench.scripts() }
            add("code", "DevTools", "Console、Network、DOM、Storage") { workbench.devtools() }
            add("settings", "設定") { showAdvancedMenu() }
        }
    }
    private fun showAppearancePicker() {
        val prefs = getSharedPreferences("abwr_settings", 0)
        val keys = listOf("system", "light", "dark")
        val selected = keys.indexOf(prefs.getString("appearance", "system")).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("外觀")
            .setSingleChoiceItems(arrayOf("跟隨系統", "淺色", "深色"), selected) { dialog, index ->
                prefs.edit().putString("appearance", keys[index]).apply()
                dialog.dismiss()
                recreate()
            }.setNegativeButton("取消", null).show()
    }
    private fun showAdvancedMenu() {
        val page = BrowserPage(this, "設定", "Abwr ${BuildConfig.VERSION_NAME}", dark)
        page.section("瀏覽")
        page.card { box ->
            page.item(box, "settings", "外觀", "跟隨系統、淺色或深色") { showAppearancePicker() }
            page.item(box, "external", "設為預設瀏覽器", "由 Android 詢問你的選擇") {
                if (Build.VERSION.SDK_INT >= 29) {
                    val roles = getSystemService(android.app.role.RoleManager::class.java)
                    if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 4207)
                    else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            page.item(box, "search", "搜尋引擎", BrowserSearch.label(searchEngine())) { showSearchEnginePicker() }
            page.item(box, "search", "文字大小與手指縮放") { everyday.textSize() }
            page.item(box, "bookmark", "書籤匯入與匯出", "Firefox／Chrome HTML 格式") { everyday.bookmarkFiles() }
            page.toggle(box, "motion", "減少動畫", "簡化頁面與分頁切換", getSharedPreferences("abwr_settings", 0).getBoolean("reduce_motion", false)) {
                getSharedPreferences("abwr_settings", 0).edit().putBoolean("reduce_motion", it).apply()
            }
            page.toggle(box, "desktop", "桌面版網站", "套用於目前容器", ctrl.isDesktopMode()) { ctrl.setDesktopMode(it) }
        }
        page.section("隱私與網站")
        page.card { box ->
            page.toggle(box, "shield", "追蹤防護", "限制跨站追蹤內容", ctrl.trackingProtectionEnabled()) { ctrl.setTrackingProtection(it) }
            page.toggle(box, "shield", "全域指紋防護", "套用於所有容器；部分網站顯示可能改變", ctrl.fingerprintProtectionEnabled()) { ctrl.setFingerprintProtection(it) }
            page.item(box, "container", "容器隱私隔離", "檢查 Cookie／Storage 隔離範圍") { showText("容器隱私隔離", ctrl.privacyReport().toString(2)) }
            page.item(box, "info", "目前網站權限") { everyday.siteInfo() }
            page.item(box, "history", "清除目前容器的歷史", "保留網站登入與書籤") { everyday.clearHistory() }
            page.item(box, "info", "登入與網站相容性", "檢查目前頁面的登入及驗證提示") { showDevInfo("compatibility", "登入與網站相容性") }
            page.item(box, "external", "在其他瀏覽器開啟", "由系統瀏覽器接手目前網址") { openExternalBrowser() }
        }
        page.section("擴充與開發")
        page.card { box ->
            page.item(box, "extension", "擴充商店", "安裝與管理 Mozilla 擴充功能") { workbench.extensions() }
            page.item(box, "code", "使用者腳本", "匯入、編輯與啟停 .user.js") { workbench.scripts() }
            page.item(box, "code", "DevTools", "Console、Network、DOM、Storage") { workbench.devtools() }
        }
        page.section("AI 與背景執行")
        page.card { box ->
            page.item(box, "agent", if (ctrl.manualMode) "交還 AI" else "人工接管", "人工接管會暫停目前容器的 AI 寫入") { toggleTakeover() }
            page.item(box, "link", "AI 連線", "本機 HTTP／MCP 與 Token") { showAgentConnection() }
            page.toggle(box, "desktop", "背景繪製", "允許背景網頁繪製與截圖", ctrl.backgroundRenderingEnabled()) { ctrl.setBackgroundRendering(it) }
            page.item(box, "settings", "Android 背景與電池設定", "開啟系統應用程式設定") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            page.item(box, "info", "執行狀態", "容器、bridge、背景 Surface 和 API 診斷") { showText("執行狀態", ctrl.status().toString(2)) }
        }
        page.section("關於 Abwr")
        page.card { page.item(it, "info", "Abwr ${BuildConfig.VERSION_NAME}", "Android GeckoView 瀏覽器 · 本機 AI 控制 · 來源 ${BuildConfig.SOURCE_REVISION.take(12)}") }
        page.show()
    }
    private fun showAgentConnection() {
        val page = BrowserPage(this, "AI 連線", "本機 HTTP／MCP", dark)
        page.section("連線方式")
        page.card { box ->
            page.item(box, "link", "http://127.0.0.1:8765", "API 只接受本機連線；AI 用 Bearer Token 驗證。")
            page.item(box, "agent", "MCP 設定與跨 AI 操作說明", "OpenCode、Codex、Claude、Gemini 等客戶端") {
                ctrl.humanNavigate("https://github.com/Tsenjii/Abwr-/blob/main/skills/abwr-browser/SKILL.md"); page.dialog.dismiss()
            }
            page.item(box, "copy", "複製 API Token", "貼到你自己的 AI 連線設定") { copyToken() }
            page.item(box, "reload", "重設 API Token", "現有 AI 連線需要更新 Token") { confirmRotateToken() }
        }
        page.section("目前狀態")
        page.card { page.item(it, "info", if (ctrl.status().optBoolean("bridge")) "網頁 bridge 已連線" else "等待網頁 bridge", "容器 ${ctrl.status().optString("activeProfile")}") }
        page.show()
    }
    private fun openExternalBrowser() {
        val uri = android.net.Uri.parse(ctrl.status().optString("url"))
        if (uri.scheme !in listOf("https", "http")) { toast("目前頁面無法交給系統瀏覽器"); return }
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }
        if (candidates.isEmpty()) { toast("沒有找到其他瀏覽器"); return }
        AlertDialog.Builder(this).setTitle("在其他瀏覽器開啟")
            .setItems(candidates.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
                val target = candidates[index].activityInfo
                runCatching { startActivity(Intent(intent).setClassName(target.packageName, target.name)) }.onFailure { toast("無法開啟其他瀏覽器") }
            }.setNegativeButton("取消", null).show()
    }

    private fun showUrlCollection(title: String, urls: org.json.JSONArray, history: Boolean) {
        val profile = ctrl.status().getString("activeProfile")
        val page = BrowserPage(this, title, "容器 · $profile", dark)
        var data = (0 until urls.length()).map { urls.optString(it) }
        val results = page.column()
        fun render(term: String) {
            results.removeAllViews()
            val rows = data.filter { it.contains(term, true) }
            if (rows.isEmpty()) page.empty(if (term.isBlank()) "尚無$title" else "沒有符合的結果",
                if (history) "瀏覽過的網頁會顯示在這裡。" else "從選單收藏網頁，方便下次開啟。", results)
            rows.forEach { url -> page.card(results) { box ->
                val row = page.item(box, if (history) "history" else "bookmark", displayHost(url), url) {
                    ctrl.switchProfile(profile); ctrl.humanNavigate(url); page.dialog.dismiss(); refresh()
                }
                row.addView(page.icon("menu", "網頁選項") {
                    val choices = if (history) arrayOf("在新分頁開啟", "複製網址", "分享", "移除此筆紀錄") else arrayOf("在新分頁開啟", "複製網址", "分享", "移除書籤")
                    AlertDialog.Builder(this).setTitle(displayHost(url)).setItems(choices) { _, choice ->
                        when (choice) {
                            0 -> { ctrl.switchProfile(profile); ctrl.createTab(url, profile, fromUser = true); page.dialog.dismiss() }
                            1 -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("網址", url)); toast("已複製網址") }
                            2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "分享網頁"))
                            3 -> { if (history) ctrl.removeHistory(url, profile) else ctrl.bookmark(url, false, profile)
                                data = data.filter { it != url }; render(term) }
                        }
                    }.show()
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            } }
        }
        val query = page.search("搜尋網站名稱或網址") { render(it) }.apply { hint = "搜尋網站名稱或網址" }
        page.body.addView(query); page.body.addView(results); render(""); page.show()
    }

    private fun showDevInfo(action: String, title: String) {
        ctrl.commandAsync(JSONObject().put("action",action)).whenComplete { result, error ->
            runOnUiThread {
                if (error != null) {
                    showText(title,"讀取失敗：" + (error.message ?: "未知錯誤"))
                } else {
                    val payload = result?.optJSONObject("result")
                    showText(title, (payload?.toString(2) ?: result?.toString(2) ?: "空").take(16000))
                }
            }
        }
    }
    private fun showText(title: String, text: String) {
        val page = BrowserPage(this, title, "Abwr", dark)
        page.body.addView(page.text(text, 13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
        page.show()
    }

    private val extensionHost = object : BrowserExtensions.Host {
        override fun confirmExtension(title: String, details: String, done: (Boolean) -> Unit) {
            if (isFinishing || isDestroyed) { done(false); return }
            var answered = false
            lateinit var dialog: AlertDialog
            fun choose(allow: Boolean) { if (!answered) { answered = true; extensionDialogs.remove(dialog); done(allow) } }
            dialog = AlertDialog.Builder(this@MainActivity).setTitle(title).setMessage(details)
                .setPositiveButton("允許") { _, _ -> choose(true) }.setNegativeButton("取消") { _, _ -> choose(false) }
                .setOnCancelListener { choose(false) }.create()
            extensionDialogs.add(dialog); dialog.show()
        }
        override fun openExtensionPage(extension: org.mozilla.geckoview.WebExtension, session: GeckoSession, open: () -> Unit) {
            if (isFinishing || isDestroyed) return
            val page = BrowserPage(this@MainActivity, ctrl.extensions.name(extension), "擴充功能", dark)
            page.root.removeView(page.scroll)
            val web = GeckoView(this@MainActivity).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
            page.root.addView(web, 1, LinearLayout.LayoutParams(-1, 0, 1f))
            extensionWindows[page.dialog] = session
            page.onDismiss = {
                web.releaseSession(); extensionWindows.remove(page.dialog); if (session.isOpen) session.close()
                ctrl.visibleSessionForExtension()?.let { ctrl.extensions.activateSession(it, restore = true) }
            }
            page.show(); open(); web.setSession(session)
        }
    }
    private fun copyToken() {
        val token = LocalApiServer.getOrCreateToken(this)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Abwr Token",token))
        toast("本機金鑰已複製")
    }
    private fun saveDocument(stream: java.io.InputStream, name: String, mime: String) {
        if (documentStream != null) { stream.close(); toast("請先完成目前的另存操作"); return }
        documentStream = stream
        runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name)
        }, documentSaveCode) }.onFailure { documentStream = null; stream.close(); toast("無法開啟儲存位置") }
    }
    private fun confirmRotateToken() {
        AlertDialog.Builder(this).setTitle("重設 API 金鑰")
            .setMessage("既有 Agent 連線將失效。網站登入資料不會清除。")
            .setNegativeButton("取消",null)
            .setPositiveButton("重設") { _, _ ->
                try {
                    ContextCompat.startForegroundService(this,
                        Intent(this,BrowserService::class.java).setAction(BrowserService.ACTION_ROTATE_TOKEN))
                    toast("已要求重設；完成後重新複製金鑰")
                } catch (e: Exception) { toast(e.message ?: "重設服務失敗") }
            }.show()
    }
    private fun startApiService() {
        try {
            ContextCompat.startForegroundService(this,Intent(this,BrowserService::class.java))
        } catch(e: Exception) { toast("本機服務啟動失敗") }
    }
    private fun refresh() {
        if (!::address.isInitialized || !::tabButton.isInitialized) return
        tabOverview?.refresh()
        if (::workbench.isInitialized) workbench.refresh()
        val value = ctrl.status()
        fullAddress = if (value.isNull("pendingUrl")) value.optString("url")
            else value.optString("pendingUrl").ifBlank { value.optString("url") }
        if (!address.hasFocus()) address.setText(displayHost(fullAddress))
        address.contentDescription = "搜尋或輸入網址，" + value.optString("title")
        loadingBar.visibility = if (value.optBoolean("loading")) View.VISIBLE else View.INVISIBLE
        reloadButton.setImageDrawable(BrowserIcon(if (value.optBoolean("loading")) "close" else "reload", ink))
        reloadButton.contentDescription = if (value.optBoolean("loading")) "停止載入" else "重新載入"
        val secure = ctrl.siteInfo().optJSONObject("security")?.optBoolean("secure") == true
        siteInfoButton?.setImageDrawable(BrowserIcon(if (secure) "shield" else "info", if (secure) primary else subtle))
        siteInfoButton?.contentDescription = if (secure) "連線已加密，網站資訊與權限" else "網站資訊與權限"
        backButton.isEnabled = value.optBoolean("canGoBack")
        forwardButton.isEnabled = value.optBoolean("canGoForward")
        backButton.alpha = if (backButton.isEnabled) 1f else 0.4f
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.4f
        val count = value.optInt("tabCount",1)
        tabButton.setImageDrawable(BrowserIcon("tabs", ink, count))
        tabButton.contentDescription = "共 $count 個分頁，開啟分頁面板"
        val profile = value.optString("activeProfile", "G001")
        profileButton.text = if (ctrl.isPrivate()) "無痕" else if (profile.matches(Regex("G[0-9]+"))) "G" + (profile.drop(1).toIntOrNull() ?: profile.drop(1)) else profile.take(5)
        profileButton.contentDescription = "目前容器 $profile，" + if (ctrl.manualMode) "人工接管中" else "AI 可操作"
        profileButton.setTextColor(if (ctrl.manualMode) subtle else primary)
    }
    private fun displayHost(url: String): String = runCatching {
        val uri = android.net.Uri.parse(url)
        uri.host?.removePrefix("www.")?.let { host ->
            host + uri.path.orEmpty().takeUnless { it == "/" }.orEmpty()
        } ?: url
    }.getOrDefault(url)
    private fun toast(value:String) {
        Toast.makeText(this,value,Toast.LENGTH_SHORT).show()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { handleBrowserBack() }
    private fun handleBrowserBack() {
        if (fullscreen) { ctrl.exitFullscreen(); setBrowserFullscreen(false); return }
        val state = ctrl.status()
        if (ctrl.canGoBack()) {
            ctrl.humanBack()
        } else if (state.optBoolean("popupActive")) {
            ctrl.closePopup(state.optString("activeProfile"), fromUser = true)
        } else if (state.optString("activeTab", "main") != "main") {
            ctrl.closeTab(state.getString("activeTab"), fromUser = true)
        } else {
            finish()
        }
    }
    override fun onStart() {
        super.onStart()
        if (::browser.isInitialized) {
            ctrl.onStateChanged = { runOnUiThread { refresh() } }
            ctrl.sitePromptHost = this
            ctrl.extensions.host = extensionHost
            ctrl.replaceBrowserView = ::replaceBrowserSurface
            ctrl.bindView(browser)
            ctrl.presentPendingInteractions()
        }
    }
    override fun onStop() {
        if (fullscreen) { ctrl.exitFullscreen(); setBrowserFullscreen(false) }
        if (::browser.isInitialized) {
            // A website dialog must not stay actionable over a different app.
            // The system document/permission picker keeps its original request.
            promptUi.filterKeys { it !is GeckoSession.PromptDelegate.FilePrompt }.keys.toList()
                .forEach { prompt -> promptUi[prompt]?.dialog?.dismiss() }
            permissionDialogs.keys.toList().forEach { it.dismiss() }; permissionDialogs.clear()
            downloadDialogs.keys.toList().forEach { it.dismiss() }; downloadDialogs.clear()
            ctrl.onStateChanged = null
            ctrl.sitePromptHost = null
            if (ctrl.extensions.host === extensionHost) ctrl.extensions.host = null
            extensionDialogs.toList().forEach { it.cancel() }
            extensionWindows.keys.toList().forEach { it.dismiss() }
            ctrl.replaceBrowserView = null
            ctrl.unbindView(browser)
            ctrl.enterBackgroundSurface()
        }
        super.onStop()
    }
    override fun onDestroy() {
        documentStream?.close(); documentStream = null
        tabOverview?.dismiss()
        if (::browser.isInitialized) ctrl.unbindView(browser)
        ctrl.onStateChanged = null
        if (ctrl.sitePromptHost === this) ctrl.sitePromptHost = null
        if (ctrl.extensions.host === extensionHost) ctrl.extensions.host = null
        extensionDialogs.toList().forEach { it.cancel() }
        extensionWindows.keys.toList().forEach { it.dismiss() }
        promptUi.values.forEach { it.dialog?.dismiss() }; promptUi.clear()
        pendingAndroidPermission?.reject()
        pendingAndroidPermission = null
        androidPermissionSession = null
        pendingFilePrompt = null
        ctrl.replaceBrowserView = null
        fileImports.shutdownNow()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportId", pendingExportId)
        super.onSaveInstanceState(outState)
    }
}
