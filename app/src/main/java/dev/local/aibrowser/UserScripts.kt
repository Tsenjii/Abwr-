package dev.local.aibrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent, user-controlled scripts executed by Firefox's isolated userScripts sandbox. */
internal class UserScripts(context: Context) {
    data class Item(val id: String, val name: String, val matches: List<String>,
        val code: String, val enabled: Boolean, val updatedAtMs: Long,
        val excludes: List<String> = emptyList(), val runAt: String = "document_idle", val grants: List<String> = emptyList()) {
        fun json(includeCode: Boolean = true) = JSONObject().put("id", id).put("name", name)
            .put("matches", JSONArray(matches)).put("enabled", enabled)
            .put("excludes", JSONArray(excludes)).put("runAt", runAt).put("grants", JSONArray(grants))
            .put("updatedAtMs", updatedAtMs).apply { if (includeCode) put("code", code) }
    }

    private val prefs = context.getSharedPreferences("abwr_user_scripts", 0)
    private val items = linkedMapOf<String, Item>()

    init {
        runCatching {
            val array = JSONArray(prefs.getString("items_v1", "[]") ?: "[]")
            for (i in 0 until minOf(array.length(), 200)) {
                val obj = array.getJSONObject(i)
                val matches = obj.optJSONArray("matches") ?: JSONArray().put("https://*/*")
                val item = Item(obj.getString("id"), obj.getString("name").take(80),
                    (0 until matches.length()).map { matches.getString(it).take(500) },
                    obj.getString("code").take(100_000), obj.optBoolean("enabled", true),
                    obj.optLong("updatedAtMs", 0L))
                if (valid(item)) {
                    val metadata = ScriptMetadata.parse(item.code)
                    items[item.id] = item.copy(excludes = metadata.excludes, runAt = metadata.runAt, grants = metadata.grants)
                }
            }
        }
    }

    fun list() = items.values.toList()
    fun enabledJson() = JSONArray(items.values.filter { it.enabled }.map { it.json() })

    fun save(id: String?, name: String, matches: List<String>, code: String): Item {
        val metadata = ScriptMetadata.parse(code)
        val cleanName = name.trim()
        val cleanMatches = matches.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(cleanName.length in 1..80) { "腳本名稱必須為 1–80 個字" }
        require(cleanMatches.isNotEmpty() && cleanMatches.size <= 30) { "至少需要一個 @match，最多 30 個" }
        require(cleanMatches.all(::validMatch)) { "@match 必須是 http(s) 網址樣式，例如 https://*.example.com/*" }
        require(code.isNotBlank() && code.length <= 100_000) { "腳本必須為 1–100000 個字" }
        require(id != null || items.size < 200) { "使用者腳本已達 200 個上限" }
        val key = id?.also { require(items.containsKey(it)) { "找不到使用者腳本" } }
            ?: "script_" + UUID.randomUUID().toString().replace("-", "")
        val item = Item(key, cleanName, cleanMatches, code, items[key]?.enabled ?: true,
            System.currentTimeMillis(), metadata.excludes, metadata.runAt, metadata.grants)
        require(valid(item)) { "腳本資料無效" }
        items[key] = item
        persist()
        return item
    }

    fun setEnabled(id: String, enabled: Boolean): Item {
        val old = items[id] ?: throw IllegalArgumentException("找不到使用者腳本")
        return old.copy(enabled = enabled, updatedAtMs = System.currentTimeMillis()).also {
            items[id] = it; persist()
        }
    }

    fun remove(id: String) {
        require(items.remove(id) != null) { "找不到使用者腳本" }
        persist()
    }

    private fun valid(item: Item) = item.id.matches(Regex("script_[a-f0-9]{32}")) &&
        item.name.isNotBlank() && item.matches.isNotEmpty() && item.matches.all(::validMatch) &&
        item.code.isNotBlank()

    private fun validMatch(value: String): Boolean {
        if (value == "<all_urls>") return true
        return value.length <= 500 && ScriptMetadata.validMatch(value)
    }

    private fun persist() {
        val array = JSONArray(items.values.map { it.json() })
        check(prefs.edit().putString("items_v1", array.toString()).commit()) { "無法儲存使用者腳本" }
    }
}
