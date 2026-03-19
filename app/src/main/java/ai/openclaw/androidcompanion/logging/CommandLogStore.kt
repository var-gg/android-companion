package ai.openclaw.androidcompanion.logging

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CommandLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("command_logs", Context.MODE_PRIVATE)

    fun append(entry: JSONObject) {
        val items = readAllMutable()
        items.put(0, entry)
        while (items.length() > MAX_ITEMS) {
            items.remove(items.length() - 1)
        }
        prefs.edit().putString(KEY, items.toString()).apply()
    }

    fun readAll(): JSONArray = readAllMutable()

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun readAllMutable(): JSONArray {
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    companion object {
        private const val KEY = "recent_command_logs"
        private const val MAX_ITEMS = 50
    }
}
