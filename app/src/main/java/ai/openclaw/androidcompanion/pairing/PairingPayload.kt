package ai.openclaw.androidcompanion.pairing

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

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

        private fun isPrivateLanHost(host: String): Boolean {
            return host.startsWith("192.168.") ||
                host.startsWith("10.") ||
                host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) ||
                host.equals("localhost", ignoreCase = true) ||
                host.equals("127.0.0.1")
        }

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
            val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") throw IllegalArgumentException("base_url must use http or https")
            val host = uri.host.orEmpty()
            if (host.isBlank()) throw IllegalArgumentException("base_url must include a host")

            val mode = transportJson.optString("mode").ifBlank { "tailscale" }.lowercase(Locale.ROOT)
            if (mode !in setOf("tailscale", "lan")) throw IllegalArgumentException("transport.mode must be tailscale or lan")

            val token = transportJson.optString("token").trim()
            val pollIntervalSeconds = transportJson.optLong("poll_interval_seconds", 10L).coerceAtLeast(10L)
            val expiresAt = metaJson.optString("expires_at").ifBlank { null }
            if (token.isNotBlank() && expiresAt == null) {
                throw IllegalArgumentException("token-bearing pairing payloads must include meta.expires_at")
            }
            if (mode == "tailscale" && isPrivateLanHost(host)) {
                throw IllegalArgumentException("tailscale mode expects a Tailscale host or MagicDNS name, not a private LAN IP")
            }

            return PairingPayload(
                type = type,
                version = version,
                label = json.optString("label").ifBlank { "Desktop" },
                transport = PairingTransport(
                    mode = mode,
                    baseUrl = baseUrl,
                    token = token,
                    pollIntervalSeconds = pollIntervalSeconds
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
