package ai.openclaw.androidcompanion.transport

import android.content.Context

data class RemoteUiState(
    val status: String,
    val detail: String,
    val timestamp: Long,
    val lastPollAt: Long,
    val lastResultUploadAt: Long,
    val lastReceivedCommandAt: Long,
    val lastReceivedAction: String,
    val lastReceivedCommandId: String
)

class RemoteUiStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("remote_ui_state", Context.MODE_PRIVATE)

    fun load(): RemoteUiState {
        return RemoteUiState(
            status = prefs.getString(KEY_STATUS, STATUS_DISCONNECTED).orEmpty(),
            detail = prefs.getString(KEY_DETAIL, "").orEmpty(),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L),
            lastPollAt = prefs.getLong(KEY_LAST_POLL_AT, 0L),
            lastResultUploadAt = prefs.getLong(KEY_LAST_RESULT_UPLOAD_AT, 0L),
            lastReceivedCommandAt = prefs.getLong(KEY_LAST_RECEIVED_COMMAND_AT, 0L),
            lastReceivedAction = prefs.getString(KEY_LAST_RECEIVED_ACTION, "").orEmpty(),
            lastReceivedCommandId = prefs.getString(KEY_LAST_RECEIVED_COMMAND_ID, "").orEmpty()
        )
    }

    fun set(status: String, detail: String = "") {
        prefs.edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun markPoll(detail: String? = null) {
        val edit = prefs.edit()
            .putLong(KEY_LAST_POLL_AT, System.currentTimeMillis())
        if (detail != null) {
            edit.putString(KEY_DETAIL, detail)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        edit.apply()
    }

    fun markCommandReceived(action: String, commandId: String, detail: String? = null) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
            .putLong(KEY_LAST_RECEIVED_COMMAND_AT, now)
            .putString(KEY_LAST_RECEIVED_ACTION, action)
            .putString(KEY_LAST_RECEIVED_COMMAND_ID, commandId)
        if (detail != null) {
            edit.putString(KEY_DETAIL, detail)
                .putLong(KEY_TIMESTAMP, now)
        }
        edit.apply()
    }

    fun markResultUpload(detail: String? = null) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
            .putLong(KEY_LAST_RESULT_UPLOAD_AT, now)
        if (detail != null) {
            edit.putString(KEY_DETAIL, detail)
                .putLong(KEY_TIMESTAMP, now)
        }
        edit.apply()
    }

    companion object {
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_SETUP_REQUIRED = "setup_required"
        const val STATUS_TEST_OK = "test_ok"
        const val STATUS_REGISTERED = "registered"
        const val STATUS_POLLING = "polling"
        const val STATUS_ERROR = "error"

        private const val KEY_STATUS = "status"
        private const val KEY_DETAIL = "detail"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_LAST_POLL_AT = "last_poll_at"
        private const val KEY_LAST_RESULT_UPLOAD_AT = "last_result_upload_at"
        private const val KEY_LAST_RECEIVED_COMMAND_AT = "last_received_command_at"
        private const val KEY_LAST_RECEIVED_ACTION = "last_received_action"
        private const val KEY_LAST_RECEIVED_COMMAND_ID = "last_received_command_id"
    }
}
