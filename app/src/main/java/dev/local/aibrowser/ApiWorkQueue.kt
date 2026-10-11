package dev.local.aibrowser

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Slow page work cannot occupy the workers that answer status and takeover. */
internal class ApiWorkQueue {
    private val controls = ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(12))
    private val operations = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8))

    fun control(task: () -> Unit): Boolean = submit(controls, task)
    fun operation(task: () -> Unit): Boolean = submit(operations, task)
    private fun submit(pool: ThreadPoolExecutor, task: () -> Unit): Boolean = try {
        pool.execute(task)
        true
    } catch (_: RejectedExecutionException) { false }

    fun stop() {
        controls.shutdownNow()
        operations.shutdownNow()
    }
}

internal class BrowserOperationException(val code: String, message: String) : IllegalStateException(message)
