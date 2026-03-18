package ai.openclaw.androidcompanion.transport

import android.content.Context
import java.util.UUID

data class TransportConfig(
    val baseUrl: String,
    val deviceId: String,
    val token: String,
    val pollIntervalSeconds: Long
)

class TransportConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("remote_transport", Context.MODE_PRIVATE)

    fun load(): TransportConfig {
        val existingDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        val deviceId = if (existingDeviceId.isNullOrBlank()) {
            val generated = "android-${UUID.randomUUID()}"
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            generated
        } else {
            existingDeviceId
        }

        return TransportConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty(),
            deviceId = deviceId,
            token = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty(),
            pollIntervalSeconds = prefs.getLong(KEY_POLL_INTERVAL_SECONDS, 30L)
        )
    }

    fun save(config: TransportConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().removeSuffix("/"))
            .putString(KEY_DEVICE_ID, config.deviceId.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .putLong(KEY_POLL_INTERVAL_SECONDS, config.pollIntervalSeconds.coerceAtLeast(10L))
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_POLL_INTERVAL_SECONDS = "poll_interval_seconds"
    }
}
