package dev.local.aibrowser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
    private val fileRequestCode = 4201
    private val permissionsRequestCode = 4302
    private var pendingAndroidPermission: GeckoSession.PermissionDelegate.Callback? = null
    private val exportRequestCode = 4203
    private var pendingExportId: String? = null

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
            else ctrl.downloads.discard(response)
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
        var entries = ctrl.downloads.list(profile)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply { this.adapter = adapter }
        val dialog = AlertDialog.Builder(this).setTitle(if (showAll) "下載 · 全部容器" else "下載 · $profile").setView(list)
            .setNeutralButton(if (showAll) "目前容器" else "全部容器") { _, _ -> showDownloads(!showAll) }
            .setNegativeButton("完成", null).create()
        fun update() {
            entries = ctrl.downloads.list(profile)
            val labels = entries.map { item ->
                val state = when (item.state) {
                    "queued" -> "等待下載"; "downloading" -> "下載中"
                    "complete" -> "完成"; "cancelled" -> "已取消"; else -> "失敗"
                }
                val size = android.text.format.Formatter.formatShortFileSize(this, item.bytes)
                val total = if (item.total >= 0L) "/" +
                    android.text.format.Formatter.formatShortFileSize(this, item.total) else ""
                item.name + "\n" + (if (showAll) item.profile + " · " else "") + state + " · " + size + total +
                    item.error.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            }
            adapter.clear()
            adapter.addAll(if (labels.isEmpty()) listOf("這個容器尚無下載紀錄") else labels)
            adapter.notifyDataSetChanged()
        }
        list.setOnItemClickListener { _, _, index, _ ->
            entries.getOrNull(index)?.let { showDownloadActions(it) }
        }
        val handler = Handler(Looper.getMainLooper())
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || isDestroyed) return
                update()
                handler.postDelayed(this, 1000L)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(refresh) }
        dialog.show()
        handler.post(refresh)
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
    // Website JS / popup / file dialogs require a visible user-facing Activity.
    override fun showSitePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.BasePrompt,
        reply: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>): Boolean {
        if (isFinishing || isDestroyed) return false
        if (promptUi.size >= 8) return false
        val item = PromptUi(session, reply)
        promptUi[prompt] = item
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
    private lateinit var status: TextView
    private lateinit var tabButton: Button
    private lateinit var profileButton: Button
    private lateinit var loadingBar: ProgressBar
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button

    private val dark: Boolean get() = when (getSharedPreferences("abwr_settings", 0).getString("appearance", "system")) {
        "dark" -> true; "light" -> false
        else -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    private val bg get() = if (dark) Color.rgb(21, 24, 31) else Color.rgb(247, 248, 251)
    private val card get() = if (dark) Color.rgb(38, 43, 54) else Color.WHITE
    private val ink get() = if (dark) Color.rgb(233, 237, 245) else Color.rgb(28, 35, 49)
    private val subtle get() = if (dark) Color.rgb(167, 177, 193) else Color.rgb(109, 119, 135)
    private val primary = Color.rgb(54, 82, 172)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 13): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

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
        pendingExportId = savedInstanceState?.getString("pendingExportId")
        ctrl = (application as BrowserApplication).controller
        ctrl.initialize()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = if (dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        val top = horizontal()
        val logo = TextView(this).apply {
            text = "Abwr"
            textSize = 19f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(12), 0, dp(12), 0)
        }
        compact(top, logo)
        status = TextView(this).apply {
            textSize = 11f
            setTextColor(subtle)
            gravity = Gravity.CENTER_VERTICAL
        }
        flexible(top, status)
        compact(top, button("⋮") { showMenu() })
        root.addView(top)
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(loadingBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val navigation = horizontal()
        backButton = button("‹") { ctrl.humanBack() }
        forwardButton = button("›") { ctrl.humanForward() }
        compact(navigation, backButton)
        compact(navigation, forwardButton)
        address = EditText(this).apply {
            hint = "搜尋或輸入網址"
            textSize = 13f
            setTextColor(ink)
            setSingleLine(true)
            setSelectAllOnFocus(true)
            background = rounded(card)
            setPadding(dp(12), 0, dp(10), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    go(); true
                } else false
            }
        }
        flexible(navigation, address, 4f)
        compact(navigation, button("↻") { ctrl.humanReload() })
        root.addView(navigation)

        browser = GeckoView(this)
        root.addView(browser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = horizontal()
        compact(bottom, button("⌂") { ctrl.humanNavigate(BrowserSearch.home(searchEngine())) })
        compact(bottom, button("+") {
            runCatching { ctrl.createTab(BrowserSearch.home(searchEngine()), fromUser = true) }
                .onFailure { toast(it.message ?: "無法新增分頁") }
            refresh()
        })
        tabButton = button("分頁 1") { showTabs() }
        flexible(bottom, tabButton, 2f)
        profileButton = button("容器") { showProfiles() }
        flexible(bottom, profileButton, 2f)
        compact(bottom, button("AI") { toggleTakeover() })
        root.addView(bottom)
        setContentView(root)
        ctrl.onStateChanged = { runOnUiThread { refresh() } }
        ctrl.sitePromptHost = this
        ctrl.bindView(browser)
        refresh()
        handleExternalLink(intent)
        startApiService()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
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
        val field = EditText(this).apply {
            hint = "尋找頁面內文字"
            setSingleLine()
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        fun find(backwards: Boolean) {
            val value = field.text.toString().trim()
            if (value.isEmpty()) return
            ctrl.findInPageAsync(value, backwards).whenComplete { result, error ->
                runOnUiThread {
                    if (error != null) toast("尋找失敗：" + (error.message ?: "未知"))
                    else toast(if (result.optBoolean("found"))
                        "${result.optInt("current")}/${result.optInt("total")} 筆"
                        else "找不到此文字")
                }
            }
        }
        AlertDialog.Builder(this).setTitle("在頁面中尋找")
            .setView(field).setPositiveButton("下一筆") { _, _ -> find(false) }
            .setNeutralButton("上一筆") { _, _ -> find(true) }
            .setNegativeButton("取消") { _, _ -> ctrl.clearFind() }
            .show()
    }

    private fun handleExternalLink(incoming: Intent?) {
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

    private fun showTabs() {
        val obj = ctrl.tabs()
        val arr = obj.getJSONArray("tabs")
        val names = (0 until arr.length()).map { i ->
            val tab = arr.getJSONObject(i)
            val title = tab.optString("title").ifBlank { tab.optString("url").ifBlank { "彈出視窗" } }
            (if (tab.optBoolean("active")) "● " else "   ") + title.take(62)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("分頁")
            .setItems(names) { _, i ->
                val id = arr.getJSONObject(i).getString("id")
                runCatching {
                    if (id != "popup") {
                        if (ctrl.status().optBoolean("popupActive")) ctrl.closePopup(fromUser = true)
                        ctrl.selectTab(id, fromUser = true)
                    }
                }.onFailure { toast(it.message ?: "無法切換分頁") }
                refresh()
            }
            .setNeutralButton("關閉目前分頁") { _, _ ->
                val state = ctrl.status()
                runCatching {
                    if (state.optBoolean("popupActive")) ctrl.closePopup(fromUser = true)
                    else {
                        val current = state.optString("activeTab", "main")
                        if (current != "main") ctrl.closeTab(current, fromUser = true)
                        else toast("主分頁無法關閉")
                    }
                }.onFailure { toast(it.message ?: "無法關閉分頁") }
                refresh()
            }
            .setPositiveButton("新增分頁") { _, _ ->
                runCatching { ctrl.createTab(BrowserSearch.home(searchEngine()), fromUser = true) }
                    .onFailure { toast(it.message ?: "新增失敗") }
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showProfiles() {
        val arr = ctrl.profiles()
        val names = (0 until arr.length()).map {
            arr.getJSONObject(it).getString("id")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("瀏覽容器")
            .setItems(names) { _, position -> ctrl.switchProfile(names[position]); refresh() }
            .setPositiveButton("新增") { _, _ ->
                val retired = ctrl.status().getJSONArray("reservedDeletedProfileIds")
                val used = names.toSet() + (0 until retired.length()).map { retired.getString(it) }
                val next = (1..300).map { "G%03d".format(it) }.firstOrNull { it !in used }
                if (next == null) toast("容器已達上限")
                else runCatching { ctrl.createProfile(next); ctrl.switchProfile(next); refresh() }
                    .onFailure { toast(it.message ?: "無法新增容器") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleTakeover() {
        val next = !ctrl.manualMode
        ctrl.setManual(next)
        toast(if (next) "已切換人工操作" else "已交還 AI")
        refresh()
    }

    private fun showMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        fun add(label: String, action: () -> Unit) { actions.add(label to action) }
        add("新增分頁") { ctrl.createTab(BrowserSearch.home(searchEngine()), fromUser = true) }
        add("收藏此頁") { ctrl.bookmark(ctrl.status().optString("url")); toast("已加入書籤") }
        add("書籤") { showUrlCollection("書籤", ctrl.bookmarks(), false) }
        add("歷史紀錄") { showUrlCollection("歷史紀錄", ctrl.history(), true) }
        add("下載") { showDownloads() }
        add("在頁面中尋找") { showFindInPage() }
        add("桌面版網站：" + if (ctrl.isDesktopMode()) "開" else "關") { ctrl.setDesktopMode(!ctrl.isDesktopMode()) }
        add("分享這個頁面") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, ctrl.status().optString("url")), "分享網頁"))
        }
        add("外觀") { showAppearancePicker() }
        add("預設搜尋引擎：" + BrowserSearch.label(searchEngine())) { showSearchEnginePicker() }
        add("工具與連線") { showAdvancedMenu() }
        AlertDialog.Builder(this).setTitle("Abwr").setItems(actions.map { it.first }.toTypedArray()) { _, i ->
            runCatching { actions[i].second(); refresh() }.onFailure { toast(it.message ?: "無法完成操作") }
        }.show()
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
        val choices = arrayOf(
            if (ctrl.trackingProtectionEnabled()) "追蹤防護：開啟" else "追蹤防護：關閉",
            "檢查網頁（DevTools）",
            "查看本站 Storage",
            "本站 Cookie 名稱",
            "在系統瀏覽器開啟",
            "在頁面中尋找",
            "桌面版網站：" + (if (ctrl.isDesktopMode()) "開" else "關"),
            "預設搜尋引擎：" + BrowserSearch.label(searchEngine()),
            "複製目前網址", "分享這個頁面",
            "收藏此頁", "書籤", "歷史紀錄",
            if (ctrl.manualMode) "交還 AI" else "人工接管",
            "複製 API Token", "重設 API Token",
            "背景截圖實驗模式", "背景與電池設定", "API 狀態",
            "登入／驗證相容性診斷",
            "容器隱私隔離狀態",
            if (ctrl.fingerprintProtectionEnabled())
                "全域指紋防護：開啟" else "全域指紋防護：關閉"
        )
        AlertDialog.Builder(this).setTitle("Abwr").setItems(choices) { _, selected ->
            if (selected == choices.lastIndex) {
                val now = !ctrl.fingerprintProtectionEnabled()
                ctrl.setFingerprintProtection(now)
                toast(if (now) "已開啟全域指紋防護" else "已關閉全域指紋防護")
                return@setItems
            }
            if (selected == choices.lastIndex - 1) {
                showText("容器隱私隔離", ctrl.privacyReport().toString(2))
                return@setItems
            }
            if (selected == choices.lastIndex - 2) {
                showDevInfo("compatibility", "登入與網站相容性")
                return@setItems
            }
            val which = if (selected >= 4) selected - 1 else selected
            if (selected == 4) {
                val current = ctrl.status().optString("url")
                val uri = android.net.Uri.parse(current)
                if (uri.scheme !in listOf("https", "http")) {
                    toast("目前頁面無法交給系統瀏覽器")
                } else {
                    // The generic Android chooser can choose Abwr itself,
                    // opening the same embedded OAuth URL and looping forever.
                    // Offer only real external browsers and launch explicitly.
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    val candidates = packageManager.queryIntentActivities(
                        intent, PackageManager.MATCH_DEFAULT_ONLY)
                        .filter { it.activityInfo.packageName != packageName }
                        .distinctBy { it.activityInfo.packageName }
                    if (candidates.isEmpty()) {
                        toast("沒有找到可開啟這個網址的其他瀏覽器")
                    } else {
                        val labels = candidates.map {
                            it.loadLabel(packageManager).toString() +
                                " · " + it.activityInfo.packageName
                        }.toTypedArray()
                        AlertDialog.Builder(this).setTitle("在其他瀏覽器開啟")
                            .setMessage("登入資料不會自動同步到 Abwr，請選擇其他瀏覽器。")
                            .setItems(labels) { _, index ->
                                val target = candidates[index].activityInfo
                                runCatching {
                                    startActivity(Intent(intent).setClassName(
                                        target.packageName, target.name))
                                }.onFailure { toast("無法開啟：" + (it.message ?: "未知原因")) }
                            }.setNegativeButton("取消", null).show()
                    }
                }
                return@setItems
            }
            when (which) {
                0 -> { ctrl.setTrackingProtection(!ctrl.trackingProtectionEnabled())
                    toast("追蹤防護設定已更新") }
                1 -> showDevInfo("devtools", "網頁檢查")
                2 -> showDevInfo("storage_inspect", "Storage · 本站")
                3 -> showDevInfo("cookie_metadata", "Cookies · 名稱")
                4 -> showFindInPage()
                5 -> { ctrl.setDesktopMode(!ctrl.isDesktopMode()); refresh() }
                6 -> showSearchEnginePicker()
                7 -> {
                    val url = ctrl.status().optString("url")
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("目前網址", url))
                    toast("已複製網址")
                }
                8 -> {
                    val url = ctrl.status().optString("url")
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    startActivity(Intent.createChooser(share, "分享網頁"))
                }
                9 -> {
                    val url = ctrl.status().optString("url")
                    runCatching { ctrl.bookmark(url) }
                        .onSuccess { toast("已加入書籤") }
                        .onFailure { toast(it.message ?: "無法收藏") }
                }
                10 -> showUrlCollection("書籤", ctrl.bookmarks(), false)
                11 -> showUrlCollection("歷史紀錄", ctrl.history(), true)
                12 -> toggleTakeover()
                13 -> copyToken()
                14 -> confirmRotateToken()
                15 -> {
                    val settings = getSharedPreferences("abwr_settings",0)
                    val enabled = !settings.getBoolean("experimental_background_renderer",false)
                    settings.edit().putBoolean("experimental_background_renderer",enabled).apply()
                    toast("背景實驗模式：" + if(enabled) "開" else "關")
                }
                16 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + packageName)))
                17 -> showText("Abwr 本機狀態", ctrl.status().toString(2).take(8500))
            }
        }.show()
    }

    private fun showUrlCollection(title: String, urls: org.json.JSONArray, history: Boolean) {
        if (urls.length() == 0) { toast("目前沒有" + title); return }
        val data = (0 until urls.length()).map { urls.optString(it) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val query = EditText(this).apply {
            hint = "搜尋網站名稱或網址"
            setSingleLine()
            textSize = 14f
        }
        val list = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, data.toMutableList())
        list.adapter = adapter
        root.addView(query, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val term = s?.toString()?.trim().orEmpty()
                val filtered = if (term.isBlank()) data else
                    data.filter { it.contains(term, ignoreCase = true) }
                adapter.clear()
                adapter.addAll(filtered)
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(root)
            .setNegativeButton("完成", null)
            .apply {
                if (!history) setNeutralButton("移除目前頁面的書籤") { _, _ ->
                    val url = ctrl.status().optString("url")
                    runCatching { ctrl.bookmark(url, false) }
                        .onSuccess { toast("已移除目前頁面的書籤") }
                        .onFailure { toast(it.message ?: "移除失敗") }
                }
            }.create()
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { ctrl.humanNavigate(it) }
            dialog.dismiss()
            refresh()
        }
        dialog.show()
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
        val content = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(ink)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton("完成",null).show()
    }
    private fun copyToken() {
        val token = LocalApiServer.getOrCreateToken(this)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Abwr Token",token))
        toast("本機金鑰已複製")
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
        val value = ctrl.status()
        if (!address.hasFocus()) address.setText(value.optString("url"))
        // Prioritize the web page instead of rendering AI debugging status
        // in the normal browser's most valuable screen space.
        val pageTitle = value.optString("title").trim()
        status.text = if (ctrl.manualMode) "人工接管" else
            pageTitle.ifBlank { if (value.optBoolean("loading")) "載入中…" else "Abwr" }
                .take(36)
        loadingBar.visibility = if (value.optBoolean("loading")) View.VISIBLE else View.GONE
        backButton.isEnabled = value.optBoolean("canGoBack")
        forwardButton.isEnabled = value.optBoolean("canGoForward")
        backButton.alpha = if (backButton.isEnabled) 1f else 0.4f
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.4f
        tabButton.text = "分頁 " + value.optInt("tabCount",1)
        profileButton.text = value.optString("activeProfile","G001")
    }
    private fun toast(value:String) {
        Toast.makeText(this,value,Toast.LENGTH_SHORT).show()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val state = ctrl.status()
        if (ctrl.canGoBack()) {
            ctrl.humanBack()
        } else if (state.optBoolean("popupActive")) {
            ctrl.closePopup(state.optString("activeProfile"), fromUser = true)
        } else if (state.optString("activeTab", "main") != "main") {
            ctrl.closeTab(state.getString("activeTab"), fromUser = true)
        } else {
            super.onBackPressed()
        }
    }
    override fun onStart() {
        super.onStart()
        if (::browser.isInitialized) {
            ctrl.onStateChanged = { runOnUiThread { refresh() } }
            ctrl.sitePromptHost = this
            ctrl.bindView(browser)
        }
    }
    override fun onStop() {
        if (::browser.isInitialized) {
            // A website dialog must not stay actionable over a different app.
            // The system document/permission picker keeps its original request.
            promptUi.filterKeys { it !is GeckoSession.PromptDelegate.FilePrompt }.keys.toList()
                .forEach { prompt -> finishPrompt(prompt) { prompt.dismiss() } }
            permissionDialogs.keys.toList().forEach { it.cancel() }
            downloadDialogs.keys.toList().forEach { it.cancel() }
            ctrl.onStateChanged = null
            ctrl.sitePromptHost = null
            ctrl.unbindView(browser)
            ctrl.enterBackgroundSurface()
        }
        super.onStop()
    }
    override fun onDestroy() {
        if (::browser.isInitialized) ctrl.unbindView(browser)
        ctrl.onStateChanged = null
        if (ctrl.sitePromptHost === this) ctrl.sitePromptHost = null
        promptUi.values.map { it.session }.distinct().forEach { dismissPromptsFor(it) }
        pendingAndroidPermission?.reject()
        pendingAndroidPermission = null
        androidPermissionSession = null
        pendingFilePrompt = null
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportId", pendingExportId)
        super.onSaveInstanceState(outState)
    }
}
