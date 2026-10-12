package dev.local.aibrowser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/** UI and authenticated API resolve the same Gecko prompt, exactly once. */
internal class BrowserPromptBroker(private val context: Context,
    private val scope: (GeckoSession) -> JSONObject,
    private val changed: (GeckoSession.PromptDelegate.BasePrompt) -> Unit,
    private val audit: (JSONObject, String) -> Unit) {
    private data class Pending(val session: GeckoSession, val prompt: GeckoSession.PromptDelegate.BasePrompt,
        val reply: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>, val scope: JSONObject, val created: Long)
    private val items = linkedMapOf<String, Pending>()
    private val ui = Handler(Looper.getMainLooper())
    fun register(session: GeckoSession, prompt: GeckoSession.PromptDelegate.BasePrompt,
        reply: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>): Boolean {
        prune()
        if (items.size >= 16) return false
        val id = UUID.randomUUID().toString()
        val item = Pending(session, prompt, reply, scope(session), System.currentTimeMillis())
        item.scope.put("scopeToken", UUID.randomUUID().toString())
        items[id] = item
        reply.accept({ _ -> items.remove(id); audit(item.scope, "resolved"); changed(prompt) }, { _ -> items.remove(id); changed(prompt) })
        ui.postDelayed({
            if (items.remove(id) != null && !prompt.isComplete) {
                reply.complete(prompt.dismiss()); audit(item.scope, "expired"); changed(prompt)
            }
        }, 120000L)
        return true
    }
    private fun prune() { items.entries.removeAll { it.value.prompt.isComplete } }
    fun list(profile: String): JSONObject {
        prune()
        return JSONObject().put("profile", profile).put("capacity", 16).put("prompts", JSONArray(items.filterValues {
            it.scope.optString("profile") == profile
        }.map { (id, item) ->
            val prompt = item.prompt
            val kind = when (prompt) {
                is GeckoSession.PromptDelegate.AlertPrompt -> "alert"
                is GeckoSession.PromptDelegate.ButtonPrompt -> "confirm"
                is GeckoSession.PromptDelegate.TextPrompt -> "prompt"
                is GeckoSession.PromptDelegate.AuthPrompt -> "http_auth"
                is GeckoSession.PromptDelegate.FilePrompt -> "file"
                is GeckoSession.PromptDelegate.BeforeUnloadPrompt -> "beforeunload"
                is GeckoSession.PromptDelegate.RepostConfirmPrompt -> "repost"
                is GeckoSession.PromptDelegate.PopupPrompt -> "popup"
                else -> "unknown"
            }
            val message = when (prompt) {
                is GeckoSession.PromptDelegate.AlertPrompt -> prompt.message
                is GeckoSession.PromptDelegate.ButtonPrompt -> prompt.message
                is GeckoSession.PromptDelegate.TextPrompt -> prompt.message
                else -> ""
            }
            JSONObject(item.scope.toString()).put("id", id).put("kind", kind).put("createdAtMs", item.created)
                .put("title", safe(prompt.title.orEmpty())).put("message", safe(message.orEmpty()))
                .put("choices", JSONArray(listOf("accept", "dismiss"))).put("credentialValuesIncluded", false)
        }))
    }
    fun resolve(input: JSONObject): JSONObject {
        prune()
        val item = items[input.getString("id")] ?: throw BrowserOperationException("PROMPT_NOT_FOUND", "Prompt already resolved or expired")
        val now = scope(item.session)
        for (key in listOf("profile", "tab", "scopeToken")) {
            if (!input.has(key) || input.getString(key) != item.scope.getString(key))
                throw BrowserOperationException("PAGE_CHANGED", "Prompt scope does not match")
        }
        for (key in listOf("profile", "tab", "documentId", "url")) {
            if (now.opt(key)?.toString() != item.scope.opt(key)?.toString())
                throw BrowserOperationException("PAGE_CHANGED", "Prompt document or tab changed")
        }
        val prompt = item.prompt
        val decision = input.getString("decision")
        require(decision in setOf("accept", "dismiss")) { "decision must be accept/dismiss" }
        val response = if (decision == "dismiss") prompt.dismiss() else when (prompt) {
            is GeckoSession.PromptDelegate.AlertPrompt -> prompt.dismiss()
            is GeckoSession.PromptDelegate.ButtonPrompt -> prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)
            is GeckoSession.PromptDelegate.TextPrompt -> prompt.confirm(input.optString("text"))
            is GeckoSession.PromptDelegate.AuthPrompt -> if (prompt.authOptions.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0)
                prompt.confirm(input.getString("password")) else prompt.confirm(input.getString("username"), input.getString("password"))
            is GeckoSession.PromptDelegate.PopupPrompt -> prompt.confirm(AllowOrDeny.ALLOW)
            is GeckoSession.PromptDelegate.BeforeUnloadPrompt -> prompt.confirm(AllowOrDeny.ALLOW)
            is GeckoSession.PromptDelegate.RepostConfirmPrompt -> prompt.confirm(AllowOrDeny.ALLOW)
            is GeckoSession.PromptDelegate.FilePrompt -> {
                val raw = input.getJSONArray("uris")
                require(raw.length() in 1..8 && (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE || raw.length() == 1)) { "Invalid file count" }
                val uris = (0 until raw.length()).map { Uri.parse(raw.getString(it)).also { uri ->
                    require(uri.scheme == "content") { "Only Android content URIs already readable by Abwr are accepted" }
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } ?: error("File is not readable")
                } }.toTypedArray()
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) prompt.confirm(context, uris) else prompt.confirm(context, uris.first())
            }
            else -> throw BrowserOperationException("NOT_IMPLEMENTED", "Prompt type cannot be resolved")
        }
        item.scope.put("actor", "api")
        item.reply.complete(response)
        audit(item.scope, "api_" + decision)
        changed(prompt)
        return JSONObject().put("dispatched", true).put("siteCompleted", false).put("profile", item.scope.getString("profile"))
            .put("tab", item.scope.getString("tab")).put("documentId", item.scope.opt("documentId") ?: JSONObject.NULL)
    }
    fun cancel(session: GeckoSession) { items.filterValues { it.session === session }.values.toList().forEach {
        if (!it.prompt.isComplete) it.reply.complete(it.prompt.dismiss())
    }; prune() }
    private fun safe(text: String) = text.take(1000).replace(Regex("(?i)(bearer\\s+|(?:token|password|secret|code)\\s*[:=]\\s*)[^\\s,;]+"), "$1[redacted]")
}
