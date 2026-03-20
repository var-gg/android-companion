package ai.openclaw.androidcompanion.update

import org.json.JSONObject

data class UpdatePolicy(
    val currentVersionName: String,
    val currentVersionCode: Long,
    val latestVersionName: String,
    val latestVersionCode: Long?,
    val minSupportedVersionCode: Long?,
    val forceUpdate: Boolean,
    val updateAvailable: Boolean,
    val supported: Boolean,
    val apkUrl: String?,
    val apkReachable: Boolean,
    val releaseUrl: String?,
    val notes: String?
)

object UpdatePolicyEvaluator {
    fun fromResult(result: JSONObject): UpdatePolicy {
        val currentVersionName = result.optString("current_version")
        val currentVersionCode = result.optLong("current_version_code", 0L)
        val latestVersionName = result.optString("latest_version")
        val latestVersionCode = result.optLongOrNull("latest_version_code")
        val minSupportedVersionCode = result.optLongOrNull("min_supported_version_code")
        val forceUpdate = result.optBoolean("force_update", false)
        val updateAvailable = result.optBoolean("update_available", false)
        val supported = result.optBoolean("supported", true)
        val apkUrl = result.optString("apk_url").takeIf { it.isNotBlank() }
        val apkReachable = result.optBoolean("apk_reachable", false)
        val releaseUrl = result.optString("release_url").takeIf { it.isNotBlank() }
        val notes = result.optString("notes").takeIf { it.isNotBlank() }

        return UpdatePolicy(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            latestVersionName = latestVersionName,
            latestVersionCode = latestVersionCode,
            minSupportedVersionCode = minSupportedVersionCode,
            forceUpdate = forceUpdate,
            updateAvailable = updateAvailable,
            supported = supported,
            apkUrl = apkUrl,
            apkReachable = apkReachable,
            releaseUrl = releaseUrl,
            notes = notes
        )
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    return if (has(key) && !isNull(key)) optLong(key) else null
}
