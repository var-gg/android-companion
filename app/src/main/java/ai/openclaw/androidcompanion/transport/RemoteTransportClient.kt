package ai.openclaw.androidcompanion.transport

import ai.openclaw.androidcompanion.contract.CommandEnvelope
import org.json.JSONObject
import java.io.BufferedWriter
import java.net.HttpURLConnection
import java.net.URL

class RemoteTransportClient(
    private val config: TransportConfig
) {
    fun testConnection(): JSONObject {
        val url = URL("${config.baseUrl}/")
        val connection = open(url, "GET")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        return JSONObject()
            .put("ok", true)
            .put("base_url", config.baseUrl)
            .put("status_code", connection.responseCode)
            .put("response", json)
    }

    fun registerDevice(deviceInfo: JSONObject): JSONObject {
        val body = JSONObject()
            .put("device_id", config.deviceId)
            .put("device", deviceInfo)
        return postJson("/api/v1/register", body)
    }

    fun fetchNextCommand(): JSONObject {
        val url = URL("${config.baseUrl}/api/v1/commands/next?device_id=${config.deviceId}")
        val connection = open(url, "GET")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    fun uploadResult(commandId: String, result: JSONObject): JSONObject {
        val body = JSONObject()
            .put("device_id", config.deviceId)
            .put("result", result)
        return postJson("/api/v1/commands/$commandId/result", body)
    }

    fun postHeartbeat(summary: JSONObject): JSONObject {
        return postJson("/api/v1/heartbeat", JSONObject()
            .put("device_id", config.deviceId)
            .put("summary", summary))
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val url = URL("${config.baseUrl}$path")
        val connection = open(url, "POST")
        BufferedWriter(connection.outputStream.writer()).use { writer ->
            writer.write(body.toString())
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun open(url: URL, method: String): HttpURLConnection {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (config.token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.token}")
            }
            doInput = true
            doOutput = method != "GET"
        }
        return connection
    }

    companion object {
        fun parseCommand(response: JSONObject): CommandEnvelope? {
            val command = response.optJSONObject("command") ?: return null
            return CommandEnvelope.fromJson(command)
        }

        fun parseCommandId(response: JSONObject): String? {
            val command = response.optJSONObject("command") ?: return null
            return command.optString("id").takeIf { it.isNotBlank() }
        }
    }
}
