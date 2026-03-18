package ai.openclaw.androidcompanion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.openclaw.androidcompanion.capabilities.AndroidCapabilityEngine
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import ai.openclaw.androidcompanion.databinding.ActivityMainBinding
import ai.openclaw.androidcompanion.logging.CommandLogStore
import ai.openclaw.androidcompanion.settings.LanguageSettings
import ai.openclaw.androidcompanion.settings.PermissionStatus
import ai.openclaw.androidcompanion.transport.RemotePollingService
import ai.openclaw.androidcompanion.transport.TransportConfig
import ai.openclaw.androidcompanion.transport.TransportConfigStore
import ai.openclaw.androidcompanion.update.UpdatePolicy
import ai.openclaw.androidcompanion.update.UpdatePolicyEvaluator
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: AndroidCapabilityEngine
    private lateinit var commandLogStore: CommandLogStore
    private lateinit var transportConfigStore: TransportConfigStore
    private var lastUpdatePolicy: UpdatePolicy? = null
    private var suppressLanguageChange = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        renderPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LanguageSettings.applySaved(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = AndroidCapabilityEngine(this)
        commandLogStore = CommandLogStore(this)
        transportConfigStore = TransportConfigStore(this)

        setupLanguageControls()
        setupPermissionControls()

        binding.executeButton.setOnClickListener {
            if (isBlockedBySoftForceUpdate()) return@setOnClickListener
            executeCommand(binding.commandInput.text?.toString().orEmpty())
        }
        binding.samplePingButton.setOnClickListener { binding.commandInput.setText(sampleHealthPing()) }
        binding.sampleListAppsButton.setOnClickListener { binding.commandInput.setText(sampleListApps()) }
        binding.sampleUpdateButton.setOnClickListener { binding.commandInput.setText(sampleSelfUpdateCheck()) }
        binding.checkUpdateButton.setOnClickListener { checkUpdateNow() }
        binding.updateNowButton.setOnClickListener { triggerUpdateNow() }

        binding.saveRemoteConfigButton.setOnClickListener {
            saveRemoteConfig()
            renderStatus(getString(R.string.status_remote_config_saved))
        }
        binding.startRemoteButton.setOnClickListener {
            if (isBlockedBySoftForceUpdate()) return@setOnClickListener
            saveRemoteConfig()
            RemotePollingService.start(this)
            renderStatus(getString(R.string.status_remote_started))
        }
        binding.stopRemoteButton.setOnClickListener {
            RemotePollingService.stop(this)
            renderStatus(getString(R.string.status_remote_stopped))
        }
        binding.registerRemoteButton.setOnClickListener {
            if (isBlockedBySoftForceUpdate()) return@setOnClickListener
            saveRemoteConfig()
            registerDeviceNow()
        }

        loadRemoteConfig()
        renderVersionInfo()
        renderPermissionStatus()
        renderRecentCommands()
        checkUpdateNow(silent = true)
    }

    override fun onResume() {
        super.onResume()
        renderPermissionStatus()
    }

    private fun setupLanguageControls() {
        suppressLanguageChange = true
        when (LanguageSettings.getSavedLanguage(this)) {
            LanguageSettings.LANGUAGE_KO -> binding.languageKoRadio.isChecked = true
            LanguageSettings.LANGUAGE_EN -> binding.languageEnRadio.isChecked = true
            else -> binding.languageSystemRadio.isChecked = true
        }
        suppressLanguageChange = false

        binding.languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressLanguageChange) return@setOnCheckedChangeListener
            val language = when (checkedId) {
                R.id.languageKoRadio -> LanguageSettings.LANGUAGE_KO
                R.id.languageEnRadio -> LanguageSettings.LANGUAGE_EN
                else -> LanguageSettings.LANGUAGE_SYSTEM
            }
            if (language != LanguageSettings.getSavedLanguage(this)) {
                LanguageSettings.setLanguage(this, language)
                recreate()
            }
        }
    }

    private fun setupPermissionControls() {
        binding.grantUsageAccessButton.setOnClickListener {
            PermissionStatus.openUsageAccessSettings(this)
        }
        binding.allowInstallsButton.setOnClickListener {
            PermissionStatus.openUnknownAppSourcesSettings(this)
        }
        binding.allowNotificationsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionStatus.openNotificationSettings(this)
            }
        }
        binding.batteryOptimizationButton.setOnClickListener {
            PermissionStatus.openBatteryOptimizationSettings(this)
        }
    }

    private fun renderPermissionStatus() {
        val snapshot = PermissionStatus.snapshot(this)
        val enabled = getString(R.string.permission_enabled)
        val disabled = getString(R.string.permission_disabled)
        val lines = listOf(
            getString(R.string.permission_status_format, getString(R.string.usage_access_label), if (snapshot.usageAccess) enabled else disabled),
            getString(R.string.permission_status_format, getString(R.string.install_unknown_apps_label), if (snapshot.installUnknownApps) enabled else disabled),
            getString(R.string.permission_status_format, getString(R.string.notifications_label), if (snapshot.notificationPermission) enabled else disabled),
            getString(R.string.permission_status_format, getString(R.string.battery_optimization_label), if (snapshot.ignoringBatteryOptimizations) enabled else disabled)
        )
        binding.permissionStatusOutput.text = lines.joinToString("\n")
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
            val result = engine.execute(envelope)
            if (envelope.action == "check_self_update" && result.optBoolean("ok", false)) {
                lastUpdatePolicy = UpdatePolicyEvaluator.fromResult(result)
            }
            commandLogStore.append(
                JSONObject()
                    .put("timestamp", Instant.now().toString())
                    .put("mode", "manual")
                    .put("action", envelope.action)
                    .put("request_id", envelope.requestId)
                    .put("ok", result.optBoolean("ok", false))
            )
            runOnUiThread {
                renderResult(result)
                renderRecentCommands()
                renderUpdateState(lastUpdatePolicy)
            }
        }
    }

    private fun registerDeviceNow() {
        val config = currentTransportConfig()
        if (config.baseUrl.isBlank()) {
            renderStatus(getString(R.string.status_base_url_required))
            return
        }
        thread {
            val result = runCatching {
                ai.openclaw.androidcompanion.transport.RemoteTransportClient(config)
                    .registerDevice(engine.execute(CommandEnvelope("device_info", JSONObject(), null)))
            }.getOrElse {
                JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName)
            }
            runOnUiThread { renderStatus(result.toString(2)) }
        }
    }

    private fun checkUpdateNow(silent: Boolean = false) {
        thread {
            val result = engine.execute(CommandEnvelope("check_self_update", JSONObject(), null))
            val policy = if (result.optBoolean("ok", false)) UpdatePolicyEvaluator.fromResult(result) else null
            lastUpdatePolicy = policy
            runOnUiThread {
                renderUpdateState(policy)
                if (!silent) {
                    binding.remoteStatusOutput.text = result.toString(2)
                }
                renderResult(result)
            }
        }
    }

    private fun triggerUpdateNow() {
        val policy = lastUpdatePolicy
        val apkUrl = policy?.apkUrl
        if (apkUrl.isNullOrBlank()) {
            renderStatus(getString(R.string.status_no_apk_url))
            return
        }
        thread {
            val result = engine.execute(
                CommandEnvelope(
                    "download_self_update",
                    JSONObject().put("apk_url", apkUrl),
                    null
                )
            )
            runOnUiThread {
                renderResult(result)
                renderStatus(getString(R.string.status_update_prompt_expected))
            }
        }
    }

    private fun isBlockedBySoftForceUpdate(): Boolean {
        val policy = lastUpdatePolicy ?: return false
        val blocked = !policy.supported || (policy.forceUpdate && policy.updateAvailable)
        if (blocked) {
            renderStatus(getString(R.string.status_update_required))
            renderUpdateState(policy)
        }
        return blocked
    }

    private fun saveRemoteConfig() {
        transportConfigStore.save(currentTransportConfig())
    }

    private fun currentTransportConfig(): TransportConfig {
        return TransportConfig(
            baseUrl = binding.remoteBaseUrlInput.text?.toString().orEmpty(),
            deviceId = binding.remoteDeviceIdInput.text?.toString().orEmpty(),
            token = binding.remoteTokenInput.text?.toString().orEmpty(),
            pollIntervalSeconds = binding.remotePollSecondsInput.text?.toString()?.toLongOrNull() ?: 10L
        )
    }

    private fun loadRemoteConfig() {
        val config = transportConfigStore.load()
        binding.remoteBaseUrlInput.setText(config.baseUrl)
        binding.remoteDeviceIdInput.setText(config.deviceId)
        binding.remoteTokenInput.setText(config.token)
        binding.remotePollSecondsInput.setText(config.pollIntervalSeconds.toString())
    }

    private fun renderVersionInfo() {
        val app = engine.appIdentity()
        binding.versionOutput.text = getString(
            R.string.current_version_format,
            app.optString("version_name"),
            app.optLong("version_code")
        )
    }

    private fun renderUpdateState(policy: UpdatePolicy?) {
        if (policy == null) {
            binding.updateStatusOutput.text = getString(R.string.update_status_unknown)
            binding.updateNowButton.isEnabled = false
            return
        }
        val lines = mutableListOf<String>()
        lines += getString(R.string.update_current, policy.currentVersionName, policy.currentVersionCode)
        lines += getString(
            R.string.update_latest,
            policy.latestVersionName,
            policy.latestVersionCode?.let { " ($it)" } ?: ""
        )
        lines += getString(R.string.update_available, policy.updateAvailable.toString())
        lines += getString(R.string.update_supported, policy.supported.toString())
        lines += getString(R.string.update_force, policy.forceUpdate.toString())
        policy.minSupportedVersionCode?.let { lines += getString(R.string.update_min_supported, it) }
        policy.apkUrl?.let { lines += getString(R.string.update_apk, it) }
        policy.notes?.let { lines += getString(R.string.update_notes, it) }
        binding.updateStatusOutput.text = lines.joinToString("\n")
        binding.updateNowButton.isEnabled = !policy.apkUrl.isNullOrBlank() && policy.updateAvailable

        val blocked = !policy.supported || (policy.forceUpdate && policy.updateAvailable)
        binding.executeButton.isEnabled = !blocked
        binding.startRemoteButton.isEnabled = true
        binding.registerRemoteButton.isEnabled = true
        binding.commandInput.isEnabled = !blocked
        if (blocked) {
            binding.remoteStatusOutput.text = getString(R.string.status_soft_force_active)
        }
    }

    private fun renderResult(result: JSONObject) {
        binding.resultOutput.text = result.toString(2)
    }

    private fun renderRecentCommands() {
        binding.recentLogsOutput.text = commandLogStore.readAll().toString(2)
    }

    private fun renderStatus(status: String) {
        binding.remoteStatusOutput.text = status
    }

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
            "manifest_url": "https://raw.githubusercontent.com/var-gg/android-companion/main/update-manifest.json"
          }
        }
    """.trimIndent()
}
