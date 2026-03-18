package ai.openclaw.androidcompanion.pairing

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

data class PairingTransport(
    val mode: String,
    val baseUrl: String,
    val token: String,
    val pollIntervalSeconds: Long
)

data class PairingDevice(
    val suggestedDeviceId: String
)

data class PairingMeta(
    val generatedAt: String?,
    val expiresAt: String?
)

data class PairingPayload(
    val type: String,
    val version: Int,
    val label: String,
    val transport: PairingTransport,
    val device: PairingDevice,
    val meta: PairingMeta
) {
    fun isExpired(now: Instant = Instant.now()): Boolean {
        val expires = meta.expiresAt ?: return false
        return runCatching { Instant.parse(expires).isBefore(now) }.getOrDefault(false)
    }

    fun summary(): String {
        val lines = mutableListOf<String>()
        lines += "label: $label"
        lines += "mode: ${transport.mode}"
        lines += "base_url: ${transport.baseUrl}"
        lines += "poll_interval_seconds: ${transport.pollIntervalSeconds}"
        if (transport.token.isNotBlank()) lines += "token: included" else lines += "token: not included"
        if (device.suggestedDeviceId.isNotBlank()) lines += "suggested_device_id: ${device.suggestedDeviceId}"
        meta.generatedAt?.let { lines += "generated_at: $it" }
        meta.expiresAt?.let { lines += "expires_at: $it" }
        return lines.joinToString("\n")
    }

    companion object {
        const val TYPE = "android-companion-pairing"
        private const val PREFIX = "acpair://v1/"

        fun parse(raw: String): PairingPayload {
            val jsonText = when {
                raw.startsWith(PREFIX, ignoreCase = true) -> {
                    val encoded = raw.removePrefix(PREFIX)
                    val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    String(bytes, StandardCharsets.UTF_8)
                }
                raw.trim().startsWith("{") -> raw
                else -> throw IllegalArgumentException("Unsupported pairing payload format")
            }

            val json = JSONObject(jsonText)
            val type = json.optString("type")
            if (type != TYPE) throw IllegalArgumentException("Unsupported pairing type: $type")
            val version = json.optInt("version", 0)
            if (version != 1) throw IllegalArgumentException("Unsupported pairing version: $version")

            val transportJson = json.optJSONObject("transport") ?: throw IllegalArgumentException("Missing transport")
            val deviceJson = json.optJSONObject("device") ?: JSONObject()
            val metaJson = json.optJSONObject("meta") ?: JSONObject()
            val baseUrl = transportJson.optString("base_url").trim().removeSuffix("/")
            if (baseUrl.isBlank()) throw IllegalArgumentException("Missing transport.base_url")
            val uri = Uri.parse(baseUrl)
            val scheme = uri.scheme.orEmpty().lowercase()
            if (scheme != "http" && scheme != "https") throw IllegalArgumentException("base_url must use http or https")

            return PairingPayload(
                type = type,
                version = version,
                label = json.optString("label").ifBlank { "Desktop" },
                transport = PairingTransport(
                    mode = transportJson.optString("mode").ifBlank { "tailscale" },
                    baseUrl = baseUrl,
                    token = transportJson.optString("token"),
                    pollIntervalSeconds = transportJson.optLong("poll_interval_seconds", 10L).coerceAtLeast(10L)
                ),
                device = PairingDevice(
                    suggestedDeviceId = deviceJson.optString("suggested_device_id")
                ),
                meta = PairingMeta(
                    generatedAt = metaJson.optString("generated_at").ifBlank { null },
                    expiresAt = metaJson.optString("expires_at").ifBlank { null }
                )
            )
        }
    }
}
