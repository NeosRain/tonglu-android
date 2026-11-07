package cn.tonglu.app.data.memory

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MemoryRequestTest {
    @Test fun waitsForEveryOwnerBeforeAcknowledgingSuccess() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val replies = mutableListOf<Boolean>()
            val writing = CompletableFuture<Boolean>()
            val request = MemoryRequest(scheduler, 2000, { replies += it })
            request.handle(listOf({ CompletableFuture.completedFuture(true) }, { writing }))
            assertTrue(replies.isEmpty())
            writing.complete(true)
            assertEquals(listOf(true), replies)
        } finally { scheduler.shutdownNow() }
    }

    @Test fun failedOwnerDoesNotPreventAnotherOwnerSaving() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            var reply: Boolean? = null
            var saved = false
            var failures = 0
            MemoryRequest(scheduler, 2000, { reply = it }, { failures++ }).handle(listOf(
                { throw IllegalStateException("storage failure") },
                { saved = true; CompletableFuture.completedFuture(true) },
            ))
            assertTrue(saved)
            assertEquals(false, reply)
            assertEquals(1, failures)
        } finally { scheduler.shutdownNow() }
    }

    @Test fun stalledOwnerTimesOutAndLateSaveNeverSendsSecondReply() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val latch = CountDownLatch(1)
            val count = AtomicInteger()
            var reply: Boolean? = null
            val stalled = CompletableFuture<Boolean>()
            MemoryRequest(scheduler, 40, { reply = it; count.incrementAndGet(); latch.countDown() })
                .handle(listOf({ stalled }))
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(false, reply)
            stalled.complete(true)
            assertEquals(1, count.get())
        } finally { scheduler.shutdownNow() }
    }

    @Test fun exceptionalAsyncCleanupCannotReportSuccess() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            var reply: Boolean? = null
            val failed = CompletableFuture<Boolean>()
            MemoryRequest(scheduler, 2000, { reply = it }).handle(listOf({ failed }))
            failed.completeExceptionally(IllegalStateException("native cleanup failed"))
            assertEquals(false, reply)
        } finally { scheduler.shutdownNow() }
    }
}
