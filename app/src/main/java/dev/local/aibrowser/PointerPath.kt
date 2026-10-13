package dev.local.aibrowser

/** Timed native touch replay. Moves before DOWN position the touch; Android has no touch hover. */
internal data class PointerStep(val kind: String, val x: Double, val y: Double, val atMs: Long)
internal object PointerPath {
    fun validate(steps: List<PointerStep>, width: Int, height: Int) {
        require(width > 0 && height > 0 && steps.size in 2..256) { "Invalid pointer path or viewport" }
        var pressed = false
        var previous = -1L
        var presses = 0
        steps.forEach { step ->
            require(step.atMs in 0L..10000L && step.atMs >= previous) { "atMs must be monotonic, 0–10000" }
            require(step.x.isFinite() && step.y.isFinite() && step.x >= 0 && step.y >= 0 && step.x < width && step.y < height) { "Pointer outside viewport" }
            when (step.kind) {
                "move" -> Unit
                "down" -> { require(!pressed) { "Pointer is already down" }; pressed = true; presses++ }
                "up" -> { require(pressed) { "Pointer is not down" }; pressed = false }
                else -> error("Unsupported pointer step")
            }
            previous = step.atMs
        }
        require(!pressed && presses > 0) { "Path must include DOWN and finish released" }
    }
}
