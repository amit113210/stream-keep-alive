package com.keepalive.yesplus

import android.content.Context

data class CalibrationOverride(
    val packagePrefix: String,
    val preferredSafeZoneIndex: Int,
    val preferredAction: HeartbeatAction?,
    val preferredMode: ServiceMode?
)

object CalibrationManager {
    private const val PREFS_NAME = "calibration_overrides"

    private fun keyZone(prefix: String) = "zone_$prefix"
    private fun keyAction(prefix: String) = "action_$prefix"
    private fun keyMode(prefix: String) = "mode_$prefix"

    fun saveOverride(
        context: Context,
        packagePrefix: String,
        preferredSafeZoneIndex: Int,
        preferredAction: HeartbeatAction?,
        preferredMode: ServiceMode?
    ) {
        prefs(context).edit()
            .putInt(keyZone(packagePrefix), preferredSafeZoneIndex)
            .putString(keyAction(packagePrefix), preferredAction?.name ?: "")
            .putString(keyMode(packagePrefix), preferredMode?.name ?: "")
            .apply()
    }

    fun overrideForPackage(context: Context, packageName: String): CalibrationOverride? {
        val bestPrefix = PackagePolicy.streamingProfiles
            .map { it.packagePrefix }
            .filter { packageName.startsWith(it) }
            .maxByOrNull { it.length }
            ?: return null

        val prefs = prefs(context)
        if (!prefs.contains(keyZone(bestPrefix)) &&
            !prefs.contains(keyAction(bestPrefix)) &&
            !prefs.contains(keyMode(bestPrefix))
        ) {
            return null
        }

        val actionRaw = prefs.getString(keyAction(bestPrefix), "").orEmpty()
        val modeRaw = prefs.getString(keyMode(bestPrefix), "").orEmpty()

        return CalibrationOverride(
            packagePrefix = bestPrefix,
            preferredSafeZoneIndex = prefs.getInt(keyZone(bestPrefix), 0),
            preferredAction = HeartbeatAction.entries.firstOrNull { it.name == actionRaw },
            preferredMode = ServiceMode.entries.firstOrNull { it.name == modeRaw }
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
