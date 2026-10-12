package dev.local.aibrowser
import org.junit.Assert.*
import org.junit.Test

class NativeGestureTest {
    @Test fun gesturesReachTheRequestedPointAndClampTime() {
        val g = NativeGesture(0.0, 10.0, 100.0, 110.0, 500)
        g.validate(200, 200)
        assertEquals(0f to 10f, g.point(-1))
        assertEquals(50f to 60f, g.point(250))
        assertEquals(100f to 110f, g.point(1000))
    }
    @Test fun invalidCoordinatesNeverCreateMotionEvents() {
        for (g in listOf(NativeGesture(Double.NaN, 0.0, 10.0, 10.0, 100), NativeGesture(0.0, 0.0, 200.0, 10.0, 100), NativeGesture(0.0, 0.0, 10.0, 10.0, 6000))) {
            assertThrows(IllegalArgumentException::class.java) { g.validate(200, 200) }
        }
    }
}
