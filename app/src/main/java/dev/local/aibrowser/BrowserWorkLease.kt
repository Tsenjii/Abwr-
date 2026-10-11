package dev.local.aibrowser

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Keeps CPU available only while a bounded local API operation is running. */
internal class BrowserWorkLease(context: Context) {
    private val power = requireNotNull(context.getSystemService(PowerManager::class.java))
    val active = AtomicInteger(0)

    fun acquire(timeoutMs: Long): AutoCloseable {
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Abwr:api-operation")
        wake.setReferenceCounted(false)
        wake.acquire(timeoutMs.coerceIn(1000L, 60000L))
        active.incrementAndGet()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                try { if (wake.isHeld) wake.release() } finally { active.decrementAndGet() }
            }
        }
    }
}
