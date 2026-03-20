package ai.openclaw.androidcompanion.capabilities

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ai.openclaw.androidcompanion.logging.CommandLogStore
import org.json.JSONObject

class IntentLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_LAUNCH_INTENT) return

        val logId = intent.getStringExtra(EXTRA_LOG_ID).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val commandAction = intent.getStringExtra(EXTRA_COMMAND_ACTION).orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val launchIntent = intent.getParcelableExtraCompat<Intent>(EXTRA_TARGET_INTENT)
        val logStore = CommandLogStore(context)

        logStore.markPhase(
            logId = logId,
            phase = PHASE_NOTIFICATION_TAPPED,
            state = STATE_ACTION_REQUIRED,
            detail = "Notification tapped by user",
            ok = true,
            payload = JSONObject()
                .put("delivery_channel", "notification")
                .put("command_action", commandAction)
                .put("request_id", requestId)
        )

        if (notificationId >= 0) {
            val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            notificationManager?.cancel(notificationId)
        }

        if (launchIntent == null) {
            logStore.markPhase(
                logId = logId,
                phase = PHASE_NOTIFICATION_TAP_FAILED,
                state = CommandLogStore.STATE_FAILED,
                detail = "Notification target intent missing",
                ok = false,
                errorCategory = "notification",
                errorReason = "missing_target_intent"
            )
            return
        }

        try {
            context.startActivity(launchIntent)
            logStore.markPhase(
                logId = logId,
                phase = PHASE_NOTIFICATION_LAUNCHED,
                state = CommandLogStore.STATE_EXECUTED,
                detail = "Intent launched from notification tap",
                ok = true,
                payload = JSONObject()
                    .put("delivery_channel", "notification")
                    .put("launch_origin", "notification_tap")
            )
        } catch (error: Throwable) {
            logStore.markPhase(
                logId = logId,
                phase = PHASE_NOTIFICATION_TAP_FAILED,
                state = CommandLogStore.STATE_FAILED,
                detail = "Notification tap launch failed",
                ok = false,
                errorCategory = "notification",
                errorReason = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    companion object {
        const val ACTION_LAUNCH_INTENT = "ai.openclaw.androidcompanion.action.LAUNCH_INTENT_FROM_NOTIFICATION"
        const val EXTRA_TARGET_INTENT = "target_intent"
        const val EXTRA_LOG_ID = "log_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_COMMAND_ACTION = "command_action"
        const val EXTRA_REQUEST_ID = "request_id"

        const val PHASE_NOTIFICATION_POSTED = "notification_posted"
        const val PHASE_NOTIFICATION_TAPPED = "notification_tapped"
        const val PHASE_NOTIFICATION_LAUNCHED = "notification_launched"
        const val PHASE_NOTIFICATION_TAP_FAILED = "notification_tap_failed"
        const val STATE_ACTION_REQUIRED = "action_required"
    }
}
