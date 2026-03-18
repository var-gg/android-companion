package ai.openclaw.androidcompanion.contract

import org.json.JSONObject

/**
 * v0.1.1 contract rule:
 * preferred envelope is { action, params, request_id? }
 * legacy flat fields are still accepted for backward compatibility.
 */
data class CommandEnvelope(
    val action: String,
    val params: JSONObject,
    val requestId: String?
) {
    companion object {
        fun fromJson(raw: JSONObject): CommandEnvelope {
            val action = raw.optString("action").trim()
            val params = raw.optJSONObject("params") ?: raw
            val requestId = raw.optString("request_id").takeIf { it.isNotBlank() }
            return CommandEnvelope(action = action, params = params, requestId = requestId)
        }
    }
}
