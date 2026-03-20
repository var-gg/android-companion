package ai.openclaw.androidcompanion.capabilities

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ai.openclaw.androidcompanion.logging.CommandLogStore
import org.json.JSONObject

class IntentLaunchProxyActivity : AppCompatActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun handleLaunch(sourceIntent: Intent?) {
        if (handled) {
            finish()
            return
        }
        handled = true

        if (sourceIntent?.action != ACTION_LAUNCH_INTENT) {
            finish()
            return
        }

        val logId = sourceIntent.getStringExtra(EXTRA_LOG_ID).orEmpty()
        val notificationId = sourceIntent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val commandAction = sourceIntent.getStringExtra(EXTRA_COMMAND_ACTION).orEmpty()
        val requestId = sourceIntent.getStringExtra(EXTRA_REQUEST_ID)
        val launchIntent = sourceIntent.getParcelableExtraCompat<Intent>(EXTRA_TARGET_INTENT)
        val logStore = CommandLogStore(this)

        logStore.markPhase(
            logId = logId,
            phase = PHASE_NOTIFICATION_TAPPED,
            state = STATE_ACTION_REQUIRED,
            detail = "Notification tapped by user",
            ok = true,
            payload = JSONObject()
                .put("delivery_channel", "notification")
                .put("tap_handler", "proxy_activity")
                .put("command_action", commandAction)
                .put("request_id", requestId)
        )

        if (notificationId >= 0) {
            val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
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
            finish()
            return
        }

        try {
            logStore.markPhase(
                logId = logId,
                phase = PHASE_NOTIFICATION_LAUNCH_ATTEMPTED,
                state = CommandLogStore.STATE_EXECUTING,
                detail = "Attempting target launch from notification tap proxy",
                ok = true,
                payload = JSONObject()
                    .put("delivery_channel", "notification")
                    .put("launch_origin", "notification_tap")
                    .put("tap_handler", "proxy_activity")
            )
            startActivity(launchIntent)
            logStore.markPhase(
                logId = logId,
                phase = PHASE_NOTIFICATION_LAUNCHED,
                state = CommandLogStore.STATE_EXECUTED,
                detail = "Intent launched from notification tap proxy",
                ok = true,
                payload = JSONObject()
                    .put("delivery_channel", "notification")
                    .put("launch_origin", "notification_tap")
                    .put("tap_handler", "proxy_activity")
            )
        } catch (error: Throwable) {
            logStore.markPhase(
                logId = logId,
                phase = PHASE_NOTIFICATION_TAP_FAILED,
                state = CommandLogStore.STATE_FAILED,
                detail = "Notification tap launch failed",
                ok = false,
                errorCategory = "notification",
                errorReason = error.message ?: error.javaClass.simpleName,
                payload = JSONObject().put("tap_handler", "proxy_activity")
            )
        }

        finish()
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
        const val ACTION_LAUNCH_INTENT = IntentLaunchReceiver.ACTION_LAUNCH_INTENT
        const val EXTRA_TARGET_INTENT = IntentLaunchReceiver.EXTRA_TARGET_INTENT
        const val EXTRA_LOG_ID = IntentLaunchReceiver.EXTRA_LOG_ID
        const val EXTRA_NOTIFICATION_ID = IntentLaunchReceiver.EXTRA_NOTIFICATION_ID
        const val EXTRA_COMMAND_ACTION = IntentLaunchReceiver.EXTRA_COMMAND_ACTION
        const val EXTRA_REQUEST_ID = IntentLaunchReceiver.EXTRA_REQUEST_ID

        const val PHASE_NOTIFICATION_TAPPED = IntentLaunchReceiver.PHASE_NOTIFICATION_TAPPED
        const val PHASE_NOTIFICATION_LAUNCH_ATTEMPTED = IntentLaunchReceiver.PHASE_NOTIFICATION_LAUNCH_ATTEMPTED
        const val PHASE_NOTIFICATION_LAUNCHED = IntentLaunchReceiver.PHASE_NOTIFICATION_LAUNCHED
        const val PHASE_NOTIFICATION_TAP_FAILED = IntentLaunchReceiver.PHASE_NOTIFICATION_TAP_FAILED
        const val STATE_ACTION_REQUIRED = IntentLaunchReceiver.STATE_ACTION_REQUIRED
    }
}
