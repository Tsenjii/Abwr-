package dev.local.aibrowser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    private lateinit var ctrl: BrowserController
    private lateinit var browser: GeckoView
    private lateinit var info: TextView
    private lateinit var address: EditText
    private lateinit var chooser: Spinner
    private lateinit var takeoverButton: Button
    private var suppressSelection = false

    private fun line(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun button(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { click() }
    }
    private fun row(parent: LinearLayout, vararg elements: android.view.View) {
        val layout = line()
        elements.forEach { layout.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        parent.addView(layout)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ctrl = (application as BrowserApplication).controller
        ctrl.initialize()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(249,250,252)) }
        val heading = TextView(this).apply {
            text = "AI Browser Server  ·  v0.3.2"
            setTextColor(Color.rgb(20,33,54))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 19f
            setPadding(18, 16, 12, 8)
        }
        root.addView(heading)
        info = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(70,82,96)); setPadding(16,8,8,12) }
        root.addView(info)
        chooser = Spinner(this)
        takeoverButton = button("接管") { ctrl.setManual(!ctrl.manualMode); refresh() }
        row(root, chooser, button("新增容器") {
            val ids = (0..300).map { "G%03d".format(it + 1) }
            val existing = ctrl.profiles().let { j -> (0 until j.length()).map { j.getJSONObject(it).getString("id") }.toSet() }
            val newId = ids.firstOrNull { it !in existing }
            if (newId == null) toast("已達上限") else {
                ctrl.createProfile(newId); ctrl.switchProfile(newId); refresh()
            }
        }, takeoverButton)
        val quickJump = EditText(this).apply {
            hint = "輸入容器編號：G025"
            setSingleLine(true)
            textSize = 13f
        }
        row(root, quickJump, button("跳至容器") {
            val id = quickJump.text.toString().trim()
            try { ctrl.switchProfile(id); refresh() }
            catch (_: Exception) { toast("找不到容器：$id") }
        })
        address = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            hint = "https://example.org/"
        }
        row(root, address, button("前往") {
            ctrl.humanNavigate(address.text.toString()); refresh()
        })
        row(root,
            button("返回") { ctrl.humanBack() },
            button("前進") { ctrl.humanForward() },
            button("重新整理") { ctrl.humanReload() }
        )
        row(root,
            button("關閉彈窗") { ctrl.closePopup(); refresh() },
            button("複製 API Token") {
                val token = LocalApiServer.getOrCreateToken(this)
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("API Token", token))
                toast("已複製本機連線金鑰；請勿分享給其他人")
            },
            button("啟動服務") { startApiService() }
        )
        row(root, button("重設 API Token") {
            AlertDialog.Builder(this)
                .setTitle("重設本機 API 金鑰？")
                .setMessage("重設後既有 Agent 連線金鑰會失效。請重新在本機複製金鑰更新 Agent。瀏覽器容器與網站資料不會被清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("確定重設") { _, _ ->
                    val intent = Intent(this, BrowserService::class.java)
                        .setAction(BrowserService.ACTION_ROTATE_TOKEN)
                    ContextCompat.startForegroundService(this, intent)
                    toast("已要求重新產生 API Token；請再按『複製 API Token』更新 Agent")
                }
                .show()
        })
        val settings = getSharedPreferences("abwr_settings", 0)
        lateinit var renderToggle: Button
        renderToggle = button("背景截圖實驗模式：" +
            if (settings.getBoolean("experimental_background_renderer", false)) "開" else "關") {
            val enabled = !settings.getBoolean("experimental_background_renderer", false)
            settings.edit().putBoolean("experimental_background_renderer", enabled).apply()
            renderToggle.text = "背景截圖實驗模式：" + if (enabled) "開" else "關"
            toast("切換至背景時生效；實驗功能須真機驗證，可能增加記憶體及電池耗用")
        }
        row(root, renderToggle, button("背景執行設定（系統）") {
            // Explicit user navigation only; do not secretly override Android battery policies.
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + packageName))
            startActivity(intent)
        }, button("如何驗證背景") {
            toast("先在系統中允許背景活動；截圖需可見 Surface，背景即時截圖尚未支援")
        })
        val hint = TextView(this).apply {
            text = "此瀏覽器資料與 Chrome／Brave 分開；背景截圖及內建 Proxy 尚未通過驗證。"
            textSize = 11f; setTextColor(Color.rgb(96,107,120)); setPadding(14,6,8,6)
        }
        root.addView(hint)
        browser = GeckoView(this)
        root.addView(browser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        chooser.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!suppressSelection) {
                    val value = chooser.selectedItem?.toString() ?: return
                    ctrl.switchProfile(value)
                    refresh()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        ctrl.onStateChanged = { runOnUiThread { refresh() } }
        ctrl.bindView(browser)
        refresh()
        startApiService()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }
    private fun startApiService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, BrowserService::class.java))
            toast("瀏覽器服務已要求啟動")
        } catch (e: Exception) {
            toast("服務無法啟動：" + (e.message ?: e.javaClass.simpleName))
        }
    }
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun refresh() {
        if (!::chooser.isInitialized) return
        val status = ctrl.status()
        info.text = "API: http://127.0.0.1:8765 | Profile: ${status.optString("activeProfile")} | " +
            "Bridge: ${if (status.optBoolean("extensionPortReady")) "Ready" else "Loading"} | " +
            (if (ctrl.manualMode) "人工操作中" else "AI 可操作") +
            (ctrl.extensionError?.let { " | ERROR: $it" } ?: "")
        takeoverButton.text = if (ctrl.manualMode) "交還 AI" else "接管"
        // Only the app's own heap. Do not confuse this with total device / Gecko native RAM.
        val heap = ctrl.diagnostics().optLong("memoryHeapUsedBytes") / (1024 * 1024)
        info.text = info.text.toString() + " | JVM heap: ${heap} MiB"
        val profiles = ctrl.profiles()
        val ids = (0 until profiles.length()).map { profiles.getJSONObject(it).getString("id") }
        val active = status.optString("activeProfile")
        if (chooser.count != ids.size || (0 until chooser.count).any { chooser.getItemAtPosition(it) != ids[it] }) {
            suppressSelection = true
            chooser.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ids)
            suppressSelection = false
        }
        val idx = ids.indexOf(active)
        if (idx >= 0 && chooser.selectedItemPosition != idx) {
            suppressSelection = true; chooser.setSelection(idx); suppressSelection = false
        }
        if (!address.hasFocus()) address.setText(status.optString("url"))
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
        ctrl.unbindView(browser)
        ctrl.onStateChanged = null
        super.onDestroy()
    }
}