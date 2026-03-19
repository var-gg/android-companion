package ai.openclaw.androidcompanion.capabilities

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale

class AndroidCapabilityEngine(
    private val context: Context
) {
    private val packageManager: PackageManager = context.packageManager

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
        val diagnostics = okAction(command)
            .put("intent_action", action)
            .put("data", data)
            .put("package", packageName)
            .put("class", className)
            .put("resolve_only", resolveOnly)
            .put("execution_context", "activity")
            .put("package_installed", packageInstalled)
            .put("resolve_activity", resolved?.let { describeResolveInfo(it) } ?: JSONObject.NULL)
            .put("matched_activities_count", matches.length())
            .put("matched_activities", matches)

        if (resolveOnly) {
            val canResolve = resolved != null || matches.length() > 0
            diagnostics.put("ok", canResolve && packageInstalled)
            if (!packageInstalled) {
                diagnostics.put("likely_block_reason", "package_not_installed")
            } else if (!canResolve) {
                diagnostics.put("likely_block_reason", "intent_not_resolved")
            } else if (command.params.optBoolean("background_launch_expected", false)) {
                diagnostics.put("likely_background_launch_blocked", true)
                diagnostics.put("likely_background_launch_message", "Intent resolves, but launching from a service/background context may still be blocked by Android background activity launch policy")
            }
            return diagnostics
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return diagnostics
                .put("ok", false)
                .put("error", JSONObject().put("code", "intent_not_resolved").put("message", e.message ?: "No activity resolved"))
        }
        return diagnostics.put("launched", true)
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

private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }
}
