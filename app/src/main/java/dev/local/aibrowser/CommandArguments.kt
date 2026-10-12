package dev.local.aibrowser

/** Shared validation before native or document commands can have side effects. */
internal object CommandArguments {
    private val common = mapOf("action" to "string", "profile" to "string", "tab" to "string",
        "expectedUrl" to "string", "documentId" to "string", "frame" to "string",
        "expectedFrameUrl" to "string", "includeFrames" to "boolean")
    private val fields = mapOf(
        "snapshot" to mapOf("limit" to "number", "offset" to "number", "selector" to "string",
            "includeHidden" to "boolean", "text" to "string", "role" to "string", "order" to "string"),
        "read" to mapOf("maxChars" to "number"), "dom" to mapOf("limit" to "number"),
        "links" to mapOf("limit" to "number"),
        "find" to mapOf("selector" to "string", "target" to "string", "text" to "string", "role" to "string", "ariaLabel" to "string"),
        "click" to emptyMap(), "clear" to emptyMap(), "focus" to emptyMap(),
        "type" to mapOf("text" to "string", "submit" to "boolean"),
        "toggle" to mapOf("checked" to "boolean"), "select" to mapOf("value" to "string"),
        "wait" to mapOf("selector" to "string", "text" to "string", "timeoutMs" to "number"),
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
                "string" -> value is String
                "boolean" -> value is Boolean
                "number" -> value is Number && value.toDouble().isFinite()
                else -> false
            }) fail("Invalid type for argument: $key")
        }
        if (action in setOf("click", "clear", "focus", "type", "toggle", "select") &&
            listOf(input["target"], input["selector"]).none { it is String && it.isNotBlank() })
            fail("A non-empty target ref or CSS selector is required")
        if (action == "wait" && listOf(input["selector"], input["text"]).none { it is String && it.isNotBlank() })
            fail("wait requires selector or text")
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
    private fun fail(message: String): Nothing = throw BrowserOperationException("BAD_ARGUMENTS", message)
}
