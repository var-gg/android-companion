package ai.openclaw.androidcompanion.logging

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CommandLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("command_logs", Context.MODE_PRIVATE)

    fun append(entry: JSONObject): JSONObject {
        val normalized = JSONObject(entry.toString())
        if (normalized.optString("log_id").isBlank()) {
            normalized.put("log_id", UUID.randomUUID().toString())
        }
        val items = readAllMutable()
        items.put(0, normalized)
        trim(items)
        save(items)
        return normalized
    }

    fun update(logId: String, transform: (JSONObject) -> JSONObject): JSONObject? {
        val items = readAllMutable()
        for (i in 0 until items.length()) {
            val existing = items.optJSONObject(i) ?: continue
            if (existing.optString("log_id") != logId) continue
            val updated = transform(JSONObject(existing.toString()))
            if (updated.optString("log_id").isBlank()) {
                updated.put("log_id", logId)
            }
            items.put(i, updated)
            save(items)
            return updated
        }
        return null
    }

    fun readAll(): JSONArray = readAllMutable()

    fun recent(limit: Int = MAX_ITEMS, action: String? = null, state: String? = null): JSONArray {
        val normalizedLimit = limit.coerceIn(1, MAX_ITEMS)
        val result = JSONArray()
        val all = readAllMutable()
        for (i in 0 until all.length()) {
            val entry = all.optJSONObject(i) ?: continue
            if (!action.isNullOrBlank() && entry.optString("action") != action) continue
            if (!state.isNullOrBlank() && entry.optString("state") != state) continue
            result.put(JSONObject(entry.toString()))
            if (result.length() >= normalizedLimit) break
        }
        return result
    }

    fun findByLogId(logId: String): JSONObject? {
        val all = readAllMutable()
        for (i in 0 until all.length()) {
            val entry = all.optJSONObject(i) ?: continue
            if (entry.optString("log_id") == logId) {
                return JSONObject(entry.toString())
            }
        }
        return null
    }

    fun findByRequestId(requestId: String): JSONArray {
        val result = JSONArray()
        val all = readAllMutable()
        for (i in 0 until all.length()) {
            val entry = all.optJSONObject(i) ?: continue
            if (entry.optString("request_id") == requestId) {
                result.put(JSONObject(entry.toString()))
            }
        }
        return result
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun readAllMutable(): JSONArray {
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun trim(items: JSONArray) {
        while (items.length() > MAX_ITEMS) {
            items.remove(items.length() - 1)
        }
    }

    private fun save(items: JSONArray) {
        prefs.edit().putString(KEY, items.toString()).apply()
    }

    companion object {
        private const val KEY = "recent_command_logs"
        const val MAX_ITEMS = 50
    }
}
