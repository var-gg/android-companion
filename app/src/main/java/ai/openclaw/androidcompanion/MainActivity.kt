package ai.openclaw.androidcompanion

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ai.openclaw.androidcompanion.capabilities.AndroidCapabilityEngine
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import ai.openclaw.androidcompanion.databinding.ActivityMainBinding
import ai.openclaw.androidcompanion.logging.CommandLogStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = AndroidCapabilityEngine(this)
        commandLogStore = CommandLogStore(this)
        transportConfigStore = TransportConfigStore(this)

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
            renderStatus("Remote config saved")
        }
        binding.startRemoteButton.setOnClickListener {
            if (isBlockedBySoftForceUpdate()) return@setOnClickListener
            saveRemoteConfig()
            RemotePollingService.start(this)
            renderStatus("Remote polling service started")
        }
        binding.stopRemoteButton.setOnClickListener {
            RemotePollingService.stop(this)
            renderStatus("Remote polling service stopped")
        }
        binding.registerRemoteButton.setOnClickListener {
            if (isBlockedBySoftForceUpdate()) return@setOnClickListener
            saveRemoteConfig()
            registerDeviceNow()
        }

        loadRemoteConfig()
        renderVersionInfo()
        renderRecentCommands()
        checkUpdateNow(silent = true)
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
            renderStatus("Base URL required before registration")
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
            renderStatus("No APK URL available yet. Run check update first.")
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
                renderStatus("Update package downloaded; Android install prompt should appear.")
            }
        }
    }

    private fun isBlockedBySoftForceUpdate(): Boolean {
        val policy = lastUpdatePolicy ?: return false
        val blocked = !policy.supported || (policy.forceUpdate && policy.updateAvailable)
        if (blocked) {
            renderStatus("Update required before continuing. Use Check update / Update now.")
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
            pollIntervalSeconds = binding.remotePollSecondsInput.text?.toString()?.toLongOrNull() ?: 30L
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
        binding.versionOutput.text = "Current version: ${app.optString("version_name")} (${app.optLong("version_code")})"
    }

    private fun renderUpdateState(policy: UpdatePolicy?) {
        if (policy == null) {
            binding.updateStatusOutput.text = "Update status unknown"
            binding.updateNowButton.isEnabled = false
            return
        }
        val lines = mutableListOf<String>()
        lines += "Current: ${policy.currentVersionName} (${policy.currentVersionCode})"
        lines += "Latest: ${policy.latestVersionName}${policy.latestVersionCode?.let { " ($it)" } ?: ""}"
        lines += "Update available: ${policy.updateAvailable}"
        lines += "Supported: ${policy.supported}"
        lines += "Force update: ${policy.forceUpdate}"
        policy.minSupportedVersionCode?.let { lines += "Min supported versionCode: $it" }
        policy.apkUrl?.let { lines += "APK: $it" }
        policy.notes?.let { lines += "Notes: $it" }
        binding.updateStatusOutput.text = lines.joinToString("\n")
        binding.updateNowButton.isEnabled = !policy.apkUrl.isNullOrBlank() && policy.updateAvailable

        val blocked = !policy.supported || (policy.forceUpdate && policy.updateAvailable)
        binding.executeButton.isEnabled = !blocked
        binding.startRemoteButton.isEnabled = !blocked
        binding.registerRemoteButton.isEnabled = !blocked
        binding.commandInput.isEnabled = !blocked
        if (blocked) {
            binding.remoteStatusOutput.text = "Soft-force update active: update required before command execution."
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
