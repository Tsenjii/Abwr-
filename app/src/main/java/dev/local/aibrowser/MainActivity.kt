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
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSession

/**
 * A normal mobile-browser shell first; advanced Agent controls live in the menu.
 * Nothing here reads the user's Chrome/Brave profile or exports auth cookies.
 */
class MainActivity : Activity(), BrowserController.SitePromptHost {
    private var pendingFilePrompt: Pair<GeckoSession.PromptDelegate.FilePrompt,
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse>>? = null
    private val fileRequestCode = 4201
    private val permissionsRequestCode = 4302
    private var pendingAndroidPermission: GeckoSession.PermissionDelegate.Callback? = null

    override fun showContentPermission(
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
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setMessage("$source 要求這項權限。$extra\n只在你信任這個網站時允許。")
            .setPositiveButton("允許") { _, _ ->
                reply.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            .setNegativeButton("拒絕") { _, _ ->
                reply.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            .setOnCancelListener {
                reply.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }.show()
        permission.notifyShown()
        return dialog.isShowing
    }

    override fun showAndroidPermissions(permissions: Array<String>,
        callback: GeckoSession.PermissionDelegate.Callback): Boolean {
        if (isFinishing || isDestroyed || pendingAndroidPermission != null) return false
        val requested = permissions.filter {
            it in listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO)
        }
        if (requested.isEmpty()) return false
        if (requested.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            callback.grant()
            return true
        }
        pendingAndroidPermission = callback
        requestPermissions(requested.toTypedArray(), permissionsRequestCode)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int,
        permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionsRequestCode) return
        val callback = pendingAndroidPermission ?: return
        pendingAndroidPermission = null
        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) callback.grant()
        else callback.reject()
    }
    // Website JS / popup / file dialogs require a visible user-facing Activity.
    override fun showSitePrompt(prompt: GeckoSession.PromptDelegate.BasePrompt,
        reply: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>): Boolean {
        if (isFinishing || isDestroyed) return false
        val heading = prompt.title?.take(150) ?: "網站要求確認"
        when (prompt) {
            is GeckoSession.PromptDelegate.AlertPrompt -> {
                AlertDialog.Builder(this).setTitle(heading)
                    .setMessage(prompt.message?.take(1000) ?: "")
                    .setPositiveButton("知道了") { _, _ -> reply.complete(prompt.dismiss()) }
                    .setOnCancelListener { reply.complete(prompt.dismiss()) }.show()
            }
            is GeckoSession.PromptDelegate.ButtonPrompt -> {
                AlertDialog.Builder(this).setTitle(heading)
                    .setMessage(prompt.message?.take(1000) ?: "")
                    .setPositiveButton("確認") { _, _ ->
                        reply.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                    }.setNegativeButton("取消") { _, _ ->
                        reply.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                    }.setOnCancelListener { reply.complete(prompt.dismiss()) }.show()
            }
            is GeckoSession.PromptDelegate.TextPrompt -> {
                val entry = EditText(this).apply {
                    setSingleLine()
                    setText(prompt.defaultValue ?: "")
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                AlertDialog.Builder(this).setTitle(heading)
                    .setMessage(prompt.message?.take(1000) ?: "")
                    .setView(entry)
                    .setPositiveButton("確定") { _, _ ->
                        reply.complete(prompt.confirm(entry.text.toString()))
                    }.setNegativeButton("取消") { _, _ ->
                        reply.complete(prompt.dismiss())
                    }.setOnCancelListener { reply.complete(prompt.dismiss()) }.show()
            }
            is GeckoSession.PromptDelegate.PopupPrompt -> {
                val target = prompt.targetUri?.take(250) ?: "這個網站"
                AlertDialog.Builder(this).setTitle("允許開啟彈出視窗？")
                    .setMessage(target + "\n\n用於網站登入與授權時，請確認是你正在操作的網站。")
                    .setPositiveButton("允許") { _, _ ->
                        reply.complete(prompt.confirm(AllowOrDeny.ALLOW))
                    }.setNegativeButton("封鎖") { _, _ ->
                        reply.complete(prompt.confirm(AllowOrDeny.DENY))
                    }.setOnCancelListener { reply.complete(prompt.dismiss()) }.show()
            }
            is GeckoSession.PromptDelegate.BeforeUnloadPrompt -> {
                AlertDialog.Builder(this).setTitle("離開此頁？")
                    .setMessage("這個網站可能有尚未儲存的內容。")
                    .setPositiveButton("離開") { _, _ ->
                        reply.complete(prompt.confirm(AllowOrDeny.ALLOW))
                    }.setNegativeButton("留在此頁") { _, _ ->
                        reply.complete(prompt.confirm(AllowOrDeny.DENY))
                    }.setOnCancelListener { reply.complete(prompt.dismiss()) }.show()
            }
            is GeckoSession.PromptDelegate.RepostConfirmPrompt -> {
                AlertDialog.Builder(this).setTitle("重新提交表單？")
                    .setMessage("重新送出可能造成重複操作，請確認。")
                    .setPositiveButton("重新提交") { _, _ ->
                        reply.complete(prompt.confirm(AllowOrDeny.ALLOW))
                    }.setNegativeButton("取消") { _, _ ->
                        reply.complete(prompt.confirm(AllowOrDeny.DENY))
                    }.setOnCancelListener { reply.complete(prompt.dismiss()) }.show()
            }
            is GeckoSession.PromptDelegate.FilePrompt -> {
                if (pendingFilePrompt != null) {
                    reply.complete(prompt.dismiss())
                    return true
                }
                pendingFilePrompt = prompt to reply
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = prompt.mimeTypes?.firstOrNull()?.takeIf { it.contains('/') } ?: "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivityForResult(intent, fileRequestCode)
                } catch (error: Exception) {
                    pendingFilePrompt = null
                    reply.complete(prompt.dismiss())
                    toast("無法開啟檔案選擇器")
                }
            }
            else -> return false
        }
        return true
    }
    @Deprecated("Uses the platform document picker, no third-party ActivityResult dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != fileRequestCode) return
        val item = pendingFilePrompt ?: return
        pendingFilePrompt = null
        val (prompt, reply) = item
        if (resultCode != RESULT_OK) {
            reply.complete(prompt.dismiss())
            return
        }
        val uris = mutableListOf<android.net.Uri>()
        data?.data?.let { uris.add(it) }
        data?.clipData?.let { clip ->
            for (i in 0 until minOf(clip.itemCount, 12))
                clip.getItemAt(i).uri?.let { if (it !in uris) uris.add(it) }
        }
        try {
            if (uris.isEmpty()) reply.complete(prompt.dismiss())
            else if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                reply.complete(prompt.confirm(this, uris.toTypedArray()))
            else reply.complete(prompt.confirm(this, uris.first()))
        } catch (error: Exception) {
            if (!prompt.isComplete) reply.complete(prompt.dismiss())
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

    private val bg = Color.rgb(247, 248, 251)
    private val ink = Color.rgb(28, 35, 49)
    private val subtle = Color.rgb(109, 119, 135)
    private val primary = Color.rgb(54, 82, 172)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 13): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(ink)
        background = rounded(Color.WHITE)
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
        super.onCreate(savedInstanceState)
        ctrl = (application as BrowserApplication).controller
        ctrl.initialize()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

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
            background = rounded(Color.WHITE)
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
                if (id != "popup") ctrl.selectTab(id, fromUser = true)
                refresh()
            }
            .setNeutralButton("關閉目前分頁") { _, _ ->
                val current = ctrl.status().optString("activeTab", "main")
                if (current != "main") ctrl.closeTab(current, fromUser = true)
                else toast("主分頁無法關閉")
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
                val used = names.toSet()
                val next = (1..300).map { "G%03d".format(it) }.firstOrNull { it !in used }
                if (next == null) toast("容器已達上限")
                else { ctrl.createProfile(next); ctrl.switchProfile(next); refresh() }
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
            "登入／驗證相容性診斷"
        )
        AlertDialog.Builder(this).setTitle("Abwr").setItems(choices) { _, selected ->
            if (selected == choices.lastIndex) {
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
                    val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                    startActivity(Intent.createChooser(intent, "選擇系統瀏覽器（Cookie 不會自動同步）"))
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
        val data = (0 until urls.length()).map { urls.optString(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setItems(data) { _, i -> ctrl.humanNavigate(data[i]); refresh() }
            .setNegativeButton("取消", null)
            .apply {
                if (!history) setNeutralButton("移除目前書籤") { _, _ ->
                    val url = ctrl.status().optString("url")
                    runCatching { ctrl.bookmark(url, false) }
                    toast("若有收藏，已移除目前頁面")
                }
            }.show()
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
        status.text = if (ctrl.manualMode) "人工模式" else
            if (value.optBoolean("extensionPortReady")) "AI 可用" else "瀏覽器"
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
        if (ctrl.status().optBoolean("popupActive")) {
            ctrl.closePopup(ctrl.status().optString("activeProfile"))
        } else if (ctrl.canGoBack()) {
            ctrl.humanBack()
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
        pendingFilePrompt?.let { (prompt, reply) ->
            if (!prompt.isComplete) reply.complete(prompt.dismiss())
        }
        pendingFilePrompt = null
        super.onDestroy()
    }
}
