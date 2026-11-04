package com.github.kr328.clash.util

import com.github.kr328.clash.core.model.ProxyDelay
import kotlinx.coroutines.channels.Channel

/** One wake-up per burst, retaining the newest result for every node without blocking Binder. */
internal class ProxyDelayUpdates {
    val available = Channel<Unit>(Channel.CONFLATED)
    private val pending = LinkedHashMap<String, ProxyDelay>()
    private var closed = false

    fun offer(results: List<ProxyDelay>): Boolean = synchronized(pending) {
        if (closed) return false
        results.forEach { pending[it.name] = it }
        available.trySend(Unit).isSuccess
    }

    fun drain(): List<ProxyDelay> = synchronized(pending) {
        pending.values.toList().also { pending.clear() }
    }

    fun close() = synchronized(pending) {
        closed = true
        // Channel.close retains the last wake-up, so the consumer drains the final batch.
        available.close()
    }

    fun cancel() = synchronized(pending) {
        closed = true
        pending.clear()
        available.cancel()
    }
}
