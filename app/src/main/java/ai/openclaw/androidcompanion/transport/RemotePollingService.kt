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
import java.time.Instant
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
        uiStateStore.set(RemoteUiStateStore.STATUS_POLLING, "Remote polling active")
        startForeground(NOTIFICATION_ID, buildNotification("Remote polling active"))

        thread(name = "remote-polling-loop") {
            var registeredThisRun = false
            while (running) {
                val config = configStore.load()
                if (config.baseUrl.isBlank()) {
                    uiStateStore.set(RemoteUiStateStore.STATUS_SETUP_REQUIRED, "Remote base URL is missing")
                    updateNotification("Waiting for remote base URL")
                    Thread.sleep(5000)
                    continue
                }
                try {
                    val client = RemoteTransportClient(config)
                    if (!registeredThisRun) {
                        client.registerDevice(engine.execute(CommandEnvelope("device_info", JSONObject(), null)))
                        registeredThisRun = true
                        uiStateStore.set(RemoteUiStateStore.STATUS_REGISTERED, "Registered ${config.deviceId}")
                        updateNotification("Registered ${config.deviceId}")
                    }
                    client.postHeartbeat(
                        JSONObject()
                            .put("timestamp", Instant.now().toString())
                            .put("status", "online")
                    )
                    uiStateStore.markPoll()
                    val response = client.fetchNextCommand()
                    val command = RemoteTransportClient.parseCommand(response)
                    val commandId = RemoteTransportClient.parseCommandId(response)
                    if (command != null && commandId != null) {
                        uiStateStore.markCommandReceived(
                            action = command.action,
                            commandId = commandId,
                            detail = "Received ${command.action}"
                        )
                        val logId = logStore.append(
                            baseLogEntry(
                                action = command.action,
                                requestId = command.requestId,
                                command = command,
                                state = "received"
                            )
                                .put("source", "remote_service")
                                .put("remote_command_id", commandId)
                                .put("poll_response", JSONObject(response.toString()))
                        ).optString("log_id")
                        uiStateStore.set(RemoteUiStateStore.STATUS_POLLING, "Executing ${command.action}")
                        updateNotification("Executing ${command.action}")
                        logStore.update(logId) { existing ->
                            existing
                                .put("state", "started")
                                .put("started_at", Instant.now().toString())
                        }
                        val result = runCatching { engine.execute(command) }
                        result.onSuccess { executionResult ->
                            val upload = runCatching { client.uploadResult(commandId, executionResult) }
                            upload.onSuccess { uploadResult ->
                                logStore.update(logId) { existing ->
                                    existing
                                        .put("state", if (executionResult.optBoolean("ok", false)) "finished" else "failed")
                                        .put("ok", executionResult.optBoolean("ok", false))
                                        .put("finished_at", Instant.now().toString())
                                        .put("result", JSONObject(executionResult.toString()))
                                        .put("upload_result", JSONObject(uploadResult.toString()))
                                }
                                uiStateStore.markResultUpload("Last command: ${command.action}")
                                uiStateStore.set(RemoteUiStateStore.STATUS_POLLING, "Last command: ${command.action}")
                                updateNotification("Last command: ${command.action}")
                            }.onFailure { uploadError ->
                                logStore.update(logId) { existing ->
                                    existing
                                        .put("state", "upload_failed")
                                        .put("ok", executionResult.optBoolean("ok", false))
                                        .put("finished_at", Instant.now().toString())
                                        .put("result", JSONObject(executionResult.toString()))
                                        .put("upload_error", uploadError.message ?: uploadError.javaClass.simpleName)
                                }
                                uiStateStore.set(
                                    RemoteUiStateStore.STATUS_ERROR,
                                    "Upload failed for ${command.action}: ${uploadError.message ?: uploadError.javaClass.simpleName}"
                                )
                                updateNotification("Upload failed: ${command.action}")
                            }
                        }.onFailure { executionError ->
                            logStore.update(logId) { existing ->
                                existing
                                    .put("state", "failed")
                                    .put("ok", false)
                                    .put("finished_at", Instant.now().toString())
                                    .put("error", executionError.message ?: executionError.javaClass.simpleName)
                            }
                            uiStateStore.set(
                                RemoteUiStateStore.STATUS_ERROR,
                                "Execution failed for ${command.action}: ${executionError.message ?: executionError.javaClass.simpleName}"
                            )
                            updateNotification("Execution failed: ${command.action}")
                        }
                    } else {
                        uiStateStore.set(RemoteUiStateStore.STATUS_POLLING, "Remote polling active")
                        updateNotification("Remote polling active")
                    }
                } catch (e: Exception) {
                    logStore.append(
                        JSONObject()
                            .put("timestamp", Instant.now().toString())
                            .put("source", "remote_service")
                            .put("state", "failed")
                            .put("ok", false)
                            .put("error", e.message ?: e.javaClass.simpleName)
                    )
                    uiStateStore.set(RemoteUiStateStore.STATUS_ERROR, e.message ?: e.javaClass.simpleName)
                    updateNotification("Remote error: ${e.javaClass.simpleName}")
                }
                val delayMs = config.pollIntervalSeconds.coerceAtLeast(10L) * 1000L
                Thread.sleep(delayMs)
            }
        }
    }

    private fun baseLogEntry(
        action: String,
        requestId: String?,
        command: CommandEnvelope,
        state: String
    ): JSONObject {
        return JSONObject()
            .put("timestamp", Instant.now().toString())
            .put("action", action)
            .put("request_id", requestId)
            .put("state", state)
            .put("command", JSONObject().put("action", command.action).put("params", JSONObject(command.params.toString())).apply { command.requestId?.let { put("request_id", it) } })
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
