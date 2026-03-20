package ai.openclaw.androidcompanion.logging

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class CommandLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("command_logs", Context.MODE_PRIVATE)

    fun append(entry: JSONObject): JSONObject {
        val normalized = normalize(entry)
        val items = readAllMutable()
        val updatedItems = JSONArray().put(normalized)
        for (i in 0 until items.length()) {
            items.opt(i)?.let { updatedItems.put(it) }
        }
        trim(updatedItems)
        save(updatedItems)
        return normalized
    }

    fun createManualLog(command: JSONObject, action: String, requestId: String?): JSONObject {
        return append(
            JSONObject()
                .put("source", "manual_ui")
                .put("action", action)
                .put("request_id", requestId)
                .put("command", JSONObject(command.toString()))
                .put("state", STATE_RECEIVED)
                .put("phase", PHASE_RECEIVED)
                .put("phases", JSONArray().put(phaseEntry(PHASE_RECEIVED, "Manual command accepted", ok = true)))
        )
    }

    fun createRemoteLog(commandId: String, command: JSONObject, action: String, requestId: String?): JSONObject {
        return append(
            JSONObject()
                .put("source", "remote_service")
                .put("remote_command_id", commandId)
                .put("action", action)
                .put("request_id", requestId)
                .put("command", JSONObject(command.toString()))
                .put("state", STATE_RECEIVED)
                .put("phase", PHASE_FETCHED)
                .put("phases", JSONArray().put(phaseEntry(PHASE_FETCHED, "Command fetched from bridge", ok = true)))
        )
    }

    fun markPhase(
        logId: String,
        phase: String,
        state: String = phase,
        detail: String? = null,
        ok: Boolean? = null,
        errorCategory: String? = null,
        errorReason: String? = null,
        payload: JSONObject? = null
    ): JSONObject? {
        return update(logId) { existing ->
            val updated = normalize(existing)
            updated.put("phase", phase)
            updated.put("state", state)
            if (ok != null) updated.put("ok", ok)
            detail?.let { updated.put("detail", it) }
            errorCategory?.let { updated.put("error_category", it) }
            errorReason?.let { updated.put("error_reason", it) }
            payload?.let { updated.put("last_payload", JSONObject(it.toString())) }
            val phases = updated.optJSONArray("phases") ?: JSONArray()
            phases.put(
                phaseEntry(
                    phase = phase,
                    detail = detail,
                    ok = ok,
                    errorCategory = errorCategory,
                    errorReason = errorReason,
                    payload = payload
                )
            )
            updated.put("phases", phases)
            if (phase == PHASE_EXECUTED || phase == PHASE_UPLOADED || phase == PHASE_FAILED) {
                updated.put("finished_at", Instant.now().toString())
            }
            updated
        }
    }

    fun attachResult(logId: String, result: JSONObject, ok: Boolean): JSONObject? {
        return update(logId) { existing ->
            normalize(existing)
                .put("result", JSONObject(result.toString()))
                .put("ok", ok)
        }
    }

    fun attachUploadResult(logId: String, result: JSONObject): JSONObject? {
        return update(logId) { existing ->
            normalize(existing)
                .put("upload_result", JSONObject(result.toString()))
        }
    }

    fun update(logId: String, transform: (JSONObject) -> JSONObject): JSONObject? {
        val items = readAllMutable()
        for (i in 0 until items.length()) {
            val existing = items.optJSONObject(i) ?: continue
            if (existing.optString("log_id") != logId) continue
            val updated = normalize(transform(JSONObject(existing.toString())))
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
            if (entry.optString("log_id") == logId) return JSONObject(entry.toString())
        }
        return null
    }

    fun findByRequestId(requestId: String): JSONArray {
        val result = JSONArray()
        val all = readAllMutable()
        for (i in 0 until all.length()) {
            val entry = all.optJSONObject(i) ?: continue
            if (entry.optString("request_id") == requestId) result.put(JSONObject(entry.toString()))
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

    private fun normalize(entry: JSONObject): JSONObject {
        val normalized = JSONObject(entry.toString())
        val now = Instant.now().toString()
        if (normalized.optString("log_id").isBlank()) normalized.put("log_id", UUID.randomUUID().toString())
        if (normalized.optString("timestamp").isBlank()) normalized.put("timestamp", now)
        if (normalized.optString("started_at").isBlank()) normalized.put("started_at", normalized.optString("timestamp"))
        if (!normalized.has("phases") || normalized.optJSONArray("phases") == null) normalized.put("phases", JSONArray())
        return normalized
    }

    private fun phaseEntry(
        phase: String,
        detail: String? = null,
        ok: Boolean? = null,
        errorCategory: String? = null,
        errorReason: String? = null,
        payload: JSONObject? = null
    ): JSONObject {
        return JSONObject()
            .put("phase", phase)
            .put("timestamp", Instant.now().toString())
            .apply {
                detail?.let { put("detail", it) }
                ok?.let { put("ok", it) }
                errorCategory?.let { put("error_category", it) }
                errorReason?.let { put("error_reason", it) }
                payload?.let { put("payload", JSONObject(it.toString())) }
            }
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
        const val MAX_ITEMS = 100

        const val STATE_RECEIVED = "received"
        const val STATE_FETCHED = "fetched"
        const val STATE_DELIVERED = "delivered"
        const val STATE_EXECUTING = "executing"
        const val STATE_EXECUTED = "executed"
        const val STATE_UPLOADED = "uploaded"
        const val STATE_FAILED = "failed"

        const val PHASE_RECEIVED = "received"
        const val PHASE_FETCHED = "fetched"
        const val PHASE_DELIVERED = "delivered"
        const val PHASE_EXECUTING = "executing"
        const val PHASE_EXECUTED = "executed"
        const val PHASE_UPLOADED = "uploaded"
        const val PHASE_FAILED = "failed"
    }
}
