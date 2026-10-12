package dev.local.aibrowser

import java.net.URI

internal data class ProxySpec(val server: String, val type: String, val host: String, val port: Int,
    val username: String, val password: String) {
    companion object {
        fun parse(server: String, username: String = "", password: String = ""): ProxySpec {
            require(server.length in 3..300 && !server.any { it.isWhitespace() }) { "Invalid proxy server" }
            val uri = runCatching { URI(if (server.contains("://")) server else "http://$server") }.getOrNull() ?: throw IllegalArgumentException("Invalid proxy URL")
            require(uri.scheme in setOf("http", "https", "socks5") && uri.host != null && uri.port in 1..65535 &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
                "Use scheme://host:port with username/password in separate fields"
            }
            require(username.length <= 256 && password.length <= 512 && !username.contains(':') && !username.contains('\n') && !password.contains('\n')) { "Invalid proxy credentials" }
            val type = if (uri.scheme == "socks5") "socks" else uri.scheme
            return ProxySpec("${uri.scheme}://${uri.rawAuthority}", type, uri.host.removeSurrounding("[", "]"), uri.port, username, password)
        }
    }
}
