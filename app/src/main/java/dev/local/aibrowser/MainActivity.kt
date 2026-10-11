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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.mozilla.geckoview.GeckoView

/**
 * A normal mobile-browser shell first; advanced Agent controls live in the menu.
 * Nothing here reads the user's Chrome/Brave profile or exports auth cookies.
 */
class MainActivity : Activity() {
    private lateinit var ctrl: BrowserController
    private lateinit var browser: GeckoView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var tabButton: Button
    private lateinit var profileButton: Button

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

        val navigation = horizontal()
        compact(navigation, button("‹") { ctrl.humanBack() })
        compact(navigation, button("›") { ctrl.humanForward() })
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
        compact(bottom, button("⌂") { ctrl.humanNavigate("https://www.google.com/") })
        compact(bottom, button("+") {
            runCatching { ctrl.createTab("https://www.google.com/") }
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
        ctrl.bindView(browser)
        refresh()
        startApiService()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    private fun go() {
        val raw = address.text.toString().trim()
        if (raw.isBlank()) return
        val url = when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.contains(' ') || !raw.contains('.') -> "https://www.google.com/search?q=" + android.net.Uri.encode(raw)
            else -> "https://" + raw
        }
        ctrl.humanNavigate(url)
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
                if (id != "popup") ctrl.selectTab(id)
                refresh()
            }
            .setNeutralButton("關閉目前分頁") { _, _ ->
                val current = ctrl.status().optString("activeTab", "main")
                if (current != "main") ctrl.closeTab(current)
                else toast("主分頁無法關閉")
                refresh()
            }
            .setPositiveButton("新增分頁") { _, _ ->
                runCatching { ctrl.createTab("https://www.google.com/") }
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
            "收藏此頁", "書籤", "歷史紀錄",
            if (ctrl.manualMode) "交還 AI" else "人工接管",
            "複製 API Token", "重設 API Token",
            "背景截圖實驗模式", "背景與電池設定", "API 狀態"
        )
        AlertDialog.Builder(this).setTitle("Abwr").setItems(choices) { _, which ->
            when(which) {
                0 -> { ctrl.setTrackingProtection(!ctrl.trackingProtectionEnabled()); refresh()
                    toast("追蹤防護設定已更新") }
                1 -> showDevInfo("devtools","網頁檢查")
                2 -> showDevInfo("storage_inspect","Storage · 本站")
                3 -> showDevInfo("cookie_metadata","Cookies · 名稱")
                4 -> {
                    val url = ctrl.status().optString("url")
                    runCatching { ctrl.bookmark(url) }
                        .onSuccess { toast("已加入書籤") }
                        .onFailure { toast(it.message ?: "無法收藏") }
                }
                5 -> showUrlCollection("書籤", ctrl.bookmarks(), false)
                6 -> showUrlCollection("歷史紀錄", ctrl.history(), true)
                7 -> toggleTakeover()
                8 -> copyToken()
                9 -> confirmRotateToken()
                10 -> {
                    val settings = getSharedPreferences("abwr_settings",0)
                    val enabled = !settings.getBoolean("experimental_background_renderer",false)
                    settings.edit().putBoolean("experimental_background_renderer",enabled).apply()
                    toast("背景實驗模式：" + if(enabled) "開" else "關")
                }
                11 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + packageName)))
                12 -> showText("Abwr 本機狀態", ctrl.status().toString(2).take(8500))
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
            if (value.optBoolean("extensionPortReady")) "AI 已連線" else "本機瀏覽器"
        tabButton.text = "分頁 " + value.optInt("tabCount",1)
        profileButton.text = value.optString("activeProfile","G001")
    }
    private fun toast(value:String) {
        Toast.makeText(this,value,Toast.LENGTH_SHORT).show()
    }
    override fun onStart() {
        super.onStart()
        if (::browser.isInitialized) {
            ctrl.onStateChanged = { runOnUiThread { refresh() } }
            ctrl.bindView(browser)
        }
    }
    override fun onStop() {
        if (::browser.isInitialized) {
            ctrl.onStateChanged = null
            ctrl.unbindView(browser)
            ctrl.enterBackgroundSurface()
        }
        super.onStop()
    }
    override fun onDestroy() {
        if (::browser.isInitialized) ctrl.unbindView(browser)
        ctrl.onStateChanged = null
        super.onDestroy()
    }
}
