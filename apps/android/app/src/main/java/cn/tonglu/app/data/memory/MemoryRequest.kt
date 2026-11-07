package cn.tonglu.app.data.memory

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One truthful response, even when a resource owner stalls or completes after the deadline. */
internal class MemoryRequest(
    scheduler: ScheduledExecutorService,
    timeoutMillis: Long,
    private val reply: (Boolean) -> Unit,
    private val onError: (Exception) -> Unit = {},
) {
    private val replied = AtomicBoolean(false)
    init { scheduler.schedule({ finish(false) }, timeoutMillis, TimeUnit.MILLISECONDS) }

    fun handle(operations: List<() -> CompletableFuture<Boolean>>) {
        val results = operations.map { operation ->
            try { operation() } catch (e: Exception) { onError(e); CompletableFuture.completedFuture(false) }
        }
        CompletableFuture.allOf(*results.toTypedArray()).whenComplete { _, error ->
            if (error != null) onError(error as? Exception ?: RuntimeException(error))
            finish(error == null && results.all { it.getNow(false) })
        }
    }

    private fun finish(success: Boolean) {
        if (replied.compareAndSet(false, true)) {
            reply(success)
        }
    }
}
