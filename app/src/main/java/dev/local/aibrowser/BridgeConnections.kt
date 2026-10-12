package dev.local.aibrowser

/** App ownership of a port; Gecko/content owns actual native disconnect. */
internal class BridgeConnections<P : Any> {
    private enum class State { ACTIVE, RETIRED, CLOSED }
    private val states = java.util.WeakHashMap<P, State>()
    fun connected(port: P): Boolean {
        if (!states.containsKey(port)) states[port] = State.ACTIVE
        return writable(port)
    }
    fun writable(port: P) = states[port] == State.ACTIVE
    fun retire(port: P) { if (states[port] != State.CLOSED) states[port] = State.RETIRED }
    fun closed(port: P) { states[port] = State.CLOSED }
}
