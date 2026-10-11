package dev.local.aibrowser

import java.util.concurrent.CompletableFuture

/** Replies belong to the exact document port as well as its browser session. */
internal class BridgeRequests<S : Any, P : Any, T> {
    private data class Request<S, P, T>(val session: S, val port: P,
        val future: CompletableFuture<T>)
    private val requests = linkedMapOf<String, Request<S, P, T>>()
    val size: Int @Synchronized get() = requests.size

    @Synchronized fun add(id: String, session: S, port: P, future: CompletableFuture<T>) {
        check(id !in requests) { "Duplicate bridge request" }
        requests[id] = Request(session, port, future)
    }
    @Synchronized fun hasSession(session: S) = requests.values.any { it.session === session }
    @Synchronized fun remove(id: String, future: CompletableFuture<T>) {
        if (requests[id]?.future === future) requests.remove(id)
    }
    fun reply(id: String, session: S, port: P, value: T): Boolean {
        val request = synchronized(this) {
            val item = requests[id]
            if (item == null || item.session !== session || item.port !== port) null else requests.remove(id)
        } ?: return false
        return request.future.complete(value)
    }
    fun fail(id: String, error: Throwable) {
        val request = synchronized(this) { requests.remove(id) }
        request?.future?.completeExceptionally(error)
    }
    fun failPort(port: P, error: Throwable) = failMatching(error) { it.port === port }
    fun failSession(session: S, error: Throwable) = failMatching(error) { it.session === session }
    private fun failMatching(error: Throwable, matches: (Request<S, P, T>) -> Boolean) {
        val removed = synchronized(this) {
            requests.filterValues(matches).keys.toList().mapNotNull { requests.remove(it) }
        }
        removed.forEach { it.future.completeExceptionally(error) }
    }
}
