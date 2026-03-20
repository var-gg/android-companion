package ai.openclaw.androidcompanion.capabilities

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ai.openclaw.androidcompanion.R
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import ai.openclaw.androidcompanion.logging.CommandLogStore
import ai.openclaw.androidcompanion.settings.PermissionStatus
import ai.openclaw.androidcompanion.transport.RemoteUiStateStore
import ai.openclaw.androidcompanion.transport.TransportConfigStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import kotlin.math.absoluteValue

class AndroidCapabilityEngine(
    private val context: Context
) {
    private val packageManager: PackageManager = context.packageManager
    private val logStore = CommandLogStore(context)
    private val uiStateStore = RemoteUiStateStore(context)
    private val transportConfigStore = TransportConfigStore(context)

    fun execute(command: CommandEnvelope): JSONObject {
        return try {
            when (command.action) {
                "health_ping" -> okAction(command)
                    .put("device", deviceIdentity())
                    .put("app", appIdentity())
                "device_info" -> okAction(command)
                    .put("device", JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("brand", Build.BRAND)
                        .put("model", Build.MODEL)
                        .put("device", Build.DEVICE)
                        .put("product", Build.PRODUCT)
                        .put("sdk_int", Build.VERSION.SDK_INT)
                        .put("release", Build.VERSION.RELEASE)
                        .put("locale", Locale.getDefault().toLanguageTag())
                    )
                    .put("app", appIdentity())
                "get_remote_status" -> getRemoteStatus(command)
                "get_command_logs" -> getCommandLogs(command)
                "get_execution_trace" -> getExecutionTrace(command)
                "open_url" -> openUrl(command)
                "launch_app" -> launchApp(command)
                "list_installed_apps" -> listInstalledApps(command)
                "usage_stats" -> usageStats(command)
                "uninstall_app" -> uninstallApp(command)
                "open_intent", "test_intent" -> openIntent(command)
                "check_self_update" -> checkSelfUpdate(command)
                "download_self_update" -> downloadSelfUpdate(command)
                else -> jsonError(command, "unsupported_action", "Unsupported action: ${command.action}")
            }
        } catch (e: Exception) {
            jsonError(command, "execution_failed", e.message ?: e.javaClass.simpleName)
        }
    }

    fun appIdentity(): JSONObject {
        val pkg = packageManager.getPackageInfoCompat(context.packageName)
        return JSONObject()
            .put("package_name", context.packageName)
            .put("version_name", pkg.versionName)
            .put("version_code", pkg.longVersionCode)
    }

    private fun getRemoteStatus(command: CommandEnvelope): JSONObject {
        val transport = transportConfigStore.load()
        val uiState = uiStateStore.load()
        val permissions = PermissionStatus.snapshot(context)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val logs = logStore.readAll()
        val lastLog = logs.optJSONObject(0)

        return okAction(command)
            .put("transport", JSONObject()
                .put("base_url", transport.baseUrl)
                .put("device_id", transport.deviceId)
                .put("has_token", transport.token.isNotBlank())
                .put("poll_interval_seconds", transport.pollIntervalSeconds)
            )
            .put("remote_ui", JSONObject()
                .put("status", uiState.status)
                .put("detail", uiState.detail)
                .put("timestamp_epoch_ms", uiState.timestamp)
                .put("timestamp", epochToIso(uiState.timestamp))
                .put("last_poll_at_epoch_ms", uiState.lastPollAt)
                .put("last_poll_at", epochToIso(uiState.lastPollAt))
                .put("last_result_upload_at_epoch_ms", uiState.lastResultUploadAt)
                .put("last_result_upload_at", epochToIso(uiState.lastResultUploadAt))
                .put("last_received_command_at_epoch_ms", uiState.lastReceivedCommandAt)
                .put("last_received_command_at", epochToIso(uiState.lastReceivedCommandAt))
                .put("last_received_action", uiState.lastReceivedAction)
                .put("last_received_command_id", uiState.lastReceivedCommandId)
            )
            .put("permissions", JSONObject()
                .put("notifications", permissions.notificationPermission)
                .put("ignore_battery_optimization", permissions.ignoringBatteryOptimizations)
                .put("usage_access", permissions.usageAccess)
                .put("install_unknown_apps", permissions.installUnknownApps)
            )
            .put("runtime", JSONObject()
                .put("battery_pct", batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: JSONObject.NULL)
                .put("power_save_mode", powerManager?.isPowerSaveMode ?: JSONObject.NULL)
                .put("available_memory_bytes", memoryInfo.availMem)
                .put("low_memory", memoryInfo.lowMemory)
            )
            .put("logs", JSONObject()
                .put("stored_count", logs.length())
                .put("max_items", CommandLogStore.MAX_ITEMS)
                .put("last_log", lastLog?.let { summarizeLog(it) } ?: JSONObject.NULL)
            )
    }

    private fun getCommandLogs(command: CommandEnvelope): JSONObject {
        val limit = command.params.optInt("limit", 10).coerceIn(1, CommandLogStore.MAX_ITEMS)
        val actionFilter = command.params.optString("action").takeIf { it.isNotBlank() }
        val stateFilter = command.params.optString("state").takeIf { it.isNotBlank() }
        val includeCommand = command.params.optBoolean("include_command", true)
        val includeResult = command.params.optBoolean("include_result", true)
        val includeUpload = command.params.optBoolean("include_upload", false)
        val items = logStore.recent(limit = limit, action = actionFilter, state = stateFilter)
        val normalized = JSONArray()
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            normalized.put(
                serializeLogEntry(
                    entry = entry,
                    includeCommand = includeCommand,
                    includeResult = includeResult,
                    includeUpload = includeUpload
                )
            )
        }
        return okAction(command)
            .put("count", normalized.length())
            .put("filters", JSONObject()
                .put("limit", limit)
                .put("action", actionFilter ?: JSONObject.NULL)
                .put("state", stateFilter ?: JSONObject.NULL)
                .put("include_command", includeCommand)
                .put("include_result", includeResult)
                .put("include_upload", includeUpload)
            )
            .put("logs", normalized)
    }

    private fun getExecutionTrace(command: CommandEnvelope): JSONObject {
        val logId = command.params.optString("log_id").takeIf { it.isNotBlank() }
        val requestId = command.params.optString("request_id").takeIf { it.isNotBlank() }
        if (logId == null && requestId == null) {
            return jsonError(command, "missing_selector", "params.log_id or params.request_id is required")
        }

        val matches = when {
            logId != null -> JSONArray().apply {
                logStore.findByLogId(logId)?.let { put(it) }
            }
            else -> logStore.findByRequestId(requestId!!)
        }

        if (matches.length() == 0) {
            return jsonError(command, "trace_not_found", "No matching command log found")
                .put("selector", JSONObject()
                    .put("log_id", logId ?: JSONObject.NULL)
                    .put("request_id", requestId ?: JSONObject.NULL)
                )
        }

        val traceSteps = JSONArray()
        for (i in 0 until matches.length()) {
            val entry = matches.optJSONObject(i) ?: continue
            traceSteps.put(buildTraceStep(entry))
        }

        val primary = matches.optJSONObject(0)
        return okAction(command)
            .put("selector", JSONObject()
                .put("log_id", logId ?: JSONObject.NULL)
                .put("request_id", requestId ?: JSONObject.NULL)
            )
            .put("trace_count", traceSteps.length())
            .put("execution_trace", traceSteps)
            .put("summary", JSONObject()
                .put("action", primary?.optString("action") ?: JSONObject.NULL)
                .put("state", primary?.optString("state") ?: JSONObject.NULL)
                .put("ok", primary?.optBoolean("ok", false) ?: JSONObject.NULL)
                .put("source", primary?.optString("source") ?: JSONObject.NULL)
            )
    }

    private fun openUrl(command: CommandEnvelope): JSONObject {
        val url = command.params.optString("url")
        if (url.isBlank()) return jsonError(command, "missing_url", "params.url is required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return okAction(command).put("url", url)
    }

    private fun launchApp(command: CommandEnvelope): JSONObject {
        val targetPackage = command.params.optString("package")
        if (targetPackage.isBlank()) return jsonError(command, "missing_package", "params.package is required")
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: return jsonError(command, "app_not_found", "No launch intent for $targetPackage")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return okAction(command).put("package", targetPackage)
    }

    private fun listInstalledApps(command: CommandEnvelope): JSONObject {
        val includeSystem = command.params.optBoolean("include_system", false)
        val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        val apps = JSONArray()
        packages.sortedBy { it.packageName }.forEach { pkg ->
            val appInfo = pkg.applicationInfo ?: return@forEach
            val systemApp = appInfo.flags.and(ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && systemApp) return@forEach
            apps.put(
                JSONObject()
                    .put("package", pkg.packageName)
                    .put("label", packageManager.getApplicationLabel(appInfo).toString())
                    .put("version_name", pkg.versionName ?: "")
                    .put("version_code", pkg.longVersionCode)
                    .put("system", systemApp)
            )
        }
        return okAction(command)
            .put("count", apps.length())
            .put("apps", apps)
    }

    private fun usageStats(command: CommandEnvelope): JSONObject {
        if (!hasUsageStatsPermission()) {
            return jsonError(command, "permission_required", "Grant PACKAGE_USAGE_STATS in system settings")
                .put("permission", "PACKAGE_USAGE_STATS")
                .put("settings_intent", Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }

        val hours = command.params.optLong("hours", 168L)
        val since = System.currentTimeMillis() - hours * 60L * 60L * 1000L
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            since,
            System.currentTimeMillis()
        )
        val items = JSONArray()
        stats.sortedByDescending { it.lastTimeUsed }.forEach {
            items.put(
                JSONObject()
                    .put("package", it.packageName)
                    .put("last_time_used", if (it.lastTimeUsed > 0) Instant.ofEpochMilli(it.lastTimeUsed).toString() else JSONObject.NULL)
                    .put("last_time_used_epoch_ms", it.lastTimeUsed)
                    .put("total_time_foreground_ms", it.totalTimeInForeground)
            )
        }
        return okAction(command)
            .put("hours", hours)
            .put("count", items.length())
            .put("items", items)
    }

    private fun uninstallApp(command: CommandEnvelope): JSONObject {
        val targetPackage = command.params.optString("package")
        if (targetPackage.isBlank()) return jsonError(command, "missing_package", "params.package is required")
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$targetPackage")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return okAction(command)
            .put("package", targetPackage)
            .put("mode", "user_confirmation_required")
    }

    private fun openIntent(command: CommandEnvelope): JSONObject {
        val action = command.params.optString("action", command.params.optString("intent_action", Intent.ACTION_VIEW))
        val data = command.params.optString("uri", command.params.optString("data", ""))
        val packageName = command.params.optString("package", "")
        val className = command.params.optString("class", "")
        val extrasObject = command.params.optJSONObject("extras") ?: JSONObject()
        val resolveOnly = command.action == "test_intent" || command.params.optBoolean("resolve_only", false)
        val requestedPolicy = command.params.optString("delivery_policy", "auto").ifBlank { "auto" }.lowercase(Locale.US)

        val intent = Intent(action)
        if (data.isNotBlank()) intent.data = Uri.parse(data)
        if (packageName.isNotBlank() && className.isNotBlank()) {
            intent.setClassName(packageName, className)
        } else if (packageName.isNotBlank()) {
            intent.setPackage(packageName)
        }
        extrasObject.keys().forEach { key ->
            val value = extrasObject.get(key)
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val matches = queryIntentActivitiesCompat(intent)
        val resolved = resolveActivityCompat(intent)
        val packageInstalled = packageName.isBlank() || isPackageInstalled(packageName)
        val canResolve = resolved != null || matches.length() > 0
        val executionContext = executionContextLabel()
        val appForeground = context is Activity
        val directLaunchReliability = if (appForeground) "foreground_expected" else "background_unreliable"
        val normalizedPolicy = when (requestedPolicy) {
            "direct", "notify", "auto" -> requestedPolicy
            else -> "auto"
        }
        val effectivePolicy = when (normalizedPolicy) {
            "direct" -> "direct"
            "notify" -> "notify"
            else -> if (appForeground) "direct" else "notify"
        }
        val diagnostics = okAction(command)
            .put("intent_action", action)
            .put("data", data)
            .put("package", packageName)
            .put("class", className)
            .put("resolve_only", resolveOnly)
            .put("execution_context", executionContext)
            .put("package_installed", packageInstalled)
            .put("resolve_activity", resolved?.let { describeResolveInfo(it) } ?: JSONObject.NULL)
            .put("matched_activities_count", matches.length())
            .put("matched_activities", matches)
            .put("delivery_policy_requested", normalizedPolicy)
            .put("delivery_policy_effective", effectivePolicy)
            .put("delivery_channel", if (effectivePolicy == "notify") "notification" else "direct")
            .put("delivery_status", if (effectivePolicy == "notify") "pending_user_action" else "launched_direct")
            .put("user_action_required", effectivePolicy == "notify")
            .put("app_foreground", appForeground)
            .put("background_launch_reliability", directLaunchReliability)
            .put("suspected_background_launch_blocked", !appForeground)
            .put("direct_launch_attempted", false)
            .put("direct_launch_succeeded", JSONObject.NULL)
            .put("notification_posted", false)
            .put("notification_channel", if (effectivePolicy == "notify") NOTIFICATION_CHANNEL_INTENT_DELIVERY else JSONObject.NULL)
            .put("notification_id", JSONObject.NULL)

        if (resolveOnly) {
            diagnostics.put("ok", canResolve && packageInstalled)
            if (!packageInstalled) {
                diagnostics.put("likely_block_reason", "package_not_installed")
            } else if (!canResolve) {
                diagnostics.put("likely_block_reason", "intent_not_resolved")
            } else if (!appForeground) {
                diagnostics.put("likely_background_launch_blocked", true)
                diagnostics.put("likely_background_launch_message", "Intent resolves, but direct launch from a service/background context is not reliable for visible execution on modern Android")
            }
            return diagnostics
        }

        if (!packageInstalled) {
            return diagnostics
                .put("ok", false)
                .put("delivery_status", "package_not_installed")
                .put("error", JSONObject().put("code", "package_not_installed").put("message", "Target package is not installed"))
        }
        if (!canResolve) {
            return diagnostics
                .put("ok", false)
                .put("delivery_status", "intent_not_resolved")
                .put("error", JSONObject().put("code", "intent_not_resolved").put("message", "No activity resolved"))
        }

        return if (effectivePolicy == "notify") {
            postIntentNotification(command, intent, diagnostics)
        } else {
            try {
                diagnostics.put("direct_launch_attempted", true)
                context.startActivity(intent)
                diagnostics
                    .put("launched", true)
                    .put("direct_launch_succeeded", true)
                    .put("delivery_status", "launched_direct")
                    .put("user_action_required", false)
            } catch (e: ActivityNotFoundException) {
                diagnostics
                    .put("ok", false)
                    .put("direct_launch_succeeded", false)
                    .put("delivery_status", "intent_not_resolved")
                    .put("error", JSONObject().put("code", "intent_not_resolved").put("message", e.message ?: "No activity resolved"))
            }
        }
    }

    private fun postIntentNotification(command: CommandEnvelope, targetIntent: Intent, diagnostics: JSONObject): JSONObject {
        val notificationsAllowed = notificationsAllowed()
        if (!notificationsAllowed) {
            return diagnostics
                .put("ok", false)
                .put("notification_posted", false)
                .put("delivery_status", "notification_permission_required")
                .put("error", JSONObject()
                    .put("code", "notification_permission_required")
                    .put("message", "Notification delivery requested but notifications are not permitted"))
        }

        ensureIntentDeliveryNotificationChannel()
        val logId = command.params.optString("log_id").ifBlank { null }
            ?: command.params.optString("command_log_id").ifBlank { null }
            ?: command.requestId
            ?: "intent-${System.currentTimeMillis()}"
        val notificationId = computeNotificationId(logId)
        val receiverIntent = Intent(context, IntentLaunchReceiver::class.java).apply {
            action = IntentLaunchReceiver.ACTION_LAUNCH_INTENT
            putExtra(IntentLaunchReceiver.EXTRA_TARGET_INTENT, Intent(targetIntent))
            putExtra(IntentLaunchReceiver.EXTRA_LOG_ID, logId)
            putExtra(IntentLaunchReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(IntentLaunchReceiver.EXTRA_COMMAND_ACTION, command.action)
            putExtra(IntentLaunchReceiver.EXTRA_REQUEST_ID, command.requestId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = command.params.optString("notification_title").ifBlank { "Android Companion action ready" }
        val body = command.params.optString("notification_body").ifBlank {
            val target = diagnostics.optString("intent_action").ifBlank { "intent" }
            "Tap to open $target"
        }
        val actionLabel = command.params.optString("notification_action_label").ifBlank { "Open" }
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_INTENT_DELIVERY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, actionLabel, pendingIntent)
            .build()

        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return diagnostics
                .put("ok", false)
                .put("notification_posted", false)
                .put("delivery_status", "notification_manager_unavailable")
                .put("error", JSONObject().put("code", "notification_manager_unavailable").put("message", "Notification manager unavailable"))

        manager.notify(notificationId, notification)
        logStore.markPhase(
            logId = logId,
            phase = IntentLaunchReceiver.PHASE_NOTIFICATION_POSTED,
            state = IntentLaunchReceiver.STATE_ACTION_REQUIRED,
            detail = "Intent posted to notification for user tap",
            ok = true,
            payload = JSONObject()
                .put("delivery_channel", "notification")
                .put("notification_id", notificationId)
                .put("intent_action", diagnostics.optString("intent_action"))
        )

        return diagnostics
            .put("notification_posted", true)
            .put("notification_id", notificationId)
            .put("delivery_status", "notification_posted")
            .put("pending_user_action", "tap_notification")
            .put("tap_to_execute", true)
            .put("launched", false)
            .put("notification_permission_available", true)
            .put("notification_tracking_log_id", logId)
    }

    private fun checkSelfUpdate(command: CommandEnvelope): JSONObject {
        val releaseApiUrl = command.params.optString("release_api_url")
        val manifestUrl = command.params.optString(
            "manifest_url",
            "https://raw.githubusercontent.com/var-gg/android-companion/main/update-manifest.json"
        )
        val current = appIdentity()
        val currentVersionName = current.optString("version_name")
        val currentVersionCode = current.optLong("version_code")

        val manifestResult = runCatching { fetchJson(manifestUrl) }.getOrNull()
        if (manifestResult != null) {
            val latestVersionName = manifestResult.optString("version_name", manifestResult.optString("tag_name"))
            val latestVersionCode = manifestResult.optLong("version_code", currentVersionCode)
            val minSupportedVersionCode = manifestResult.optLong("min_supported_version_code", 0L)
            val forceUpdate = manifestResult.optBoolean("force_update", false)
            val updateAvailable = when {
                latestVersionCode > 0L -> latestVersionCode > currentVersionCode
                latestVersionName.isNotBlank() -> latestVersionName != currentVersionName
                else -> false
            }
            val supported = minSupportedVersionCode <= 0L || currentVersionCode >= minSupportedVersionCode
            val apkUrl = manifestResult.optString("apk_url")
            val apkReachable = if (apkUrl.isNotBlank()) isUrlReachable(apkUrl) else false
            return okAction(command)
                .put("current_version", currentVersionName)
                .put("current_version_code", currentVersionCode)
                .put("latest_version", latestVersionName)
                .put("latest_version_code", latestVersionCode)
                .put("min_supported_version_code", minSupportedVersionCode)
                .put("force_update", forceUpdate)
                .put("supported", supported)
                .put("update_available", updateAvailable)
                .put("apk_url", apkUrl)
                .put("apk_reachable", apkReachable)
                .put("release_url", manifestResult.optString("release_url"))
                .put("manifest_url", manifestUrl)
                .put("notes", manifestResult.optString("notes"))
                .put("source", "manifest")
        }

        val resolvedReleaseApiUrl = if (releaseApiUrl.isBlank()) {
            "https://api.github.com/repos/var-gg/android-companion/releases/latest"
        } else {
            releaseApiUrl
        }
        val response = fetchJson(resolvedReleaseApiUrl)
        val latestTag = response.optString("tag_name")
        val assets = response.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        return okAction(command)
            .put("current_version", currentVersionName)
            .put("current_version_code", currentVersionCode)
            .put("latest_version", latestTag)
            .put("update_available", latestTag.isNotBlank() && latestTag != currentVersionName)
            .put("apk_url", apkUrl)
            .put("apk_reachable", apkUrl?.let { isUrlReachable(it) } ?: false)
            .put("release_url", response.optString("html_url"))
            .put("release_api_url", resolvedReleaseApiUrl)
            .put("force_update", false)
            .put("supported", true)
            .put("source", "github_release")
    }

    private fun downloadSelfUpdate(command: CommandEnvelope): JSONObject {
        val apkUrl = command.params.optString("apk_url")
        if (apkUrl.isBlank()) return jsonError(command, "missing_apk_url", "params.apk_url is required")
        val outputFile = File(context.getExternalFilesDir(null), "android-companion-update.apk")
        downloadFile(apkUrl, outputFile)
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return okAction(command)
            .put("apk_url", apkUrl)
            .put("downloaded_to", outputFile.absolutePath)
            .put("install_prompt", true)
    }

    private fun serializeLogEntry(
        entry: JSONObject,
        includeCommand: Boolean,
        includeResult: Boolean,
        includeUpload: Boolean
    ): JSONObject {
        val normalized = JSONObject()
            .put("log_id", entry.optString("log_id"))
            .put("timestamp", entry.optString("timestamp"))
            .put("action", entry.optString("action"))
            .put("request_id", entry.optString("request_id").ifBlank { JSONObject.NULL })
            .put("state", entry.optString("state"))
            .put("source", entry.optString("source").ifBlank { entry.optString("mode") })
            .put("ok", if (entry.has("ok")) entry.optBoolean("ok", false) else JSONObject.NULL)
            .put("remote_command_id", entry.optString("remote_command_id").ifBlank { JSONObject.NULL })
            .put("started_at", entry.optString("started_at").ifBlank { JSONObject.NULL })
            .put("finished_at", entry.optString("finished_at").ifBlank { JSONObject.NULL })
            .put("error", entry.optString("error").ifBlank { JSONObject.NULL })
            .put("upload_error", entry.optString("upload_error").ifBlank { JSONObject.NULL })
            .put("phase", entry.optString("phase").ifBlank { JSONObject.NULL })
            .put("detail", entry.optString("detail").ifBlank { JSONObject.NULL })
            .put("error_category", entry.optString("error_category").ifBlank { JSONObject.NULL })
            .put("error_reason", entry.optString("error_reason").ifBlank { JSONObject.NULL })
            .put("last_payload", copyOrNull(entry.optJSONObject("last_payload")))
            .put("phases", copyOrNull(entry.optJSONArray("phases")))
        if (includeCommand) normalized.put("command", copyOrNull(entry.optJSONObject("command")))
        if (includeResult) normalized.put("result", copyOrNull(entry.optJSONObject("result")))
        if (includeUpload) {
            normalized.put("upload_result", copyOrNull(entry.optJSONObject("upload_result")))
            normalized.put("poll_response", copyOrNull(entry.optJSONObject("poll_response")))
        }
        return normalized
    }

    private fun buildTraceStep(entry: JSONObject): JSONObject {
        val trace = JSONObject()
            .put("log_id", entry.optString("log_id"))
            .put("action", entry.optString("action"))
            .put("request_id", entry.optString("request_id").ifBlank { JSONObject.NULL })
            .put("source", entry.optString("source").ifBlank { entry.optString("mode") })
            .put("state", entry.optString("state"))
            .put("phase", entry.optString("phase").ifBlank { JSONObject.NULL })
            .put("ok", if (entry.has("ok")) entry.optBoolean("ok", false) else JSONObject.NULL)

        val timeline = JSONArray()
        addTimelineEvent(timeline, "received", entry.optString("timestamp"), JSONObject()
            .put("state", entry.optString("state"))
            .put("remote_command_id", entry.optString("remote_command_id").ifBlank { JSONObject.NULL })
        )
        addTimelineEvent(timeline, "started", entry.optString("started_at"), null)
        addTimelineEvent(timeline, "finished", entry.optString("finished_at"), JSONObject()
            .put("state", entry.optString("state"))
            .put("ok", if (entry.has("ok")) entry.optBoolean("ok", false) else JSONObject.NULL)
        )
        val phases = entry.optJSONArray("phases")
        if (phases != null) {
            for (i in 0 until phases.length()) {
                val phase = phases.optJSONObject(i) ?: continue
                addTimelineEvent(
                    timeline,
                    phase.optString("phase"),
                    phase.optString("timestamp"),
                    JSONObject(phase.toString()).apply { remove("phase"); remove("timestamp") }
                )
            }
        }
        trace.put("timeline", timeline)
        trace.put("command", copyOrNull(entry.optJSONObject("command")))
        trace.put("result", copyOrNull(entry.optJSONObject("result")))
        trace.put("upload", JSONObject()
            .put("upload_result", copyOrNull(entry.optJSONObject("upload_result")))
            .put("upload_error", entry.optString("upload_error").ifBlank { JSONObject.NULL })
            .put("poll_response", copyOrNull(entry.optJSONObject("poll_response")))
        )
        return trace
    }

    private fun addTimelineEvent(timeline: JSONArray, label: String, at: String?, detail: JSONObject?) {
        if (at.isNullOrBlank()) return
        val item = JSONObject()
            .put("event", label)
            .put("at", at)
        if (detail != null) item.put("detail", detail)
        timeline.put(item)
    }

    private fun summarizeLog(entry: JSONObject): JSONObject {
        return JSONObject()
            .put("log_id", entry.optString("log_id"))
            .put("action", entry.optString("action"))
            .put("request_id", entry.optString("request_id").ifBlank { JSONObject.NULL })
            .put("state", entry.optString("state"))
            .put("phase", entry.optString("phase").ifBlank { JSONObject.NULL })
            .put("ok", if (entry.has("ok")) entry.optBoolean("ok", false) else JSONObject.NULL)
            .put("timestamp", entry.optString("timestamp"))
            .put("finished_at", entry.optString("finished_at").ifBlank { JSONObject.NULL })
            .put("detail", entry.optString("detail").ifBlank { JSONObject.NULL })
            .put("error_category", entry.optString("error_category").ifBlank { JSONObject.NULL })
    }

    private fun copyOrNull(obj: JSONObject?): Any = obj?.let { JSONObject(it.toString()) } ?: JSONObject.NULL
    private fun copyOrNull(array: JSONArray?): Any = array?.let { JSONArray(it.toString()) } ?: JSONObject.NULL

    private fun epochToIso(value: Long): Any = if (value > 0L) Instant.ofEpochMilli(value).toString() else JSONObject.NULL

    private fun isUrlReachable(url: String): Boolean {
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()
            val code = connection.responseCode
            code in 200..399
        }.getOrDefault(false)
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.connect()
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun downloadFile(url: String, outputFile: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.connect()
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(outputFile).use { output -> input.copyTo(output) }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            packageManager.getPackageInfoCompat(packageName)
            true
        }.getOrDefault(false)
    }

    private fun notificationsAllowed(): Boolean {
        val snapshot = PermissionStatus.snapshot(context)
        return snapshot.notificationPermission && snapshot.notificationsEnabled
    }

    private fun ensureIntentDeliveryNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_INTENT_DELIVERY,
                "Android Companion intent delivery",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification-mediated execution path for open_intent commands"
            }
        )
    }

    private fun executionContextLabel(): String = when (context) {
        is Activity -> "activity"
        else -> context.javaClass.simpleName.ifBlank { "context" }.lowercase(Locale.US)
    }

    private fun computeNotificationId(logId: String): Int {
        return (logId.hashCode().absoluteValue % 100000) + 2000
    }

    private fun resolveActivityCompat(intent: Intent): ResolveInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private fun queryIntentActivitiesCompat(intent: Intent): JSONArray {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return JSONArray().apply { infos.forEach { put(describeResolveInfo(it)) } }
    }

    private fun describeResolveInfo(info: ResolveInfo): JSONObject {
        val activityInfo = info.activityInfo
        return JSONObject()
            .put("package", activityInfo?.packageName ?: "")
            .put("name", activityInfo?.name ?: "")
            .put("exported", activityInfo?.exported ?: false)
    }

    private fun deviceIdentity(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("sdk_int", Build.VERSION.SDK_INT)

    private fun okAction(command: CommandEnvelope): JSONObject = JSONObject()
        .put("ok", true)
        .put("action", command.action)
        .put("request_id", command.requestId)
        .put("timestamp", Instant.now().toString())

    private fun jsonError(command: CommandEnvelope, code: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("action", command.action)
        .put("request_id", command.requestId)
        .put("error", JSONObject().put("code", code).put("message", message))
        .put("timestamp", Instant.now().toString())

}

private const val NOTIFICATION_CHANNEL_INTENT_DELIVERY = "intent_delivery"

private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }
}
