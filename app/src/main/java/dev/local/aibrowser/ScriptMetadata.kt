package dev.local.aibrowser

/** Validates the deliberately small built-in script API before saving or registration. */
internal object ScriptMetadata {
    data class Parsed(val name: String?, val matches: List<String>, val excludes: List<String>,
        val runAt: String, val grants: List<String>)
    private val supportedGrants = setOf("none", "GM_addStyle", "GM_log")
    fun validMatch(value: String): Boolean {
        if (value == "<all_urls>") return true
        return value.length <= 500 && Regex("(?:https?|\\*)://(?:\\*|(?:\\*\\.)?[A-Za-z0-9.-]+(?::[0-9]+)?)/[^\\s]*").matches(value)
    }
    fun parse(code: String): Parsed {
        require(code.isNotBlank() && code.length <= 100_000) { "腳本必須為 1–100000 個字" }
        val block = Regex("(?s)//\\s*==UserScript==(.*?)//\\s*==/UserScript==").find(code)?.groupValues?.get(1).orEmpty()
        val values = mutableMapOf<String, MutableList<String>>()
        block.lineSequence().forEach { line ->
            Regex("^\\s*//\\s*@([A-Za-z-]+)\\s+(.+?)\\s*$").find(line)?.let {
                values.getOrPut(it.groupValues[1]) { mutableListOf() }.add(it.groupValues[2])
            }
        }
        val matches = values["match"].orEmpty().distinct()
        val excludes = values["exclude-match"].orEmpty().distinct()
        val grants = values["grant"].orEmpty().distinct()
        val runAt = values["run-at"]?.lastOrNull()?.replace('-', '_') ?: "document_idle"
        require(matches.size <= 30 && excludes.size <= 30 && (matches + excludes).all(::validMatch)) { "@match／@exclude-match 網址樣式無效" }
        require(runAt in setOf("document_start", "document_end", "document_idle")) { "不支援的 @run-at" }
        require(grants.all { it in supportedGrants }) { "此腳本使用完整 GM API，請使用 Tampermonkey／Violentmonkey 匯入" }
        require(listOf("require", "resource", "include", "exclude", "connect").none { values[it]?.isNotEmpty() == true }) {
            "此腳本需要額外資源或匹配功能，請使用 Tampermonkey／Violentmonkey 匯入"
        }
        return Parsed(values["name"]?.firstOrNull()?.take(80), matches, excludes, runAt, grants.filter { it != "none" })
    }
}
