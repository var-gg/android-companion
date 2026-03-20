package ai.openclaw.androidcompanion.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.openclaw.androidcompanion.R
import ai.openclaw.androidcompanion.capabilities.AndroidCapabilityEngine
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import ai.openclaw.androidcompanion.logging.CommandLogStore
import org.json.JSONObject
import kotlin.concurrent.thread

class RemotePollingService : Service() {
    private var running = false
    private lateinit var configStore: TransportConfigStore
    private lateinit var logStore: CommandLogStore
    private lateinit var engine: AndroidCapabilityEngine
    private lateinit var uiStateStore: RemoteUiStateStore

    override fun onCreate() {
        super.onCreate()
        configStore = TransportConfigStore(this)
        logStore = CommandLogStore(this)
        engine = AndroidCapabilityEngine(this)
        uiStateStore = RemoteUiStateStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                uiStateStore.markServiceRunning(false, "Remote polling stopped")
                uiStateStore.set(RemoteUiStateStore.STATUS_DISCONNECTED, "Remote polling stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startPollingLoop()
        }
        return START_STICKY
    }

    private fun startPollingLoop() {
        if (running) return
        running = true
        uiStateStore.markServiceRunning(true, "Remote polling service running")
        uiStateStore.set(RemoteUiStateStore.STATUS_POLLING, "Remote polling active")
        startForeground(NOTIFICATION_ID, buildNotification("Remote polling active"))

        thread(name = "remote-polling-loop") {
            var registeredDeviceId: String? = null
            while (running) {
                val config = configStore.load()
                if (config.baseUrl.isBlank()) {
                    uiStateStore.set(RemoteUiStateStore.STATUS_SETUP_REQUIRED, "Remote base URL is missing")
                    updateNotification("Waiting for remote base URL")
                    Thread.sleep(5000)
                    continue
                }

                uiStateStore.markPollStarted("Polling ${config.baseUrl}")
                try {
                    val client = RemoteTransportClient(config)
                    if (registeredDeviceId != config.deviceId) {
                        client.registerDevice(engine.execute(CommandEnvelope("device_info", JSONObject(), null)))
                        registeredDeviceId = config.deviceId
                        uiStateStore.set(RemoteUiStateStore.STATUS_REGISTERED, "Registered ${config.deviceId}")
                    }

                    client.postHeartbeat(
                        JSONObject()
                            .put("status", "online")
                            .put("app_version", ai.openclaw.androidcompanion.BuildConfig.VERSION_NAME)
                    )
                    uiStateStore.markHeartbeat("Heartbeat delivered")

                    val response = client.fetchNextCommand()
                    val command = RemoteTransportClient.parseCommand(response)
                    val commandId = RemoteTransportClient.parseCommandId(response)
                    if (command != null && commandId != null) {
                        val commandJson = JSONObject()
                            .put("action", command.action)
                            .put("params", JSONObject(command.params.toString()))
                            .apply { command.requestId?.let { put("request_id", it) } }
                        val log = logStore.createRemoteLog(commandId, commandJson, command.action, command.requestId)
                        val logId = log.optString("log_id")
                        uiStateStore.markCommandFetched(command.action, commandId, "Fetched ${command.action}")
                        logStore.markPhase(logId, CommandLogStore.PHASE_DELIVERED, CommandLogStore.STATE_DELIVERED, "Command delivered to executor", true)
                        uiStateStore.markCommandDelivered(command.action, commandId, "Delivered ${command.action} to executor")
                        updateNotification("Executing ${command.action}")
                        logStore.markPhase(logId, CommandLogStore.PHASE_EXECUTING, CommandLogStore.STATE_EXECUTING, "Executor running", true)

                        val execution = runCatching { engine.execute(command) }
                        execution.onSuccess { executionResult ->
                            val executionOk = executionResult.optBoolean("ok", false)
                            logStore.attachResult(logId, executionResult, executionOk)
                            logStore.markPhase(
                                logId,
                                CommandLogStore.PHASE_EXECUTED,
                                CommandLogStore.STATE_EXECUTED,
                                detail = if (executionOk) "Execution finished" else "Execution returned failure",
                                ok = executionOk,
                                payload = executionResult
                            )
                            uiStateStore.markCommandExecuted(command.action, commandId, "Executed ${command.action}")

                            val upload = runCatching { client.uploadResult(commandId, executionResult) }
                            upload.onSuccess { uploadResult ->
                                logStore.attachUploadResult(logId, uploadResult)
                                logStore.markPhase(
                                    logId,
                                    CommandLogStore.PHASE_UPLOADED,
                                    CommandLogStore.STATE_UPLOADED,
                                    detail = "Result uploaded to bridge",
                                    ok = true,
                                    payload = uploadResult
                                )
                                uiStateStore.markCommandUploaded(command.action, commandId, "Uploaded ${command.action} result")
                                uiStateStore.markPollSucceeded("Last uploaded command: ${command.action}")
                                updateNotification("Uploaded ${command.action}")
                            }.onFailure { uploadError ->
                                val reason = uploadError.message ?: uploadError.javaClass.simpleName
                                logStore.markPhase(
                                    logId,
                                    CommandLogStore.PHASE_FAILED,
                                    CommandLogStore.STATE_FAILED,
                                    detail = "Upload failed",
                                    ok = false,
                                    errorCategory = "upload",
                                    errorReason = reason
                                )
                                uiStateStore.markError("upload", reason, "Upload failed for ${command.action}")
                                updateNotification("Upload failed: ${command.action}")
                            }
                        }.onFailure { executionError ->
                            val reason = executionError.message ?: executionError.javaClass.simpleName
                            logStore.markPhase(
                                logId,
                                CommandLogStore.PHASE_FAILED,
                                CommandLogStore.STATE_FAILED,
                                detail = "Execution failed",
                                ok = false,
                                errorCategory = "execution",
                                errorReason = reason
                            )
                            uiStateStore.markError("execution", reason, "Execution failed for ${command.action}")
                            updateNotification("Execution failed: ${command.action}")
                        }
                    } else {
                        uiStateStore.markPollSucceeded("No command waiting")
                        updateNotification("Remote polling active")
                    }
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    logStore.append(
                        JSONObject()
                            .put("source", "remote_service")
                            .put("state", CommandLogStore.STATE_FAILED)
                            .put("phase", CommandLogStore.PHASE_FAILED)
                            .put("ok", false)
                            .put("error_category", "transport")
                            .put("error_reason", reason)
                            .put("detail", "Polling loop failed")
                            .put(
                                "phases",
                                org.json.JSONArray().put(
                                    JSONObject()
                                        .put("phase", CommandLogStore.PHASE_FAILED)
                                        .put("detail", "Polling loop failed")
                                        .put("error_category", "transport")
                                        .put("error_reason", reason)
                                )
                            )
                    )
                    uiStateStore.markError("transport", reason, reason)
                    updateNotification("Remote error: ${e.javaClass.simpleName}")
                }
                val delayMs = config.pollIntervalSeconds.coerceAtLeast(10L) * 1000L
                Thread.sleep(delayMs)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Companion Remote")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Android Companion Remote Polling",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "remote_polling"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ai.openclaw.androidcompanion.START_REMOTE_POLLING"
        const val ACTION_STOP = "ai.openclaw.androidcompanion.STOP_REMOTE_POLLING"

        fun start(context: Context) {
            val intent = Intent(context, RemotePollingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RemotePollingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
