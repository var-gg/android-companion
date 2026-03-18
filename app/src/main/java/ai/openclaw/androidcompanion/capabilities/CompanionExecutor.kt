package ai.openclaw.androidcompanion.capabilities

import ai.openclaw.androidcompanion.contract.CommandEnvelope
import org.json.JSONObject

class CompanionExecutor(
    private val handlers: CapabilityHandlers
) {
    fun execute(command: CommandEnvelope): JSONObject {
        return when (command.action) {
            "health_ping" -> handlers.healthPing(command)
            "device_info" -> handlers.deviceInfo(command)
            "open_url" -> handlers.openUrl(command)
            "launch_app" -> handlers.launchApp(command)
            "list_installed_apps" -> handlers.listInstalledApps(command)
            "usage_stats" -> handlers.usageStats(command)
            "uninstall_app" -> handlers.uninstallApp(command)
            "open_intent", "test_intent" -> handlers.openIntent(command)
            "check_self_update" -> handlers.checkSelfUpdate(command)
            "download_self_update" -> handlers.downloadSelfUpdate(command)
            else -> handlers.unsupported(command)
        }
    }
}

interface CapabilityHandlers {
    fun healthPing(command: CommandEnvelope): JSONObject
    fun deviceInfo(command: CommandEnvelope): JSONObject
    fun openUrl(command: CommandEnvelope): JSONObject
    fun launchApp(command: CommandEnvelope): JSONObject
    fun listInstalledApps(command: CommandEnvelope): JSONObject
    fun usageStats(command: CommandEnvelope): JSONObject
    fun uninstallApp(command: CommandEnvelope): JSONObject
    fun openIntent(command: CommandEnvelope): JSONObject
    fun checkSelfUpdate(command: CommandEnvelope): JSONObject
    fun downloadSelfUpdate(command: CommandEnvelope): JSONObject
    fun unsupported(command: CommandEnvelope): JSONObject
}
