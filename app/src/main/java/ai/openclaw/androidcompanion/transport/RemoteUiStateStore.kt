package ai.openclaw.androidcompanion.transport

import android.content.Context

data class RemoteUiState(
    val status: String,
    val detail: String,
    val timestamp: Long
)

class RemoteUiStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("remote_ui_state", Context.MODE_PRIVATE)

    fun load(): RemoteUiState {
        return RemoteUiState(
            status = prefs.getString(KEY_STATUS, STATUS_DISCONNECTED).orEmpty(),
            detail = prefs.getString(KEY_DETAIL, "").orEmpty(),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        )
    }

    fun set(status: String, detail: String = "") {
        prefs.edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
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
    }
}
