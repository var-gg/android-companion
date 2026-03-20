package ai.openclaw.androidcompanion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent as AndroidIntent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.openclaw.androidcompanion.capabilities.AndroidCapabilityEngine
import ai.openclaw.androidcompanion.contract.CommandEnvelope
import ai.openclaw.androidcompanion.databinding.ActivityMainBinding
import ai.openclaw.androidcompanion.logging.CommandLogStore
import ai.openclaw.androidcompanion.pairing.PairingPayload
import ai.openclaw.androidcompanion.settings.LanguageSettings
import ai.openclaw.androidcompanion.settings.PermissionStatus
import ai.openclaw.androidcompanion.transport.RemotePollingService
import ai.openclaw.androidcompanion.transport.RemoteTransportClient
import ai.openclaw.androidcompanion.transport.RemoteUiStateStore
import ai.openclaw.androidcompanion.transport.TransportConfig
import ai.openclaw.androidcompanion.transport.TransportConfigStore
import ai.openclaw.androidcompanion.update.UpdatePolicy
import ai.openclaw.androidcompanion.update.UpdatePolicyEvaluator
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.client.android.Intents
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
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
    private var selectedLogId: String? = null
    private var currentSection = SECTION_HOME
    private var lastLogsRefreshAt: Instant? = null

    private val logPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { renderRecentCommands() } }
    private val remoteStatePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { renderConnectionStatus() } }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderPermissionStatus() }
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isBlank()) {
            renderStatus(getString(R.string.status_qr_scan_cancelled))
            return@registerForActivityResult
        }
        binding.pairingCodeInput.setText(raw)
        importPairingPayload(raw)
    }
    private val pairingImagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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

        setupSections()
        setupLanguageControls()
        setupPermissionControls()
        setupButtons()

        loadRemoteConfig()
        renderVersionInfo()
        renderPermissionStatus()
        renderRecentCommands()
        renderTailscaleStatus()
        renderConnectionStatus()
        handlePairingIntent(intent)
        showSection(SECTION_HOME)
        checkUpdateNow(silent = true)
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("command_logs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(logPrefsListener)
        getSharedPreferences("remote_ui_state", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(remoteStatePrefsListener)
    }

    override fun onStop() {
        getSharedPreferences("command_logs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(logPrefsListener)
        getSharedPreferences("remote_ui_state", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(remoteStatePrefsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        renderPermissionStatus()
        renderTailscaleStatus()
        renderConnectionStatus()
        renderRecentCommands()
        handlePairingIntent(intent)
    }

    private fun setupSections() {
        binding.navHomeButton.setOnClickListener { showSection(SECTION_HOME) }
        binding.navConnectButton.setOnClickListener { showSection(SECTION_CONNECT) }
        binding.navLogsButton.setOnClickListener { showSection(SECTION_LOGS) }
        binding.navOpsButton.setOnClickListener { showSection(SECTION_OPS) }
    }

    private fun showSection(section: String) {
        currentSection = section
        binding.homeSection.visibility = if (section == SECTION_HOME) View.VISIBLE else View.GONE
        binding.connectSection.visibility = if (section == SECTION_CONNECT) View.VISIBLE else View.GONE
        binding.logsSection.visibility = if (section == SECTION_LOGS) View.VISIBLE else View.GONE
        binding.opsSection.visibility = if (section == SECTION_OPS) View.VISIBLE else View.GONE
        listOf(binding.navHomeButton, binding.navConnectButton, binding.navLogsButton, binding.navOpsButton).forEach { it.alpha = 0.65f }
        when (section) {
            SECTION_HOME -> binding.navHomeButton.alpha = 1f
            SECTION_CONNECT -> binding.navConnectButton.alpha = 1f
            SECTION_LOGS -> binding.navLogsButton.alpha = 1f
            SECTION_OPS -> binding.navOpsButton.alpha = 1f
        }
    }

    private fun setupButtons() {
        binding.executeButton.setOnClickListener { if (!isBlockedBySoftForceUpdate()) executeCommand(binding.commandInput.text?.toString().orEmpty()) }
        binding.samplePingButton.setOnClickListener { binding.commandInput.setText(sampleHealthPing()) }
        binding.sampleListAppsButton.setOnClickListener { binding.commandInput.setText(sampleListApps()) }
        binding.sampleUpdateButton.setOnClickListener { binding.commandInput.setText(sampleSelfUpdateCheck()) }
        binding.checkUpdateButton.setOnClickListener { checkUpdateNow() }
        binding.updateNowButton.setOnClickListener { triggerUpdateNow() }
        binding.installTailscaleButton.setOnClickListener { installOrOpenTailscale(true) }
        binding.openTailscaleButton.setOnClickListener { installOrOpenTailscale(false) }
        binding.scanPairingQrButton.setOnClickListener { launchPairingQrScanner() }
        binding.importPairingImageButton.setOnClickListener { pairingImagePickerLauncher.launch("image/*") }
        binding.importPairingCodeButton.setOnClickListener { importPairingFromField() }
        binding.testRemoteConnectionButton.setOnClickListener { saveRemoteConfig(); testRemoteConnection() }
        binding.saveRemoteConfigButton.setOnClickListener { saveRemoteConfig(); renderStatus(getString(R.string.status_remote_config_saved)) }
        binding.refreshLogsButton.setOnClickListener { renderRecentCommands(manualRefresh = true) }
        binding.clearLogsButton.setOnClickListener { commandLogStore.clear(); selectedLogId = null; renderRecentCommands(manualRefresh = true); renderStatus(getString(R.string.status_logs_cleared)) }
        binding.loadLogToEditorButton.setOnClickListener { loadSelectedLogToEditor() }
        binding.rerunLogButton.setOnClickListener { rerunSelectedLog() }
        binding.replayDirectButton.setOnClickListener { replaySelectedLog(ReplayMode.DIRECT) }
        binding.replayNotifyButton.setOnClickListener { replaySelectedLog(ReplayMode.NOTIFY) }
        binding.replayViewButton.setOnClickListener { replaySelectedLog(ReplayMode.VIEW) }
        binding.startRemoteButton.setOnClickListener { if (!isBlockedBySoftForceUpdate()) { saveRemoteConfig(); RemotePollingService.start(this); renderStatus(getString(R.string.status_remote_started)); renderConnectionStatus() } }
        binding.stopRemoteButton.setOnClickListener { RemotePollingService.stop(this); renderStatus(getString(R.string.status_remote_stopped)); renderConnectionStatus() }
        binding.registerRemoteButton.setOnClickListener { if (!isBlockedBySoftForceUpdate()) { saveRemoteConfig(); registerDeviceNow() } }
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
        binding.grantUsageAccessButton.setOnClickListener { openSettingsOrExplain { PermissionStatus.openUsageAccessSettings(this) } }
        binding.allowInstallsButton.setOnClickListener { openSettingsOrExplain { PermissionStatus.openUnknownAppSourcesSettings(this) } }
        binding.allowNotificationsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSettingsOrExplain { PermissionStatus.openNotificationSettings(this) }
            }
        }
        binding.batteryOptimizationButton.setOnClickListener { openSettingsOrExplain { PermissionStatus.openBatteryOptimizationRequest(this) } }
        binding.openBatterySettingsButton.setOnClickListener { openSettingsOrExplain { PermissionStatus.openBatteryOptimizationSettings(this) } }
        binding.openAppDetailsButton.setOnClickListener { openSettingsOrExplain { PermissionStatus.openAppDetailsSettings(this) } }
    }

    private fun renderPermissionStatus() {
        val snapshot = PermissionStatus.snapshot(this)
        val notificationsReady = snapshot.notificationPermission && snapshot.notificationsEnabled
        val missing = mutableListOf<String>()
        if (!notificationsReady) missing += getString(R.string.notifications_label)
        if (!snapshot.ignoringBatteryOptimizations) missing += getString(R.string.battery_optimization_label)
        if (!snapshot.usageAccess) missing += getString(R.string.usage_access_label)
        if (!snapshot.installUnknownApps) missing += getString(R.string.install_unknown_apps_label)
        val lines = mutableListOf<String>()
        lines += if (missing.isEmpty()) getString(R.string.setup_ready) + " — " + getString(R.string.setup_summary_ready) else getString(R.string.setup_needs_attention) + " — " + getString(R.string.setup_summary_needs_attention, missing.joinToString(", "))
        lines += ""
        lines += formatChecklistLine(getString(R.string.readiness_item_notifications), notificationsReady)
        lines += formatChecklistLine(getString(R.string.readiness_item_battery), snapshot.ignoringBatteryOptimizations)
        lines += formatChecklistLine(getString(R.string.readiness_item_usage), snapshot.usageAccess)
        lines += formatChecklistLine(getString(R.string.readiness_item_installs), snapshot.installUnknownApps)
        binding.permissionStatusOutput.text = lines.joinToString("\n")
        binding.readinessDiagnosticsOutput.text = listOf(
            getString(R.string.readiness_diag_background_launch),
            getString(R.string.readiness_diag_notifications, readinessState(notificationsReady)),
            getString(R.string.readiness_diag_battery, readinessState(snapshot.ignoringBatteryOptimizations)),
            getString(R.string.readiness_diag_usage, readinessState(snapshot.usageAccess)),
            getString(R.string.readiness_diag_installs, readinessState(snapshot.installUnknownApps))
        ).joinToString("\n")
        binding.oemFallbackOutput.text = getString(R.string.oem_fallback_copy)
    }

    private fun renderTailscaleStatus() {
        val installed = isPackageInstalled(TAILSCALE_PACKAGE)
        binding.tailscaleStatusOutput.text = if (installed) getString(R.string.tailscale_status_installed) else getString(R.string.tailscale_status_missing)
        binding.openTailscaleButton.isEnabled = installed
    }

    private fun renderConnectionStatus() {
        val config = transportConfigStore.load()
        val state = remoteUiStateStore.load()
        val statusHeader = when {
            config.baseUrl.isBlank() -> getString(R.string.connection_state_setup_required)
            state.status == RemoteUiStateStore.STATUS_ERROR -> getString(R.string.connection_state_error)
            state.status == RemoteUiStateStore.STATUS_REGISTERED -> getString(R.string.connection_state_registered)
            state.status == RemoteUiStateStore.STATUS_TEST_OK -> getString(R.string.connection_state_test_ok)
            state.status == RemoteUiStateStore.STATUS_POLLING -> getString(R.string.connection_state_polling)
            else -> getString(R.string.connection_state_disconnected)
        }
        binding.remoteStatusOutput.text = buildString {
            appendLine(statusHeader)
            appendLine(state.detail.ifBlank { getString(R.string.connection_state_disconnected_detail) })
            appendLine()
            appendLine("service_running: ${state.serviceRunning}")
            appendLine("app_version: ${state.appVersionName} (${state.appVersionCode})")
            appendLine("last_poll_started: ${formatEpoch(state.lastPollStartedAt)}")
            appendLine("last_poll_succeeded: ${formatEpoch(state.lastPollSucceededAt)}")
            appendLine("last_poll_failed: ${formatEpoch(state.lastPollFailedAt)}")
            appendLine("last_heartbeat: ${formatEpoch(state.lastHeartbeatAt)}")
            appendLine("last_command_fetched: ${formatEpoch(state.lastCommandFetchedAt)}")
            appendLine("last_command_delivered: ${formatEpoch(state.lastCommandDeliveredAt)}")
            appendLine("last_command_executed: ${formatEpoch(state.lastCommandExecutedAt)}")
            appendLine("last_command_uploaded: ${formatEpoch(state.lastCommandUploadedAt)}")
            appendLine("last_command_action: ${state.lastCommandAction.ifBlank { "-" }}")
            appendLine("last_command_id: ${state.lastCommandId.ifBlank { "-" }}")
            appendLine("last_error_category: ${state.lastErrorCategory.ifBlank { "-" }}")
            append("last_error_reason: ${state.lastErrorReason.ifBlank { "-" }}")
        }
    }

    private fun executeCommand(raw: String) {
        val json = try { JSONObject(raw) } catch (e: JSONException) { renderResult(jsonError("invalid_json", e.message ?: "Malformed JSON input")); return }
        executeCommandJson(json)
    }

    private fun executeCommandJson(json: JSONObject) {
        val envelope = CommandEnvelope.fromJson(json)
        if (envelope.action.isBlank()) {
            renderResult(jsonError("missing_action", "action is required"))
            return
        }
        val log = commandLogStore.createManualLog(json, envelope.action, envelope.requestId)
        val logId = log.optString("log_id")
        commandLogStore.markPhase(logId, CommandLogStore.PHASE_DELIVERED, CommandLogStore.STATE_DELIVERED, "Manual command delivered to executor", true)
        commandLogStore.markPhase(logId, CommandLogStore.PHASE_EXECUTING, CommandLogStore.STATE_EXECUTING, "Executor running", true)
        thread {
            val result = runCatching { engine.execute(envelope) }
            result.onSuccess { executionResult ->
                if (envelope.action == "check_self_update" && executionResult.optBoolean("ok", false)) lastUpdatePolicy = UpdatePolicyEvaluator.fromResult(executionResult)
                commandLogStore.attachResult(logId, executionResult, executionResult.optBoolean("ok", false))
                commandLogStore.markPhase(logId, CommandLogStore.PHASE_EXECUTED, CommandLogStore.STATE_EXECUTED, "Execution finished", executionResult.optBoolean("ok", false), payload = executionResult)
                runOnUiThread { selectedLogId = logId; renderResult(executionResult); renderRecentCommands(); renderUpdateState(lastUpdatePolicy) }
            }.onFailure { error ->
                commandLogStore.markPhase(logId, CommandLogStore.PHASE_FAILED, CommandLogStore.STATE_FAILED, "Execution failed", false, "execution", error.message ?: error.javaClass.simpleName)
                val failure = jsonError("execution_failed", error.message ?: error.javaClass.simpleName)
                runOnUiThread { selectedLogId = logId; renderResult(failure); renderRecentCommands() }
            }
        }
    }

    private fun renderRecentCommands(manualRefresh: Boolean = false) {
        lastLogsRefreshAt = Instant.now()
        val logs = commandLogStore.readAll()
        binding.recentLogsList.removeAllViews()
        if (logs.length() == 0) {
            binding.recentLogsList.addView(TextView(this).apply { text = getString(R.string.logs_empty); setPadding(16, 16, 16, 16) })
            binding.logSummaryOutput.text = getString(R.string.log_summary_empty)
            binding.logDetailOutput.text = getString(R.string.log_detail_empty)
            setReplayButtonsEnabled(false, false)
            selectedLogId = null
            return
        }
        val resolvedIndex = findSelectedLogIndex(logs)
        selectedLogId = logs.optJSONObject(resolvedIndex)?.optString("log_id")
        for (i in 0 until logs.length()) {
            val entry = logs.optJSONObject(i) ?: continue
            val isSelected = i == resolvedIndex
            binding.recentLogsList.addView(TextView(this).apply {
                text = formatLogRow(entry, isSelected, i)
                setPadding(20, 16, 20, 16)
                setBackgroundResource(if (isSelected) R.drawable.log_row_selected_background else R.drawable.log_row_background)
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setOnClickListener { selectedLogId = entry.optString("log_id"); renderRecentCommands() }
            })
        }
        val selectedEntry = logs.optJSONObject(resolvedIndex)
        binding.logSummaryOutput.text = buildLogSummary(logs, resolvedIndex, selectedEntry, manualRefresh)
        renderLogDetail(selectedEntry)
    }

    private fun formatLogRow(entry: JSONObject, selected: Boolean, index: Int): String {
        val phases = entry.optJSONArray("phases")?.let { summarizePhases(it) }.orEmpty()
        return buildString {
            append(if (selected) "SELECTED" else "LOG")
            append(" #")
            append(index + 1)
            append("  ")
            append(entry.optString("action", "unknown"))
            append("\nstarted=")
            append(entry.optString("started_at").ifBlank { entry.optString("timestamp") })
            append("\nstate=")
            append(entry.optString("state"))
            append("  source=")
            append(entry.optString("source"))
            append("  path=")
            append(inferExecutionPath(entry))
            append("\ncommand_id=")
            append(entry.optString("remote_command_id").ifBlank { "-" })
            append("  request_id=")
            append(entry.optString("request_id").ifBlank { "-" })
            entry.optString("detail").takeIf { it.isNotBlank() }?.let {
                append("\ndetail=")
                append(it)
            }
            if (phases.isNotBlank()) {
                append("\nphases=")
                append(phases)
            }
        }
    }

    private fun renderLogDetail(entry: JSONObject?) {
        if (entry == null) {
            binding.logSummaryOutput.text = getString(R.string.log_summary_empty)
            binding.logDetailOutput.text = getString(R.string.log_detail_empty)
            setReplayButtonsEnabled(false, false)
            return
        }
        val command = entry.optJSONObject("command")
        val supportsIntentReplay = command?.optString("action") == "open_intent"
        val lines = mutableListOf(
            "log_id: ${entry.optString("log_id")}",
            "timestamp: ${entry.optString("timestamp")}",
            "started_at: ${entry.optString("started_at").ifBlank { "-" }}",
            "finished_at: ${entry.optString("finished_at").ifBlank { "-" }}",
            "source: ${entry.optString("source")}",
            "execution_path: ${inferExecutionPath(entry)}",
            "state: ${entry.optString("state")}",
            "phase: ${entry.optString("phase")}",
            "action: ${entry.optString("action")}",
            "request_id: ${entry.optString("request_id").ifBlank { "-" }}",
            "remote_command_id: ${entry.optString("remote_command_id").ifBlank { "-" }}",
            "detail: ${entry.optString("detail").ifBlank { "-" }}",
            "error_category: ${entry.optString("error_category").ifBlank { "-" }}",
            "error_reason: ${entry.optString("error_reason").ifBlank { "-" }}"
        )
        entry.optJSONObject("last_payload")?.let { lines += ""; lines += "last_payload:"; lines += it.toString(2) }
        entry.optJSONArray("phases")?.let { phases ->
            lines += ""
            lines += "phase_timeline:"
            for (i in 0 until phases.length()) {
                val phase = phases.optJSONObject(i) ?: continue
                lines += "- ${phase.optString("timestamp")}  ${phase.optString("phase")}  ok=${phase.opt("ok") ?: "-"}  detail=${phase.optString("detail").ifBlank { "-" }}"
                phase.optString("error_reason").takeIf { it.isNotBlank() }?.let { lines += "  error_reason: $it" }
                phase.optJSONObject("payload")?.let { lines += "  payload: ${it.toString()}" }
            }
        }
        command?.let { lines += ""; lines += "command:"; lines += it.toString(2) }
        entry.optJSONObject("result")?.let { lines += ""; lines += "result:"; lines += it.toString(2) }
        entry.optJSONObject("upload_result")?.let { lines += ""; lines += "upload_result:"; lines += it.toString(2) }
        binding.logDetailOutput.text = lines.joinToString("\n")
        setReplayButtonsEnabled(command != null, supportsIntentReplay)
    }

    private fun buildLogSummary(logs: JSONArray, resolvedIndex: Int, selectedEntry: JSONObject?, manualRefresh: Boolean): String {
        val selectedLabel = selectedEntry?.optString("log_id")?.ifBlank { "-" } ?: "-"
        return buildString {
            appendLine("stored_logs: ${logs.length()} / ${CommandLogStore.MAX_ITEMS}")
            appendLine("auto_refresh: active while app is visible")
            appendLine("refresh_reason: ${if (manualRefresh) "manual_button" else "ui_or_log_change"}")
            appendLine("last_refresh: ${lastLogsRefreshAt?.toString() ?: "-"}")
            appendLine("selected_index: ${resolvedIndex + 1}")
            append("selected_log: $selectedLabel")
        }
    }

    private fun inferExecutionPath(entry: JSONObject): String {
        val phases = entry.optJSONArray("phases") ?: JSONArray()
        var sawNotification = false
        var sawTap = false
        for (i in 0 until phases.length()) {
            val phase = phases.optJSONObject(i)?.optString("phase").orEmpty()
            if (phase.contains("notification")) sawNotification = true
            if (phase.contains("tapped") || phase.contains("launch_attempted")) sawTap = true
        }
        return when {
            sawNotification && sawTap -> "notification_tap_proxy"
            sawNotification -> "notification_pending"
            entry.optString("source") == "manual_ui" -> "manual_ui"
            entry.optString("source") == "remote_service" -> "remote_service"
            else -> "standard"
        }
    }

    private fun selectedLogEntry(): JSONObject? {
        val selectedId = selectedLogId ?: return null
        val all = commandLogStore.readAll()
        for (i in 0 until all.length()) {
            val entry = all.optJSONObject(i) ?: continue
            if (entry.optString("log_id") == selectedId) return entry
        }
        return null
    }

    private fun setReplayButtonsEnabled(hasCommand: Boolean, supportsIntentReplay: Boolean) {
        binding.loadLogToEditorButton.isEnabled = hasCommand
        binding.rerunLogButton.isEnabled = hasCommand
        binding.replayDirectButton.isEnabled = supportsIntentReplay
        binding.replayNotifyButton.isEnabled = supportsIntentReplay
        binding.replayViewButton.isEnabled = supportsIntentReplay
    }

    private fun loadSelectedLogToEditor() {
        val command = selectedLogEntry()?.optJSONObject("command")
        if (command == null) {
            renderStatus(getString(R.string.status_log_missing_command))
            return
        }
        binding.commandInput.setText(command.toString(2))
        showSection(SECTION_OPS)
        renderStatus(getString(R.string.status_log_loaded_to_editor, command.optString("action", "command")))
    }

    private fun replaySelectedLog(mode: ReplayMode? = null) {
        val command = selectedLogEntry()?.optJSONObject("command")
        if (command == null) {
            renderStatus(getString(R.string.status_log_missing_command))
            return
        }
        val replayCommand = when (mode) {
            null -> JSONObject(command.toString())
            ReplayMode.DIRECT -> buildReplayCommand(command, "direct")
            ReplayMode.NOTIFY -> buildReplayCommand(command, "notify")
            ReplayMode.VIEW -> buildReplayViewCommand(command)
        }
        if (replayCommand == null) {
            renderStatus(getString(R.string.status_replay_not_supported, mode?.label ?: "stored"))
            return
        }
        binding.commandInput.setText(replayCommand.toString(2))
        showSection(SECTION_OPS)
        renderStatus(getString(R.string.status_rerunning_log, replayCommand.optString("action", "command"), mode?.label ?: "stored"))
        executeCommandJson(replayCommand)
    }

    private fun buildReplayCommand(command: JSONObject, deliveryPolicy: String): JSONObject? {
        if (command.optString("action") != "open_intent") return null
        val replay = JSONObject(command.toString())
        val params = JSONObject(replay.optJSONObject("params")?.toString() ?: "{}")
        params.put("delivery_policy", deliveryPolicy)
        replay.put("params", params)
        return replay
    }

    private fun buildReplayViewCommand(command: JSONObject): JSONObject? {
        if (command.optString("action") != "open_intent") return null
        val replay = JSONObject(command.toString())
        val params = JSONObject(replay.optJSONObject("params")?.toString() ?: "{}")
        val data = listOf("uri", "data", "url").firstNotNullOfOrNull { key -> params.optString(key).takeIf { it.isNotBlank() } } ?: return null
        params.put("action", AndroidIntent.ACTION_VIEW)
        params.put("uri", data)
        if (params.optString("delivery_policy").isBlank()) params.put("delivery_policy", "auto")
        replay.put("params", params)
        return replay
    }

    private fun summarizePhases(phases: JSONArray): String {
        val items = mutableListOf<String>()
        for (i in 0 until phases.length()) items += phases.optJSONObject(i)?.optString("phase").orEmpty()
        return items.filter { it.isNotBlank() }.joinToString(" → ")
    }

    private fun registerDeviceNow() {
        val config = currentTransportConfig()
        if (config.baseUrl.isBlank()) { renderStatus(getString(R.string.status_base_url_required)); return }
        thread {
            val result = runCatching { RemoteTransportClient(config).registerDevice(engine.execute(CommandEnvelope("device_info", JSONObject(), null))) }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
            runOnUiThread {
                if (result.optBoolean("ok", false)) remoteUiStateStore.set(RemoteUiStateStore.STATUS_REGISTERED, "Registered ${config.deviceId}") else remoteUiStateStore.markError("registration", result.optString("error"), result.optString("error"))
                renderStatus(if (result.optBoolean("ok", false)) getString(R.string.connection_state_registered_detail) else result.optString("error"))
                renderResult(result)
                renderConnectionStatus()
            }
        }
    }

    private fun testRemoteConnection() {
        val config = currentTransportConfig()
        if (config.baseUrl.isBlank()) { renderStatus(getString(R.string.status_base_url_required)); return }
        thread {
            val result = runCatching { RemoteTransportClient(config).testConnection() }.getOrElse { JSONObject().put("ok", false).put("base_url", config.baseUrl).put("error", it.message ?: it.javaClass.simpleName) }
            runOnUiThread {
                if (result.optBoolean("ok", false)) remoteUiStateStore.set(RemoteUiStateStore.STATUS_TEST_OK, "Bridge responded at ${config.baseUrl}") else remoteUiStateStore.markError("connection_test", result.optString("error"), result.optString("error"))
                renderStatus(if (result.optBoolean("ok", false)) getString(R.string.connection_state_test_ok_detail) else result.optString("error"))
                renderResult(result)
                renderConnectionStatus()
            }
        }
    }

    private fun saveRemoteConfig() {
        val config = currentTransportConfig()
        transportConfigStore.save(config)
        if (config.baseUrl.isBlank()) remoteUiStateStore.set(RemoteUiStateStore.STATUS_DISCONNECTED, "Add a remote base URL or import pairing first")
    }

    private fun currentTransportConfig(): TransportConfig = TransportConfig(
        baseUrl = binding.remoteBaseUrlInput.text?.toString().orEmpty(),
        deviceId = binding.remoteDeviceIdInput.text?.toString().orEmpty(),
        token = binding.remoteTokenInput.text?.toString().orEmpty(),
        pollIntervalSeconds = binding.remotePollSecondsInput.text?.toString()?.toLongOrNull() ?: 10L
    )

    private fun loadRemoteConfig() {
        val config = transportConfigStore.load()
        binding.remoteBaseUrlInput.setText(config.baseUrl)
        binding.remoteDeviceIdInput.setText(config.deviceId)
        binding.remoteTokenInput.setText(config.token)
        binding.remotePollSecondsInput.setText(config.pollIntervalSeconds.toString())
    }

    private fun renderVersionInfo() {
        val app = engine.appIdentity()
        binding.versionOutput.text = getString(R.string.current_version_format, app.optString("version_name"), app.optLong("version_code"))
    }

    private fun checkUpdateNow(silent: Boolean = false) {
        thread {
            val result = engine.execute(CommandEnvelope("check_self_update", JSONObject(), null))
            val policy = if (result.optBoolean("ok", false)) UpdatePolicyEvaluator.fromResult(result) else null
            lastUpdatePolicy = policy
            runOnUiThread {
                renderUpdateState(policy)
                if (!silent) renderResult(result)
            }
        }
    }

    private fun renderUpdateState(policy: UpdatePolicy?) {
        if (policy == null) {
            binding.updateStatusOutput.text = getString(R.string.update_status_unknown)
            binding.updateNowButton.isEnabled = false
            return
        }
        binding.updateStatusOutput.text = listOf(
            getString(R.string.update_current, policy.currentVersionName, policy.currentVersionCode),
            getString(R.string.update_latest, policy.latestVersionName, policy.latestVersionCode?.let { " ($it)" } ?: ""),
            getString(R.string.update_available, policy.updateAvailable.toString()),
            getString(R.string.update_supported, policy.supported.toString()),
            getString(R.string.update_force, policy.forceUpdate.toString()),
            policy.minSupportedVersionCode?.let { getString(R.string.update_min_supported, it) },
            policy.apkUrl?.let { getString(R.string.update_apk, it) },
            policy.notes?.let { getString(R.string.update_notes, it) }
        ).filterNotNull().joinToString("\n")
        binding.updateNowButton.isEnabled = !policy.apkUrl.isNullOrBlank() && policy.updateAvailable
    }

    private fun triggerUpdateNow() {
        val apkUrl = lastUpdatePolicy?.apkUrl
        if (apkUrl.isNullOrBlank()) { renderStatus(getString(R.string.status_no_apk_url)); return }
        thread {
            val result = engine.execute(CommandEnvelope("download_self_update", JSONObject().put("apk_url", apkUrl), null))
            runOnUiThread { renderResult(result); renderStatus(getString(R.string.status_update_prompt_expected)) }
        }
    }

    private fun isBlockedBySoftForceUpdate(): Boolean {
        val policy = lastUpdatePolicy ?: return false
        val blocked = !policy.supported || (policy.forceUpdate && policy.updateAvailable)
        if (blocked) renderStatus(getString(R.string.status_update_required))
        return blocked
    }

    private fun installOrOpenTailscale(forceStore: Boolean) {
        val installed = isPackageInstalled(TAILSCALE_PACKAGE)
        val intent = when {
            installed && !forceStore -> packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
        } ?: Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY_STORE_WEB_URL))
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY_STORE_WEB_URL))) }
    }

    private fun launchPairingQrScanner() {
        qrScanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(getString(R.string.qr_scan_prompt)).setBeepEnabled(false).setOrientationLocked(false).addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN))
    }

    private fun importPairingFromImage(uri: Uri) {
        thread {
            val result = runCatching {
                val raw = decodeQrTextFromImage(uri)
                if (raw.isBlank()) throw IllegalArgumentException(getString(R.string.status_no_qr_found_in_image))
                runOnUiThread { binding.pairingCodeInput.setText(raw); importPairingPayload(raw) }
            }.exceptionOrNull()
            if (result != null) runOnUiThread { renderStatus(getString(R.string.status_pairing_import_failed, result.message ?: result.javaClass.simpleName)) }
        }
    }

    private fun importPairingFromField() {
        val raw = binding.pairingCodeInput.text?.toString().orEmpty().trim()
        if (raw.isBlank()) { renderStatus(getString(R.string.status_pairing_code_required)); return }
        importPairingPayload(raw)
    }

    private fun handlePairingIntent(intent: Intent?) {
        val pairingData = intent?.dataString?.trim().orEmpty()
        if (!pairingData.startsWith("acpair://", true)) return
        importPairingPayload(pairingData)
        val clearedIntent = Intent(intent)
        clearedIntent.data = null
        setIntent(clearedIntent)
    }

    private fun importPairingPayload(raw: String) {
        val payload = runCatching { PairingPayload.parse(raw) }.getOrElse { renderStatus(getString(R.string.status_pairing_import_failed, it.message ?: it.javaClass.simpleName)); return }
        if (payload.isExpired()) { renderStatus(getString(R.string.status_pairing_expired)); return }
        binding.remoteBaseUrlInput.setText(payload.transport.baseUrl)
        binding.remoteTokenInput.setText(payload.transport.token)
        binding.remotePollSecondsInput.setText(payload.transport.pollIntervalSeconds.toString())
        if (binding.remoteDeviceIdInput.text.isNullOrBlank() && payload.device.suggestedDeviceId.isNotBlank()) binding.remoteDeviceIdInput.setText(payload.device.suggestedDeviceId)
        binding.pairingCodeInput.setText(raw)
        saveRemoteConfig()
        remoteUiStateStore.set(RemoteUiStateStore.STATUS_SETUP_REQUIRED, "Pairing imported for ${payload.label}")
        renderStatus(getString(R.string.status_pairing_imported, payload.label, payload.transport.mode) + "\n" + payload.summary())
        showSection(SECTION_CONNECT)
    }

    private fun decodeQrTextFromImage(uri: Uri): String {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE) }.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        return try { MultiFormatReader().decode(binaryBitmap).text.orEmpty().trim() } catch (_: NotFoundException) { "" }
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)); true
    }.recoverCatching {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0); true
    }.getOrDefault(false)

    private fun findSelectedLogIndex(logs: JSONArray): Int {
        val selectedId = selectedLogId ?: return 0
        for (i in 0 until logs.length()) if (logs.optJSONObject(i)?.optString("log_id") == selectedId) return i
        return 0
    }

    private fun rerunSelectedLog() { replaySelectedLog() }

    private fun renderResult(result: JSONObject) { binding.resultOutput.text = result.toString(2) }
    private fun renderStatus(status: String) { binding.statusMessageOutput.text = status }
    private fun openSettingsOrExplain(openAction: () -> Boolean) { if (!openAction()) renderStatus(getString(R.string.status_settings_open_failed)) }
    private fun formatChecklistLine(label: String, ready: Boolean) = "${if (ready) "☑" else "☐"} $label"
    private fun readinessState(ready: Boolean) = if (ready) getString(R.string.permission_enabled) else getString(R.string.permission_disabled)
    private fun formatEpoch(value: Long) = if (value <= 0L) "-" else Instant.ofEpochMilli(value).toString()
    private fun jsonError(code: String, message: String) = JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message)).put("timestamp", Instant.now().toString())
    private fun sampleHealthPing() = """{\n  \"action\": \"health_ping\",\n  \"params\": {}\n}"""
    private fun sampleListApps() = """{\n  \"action\": \"list_installed_apps\",\n  \"params\": {\n    \"include_system\": false\n  }\n}"""
    private fun sampleSelfUpdateCheck() = """{\n  \"action\": \"check_self_update\",\n  \"params\": {\n    \"manifest_url\": \"https://raw.githubusercontent.com/var-gg/android-companion/main/update-manifest.json\"\n  }\n}"""

    companion object {
        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        private const val TAILSCALE_PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=com.tailscale.ipn"
        private const val SECTION_HOME = "home"
        private const val SECTION_CONNECT = "connect"
        private const val SECTION_LOGS = "logs"
        private const val SECTION_OPS = "ops"
    }

    private enum class ReplayMode(val label: String) {
        DIRECT("direct"),
        NOTIFY("notify"),
        VIEW("view")
    }
}
