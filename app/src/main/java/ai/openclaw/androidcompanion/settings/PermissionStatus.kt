package ai.openclaw.androidcompanion.settings

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject

data class PermissionSnapshot(
    val usageAccess: Boolean,
    val installUnknownApps: Boolean,
    val notificationPermission: Boolean,
    val notificationsEnabled: Boolean,
    val ignoringBatteryOptimizations: Boolean
)

object PermissionStatus {
    fun snapshot(context: Context): PermissionSnapshot {
        return PermissionSnapshot(
            usageAccess = hasUsageAccess(context),
            installUnknownApps = canInstallUnknownApps(context),
            notificationPermission = hasNotificationPermission(context),
            notificationsEnabled = areNotificationsEnabled(context),
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        )
    }

    fun toJson(snapshot: PermissionSnapshot): JSONObject = JSONObject()
        .put("usage_access", snapshot.usageAccess)
        .put("install_unknown_apps", snapshot.installUnknownApps)
        .put("notification_permission", snapshot.notificationPermission)
        .put("notifications_enabled", snapshot.notificationsEnabled)
        .put("ignoring_battery_optimizations", snapshot.ignoringBatteryOptimizations)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun canInstallUnknownApps(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openUsageAccessSettings(context: Context): Boolean {
        return launchSettingsActivity(
            context,
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        )
    }

    fun openUnknownAppSourcesSettings(context: Context): Boolean {
        val primaryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(context))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        return launchSettingsActivity(context, primaryIntent) ||
            launchSettingsActivity(context, appDetailsIntent(context))
    }

    fun openNotificationSettings(context: Context): Boolean {
        val primaryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetailsIntent(context)
        }
        return launchSettingsActivity(context, primaryIntent) ||
            launchSettingsActivity(context, appDetailsIntent(context))
    }

    fun openBatteryOptimizationRequest(context: Context): Boolean {
        val primaryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        return launchSettingsActivity(context, primaryIntent) ||
            openBatteryOptimizationSettings(context)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val primaryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        return launchSettingsActivity(context, primaryIntent) ||
            launchSettingsActivity(context, appDetailsIntent(context))
    }

    fun openAppDetailsSettings(context: Context): Boolean {
        return launchSettingsActivity(context, appDetailsIntent(context))
    }

    private fun appDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    private fun launchSettingsActivity(context: Context, intent: Intent): Boolean {
        val safeIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        safeIntent.resolveActivity(context.packageManager) ?: return false
        context.startActivity(safeIntent)
        return true
    }
}
