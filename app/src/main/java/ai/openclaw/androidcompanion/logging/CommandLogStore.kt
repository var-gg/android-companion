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
        private const val MAX_ITEMS = 50
    }
}
