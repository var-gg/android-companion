package ai.openclaw.androidcompanion.transport

import android.content.Context
import ai.openclaw.androidcompanion.BuildConfig


data class RemoteUiState(
    val status: String,
    val detail: String,
    val timestamp: Long,
    val serviceRunning: Boolean,
    val appVersionName: String,
    val appVersionCode: Int,
    val lastPollStartedAt: Long,
    val lastPollSucceededAt: Long,
    val lastPollFailedAt: Long,
    val lastHeartbeatAt: Long,
    val lastCommandFetchedAt: Long,
    val lastCommandDeliveredAt: Long,
    val lastCommandExecutedAt: Long,
    val lastCommandUploadedAt: Long,
    val lastCommandAction: String,
    val lastCommandId: String,
    val lastErrorCategory: String,
    val lastErrorReason: String
) {
    val lastPollAt: Long get() = maxOf(lastPollStartedAt, lastPollSucceededAt, lastPollFailedAt)
    val lastResultUploadAt: Long get() = lastCommandUploadedAt
    val lastReceivedCommandAt: Long get() = lastCommandFetchedAt
    val lastReceivedAction: String get() = lastCommandAction
    val lastReceivedCommandId: String get() = lastCommandId
}

class RemoteUiStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("remote_ui_state", Context.MODE_PRIVATE)

    fun load(): RemoteUiState {
        return RemoteUiState(
            status = prefs.getString(KEY_STATUS, STATUS_DISCONNECTED).orEmpty(),
            detail = prefs.getString(KEY_DETAIL, "").orEmpty(),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L),
            serviceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false),
            appVersionName = prefs.getString(KEY_APP_VERSION_NAME, BuildConfig.VERSION_NAME).orEmpty(),
            appVersionCode = prefs.getInt(KEY_APP_VERSION_CODE, BuildConfig.VERSION_CODE),
            lastPollStartedAt = prefs.getLong(KEY_LAST_POLL_STARTED_AT, 0L),
            lastPollSucceededAt = prefs.getLong(KEY_LAST_POLL_SUCCEEDED_AT, 0L),
            lastPollFailedAt = prefs.getLong(KEY_LAST_POLL_FAILED_AT, 0L),
            lastHeartbeatAt = prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0L),
            lastCommandFetchedAt = prefs.getLong(KEY_LAST_COMMAND_FETCHED_AT, 0L),
            lastCommandDeliveredAt = prefs.getLong(KEY_LAST_COMMAND_DELIVERED_AT, 0L),
            lastCommandExecutedAt = prefs.getLong(KEY_LAST_COMMAND_EXECUTED_AT, 0L),
            lastCommandUploadedAt = prefs.getLong(KEY_LAST_COMMAND_UPLOADED_AT, 0L),
            lastCommandAction = prefs.getString(KEY_LAST_COMMAND_ACTION, "").orEmpty(),
            lastCommandId = prefs.getString(KEY_LAST_COMMAND_ID, "").orEmpty(),
            lastErrorCategory = prefs.getString(KEY_LAST_ERROR_CATEGORY, "").orEmpty(),
            lastErrorReason = prefs.getString(KEY_LAST_ERROR_REASON, "").orEmpty()
        )
    }

    fun set(status: String, detail: String = "") {
        prefs.edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_APP_VERSION_NAME, BuildConfig.VERSION_NAME)
            .putInt(KEY_APP_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
    }

    fun markServiceRunning(running: Boolean, detail: String? = null) {
        val edit = prefs.edit()
            .putBoolean(KEY_SERVICE_RUNNING, running)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_APP_VERSION_NAME, BuildConfig.VERSION_NAME)
            .putInt(KEY_APP_VERSION_CODE, BuildConfig.VERSION_CODE)
        detail?.let { edit.putString(KEY_DETAIL, it) }
        edit.apply()
    }

    fun markPollStarted(detail: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_STATUS, STATUS_POLLING)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_LAST_POLL_STARTED_AT, now)
            .putLong(KEY_TIMESTAMP, now)
            .apply()
    }

    fun markPollSucceeded(detail: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_STATUS, STATUS_POLLING)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_LAST_POLL_SUCCEEDED_AT, now)
            .putLong(KEY_TIMESTAMP, now)
            .apply()
    }

    fun markHeartbeat(detail: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_LAST_HEARTBEAT_AT, now)
            .putLong(KEY_TIMESTAMP, now)
            .apply()
    }

    fun markCommandFetched(action: String, commandId: String, detail: String) = markCommand(action, commandId, detail, KEY_LAST_COMMAND_FETCHED_AT)
    fun markCommandDelivered(action: String, commandId: String, detail: String) = markCommand(action, commandId, detail, KEY_LAST_COMMAND_DELIVERED_AT)
    fun markCommandExecuted(action: String, commandId: String, detail: String) = markCommand(action, commandId, detail, KEY_LAST_COMMAND_EXECUTED_AT)
    fun markCommandUploaded(action: String, commandId: String, detail: String) = markCommand(action, commandId, detail, KEY_LAST_COMMAND_UPLOADED_AT)

    fun markError(category: String, reason: String, detail: String = reason) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_STATUS, STATUS_ERROR)
            .putString(KEY_DETAIL, detail)
            .putString(KEY_LAST_ERROR_CATEGORY, category)
            .putString(KEY_LAST_ERROR_REASON, reason)
            .putLong(KEY_LAST_POLL_FAILED_AT, now)
            .putLong(KEY_TIMESTAMP, now)
            .apply()
    }

    private fun markCommand(action: String, commandId: String, detail: String, key: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_STATUS, STATUS_POLLING)
            .putString(KEY_DETAIL, detail)
            .putString(KEY_LAST_COMMAND_ACTION, action)
            .putString(KEY_LAST_COMMAND_ID, commandId)
            .putLong(key, now)
            .putLong(KEY_TIMESTAMP, now)
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
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_APP_VERSION_NAME = "app_version_name"
        private const val KEY_APP_VERSION_CODE = "app_version_code"
        private const val KEY_LAST_POLL_STARTED_AT = "last_poll_started_at"
        private const val KEY_LAST_POLL_SUCCEEDED_AT = "last_poll_succeeded_at"
        private const val KEY_LAST_POLL_FAILED_AT = "last_poll_failed_at"
        private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
        private const val KEY_LAST_COMMAND_FETCHED_AT = "last_command_fetched_at"
        private const val KEY_LAST_COMMAND_DELIVERED_AT = "last_command_delivered_at"
        private const val KEY_LAST_COMMAND_EXECUTED_AT = "last_command_executed_at"
        private const val KEY_LAST_COMMAND_UPLOADED_AT = "last_command_uploaded_at"
        private const val KEY_LAST_COMMAND_ACTION = "last_command_action"
        private const val KEY_LAST_COMMAND_ID = "last_command_id"
        private const val KEY_LAST_ERROR_CATEGORY = "last_error_category"
        private const val KEY_LAST_ERROR_REASON = "last_error_reason"
    }
}
