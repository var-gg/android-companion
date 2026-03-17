package ai.openclaw.androidcompanion

import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ai.openclaw.androidcompanion.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.executeButton.setOnClickListener {
            executeCommand(binding.commandInput.text?.toString().orEmpty())
        }

        binding.samplePingButton.setOnClickListener {
            binding.commandInput.setText("{\n  \"action\": \"health_ping\"\n}")
        }

        binding.sampleListAppsButton.setOnClickListener {
            binding.commandInput.setText("{\n  \"action\": \"list_installed_apps\",\n  \"include_system\": false\n}")
        }

        binding.sampleUpdateButton.setOnClickListener {
            binding.commandInput.setText("{\n  \"action\": \"check_self_update\",\n  \"release_api_url\": \"https://api.github.com/repos/OWNER/REPO/releases/latest\"\n}")
        }
    }

    private fun executeCommand(raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            renderResult(jsonError("invalid_json", e.message ?: "Malformed JSON input"))
            return
        }

        thread {
            val result = runCommand(json)
            runOnUiThread { renderResult(result) }
        }
    }

    private fun renderResult(result: JSONObject) {
        binding.resultOutput.text = result.toString(2)
    }

    private fun runCommand(command: JSONObject): JSONObject {
        return try {
            when (command.getString("action")) {
                "health_ping" -> healthPing()
                "device_info" -> deviceInfo()
                "open_url" -> openUrl(command)
                "launch_app" -> launchApp(command)
                "list_installed_apps" -> listInstalledApps(command)
                "usage_stats" -> usageStats(command)
                "uninstall_app" -> uninstallApp(command)
                "open_intent", "test_intent" -> openIntent(command)
                "check_self_update" -> checkSelfUpdate(command)
                "download_self_update" -> downloadSelfUpdate(command)
                else -> jsonError("unsupported_action", "Unsupported action: ${command.optString("action")}")
            }
        } catch (e: Exception) {
            jsonError("execution_failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun healthPing(): JSONObject = JSONObject()
        .put("ok", true)
        .put("action", "health_ping")
        .put("timestamp", Instant.now().toString())
        .put("device", deviceIdentity())

    private fun deviceInfo(): JSONObject = JSONObject()
        .put("ok", true)
        .put("action", "device_info")
        .put("device", JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("package_name", packageName)
            .put("version_name", packageManager.getPackageInfoCompat(packageName).versionName)
            .put("version_code", packageManager.getPackageInfoCompat(packageName).longVersionCode)
        )

    private fun openUrl(command: JSONObject): JSONObject {
        val url = command.optString("url")
        if (url.isBlank()) return jsonError("missing_url", "url is required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return okAction("open_url").put("url", url)
    }

    private fun launchApp(command: JSONObject): JSONObject {
        val targetPackage = command.optString("package")
        if (targetPackage.isBlank()) return jsonError("missing_package", "package is required")
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: return jsonError("app_not_found", "No launch intent for $targetPackage")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return okAction("launch_app").put("package", targetPackage)
    }

    private fun listInstalledApps(command: JSONObject): JSONObject {
        val includeSystem = command.optBoolean("include_system", false)
        val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        val apps = JSONArray()
        packages.sortedBy { it.packageName }.forEach { pkg ->
            val systemApp = pkg.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && systemApp) return@forEach
            apps.put(JSONObject()
                .put("package", pkg.packageName)
                .put("label", packageManager.getApplicationLabel(pkg.applicationInfo!!).toString())
                .put("version_name", pkg.versionName ?: "")
                .put("version_code", pkg.longVersionCode)
                .put("system", systemApp)
            )
        }
        return okAction("list_installed_apps").put("count", apps.length()).put("apps", apps)
    }

    private fun usageStats(command: JSONObject): JSONObject {
        if (!hasUsageStatsPermission()) {
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Usage access required")
                    .setMessage("Allow usage access for Android Companion to read recent app activity.")
                    .setPositiveButton("Open settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            return jsonError("permission_required", "Grant PACKAGE_USAGE_STATS in system settings")
                .put("permission", "PACKAGE_USAGE_STATS")
                .put("settings_intent", Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }

        val hours = command.optLong("hours", 6L)
        val since = System.currentTimeMillis() - hours * 60L * 60L * 1000L
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, System.currentTimeMillis())
        val items = JSONArray()
        stats.sortedByDescending { it.lastTimeUsed }.forEach {
            items.put(JSONObject()
                .put("package", it.packageName)
                .put("last_time_used", Instant.ofEpochMilli(it.lastTimeUsed).toString())
                .put("total_time_foreground_ms", it.totalTimeInForeground)
            )
        }
        return okAction("usage_stats")
            .put("hours", hours)
            .put("count", items.length())
            .put("items", items)
    }

    private fun uninstallApp(command: JSONObject): JSONObject {
        val targetPackage = command.optString("package")
        if (targetPackage.isBlank()) return jsonError("missing_package", "package is required")
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$targetPackage")
        }
        startActivity(intent)
        return okAction("uninstall_app").put("package", targetPackage).put("mode", "user_confirmation_required")
    }

    private fun openIntent(command: JSONObject): JSONObject {
        val action = command.optString("intent_action", Intent.ACTION_VIEW)
        val data = command.optString("data", "")
        val packageName = command.optString("package", "")
        val className = command.optString("class", "")
        val extrasObject = command.optJSONObject("extras") ?: JSONObject()

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
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return jsonError("intent_not_resolved", e.message ?: "No activity resolved")
        }
        return okAction(command.getString("action"))
            .put("intent_action", action)
            .put("data", data)
            .put("package", packageName)
    }

    private fun checkSelfUpdate(command: JSONObject): JSONObject {
        val releaseApiUrl = command.optString("release_api_url")
        if (releaseApiUrl.isBlank()) return jsonError("missing_release_api_url", "release_api_url is required")
        val response = fetchJson(releaseApiUrl)
        val latestTag = response.optString("tag_name")
        val currentVersion = packageManager.getPackageInfoCompat(packageName).versionName ?: "0.0.0"
        val assets = response.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        return okAction("check_self_update")
            .put("current_version", currentVersion)
            .put("latest_version", latestTag)
            .put("update_available", latestTag.isNotBlank() && latestTag != currentVersion)
            .put("apk_url", apkUrl)
            .put("release_url", response.optString("html_url"))
    }

    private fun downloadSelfUpdate(command: JSONObject): JSONObject {
        val apkUrl = command.optString("apk_url")
        if (apkUrl.isBlank()) return jsonError("missing_apk_url", "apk_url is required")
        val outputFile = File(getExternalFilesDir(null), "android-companion-update.apk")
        downloadFile(apkUrl, outputFile)
        val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
        return okAction("download_self_update")
            .put("apk_url", apkUrl)
            .put("downloaded_to", outputFile.absolutePath)
            .put("install_prompt", true)
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
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
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun deviceIdentity(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("sdk_int", Build.VERSION.SDK_INT)

    private fun okAction(action: String): JSONObject = JSONObject()
        .put("ok", true)
        .put("action", action)
        .put("timestamp", Instant.now().toString())

    private fun jsonError(code: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
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
