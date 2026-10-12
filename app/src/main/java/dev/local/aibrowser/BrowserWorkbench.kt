package dev.local.aibrowser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import org.json.JSONArray
import org.json.JSONObject

/** User-facing add-on, script and inspection pages. All privileged operations remain native. */
internal class BrowserWorkbench(private val activity: Activity, private val ctrl: BrowserController,
    private val dark: Boolean, private val toast: (String) -> Unit, private val importFile: () -> Unit) {
    private var extensionPage: BrowserPage? = null
    private var scriptsPage: BrowserPage? = null
    private var devPage: BrowserPage? = null
    private var storeQuery = ""
    private var storeResults: List<BrowserExtensions.Listing>? = null
    private var storeError: String? = null
    private var searchGeneration = 0
    private var storeLoading = false
    private var extensionsTab = "installed"
    private var scriptErrors = ""

    fun refresh() {
        extensionPage?.takeIf { it.dialog.isShowing }?.let(::renderExtensions)
        val status = ctrl.extensions.scriptStatus
        val signature = status.toString()
        if (signature != scriptErrors) { scriptErrors = signature; scriptsPage?.takeIf { it.dialog.isShowing }?.let(::renderScripts) }
    }
    fun extensions() {
        val page = BrowserPage(activity, "擴充功能", "Mozilla 擴充商店 · 所有容器", dark)
        extensionPage = page
        val tabs = page.row()
        for ((key, title) in listOf("installed" to "已安裝", "store" to "探索商店")) {
            tabs.addView(page.button(title) { extensionsTab = key; renderExtensions(page) }, LinearLayout.LayoutParams(0, page.dp(48), 1f).apply { marginEnd = page.dp(6) })
        }
        page.root.addView(tabs, 1)
        page.onDismiss = { if (extensionPage === page) extensionPage = null }
        renderExtensions(page); page.show(); ctrl.extensions.refresh()
    }
    private fun renderExtensions(page: BrowserPage) {
        page.body.removeAllViews()
        if (extensionsTab == "installed") {
            page.section("你的擴充功能")
            if (ctrl.extensions.loading) page.empty("正在讀取…", "整理已安裝的擴充功能")
            else {
                ctrl.extensions.error?.let { page.card { box -> page.item(box, "info", "操作未完成", it) } }
                val entries = ctrl.extensions.list()
                if (entries.isEmpty()) page.empty("為 Abwr 加入新能力", "到「探索商店」安裝腳本管理器、廣告阻擋或閱讀工具。")
                entries.forEach { extension ->
                    page.card { box ->
                        page.toggle(box, "extension", ctrl.extensions.name(extension),
                            "${extension.metaData?.version.orEmpty()} · ${extension.metaData?.description.orEmpty().take(160)}",
                            extension.metaData?.enabled == true) { enabled ->
                            ctrl.extensions.enable(extension, enabled) { problem -> if (problem != null) toast(problem) }
                        }
                        val actions = page.row()
                        actions.addView(page.button("開啟") {
                            if (!ctrl.extensions.openAction(extension, ctrl.visibleSessionForExtension())) {
                                if (extension.metaData?.optionsPageUrl.isNullOrBlank()) toast("此擴充沒有可開啟的面板") else ctrl.extensions.options(extension)
                            }
                        }, LinearLayout.LayoutParams(0, page.dp(48), 1f))
                        actions.addView(page.icon("settings", "設定") { ctrl.extensions.options(extension) }, LinearLayout.LayoutParams(page.dp(48), page.dp(48)))
                        actions.addView(page.icon("reload", "檢查更新") { ctrl.extensions.update(extension) { toast(it ?: "更新檢查完成") } }, LinearLayout.LayoutParams(page.dp(48), page.dp(48)))
                        actions.addView(page.icon("trash", "移除擴充") {
                            AlertDialog.Builder(activity).setTitle("移除 ${ctrl.extensions.name(extension)}？")
                                .setMessage("擴充功能和它儲存的設定將被移除。")
                                .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                                    ctrl.extensions.uninstall(extension) { toast(it ?: "已移除") }
                                }.show()
                        }, LinearLayout.LayoutParams(page.dp(48), page.dp(48)))
                        box.addView(actions)
                    }
                }
            }
            page.section("使用者腳本")
            page.card { page.item(it, "code", "內建腳本管理", "匯入 .user.js、編輯、啟停與設定執行網址") { scripts() } }
        } else {
            page.section("探索 Mozilla 商店")
            val query = page.field("搜尋擴充功能", storeQuery)
            val searchRow = page.row()
            searchRow.addView(query, LinearLayout.LayoutParams(0, -2, 1f))
            searchRow.addView(page.icon("search", "搜尋商店") { storeQuery = query.text.toString(); searchStore(page) }, LinearLayout.LayoutParams(page.dp(48), page.dp(48)))
            page.body.addView(searchRow)
            val shortcuts = page.row()
            for (name in listOf("Tampermonkey", "Violentmonkey")) shortcuts.addView(page.button(name) {
                storeQuery = name; searchStore(page)
            }, LinearLayout.LayoutParams(0, page.dp(48), 1f).apply { topMargin = page.dp(12); marginEnd = page.dp(6) })
            page.body.addView(shortcuts)
            page.body.addView(page.text("來源：addons.mozilla.org。安裝時會顯示權限。", 12f, page.subtle).apply { setPadding(0, page.dp(12), 0, 0) })
            if (storeLoading) page.empty("正在搜尋…", "讀取適用於 Android 的擴充功能")
            else if (storeError != null) {
                page.empty("商店暫時無法連線", storeError.orEmpty())
                page.body.addView(page.button("重試", true) { searchStore(page) })
            } else if (storeResults != null) {
                if (storeResults!!.isEmpty()) page.empty("沒有找到擴充功能", "試試不同的名稱或關鍵字。")
                storeResults!!.forEach { listing -> page.card { box ->
                    page.item(box, "extension", listing.name, listing.summary)
                    box.addView(page.text("${listing.author} · v${listing.version} · 評分 ${listing.rating}", 12f, page.subtle).apply { setPadding(page.dp(8), 0, 0, page.dp(10)) })
                    val installed = ctrl.extensions.list().any { it.id == listing.id }
                    box.addView(page.button(if (installed) "已安裝" else if (ctrl.extensions.busy) "正在處理…" else "安裝", !installed) {
                        if (installed) { extensionsTab = "installed"; renderExtensions(page) }
                        else ctrl.extensions.install(listing.xpi) { problem ->
                            toast(problem ?: "${listing.name} 已安裝")
                            if (problem == null) { extensionsTab = "installed"; renderExtensions(page) }
                        }
                    }.apply { isEnabled = !ctrl.extensions.busy })
                } }
            } else page.empty("找你常用的工具", "搜尋 Tampermonkey、uBlock Origin、Dark Reader 或其他擴充功能。")
        }
    }
    private fun searchStore(page: BrowserPage) {
        val generation = ++searchGeneration; storeLoading = true; storeError = null; renderExtensions(page)
        ctrl.extensions.search(storeQuery) { items, error ->
            if (generation != searchGeneration) return@search
            storeResults = items; storeError = error; storeLoading = false
            if (page.dialog.isShowing && extensionPage === page) renderExtensions(page)
        }
    }
    fun scripts() {
        val page = BrowserPage(activity, "使用者腳本", "依網址範圍套用到所有容器", dark)
        scriptsPage = page
        page.toolbar.addView(page.icon("plus", "新增腳本") { editScript(null) }, LinearLayout.LayoutParams(page.dp(48), page.dp(48)))
        page.onDismiss = { if (scriptsPage === page) scriptsPage = null }
        renderScripts(page); page.show()
    }
    private fun renderScripts(page: BrowserPage) {
        page.body.removeAllViews()
        page.card { box ->
            val status = ctrl.extensions.scriptStatus
            page.item(box, "code", if (status.optBoolean("connected")) "執行服務已連線" else "正在連線腳本服務…",
                "已註冊 ${status.optInt("active")} 個腳本。修改或停用後，重新整理網頁套用。")
            status.optJSONArray("errors")?.let { errors -> for (i in 0 until errors.length()) {
                box.addView(page.text(errors.optJSONObject(i)?.optString("message").orEmpty(), 12f, page.accent))
            } }
            page.item(box, "download", "匯入 .user.js", "從檔案選擇腳本，預覽內容後儲存") { importFile() }
        }
        val items = ctrl.userScripts.list()
        if (items.isEmpty()) page.empty("第一個腳本，從這裡開始", "新增或匯入腳本，讓常用網站更順手。")
        items.forEach { item -> page.card { box ->
            page.toggle(box, "code", item.name, item.matches.joinToString("\n"), item.enabled) {
                runCatching { ctrl.userScripts.setEnabled(item.id, it); ctrl.syncUserScripts(); renderScripts(page) }
                    .onFailure { error -> toast(error.message ?: "儲存失敗") }
            }
            val actions = page.row()
            actions.addView(page.button("編輯腳本") { editScript(item) }, LinearLayout.LayoutParams(0, page.dp(48), 1f))
            actions.addView(page.icon("trash", "移除 ${item.name}") {
                AlertDialog.Builder(activity).setTitle("移除腳本？").setMessage(item.name)
                    .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                        runCatching { ctrl.userScripts.remove(item.id); ctrl.syncUserScripts(); renderScripts(page) }.onFailure { toast(it.message ?: "移除失敗") }
                    }.show()
            }, LinearLayout.LayoutParams(page.dp(48), page.dp(48)))
            box.addView(actions)
        } }
        page.body.addView(page.text("內建支援 @match、@exclude-match、@run-at、GM_addStyle、GM_log。需要其他 GM API 的腳本請使用 Tampermonkey／Violentmonkey。", 12f, page.subtle))
    }
    fun importScript(code: String) {
        runCatching { ScriptMetadata.parse(code) }.onSuccess { editScript(null, code) }.onFailure { toast(it.message ?: "腳本格式無效") }
    }
    private fun editScript(item: UserScripts.Item?, imported: String? = null) {
        val template = "// ==UserScript==\n// @name 我的腳本\n// @match https://example.org/*\n// @grant none\n// ==/UserScript==\n\n// 在此加入腳本\n"
        val initial = imported ?: item?.code ?: template
        val metadata = ScriptMetadata.parse(initial)
        val page = BrowserPage(activity, if (item == null) "新增腳本" else "編輯腳本", "儲存後重新整理符合範圍的網頁", dark)
        page.section("名稱")
        val name = page.field("腳本名稱", item?.name ?: metadata.name.orEmpty()); page.body.addView(name)
        page.section("執行網址 · 每行一個 @match")
        val matches = page.field("https://*.example.com/*", (item?.matches ?: metadata.matches).joinToString("\n"), true); page.body.addView(matches)
        page.section("JavaScript")
        val code = page.field("腳本內容", initial, true).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; minLines = 15; setHorizontallyScrolling(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        page.body.addView(code)
        val errorLabel = page.text("", 13f, page.accent); page.body.addView(errorLabel)
        page.footer.visibility = View.VISIBLE
        page.footer.addView(page.button("儲存腳本", true) {
            runCatching { ctrl.userScripts.save(item?.id, name.text.toString(), matches.text.toString().lines(), code.text.toString()); ctrl.syncUserScripts() }
                .onSuccess { page.dialog.dismiss(); toast("腳本已儲存，重新整理網頁即可套用"); scriptsPage?.let(::renderScripts) }
                .onFailure { errorLabel.text = it.message ?: "儲存失敗" }
        }, LinearLayout.LayoutParams(-1, page.dp(48)))
        page.show()
    }
    fun devtools() {
        val page = BrowserPage(activity, "DevTools", "目前容器 · ${ctrl.status().optString("activeProfile")}", dark)
        devPage = page
        var tab = "console"; var recording = false; var loading = false; var sequence = 0
        val toolbar = page.row()
        val tabs = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(toolbar) }
        for ((key, title) in listOf("console" to "Console", "network" to "Network", "dom" to "DOM", "storage" to "Storage")) {
            toolbar.addView(page.button(title) { tab = key; loadDevtools(page, key, null, false) }, LinearLayout.LayoutParams(-2, page.dp(48)).apply { marginEnd = page.dp(8) })
        }
        page.root.addView(tabs, 1)
        fun load(record: Boolean? = null, clear: Boolean = false) { loadDevtools(page, tab, record, clear) }
        page.toolbar.addView(page.icon("reload", "重新讀取 DevTools") { load() }, LinearLayout.LayoutParams(page.dp(48), page.dp(48)))
        page.onDismiss = { if (devPage === page) devPage = null }
        page.show(); load()
    }
    private var devSequence = 0
    private fun loadDevtools(page: BrowserPage, tab: String, record: Boolean?, clear: Boolean) {
        val sequence = ++devSequence
        page.body.removeAllViews(); page.empty("正在讀取…", "檢查目前網頁")
        if (tab == "dom") {
            page.body.removeAllViews(); page.section("元素檢查")
            val query = page.field("CSS selector", "html"); page.body.addView(query)
            val resultBox = page.column(); page.body.addView(resultBox)
            page.body.addView(page.button("檢查元素", true) {
                resultBox.removeAllViews()
                read(JSONObject().put("action", "inspect_element").put("selector", query.text.toString())) { payload, error ->
                    if (!page.dialog.isShowing || sequence != devSequence) return@read
                    showJson(page, resultBox, payload, error)
                }
            })
            page.body.addView(page.text("顯示屬性、尺寸、CSS 和子元素。輸入值與 data-* 內容會遮蔽。", 12f, page.subtle))
            return
        }
        val action = if (tab == "storage") "storage_inspect" else "devtools"
        val request = JSONObject().put("action", action)
        if (record != null) request.put("record", record)
        if (clear) request.put("clear", true)
        read(request) { payload, error ->
            if (!page.dialog.isShowing || sequence != devSequence) return@read
            page.body.removeAllViews()
            if (error != null || payload == null) {
                page.empty("無法讀取網頁", error ?: "目前沒有資料")
                page.body.addView(page.button("重試", true) { loadDevtools(page, tab, record, clear) }); return@read
            }
            if (tab == "storage") { showJson(page, page.body, payload, null); return@read }
            val data = payload.optJSONObject(if (tab == "network") "network" else "console") ?: JSONObject()
            val controls = page.row()
            controls.addView(page.button(if (data.optBoolean("recording")) "停止記錄" else "開始記錄", true) {
                loadDevtools(page, tab, !data.optBoolean("recording"), false)
            }, LinearLayout.LayoutParams(0, page.dp(48), 1f))
            controls.addView(page.button("清除") { loadDevtools(page, tab, null, true) }, LinearLayout.LayoutParams(0, page.dp(48), 1f).apply { marginStart = page.dp(8) })
            page.body.addView(controls)
            page.body.addView(page.text(if (tab == "network") "只記錄啟動後目前分頁的請求。網址查詢、敏感標頭與回應本文不會儲存。" else "啟動後記錄 Console 訊息；重新整理後繼續記錄。物件內容不會展開。", 12f, page.subtle).apply { setPadding(0, page.dp(12), 0, page.dp(12)) })
            val rows = data.optJSONArray(if (tab == "network") "requests" else "events") ?: JSONArray()
            val filtered = page.column()
            fun render(term: String) {
                filtered.removeAllViews()
                val entries = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .filter { it.toString().contains(term, true) }.asReversed()
                if (entries.isEmpty()) page.empty("尚無記錄", "開始記錄後操作網頁，再點右上角重新讀取。", filtered)
                entries.forEach { row -> page.card(filtered) { box ->
                    if (tab == "network") page.item(box, "network", "${row.optString("method")} · ${row.optInt("status", 0).takeIf { it > 0 } ?: row.optString("phase")}",
                        "${row.optString("url")}\n${row.optLong("durationMs")} ms · ${row.optString("type")}") { detail("請求內容", row.toString(2)) }
                    else box.addView(page.text("${row.optString("level").uppercase()}  ${row.optString("message")}", 13f,
                        if (row.optString("level") == "error") page.accent else page.ink).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
                } }
            }
            page.body.addView(page.search(if (tab == "network") "篩選網址、方法或狀態" else "篩選 Console 訊息") { render(it) })
            page.body.addView(filtered); render("")
        }
    }
    private fun read(request: JSONObject, done: (JSONObject?, String?) -> Unit) {
        ctrl.commandAsync(request).whenComplete { reply, error -> activity.runOnUiThread {
            if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
            done(reply?.optJSONObject("result"), error?.cause?.message ?: error?.message)
        } }
    }
    private fun showJson(page: BrowserPage, parent: LinearLayout, json: JSONObject?, error: String?) {
        parent.addView(page.text(error ?: json?.toString(2) ?: "沒有資料", 13f).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, page.dp(16), 0, 0)
        })
    }
    private fun detail(title: String, value: String) {
        val page = BrowserPage(activity, title, "DevTools", dark)
        page.body.addView(page.text(value, 13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }); page.show()
    }
}
