package dev.local.aibrowser

/** Shared validation before native or document commands can have side effects. */
internal object CommandArguments {
    private val common = mapOf("action" to "string", "profile" to "string", "tab" to "string",
        "expectedUrl" to "string", "documentId" to "string", "frame" to "string",
        "expectedFrameUrl" to "string", "includeFrames" to "boolean")
    private val fields = mapOf(
        "snapshot" to mapOf("limit" to "number", "offset" to "number", "selector" to "string",
            "includeHidden" to "boolean", "text" to "string", "role" to "string", "order" to "string"),
        "read" to mapOf("maxChars" to "number"), "dom" to mapOf("limit" to "number", "offset" to "number"),
        "links" to mapOf("limit" to "number", "offset" to "number"),
        "find" to mapOf("selector" to "string", "target" to "string", "text" to "string", "role" to "string", "ariaLabel" to "string", "limit" to "number", "offset" to "number", "includeHidden" to "boolean"),
        "click" to emptyMap(), "clear" to emptyMap(), "focus" to emptyMap(),
        "type" to mapOf("text" to "string", "submit" to "boolean"),
        "toggle" to mapOf("checked" to "boolean"), "select" to mapOf("value" to "string"),
        "wait" to mapOf("selector" to "string", "text" to "string", "timeoutMs" to "number",
            "condition" to "string", "previousDocumentId" to "string", "previousUrl" to "string", "durationMs" to "number", "idleMs" to "number"),
        "pointer_path" to mapOf("steps" to "array", "imageWidth" to "number", "imageHeight" to "number"),
        "key_sequence" to mapOf("keys" to "array", "intervalMs" to "number"),
        "geometry" to mapOf("selector" to "string", "target" to "string"),
        "readonly_probe" to mapOf("query" to "string", "selector" to "string", "target" to "string", "enabled" to "boolean"),
        "network_observe" to mapOf("record" to "boolean", "clear" to "boolean", "afterSeq" to "number", "limit" to "number"),
        "scroll" to mapOf("y" to "number", "direction" to "string", "pixels" to "number"),
        "scrollTo" to mapOf("position" to "string"),
        "key" to mapOf("key" to "string"), "keyboard" to mapOf("key" to "string"), "press" to mapOf("key" to "string"),
        "click_point" to mapOf("x" to "number", "y" to "number", "imageWidth" to "number", "imageHeight" to "number"),
        "drag" to mapOf("fromX" to "number", "fromY" to "number", "toX" to "number", "toY" to "number", "durationMs" to "number"),
        "swipe" to mapOf("fromX" to "number", "fromY" to "number", "toX" to "number", "toY" to "number", "durationMs" to "number"),
        "navigate" to mapOf("url" to "string", "waitForLoad" to "boolean", "timeoutMs" to "number"),
        "back" to emptyMap(), "forward" to emptyMap(), "reload" to emptyMap(),
        "status" to emptyMap(), "frames" to emptyMap(), "cookie_metadata" to emptyMap(),
        "storage_inspect" to emptyMap(), "inspect_element" to mapOf("selector" to "string"),
        "devtools" to mapOf("record" to "boolean", "clear" to "boolean", "captureBodies" to "boolean"), "compatibility" to emptyMap())
    private val targetActions = setOf("click", "clear", "focus", "type", "toggle", "select", "key", "keyboard", "press", "scrollTo")

    fun validate(input: Map<String, Any?>) {
        val raw = input["action"] as? String ?: fail("action must be a string")
        val action = if (raw in setOf("goto", "open", "load")) "navigate" else raw
        val specific = fields[action] ?: fail("Unsupported action")
        val allowed = common + specific + if (action in targetActions) mapOf("target" to "string", "selector" to "string") else emptyMap()
        input.forEach { (key, value) ->
            val kind = allowed[key] ?: fail("Unknown argument: $key")
            if (!when (kind) {
                "array" -> value is List<*>
                "string" -> value is String
                "boolean" -> value is Boolean
                "number" -> value is Number && value.toDouble().isFinite()
                else -> false
            }) fail("Invalid type for argument: $key")
        }
        if (action in setOf("click", "clear", "focus", "type", "toggle", "select") &&
            listOf(input["target"], input["selector"]).none { it is String && it.isNotBlank() })
            fail("A non-empty target ref or CSS selector is required")
        if (input["frame"] is String && input["frame"] != "main") {
            requireArgument(input["expectedFrameUrl"] is String && (input["expectedFrameUrl"] as String).isNotBlank(), "expectedFrameUrl required for a child frame")
            if (action in setOf("key_sequence", "pointer_path", "click_point", "drag", "swipe")) fail("Native input uses the main rendered viewport; focus the intended element separately")
        }
        if (action == "wait" && "condition" !in input && listOf(input["selector"], input["text"]).none { it is String && it.isNotBlank() })
            fail("wait requires selector or text")
        if (action == "wait" && "condition" in input) {
            requireArgument(input["condition"] in setOf("selector", "text", "url_changed", "document_changed", "load", "frame_ready", "network_idle", "delay"), "Unknown wait condition")
            if (input["condition"] == "document_changed") requireArgument(input["previousDocumentId"] is String, "previousDocumentId required")
            if (input["condition"] == "url_changed") requireArgument(input["previousUrl"] is String, "previousUrl required")
            if (input["condition"] == "delay") requireArgument(input["durationMs"] is Number, "durationMs required")
        }
        for ((field, range) in mapOf("timeoutMs" to 100.0..20000.0, "intervalMs" to 0.0..1000.0, "idleMs" to 100.0..5000.0, "offset" to 0.0..6000.0)) {
            if (field in input) { val n = (input[field] as Number).toDouble(); requireArgument(n in range && n == n.toLong().toDouble(), "Invalid $field") }
        }
        if (action == "key_sequence") {
            val keys = input["keys"] as? List<*> ?: fail("keys required")
            requireArgument(keys.size in 1..128 && keys.all { it is String && it.length in 1..40 }, "keys must contain 1–128 key names")
            requireArgument((keys.size - 1) * ((input["intervalMs"] as? Number)?.toDouble() ?: 50.0) <= 10000, "Key sequence exceeds 10 seconds")
        }
        if (action == "pointer_path") {
            val steps = input["steps"] as? List<*> ?: fail("steps required")
            requireArgument(steps.size in 2..256, "steps must contain 2–256 entries")
            steps.forEach { rawStep ->
                val step = rawStep as? Map<*, *> ?: fail("Each step must be an object")
                requireArgument(step.keys == setOf("kind", "x", "y", "atMs"), "Step requires only kind/x/y/atMs")
                requireArgument(step["kind"] in setOf("move", "down", "up"), "Unsupported pointer kind")
                listOf("x", "y", "atMs").forEach { requireArgument(step[it] is Number && (step[it] as Number).toDouble().isFinite(), "Invalid pointer number") }
                val time = (step["atMs"] as Number).toDouble()
                requireArgument(time in 0.0..10000.0 && time == time.toLong().toDouble(), "Invalid atMs")
            }
        }
        val required = when (action) {
            "navigate" -> listOf("url"); "type" -> listOf("text"); "select" -> listOf("value")
            "key", "press", "keyboard" -> listOf("key"); "click_point" -> listOf("x", "y")
            "drag", "swipe" -> listOf("fromX", "fromY", "toX", "toY")
            else -> emptyList()
        }
        required.forEach { if (it !in input) fail("Missing argument: $it") }
        if (action in setOf("drag", "swipe") && "durationMs" in input) {
            val n = (input["durationMs"] as Number).toDouble()
            if (n !in 100.0..5000.0 || n != n.toLong().toDouble()) fail("durationMs must be an integer between 100 and 5000")
        }
        if (action == "scroll") {
            if ("y" in input && ("direction" in input || "pixels" in input))
                fail("Use y or direction/pixels, not both")
            if ("direction" in input && input["direction"] !in setOf("up", "down"))
                fail("direction must be up or down")
            if ("pixels" in input && (input["pixels"] as Number).toDouble() !in 0.0..20000.0)
                fail("pixels must be between 0 and 20000")
            if ("y" in input && (input["y"] as Number).toDouble() !in -20000.0..20000.0)
                fail("y must be between -20000 and 20000")
        }
    }
    private fun requireArgument(valid: Boolean, message: String) { if (!valid) fail(message) }
    private fun fail(message: String): Nothing = throw BrowserOperationException("BAD_ARGUMENTS", message)
}
