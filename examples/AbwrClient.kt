// Android/JVM example; call on an IO worker, never the Android UI thread.
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
class AbwrClient(private val profile: String, private val token: String) {
    fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        require(path.startsWith("/v1/") && !path.contains('?'))
        val conn = URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.instanceFollowRedirects = false
        conn.connectTimeout = 5000; conn.readTimeout = 23000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-Browser-Profile", profile)
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val data = stream?.use { it.readNBytes(8 * 1024 * 1024 + 1) } ?: error("No local reply")
            require(data.size <= 8 * 1024 * 1024) { "RESPONSE_TOO_LARGE" }
            val result = JSONObject(String(data, Charsets.UTF_8))
            check(code in 200..299 && result.optBoolean("ok", true)) { result.optString("code", "API_ERROR") }
            return result
        } finally { conn.disconnect() } // Never automatically replay a write.
    }
    fun checkVersion() = request("GET", "/v1/capabilities").also {
        check(it.optString("apiVersion") == "0.14.0" && it.optInt("bridgeSchemaVersion") == 4 && it.optInt("mcpToolCount") == 82) { "VERSION_MISMATCH" }
    }
}
