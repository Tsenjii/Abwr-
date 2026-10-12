package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class BridgeConnectionsTest {
    @Test fun newConnectionCanWrite() {
        val ports = BridgeConnections<Any>(); val port = Any()
        assertFalse(ports.writable(port)); assertTrue(ports.connected(port)); assertTrue(ports.writable(port))
    }
    @Test fun lateReadyDoesNotReviveRetiredConnection() {
        val ports = BridgeConnections<Any>(); val port = Any()
        ports.connected(port); ports.retire(port)
        assertFalse(ports.connected(port)); assertFalse(ports.writable(port))
    }
    @Test fun disconnectDoesNotReviveAfterRetirement() {
        val ports = BridgeConnections<Any>(); val port = Any()
        ports.connected(port); ports.closed(port); ports.retire(port)
        assertFalse(ports.connected(port)); assertFalse(ports.writable(port))
    }
    @Test fun replacementCanWriteWithoutRevivingPreviousPort() {
        val ports = BridgeConnections<Any>(); val old = Any(); val current = Any()
        ports.connected(old); ports.retire(old); ports.connected(current)
        assertTrue(ports.writable(current)); assertFalse(ports.writable(old))
        ports.closed(old); assertTrue(ports.writable(current))
    }
    @Test fun disconnectBeforeConnectRemainsTerminal() {
        val ports = BridgeConnections<Any>(); val port = Any()
        ports.closed(port); assertFalse(ports.connected(port))
    }
}
