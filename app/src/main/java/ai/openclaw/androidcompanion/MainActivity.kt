package ai.openclaw.androidcompanion

import android.app.AlertDialog
import android.app.AppOpsManager
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
import ai.openclaw.androidcompanion.capabilities.CapabilityHandlers
import ai.openclaw.androidcompanion.capabilities.CompanionExecutor
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import ai.openclaw.androidcompanion.databinding.ActivityMainBinding
import ai.openclaw.androidcompanion.logging.CommandLogStore
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

class MainActivity : AppCompatActivity(), CapabilityHandlers {
    private lateinit var binding: ActivityMainBinding
    private lateinit var executor: CompanionExecutor
    private lateinit var commandLogStore: CommandLogStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        executor = CompanionExecutor(this)
        commandLogStore = CommandLogStore(this)

        binding.executeButton.setOnClickListener {
            executeCommand(binding.commandInput.text?.toString().orEmpty())
        }

        binding.samplePingButton.setOnClickListener {
            binding.commandInput.setText(sampleHealthPing())
        }

        binding.sampleListAppsButton.setOnClickListener {
            binding.commandInput.setText(sampleListApps())
        }

        binding.sampleUpdateButton.setOnClickListener {
            binding.commandInput.setText(sampleSelfUpdateCheck())
        }

        renderRecentCommands()
    }

    private fun executeCommand(raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            renderResult(jsonError("invalid_json", e.message ?: "Malformed JSON input"))
            return
        }

        val envelope = CommandEnvelope.fromJson(json)
        if (envelope.action.isBlank()) {
            renderResult(jsonError("missing_action", "action is required"))
            return
        }

        thread {
            val result = runCatching { executor.execute(envelope) }
                .getOrElse { jsonError("execution_failed", it.message ?: it.javaClass.simpleName) }
            commandLogStore.append(
                JSONObject()
                    .put("timestamp", Instant.now().toString())
                    .put("action", envelope.action)
                    .put("request_id", envelope.requestId)
                    .put("ok", result.optBoolean("ok", false))
            )
            runOnUiThread {
                renderResult(result)
                renderRecentCommands()
            }
        }
    }

    private fun renderResult(result: JSONObject) {
        binding.resultOutput.text = result.toString(2)
    }

    private fun renderRecentCommands() {
        binding.recentLogsOutput.text = commandLogStore.readAll().toString(2)
    }

    override fun healthPing(command: CommandEnvelope): JSONObject = okAction(command)
        .put("device", deviceIdentity())
        .put("app", appIdentity())

    override fun deviceInfo(command: CommandEnvelope): JSONObject = okAction(command)
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

    override fun openUrl(command: CommandEnvelope): JSONObject {
        val url = command.params.optString("url")
        if (url.isBlank()) return jsonError(command, "missing_url", "params.url is required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return okAction(command).put("url", url)
    }

    override fun launchApp(command: CommandEnvelope): JSONObject {
        val targetPackage = command.params.optString("package")
        if (targetPackage.isBlank()) return jsonError(command, "missing_package", "params.package is required")
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: return jsonError(command, "app_not_found", "No launch intent for $targetPackage")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return okAction(command).put("package", targetPackage)
    }

    override fun listInstalledApps(command: CommandEnvelope): JSONObject {
        val includeSystem = command.params.optBoolean("include_system", false)
        val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        val apps = JSONArray()
        packages.sortedBy { it.packageName }.forEach { pkg ->
            val appInfo = pkg.applicationInfo ?: return@forEach
            val systemApp = appInfo.flags.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
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

    override fun usageStats(command: CommandEnvelope): JSONObject {
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
            return jsonError(command, "permission_required", "Grant PACKAGE_USAGE_STATS in system settings")
                .put("permission", "PACKAGE_USAGE_STATS")
                .put("settings_intent", Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }

        val hours = command.params.optLong("hours", 6L)
        val since = System.currentTimeMillis() - hours * 60L * 60L * 1000L
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
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
                    .put("last_time_used", Instant.ofEpochMilli(it.lastTimeUsed).toString())
                    .put("total_time_foreground_ms", it.totalTimeInForeground)
            )
        }
        return okAction(command)
            .put("hours", hours)
            .put("count", items.length())
            .put("items", items)
    }

    override fun uninstallApp(command: CommandEnvelope): JSONObject {
        val targetPackage = command.params.optString("package")
        if (targetPackage.isBlank()) return jsonError(command, "missing_package", "params.package is required")
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$targetPackage")
        }
        startActivity(intent)
        return okAction(command)
            .put("package", targetPackage)
            .put("mode", "user_confirmation_required")
    }

    override fun openIntent(command: CommandEnvelope): JSONObject {
        val action = command.params.optString("action", command.params.optString("intent_action", Intent.ACTION_VIEW))
        val data = command.params.optString("uri", command.params.optString("data", ""))
        val packageName = command.params.optString("package", "")
        val className = command.params.optString("class", "")
        val extrasObject = command.params.optJSONObject("extras") ?: JSONObject()

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
            return jsonError(command, "intent_not_resolved", e.message ?: "No activity resolved")
        }
        return okAction(command)
            .put("intent_action", action)
            .put("data", data)
            .put("package", packageName)
    }

    override fun checkSelfUpdate(command: CommandEnvelope): JSONObject {
        val releaseApiUrl = command.params.optString(
            "release_api_url",
            "https://api.github.com/repos/var-gg/android-companion/releases/latest"
        )
        if (releaseApiUrl.isBlank()) return jsonError(command, "missing_release_api_url", "params.release_api_url is required")
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
        return okAction(command)
            .put("current_version", currentVersion)
            .put("latest_version", latestTag)
            .put("update_available", latestTag.isNotBlank() && latestTag != currentVersion)
            .put("apk_url", apkUrl)
            .put("release_url", response.optString("html_url"))
            .put("release_api_url", releaseApiUrl)
    }

    override fun downloadSelfUpdate(command: CommandEnvelope): JSONObject {
        val apkUrl = command.params.optString("apk_url")
        if (apkUrl.isBlank()) return jsonError(command, "missing_apk_url", "params.apk_url is required")
        val outputFile = File(getExternalFilesDir(null), "android-companion-update.apk")
        downloadFile(apkUrl, outputFile)
        val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
        return okAction(command)
            .put("apk_url", apkUrl)
            .put("downloaded_to", outputFile.absolutePath)
            .put("install_prompt", true)
    }

    override fun unsupported(command: CommandEnvelope): JSONObject {
        return jsonError(command, "unsupported_action", "Unsupported action: ${command.action}")
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
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun deviceIdentity(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("sdk_int", Build.VERSION.SDK_INT)

    private fun appIdentity(): JSONObject {
        val pkg = packageManager.getPackageInfoCompat(packageName)
        return JSONObject()
            .put("package_name", packageName)
            .put("version_name", pkg.versionName)
            .put("version_code", pkg.longVersionCode)
    }

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

    private fun jsonError(code: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))
        .put("timestamp", Instant.now().toString())

    private fun sampleHealthPing() = """
        {
          "action": "health_ping",
          "params": {}
        }
    """.trimIndent()

    private fun sampleListApps() = """
        {
          "action": "list_installed_apps",
          "params": {
            "include_system": false
          }
        }
    """.trimIndent()

    private fun sampleSelfUpdateCheck() = """
        {
          "action": "check_self_update",
          "params": {
            "release_api_url": "https://api.github.com/repos/var-gg/android-companion/releases/latest"
          }
        }
    """.trimIndent()
}

private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }
}
