package ai.openclaw.androidcompanion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.client.android.Intents
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ai.openclaw.androidcompanion.capabilities.AndroidCapabilityEngine
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import ai.openclaw.androidcompanion.databinding.ActivityMainBinding
import ai.openclaw.androidcompanion.logging.CommandLogStore
import ai.openclaw.androidcompanion.pairing.PairingPayload
import ai.openclaw.androidcompanion.settings.LanguageSettings
import ai.openclaw.androidcompanion.settings.PermissionStatus
import ai.openclaw.androidcompanion.transport.RemotePollingService
import ai.openclaw.androidcompanion.transport.RemoteUiStateStore
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
    private lateinit var remoteUiStateStore: RemoteUiStateStore
    private var lastUpdatePolicy: UpdatePolicy? = null
    private var suppressLanguageChange = false
    private var advancedVisible = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        renderPermissionStatus()
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isBlank()) {
            renderStatus(getString(R.string.status_qr_scan_cancelled))
            return@registerForActivityResult
        }
        binding.pairingCodeInput.setText(raw)
        importPairingPayload(raw)
    }

    private val pairingImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            renderStatus(getString(R.string.status_pairing_image_cancelled))
            return@registerForActivityResult
        }
        importPairingFromImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LanguageSettings.applySaved(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = AndroidCapabilityEngine(this)
        commandLogStore = CommandLogStore(this)
        transportConfigStore = TransportConfigStore(this)
        remoteUiStateStore = RemoteUiStateStore(this)

        setupLanguageControls()
        setupPermissionControls()
        setupAdvancedSection()

        binding.executeButton.setOnClickListener {
            if (isBlockedBySoftForceUpdate()) return@setOnClickListener
            executeCommand(binding.commandInput.text?.toString().orEmpty())
        }
        binding.samplePingButton.setOnClickListener { binding.commandInput.setText(sampleHealthPing()) }
        binding.sampleListAppsButton.setOnClickListener { binding.commandInput.setText(sampleListApps()) }
        binding.sampleUpdateButton.setOnClickListener { binding.commandInput.setText(sampleSelfUpdateCheck()) }
        binding.checkUpdateButton.setOnClickListener { checkUpdateNow() }
        binding.updateNowButton.setOnClickListener { triggerUpdateNow() }

        binding.installTailscaleButton.setOnClickListener { installOrOpenTailscale(forceStore = true) }
        binding.openTailscaleButton.setOnClickListener { installOrOpenTailscale(forceStore = false) }
        binding.scanPairingQrButton.setOnClickListener { launchPairingQrScanner() }
        binding.importPairingImageButton.setOnClickListener { pairingImagePickerLauncher.launch("image/*") }
        binding.importPairingCodeButton.setOnClickListener { importPairingFromField() }
        binding.testRemoteConnectionButton.setOnClickListener {
            saveRemoteConfig()
            testRemoteConnection()
        }
        binding.connectionDetailsToggleButton.setOnClickListener {
            toggleConnectionDetails()
        }

        binding.saveRemoteConfigButton.setOnClickListener {
            saveRemoteConfig()
            renderStatus(getString(R.string.status_remote_config_saved))
        }
        binding.startRemoteButton.setOnClickListener {
            if (isBlockedBySoftForceUpdate()) return@setOnClickListener
            saveRemoteConfig()
            RemotePollingService.start(this)
            renderStatus(getString(R.string.status_remote_started))
            renderConnectionStatus()
        }
        binding.stopRemoteButton.setOnClickListener {
            RemotePollingService.stop(this)
            renderStatus(getString(R.string.status_remote_stopped))
            renderConnectionStatus()
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
        renderTailscaleStatus()
        renderConnectionStatus()
        renderConnectionDetailsVisibility()
        handlePairingIntent(intent)
        checkUpdateNow(silent = true)
    }

    override fun onResume() {
        super.onResume()
        renderPermissionStatus()
        renderTailscaleStatus()
        renderConnectionStatus()
        handlePairingIntent(intent)
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

    private fun setupAdvancedSection() {
        binding.advancedToggleButton.setOnClickListener {
            advancedVisible = !advancedVisible
            renderAdvancedSection()
        }
        renderAdvancedSection()
    }

    private fun renderAdvancedSection() {
        binding.advancedSection.visibility = if (advancedVisible) View.VISIBLE else View.GONE
        binding.advancedToggleButton.text = getString(
            if (advancedVisible) R.string.hide_advanced else R.string.show_advanced
        )
    }

    private fun toggleConnectionDetails() {
        binding.connectionDetailsSection.visibility =
            if (binding.connectionDetailsSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        renderConnectionDetailsVisibility()
    }

    private fun renderConnectionDetailsVisibility() {
        binding.connectionDetailsToggleButton.text = getString(
            if (binding.connectionDetailsSection.visibility == View.VISIBLE) {
                R.string.hide_connection_details
            } else {
                R.string.show_connection_details
            }
        )
    }

    private fun renderPermissionStatus() {
        val snapshot = PermissionStatus.snapshot(this)
        val missing = mutableListOf<String>()
        if (!snapshot.notificationPermission) missing += getString(R.string.notifications_label)
        if (!snapshot.ignoringBatteryOptimizations) missing += getString(R.string.battery_optimization_label)
        if (!snapshot.usageAccess) missing += getString(R.string.usage_access_label)
        if (!snapshot.installUnknownApps) missing += getString(R.string.install_unknown_apps_label)

        val lines = mutableListOf<String>()
        lines += if (missing.isEmpty()) {
            getString(R.string.setup_ready) + " — " + getString(R.string.setup_summary_ready)
        } else {
            getString(R.string.setup_needs_attention) + " — " + getString(
                R.string.setup_summary_needs_attention,
                missing.joinToString(", ")
            )
        }
        lines += ""
        lines += "• ${getString(R.string.notifications_label)}: ${if (snapshot.notificationPermission) getString(R.string.permission_enabled) else getString(R.string.permission_disabled)}"
        lines += "• ${getString(R.string.battery_optimization_label)}: ${if (snapshot.ignoringBatteryOptimizations) getString(R.string.permission_enabled) else getString(R.string.permission_disabled)}"
        lines += "• ${getString(R.string.usage_access_label)}: ${if (snapshot.usageAccess) getString(R.string.permission_enabled) else getString(R.string.permission_disabled)}"
        lines += "• ${getString(R.string.install_unknown_apps_label)}: ${if (snapshot.installUnknownApps) getString(R.string.permission_enabled) else getString(R.string.permission_disabled)}"
        binding.permissionStatusOutput.text = lines.joinToString("\n")
    }

    private fun renderTailscaleStatus() {
        val installed = isPackageInstalled(TAILSCALE_PACKAGE)
        binding.tailscaleStatusOutput.text = if (installed) {
            getString(R.string.tailscale_status_installed)
        } else {
            getString(R.string.tailscale_status_missing)
        }
        binding.openTailscaleButton.isEnabled = installed
    }

    private fun renderConnectionStatus() {
        val config = transportConfigStore.load()
        val state = remoteUiStateStore.load()
        val (title, detail) = when {
            config.baseUrl.isBlank() -> getString(R.string.connection_state_setup_required) to getString(R.string.connection_state_setup_required_detail)
            state.status == RemoteUiStateStore.STATUS_POLLING -> getString(R.string.connection_state_polling) to state.detail.ifBlank { getString(R.string.connection_state_polling_detail) }
            state.status == RemoteUiStateStore.STATUS_REGISTERED -> getString(R.string.connection_state_registered) to state.detail.ifBlank { getString(R.string.connection_state_registered_detail) }
            state.status == RemoteUiStateStore.STATUS_TEST_OK -> getString(R.string.connection_state_test_ok) to state.detail.ifBlank { getString(R.string.connection_state_test_ok_detail) }
            state.status == RemoteUiStateStore.STATUS_ERROR -> getString(R.string.connection_state_error) to state.detail.ifBlank { getString(R.string.connection_state_error_detail) }
            else -> getString(R.string.connection_state_disconnected) to getString(R.string.connection_state_disconnected_detail)
        }
        binding.remoteStatusOutput.text = "$title\n$detail"
    }

    private fun installOrOpenTailscale(forceStore: Boolean) {
        val installed = isPackageInstalled(TAILSCALE_PACKAGE)
        val intent = when {
            installed && !forceStore -> packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
        } ?: Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY_STORE_WEB_URL))

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY_STORE_WEB_URL)))
        }
    }

    private fun launchPairingQrScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.qr_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
        qrScanLauncher.launch(options)
    }

    private fun importPairingFromImage(uri: Uri) {
        thread {
            val result = runCatching {
                val raw = decodeQrTextFromImage(uri)
                if (raw.isBlank()) throw IllegalArgumentException(getString(R.string.status_no_qr_found_in_image))
                runOnUiThread {
                    binding.pairingCodeInput.setText(raw)
                    importPairingPayload(raw)
                }
            }.exceptionOrNull()
            if (result != null) {
                runOnUiThread {
                    renderStatus(getString(R.string.status_pairing_import_failed, result.message ?: result.javaClass.simpleName))
                }
            }
        }
    }

    private fun importPairingFromField() {
        val raw = binding.pairingCodeInput.text?.toString().orEmpty().trim()
        if (raw.isBlank()) {
            renderStatus(getString(R.string.status_pairing_code_required))
            return
        }
        importPairingPayload(raw)
    }

    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.dataString?.trim().orEmpty()
        if (!data.startsWith("acpair://", ignoreCase = true)) return
        importPairingPayload(data)
        val clearedIntent = Intent(intent)
        clearedIntent.data = null
        setIntent(clearedIntent)
    }

    private fun importPairingPayload(raw: String) {
        val payload = runCatching { PairingPayload.parse(raw) }.getOrElse {
            renderStatus(getString(R.string.status_pairing_import_failed, it.message ?: it.javaClass.simpleName))
            return
        }
        if (payload.isExpired()) {
            renderStatus(getString(R.string.status_pairing_expired))
            return
        }

        binding.remoteBaseUrlInput.setText(payload.transport.baseUrl)
        binding.remoteTokenInput.setText(payload.transport.token)
        binding.remotePollSecondsInput.setText(payload.transport.pollIntervalSeconds.toString())
        if (binding.remoteDeviceIdInput.text.isNullOrBlank() && payload.device.suggestedDeviceId.isNotBlank()) {
            binding.remoteDeviceIdInput.setText(payload.device.suggestedDeviceId)
        }
        binding.pairingCodeInput.setText(raw)
        saveRemoteConfig()
        remoteUiStateStore.set(RemoteUiStateStore.STATUS_SETUP_REQUIRED, "Pairing imported for ${payload.label}")
        renderStatus(getString(R.string.status_pairing_imported, payload.label, payload.transport.mode) + "\n" + payload.summary())
    }

    private fun decodeQrTextFromImage(uri: Uri): String {
        val source = ImageDecoder.createSource(contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        }.copy(Bitmap.Config.ARGB_8888, false)

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val binaryBitmap = BinaryBitmap(
            HybridBinarizer(
                RGBLuminanceSource(width, height, pixels)
            )
        )

        return try {
            MultiFormatReader().decode(binaryBitmap).text.orEmpty().trim()
        } catch (_: NotFoundException) {
            ""
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            true
        }.recoverCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
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
            runOnUiThread {
                remoteUiStateStore.set(RemoteUiStateStore.STATUS_REGISTERED, "Registered ${config.deviceId}")
                renderStatus(getString(R.string.connection_state_registered_detail))
                renderResult(result)
                renderConnectionStatus()
            }
        }
    }

    private fun testRemoteConnection() {
        val config = currentTransportConfig()
        if (config.baseUrl.isBlank()) {
            renderStatus(getString(R.string.status_base_url_required))
            return
        }
        thread {
            val result = runCatching {
                ai.openclaw.androidcompanion.transport.RemoteTransportClient(config).testConnection()
            }.getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("base_url", config.baseUrl)
                    .put("error", it.message ?: it.javaClass.simpleName)
            }
            runOnUiThread {
                if (result.optBoolean("ok", false)) {
                    remoteUiStateStore.set(RemoteUiStateStore.STATUS_TEST_OK, "Bridge responded at ${config.baseUrl}")
                    renderStatus(getString(R.string.connection_state_test_ok_detail))
                } else {
                    remoteUiStateStore.set(RemoteUiStateStore.STATUS_ERROR, result.optString("error"))
                    renderStatus(result.optString("error"))
                }
                renderResult(result)
                renderConnectionStatus()
            }
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
        val config = currentTransportConfig()
        transportConfigStore.save(config)
        if (config.baseUrl.isBlank()) {
            remoteUiStateStore.set(RemoteUiStateStore.STATUS_DISCONNECTED, "Add a remote base URL or import pairing first")
        }
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
        binding.statusMessageOutput.text = status
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

    companion object {
        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        private const val TAILSCALE_PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=com.tailscale.ipn"
    }
}
