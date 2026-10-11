package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiWorkQueueTest {
    @Test fun slowPageSaturationDoesNotBlockStatusAndIsBounded() {
        val work = ApiWorkQueue()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            repeat(2) { assertTrue(work.operation { entered.countDown(); release.await() }) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(8) { assertTrue(work.operation { release.await() }) }
            assertFalse(work.operation { fail("overflow must never execute") })
            val status = CountDownLatch(1)
            assertTrue(work.control { status.countDown() })
            assertTrue("control must answer while all page workers are blocked", status.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); work.stop() }
    }

    @Test fun stopDropsQueuedWorkAndRejectsNewConnections() {
        val work = ApiWorkQueue()
        val entered = CountDownLatch(2)
        val exited = CountDownLatch(2)
        val queued = AtomicInteger()
        val never = CountDownLatch(1)
        try {
            repeat(2) { work.operation {
                entered.countDown()
                try { never.await() } catch (_: InterruptedException) { }
                finally { exited.countDown() }
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(8) { assertTrue(work.operation { queued.incrementAndGet() }) }
            work.stop()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertEquals(0, queued.get())
            assertFalse(work.operation { fail("work after stop") })
            assertFalse(work.control { fail("connection after stop") })
        } finally { never.countDown(); work.stop() }
    }
}
