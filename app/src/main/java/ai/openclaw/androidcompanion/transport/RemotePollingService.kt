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
import ai.openclaw.androidcompanion.logging.CommandLogStore
import org.json.JSONObject
import java.time.Instant
import kotlin.concurrent.thread

class RemotePollingService : Service() {
    private var running = false
    private lateinit var configStore: TransportConfigStore
    private lateinit var logStore: CommandLogStore
    private lateinit var engine: AndroidCapabilityEngine

    override fun onCreate() {
        super.onCreate()
        configStore = TransportConfigStore(this)
        logStore = CommandLogStore(this)
        engine = AndroidCapabilityEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
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
        startForeground(NOTIFICATION_ID, buildNotification("Remote polling active"))

        thread(name = "remote-polling-loop") {
            while (running) {
                val config = configStore.load()
                if (config.baseUrl.isBlank()) {
                    updateNotification("Waiting for remote base URL")
                    Thread.sleep(5000)
                    continue
                }
                try {
                    val client = RemoteTransportClient(config)
                    client.postHeartbeat(
                        JSONObject()
                            .put("timestamp", Instant.now().toString())
                            .put("status", "online")
                    )
                    val response = client.fetchNextCommand()
                    val command = RemoteTransportClient.parseCommand(response)
                    val commandId = RemoteTransportClient.parseCommandId(response)
                    if (command != null && commandId != null) {
                        updateNotification("Executing ${command.action}")
                        val result = engine.execute(command)
                        client.uploadResult(commandId, result)
                        logStore.append(
                            JSONObject()
                                .put("timestamp", Instant.now().toString())
                                .put("mode", "remote")
                                .put("action", command.action)
                                .put("request_id", command.requestId)
                                .put("ok", result.optBoolean("ok", false))
                        )
                        updateNotification("Last command: ${command.action}")
                    } else {
                        updateNotification("Remote polling active")
                    }
                } catch (e: Exception) {
                    logStore.append(
                        JSONObject()
                            .put("timestamp", Instant.now().toString())
                            .put("mode", "remote")
                            .put("ok", false)
                            .put("error", e.message ?: e.javaClass.simpleName)
                    )
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
