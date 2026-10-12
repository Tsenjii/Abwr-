package dev.local.aibrowser

/** Viewport coordinates for ordinary native browser dragging; no hidden retries. */
internal data class NativeGesture(val fromX: Double, val fromY: Double, val toX: Double, val toY: Double, val durationMs: Long) {
    fun validate(width: Int, height: Int) {
        require(width > 0 && height > 0 && durationMs in 100L..5000L) { "Invalid gesture viewport or duration" }
        require(listOf(fromX, fromY, toX, toY).all { it.isFinite() }) { "Coordinates must be finite" }
        require(fromX >= 0 && toX >= 0 && fromY >= 0 && toY >= 0 && fromX < width && toX < width && fromY < height && toY < height) {
            "Gesture coordinates outside browser viewport"
        }
    }
    fun point(elapsedMs: Long): Pair<Float, Float> {
        val t = (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return (fromX + (toX - fromX) * t).toFloat() to (fromY + (toY - fromY) * t).toFloat()
    }
}
