package dev.local.aibrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bounded audit metadata only. Never accepts URL, page text, bodies or credentials. */
internal class AutomationAudit(context: Context) {
    private val prefs = context.getSharedPreferences("automation_audit", 0)
    private val items = ArrayDeque<JSONObject>()
    init { runCatching {
        val saved = JSONArray(prefs.getString("events", "[]"))
        for (i in maxOf(0, saved.length() - 256) until saved.length()) items.addLast(saved.getJSONObject(i))
    } }
    fun record(event: String, profile: String, tab: String = "", document: String = "", actor: String = "api", outcome: String = "dispatched", private: Boolean = false) {
        val item = JSONObject().put("event", event.take(60)).put("profile", profile.take(31))
            .put("tab", tab.take(40)).put("documentId", document.take(120)).put("actor", actor.take(20))
            .put("outcome", outcome.take(60)).put("timestampMs", System.currentTimeMillis())
        if (private) return
        items.addLast(item)
        while (items.size > 256) items.removeFirst()
        prefs.edit().putString("events", JSONArray(items.toList()).toString()).apply()
    }
    fun read(profile: String?) = JSONObject().put("events", JSONArray(items.filter { profile == null || it.optString("profile") == profile }))
        .put("capacity", 256).put("sensitiveValuesIncluded", false)
}
