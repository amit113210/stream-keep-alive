package com.keepalive.yesplus

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

/**
 * Main Activity for Stream Keep Alive.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 2201
        private const val CALIBRATION_TOTAL_MS = 12L * 60L * 1000L
    }

    private data class CalibrationStep(
        val zoneIndex: Int,
        val action: HeartbeatAction,
        val mode: ServiceMode
    )

    private data class CalibrationScore(
        var completed: Long = 0,
        var cancelled: Long = 0,
        var rejected: Long = 0,
        var playbackSignals: Long = 0,
        var dialogsDismissed: Long = 0
    ) {
        fun score(): Long = completed * 2L + playbackSignals + dialogsDismissed - cancelled - rejected
    }

    private data class CalibrationRunState(
        val packagePrefix: String,
        val steps: List<CalibrationStep>,
        var stepIndex: Int = 0,
        var baselineCompleted: Long = 0L,
        var baselineCancelled: Long = 0L,
        var baselineRejected: Long = 0L,
        var baselineDialogsDismissed: Long = 0L,
        val scores: MutableMap<String, CalibrationScore> = mutableMapOf()
    )

    private lateinit var statusIndicator: ImageView
    private lateinit var statusText: TextView
    private lateinit var protectionStatusText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var versionText: TextView
    private lateinit var settingsButton: Button
    private lateinit var hotspotButton: Button
    private lateinit var powerSettingsButton: Button
    private lateinit var modeSelectorButton: Button
    private lateinit var startProtectionButton: Button
    private lateinit var stopProtectionButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var notificationAccessButton: Button
    private lateinit var debugTelemetryText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var telemetryRunnable: Runnable? = null
    private var pendingStartAfterPermission = false
    private var activeCalibration: CalibrationRunState? = null

    private var dpadPressCount = 0
    private val requiredPresses = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        protectionStatusText = findViewById(R.id.protectionStatusText)
        descriptionText = findViewById(R.id.descriptionText)
        versionText = findViewById(R.id.versionText)
        settingsButton = findViewById(R.id.settingsButton)
        hotspotButton = findViewById(R.id.hotspotButton)
        powerSettingsButton = findViewById(R.id.powerSettingsButton)
        modeSelectorButton = findViewById(R.id.modeSelectorButton)
        startProtectionButton = findViewById(R.id.startProtectionButton)
        stopProtectionButton = findViewById(R.id.stopProtectionButton)
        calibrationButton = findViewById(R.id.calibrationButton)
        notificationAccessButton = findViewById(R.id.notificationAccessButton)
        debugTelemetryText = findViewById(R.id.debugTelemetryText)

        versionText.text = getInstalledVersionText()

        settingsButton.setOnClickListener { onSettingsClicked() }
        hotspotButton.setOnClickListener { openNetworkSettings() }
        startProtectionButton.setOnClickListener { onStartProtectionClicked() }
        modeSelectorButton.setOnClickListener { cycleProtectionMode() }
        stopProtectionButton.setOnClickListener { stopProtectionSession() }
        calibrationButton.setOnClickListener { openCalibrationPackagePicker() }
        notificationAccessButton.setOnClickListener { openNotificationListenerSettings() }
        powerSettingsButton.setOnClickListener { openPowerSettingsHelper() }

        updateModeButtonLabel(ProtectionSessionManager.currentMode(this))
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        dpadPressCount = 0
        showBootResumeReminderIfNeeded()
        refreshProtectionCompanionIfNeeded()
        updateServiceStatus()
        startTelemetryUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopTelemetryUpdates()
    }

    private fun showBootResumeReminderIfNeeded() {
        if (ProtectionSessionManager.consumeResumeReminderPending(this)) {
            Toast.makeText(this, getString(R.string.boot_resume_reminder), Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshProtectionCompanionIfNeeded() {
        if (!ProtectionSessionManager.isProtectionActive(this)) return
        try {
            ContextCompat.startForegroundService(this, ProtectionSessionService.createRefreshIntent(this))
        } catch (_: Exception) {
            // no-op
        }
    }

    private fun onSettingsClicked() {
        dpadPressCount++
        if (dpadPressCount >= requiredPresses) {
            dpadPressCount = 0
            openAccessibilitySettings()
            return
        }

        Toast.makeText(
            this,
            "לחץ עוד ${requiredPresses - dpadPressCount} פעמים לפתיחת הגדרות נגישות",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun onStartProtectionClicked() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "הפעל קודם שירות נגישות", Toast.LENGTH_SHORT).show()
            return
        }

        if (!ensureNotificationPermission()) {
            pendingStartAfterPermission = true
            return
        }

        if (!ensureBatteryOptimizationExemption()) {
            return
        }

        startProtectionSession(ProtectionSessionManager.currentMode(this))
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = getSystemService(PowerManager::class.java) ?: return true
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            true
        }
    }

    private fun ensureBatteryOptimizationExemption(): Boolean {
        if (isBatteryOptimizationExempt()) return true

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_optimization_title))
            .setMessage(getString(R.string.battery_optimization_required))
            .setPositiveButton("Allow") { _, _ ->
                openBatteryOptimizationSettings()
            }
            .setNeutralButton("Continue anyway") { _, _ ->
                startProtectionSession(ProtectionSessionManager.currentMode(this))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        return false
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun openPowerSettingsHelper() {
        val options = arrayOf(
            "Battery optimization",
            "Accessibility settings",
            "Notification access",
            "App info"
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.button_power_settings))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openBatteryOptimizationSettings()
                    1 -> openAccessibilitySettings()
                    2 -> openNotificationListenerSettings()
                    3 -> openAppDetailsSettings()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun cycleProtectionMode() {
        val current = ProtectionSessionManager.currentMode(this)
        val next = when (current) {
            ServiceMode.NORMAL -> ServiceMode.AGGRESSIVE
            ServiceMode.AGGRESSIVE -> ServiceMode.MAXIMUM
            ServiceMode.MAXIMUM -> ServiceMode.NORMAL
            ServiceMode.DIALOG_ONLY -> ServiceMode.NORMAL
        }
        ProtectionSessionManager.setMode(this, next)
        updateModeButtonLabel(next)
        if (ProtectionSessionManager.isProtectionActive(this)) {
            try {
                ContextCompat.startForegroundService(this, ProtectionSessionService.createRefreshIntent(this))
            } catch (_: Exception) {
                // no-op
            }
        }
        Toast.makeText(this, getString(R.string.mode_changed_to, next.name), Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun updateModeButtonLabel(mode: ServiceMode) {
        modeSelectorButton.text = getString(R.string.mode_selector_format, mode.name)
    }

    private fun startProtectionSession(mode: ServiceMode, durationTargetMinutes: Int = 0) {
        ProtectionSessionManager.startProtection(this, mode, durationTargetMinutes)
        ContextCompat.startForegroundService(this, ProtectionSessionService.createStartIntent(this, mode))
        updateServiceStatus()
        Toast.makeText(this, "Protection Session התחיל (${mode.name})", Toast.LENGTH_SHORT).show()
    }

    private fun stopProtectionSession() {
        try {
            startService(ProtectionSessionService.createStopIntent(this))
        } catch (_: Exception) {
            // no-op
        }
        ProtectionSessionManager.stopProtection(this)
        updateServiceStatus()
        Toast.makeText(this, "Protection Session נעצר", Toast.LENGTH_SHORT).show()
    }

    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
                return false
            }
        }

        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!notificationsEnabled) {
            Toast.makeText(this, getString(R.string.notification_permission_needed), Toast.LENGTH_LONG).show()
            openAppNotificationSettings()
            return false
        }

        return true
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun updateServiceStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val protectionActive = ProtectionSessionManager.isProtectionActive(this)

        if (accessibilityEnabled) {
            statusIndicator.setImageResource(R.drawable.ic_status_active)
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            statusIndicator.setImageResource(R.drawable.ic_status_inactive)
            statusText.text = getString(R.string.status_inactive)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }

        protectionStatusText.text = if (protectionActive) {
            getString(R.string.protection_status_on)
        } else {
            getString(R.string.protection_status_off)
        }

        val telemetry = KeepAliveAccessibilityService.getTelemetrySnapshot()
        val selectedMode = ProtectionSessionManager.currentMode(this)
        val notificationAccessEnabled = isNotificationListenerEnabled()
        val batteryExempt = isBatteryOptimizationExempt()
        val playbackSignalsAvailable = telemetry.mediaSessionAccessAvailable || telemetry.notificationListenerEnabled
        val playbackActive = telemetry.playbackStateFriendly == PlaybackFriendlyState.PLAYING_ACTIVE.name
        val heartbeatAllowed = telemetry.shouldRunHeartbeatNow
        val heartbeatReason = telemetry.heartbeatSuppressedReason.ifEmpty { "-" }

        descriptionText.text = String.format(
            Locale.US,
            "Checklist\n" +
                "• Accessibility: %s\n" +
                "• Protection Session: %s\n" +
                "• Notification Access: %s\n" +
                "• Foreground Companion: %s\n" +
                "• Battery Optimization Exempt: %s\n" +
                "• Playback Signals Available: %s\n" +
                "• Active Playback: %s\n\n" +
                "Runtime\n" +
                "• Selected Mode: %s\n" +
                "• Heartbeat Allowed: %s\n" +
                "• Heartbeat Gate Reason: %s\n" +
                "• Package: %s\n" +
                "• Profile: %s\n" +
                "• Mode: %s\n" +
                "• Playback Source: %s\n" +
                "• Playback Confidence: %s",
            if (accessibilityEnabled) getString(R.string.status_yes) else getString(R.string.status_no),
            if (protectionActive) getString(R.string.status_yes) else getString(R.string.status_no),
            if (notificationAccessEnabled) getString(R.string.status_yes) else getString(R.string.status_no),
            if (telemetry.foregroundServiceRunning) getString(R.string.status_yes) else getString(R.string.status_no),
            if (batteryExempt) getString(R.string.status_yes) else getString(R.string.status_no),
            if (playbackSignalsAvailable) getString(R.string.status_yes) else getString(R.string.status_no),
            if (playbackActive) getString(R.string.status_yes) else getString(R.string.status_no),
            selectedMode.name,
            if (heartbeatAllowed) getString(R.string.status_yes) else getString(R.string.status_no),
            heartbeatReason,
            telemetry.currentPackage.ifEmpty { "-" },
            telemetry.currentProfile.ifEmpty { "-" },
            telemetry.currentMode,
            telemetry.playbackSignalSource,
            telemetry.playbackConfidence
        )
        updateModeButtonLabel(selectedMode)

        startProtectionButton.isEnabled = !protectionActive
        stopProtectionButton.isEnabled = protectionActive
        notificationAccessButton.visibility = if (notificationAccessEnabled) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${KeepAliveAccessibilityService::class.java.canonicalName}"
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains(serviceName)
        } catch (_: Exception) {
            KeepAliveAccessibilityService.isRunning
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isNotificationListenerEnabled(): Boolean {
        return PlaybackStateNotificationListenerService.isNotificationAccessEnabled(this)
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun startTelemetryUpdates() {
        stopTelemetryUpdates()
        telemetryRunnable = object : Runnable {
            override fun run() {
                updateTelemetryPanel()
                updateServiceStatus()
                uiHandler.postDelayed(this, 2000L)
            }
        }
        uiHandler.post(telemetryRunnable!!)
    }

    private fun stopTelemetryUpdates() {
        telemetryRunnable?.let { uiHandler.removeCallbacks(it) }
        telemetryRunnable = null
    }

    private fun updateTelemetryPanel() {
        val t = KeepAliveAccessibilityService.getTelemetrySnapshot()
        debugTelemetryText.text = String.format(
            Locale.US,
            "Session: active=%s startedAt=%d fgs=%s notifPerm=%s notifAccess=%s\n" +
                "Playback: pkg=%s state=%s src=%s conf=%s changedAt=%d mediaAccess=%s\n" +
                "Gate: runNow=%s reason=%s\n" +
                "Current: pkg=%s profile=%s mode=%s interval=%dms esc=%d burst=%s\n" +
                "Heartbeat: scheduled=%d executed=%d\n" +
                "Gesture: result=%s action=%s completion=%d fail=%d cancel=%d\n" +
                "Dialog: detectedAt=%d dismissedAt=%d strategy=%s stats=%d/%d/%d\n" +
                "Gestures: sent=%d done=%d cancel=%d reject=%d\n" +
                "Calibration: mode=%s action=%s zone=%d\n" +
                "Wake: acquire=%d release=%d held=%s\n" +
                "Power: batteryExempt=%s",
            t.protectionSessionActive,
            t.protectionSessionStartedAt,
            t.foregroundServiceRunning,
            t.notificationPermissionGranted,
            t.notificationListenerEnabled,
            t.activePlaybackPackage.ifEmpty { "-" },
            t.playbackStateFriendly,
            t.playbackSignalSource,
            t.playbackConfidence,
            t.lastPlaybackStateChangeAt,
            t.mediaSessionAccessAvailable,
            t.shouldRunHeartbeatNow,
            t.heartbeatSuppressedReason.ifEmpty { "-" },
            t.currentPackage.ifEmpty { "-" },
            t.currentProfile.ifEmpty { "-" },
            t.currentMode,
            t.currentHeartbeatIntervalMs,
            t.currentEscalationStep,
            t.dialogBurstModeActive,
            t.lastHeartbeatScheduledAt,
            t.lastHeartbeatExecutedAt,
            t.lastGestureDispatchResult.ifEmpty { "-" },
            t.lastGestureAction.ifEmpty { "-" },
            t.lastGestureCompletionAt,
            t.consecutiveGestureFailures,
            t.consecutiveGestureCancels,
            t.lastDialogDetectionAt,
            t.lastDialogDismissAt,
            t.lastDialogDismissStrategy.ifEmpty { "-" },
            t.dialogScans,
            t.dialogsDetected,
            t.dialogsDismissed,
            t.gesturesDispatched,
            t.gesturesCompleted,
            t.gesturesCancelled,
            t.gesturesDispatchRejected,
            t.calibrationRecommendedMode.ifEmpty { "-" },
            t.calibrationRecommendedGesture.ifEmpty { "-" },
            t.calibrationRecommendedZone,
            t.wakeAcquires,
            t.wakeReleases,
            t.wakeLockHeld,
            t.batteryOptimizationExempt
        )
    }

    private fun openCalibrationPackagePicker() {
        val packages = PackagePolicy.streamingProfiles
            .map { it.packagePrefix }
            .distinct()
            .sorted()

        AlertDialog.Builder(this)
            .setTitle("Calibration package")
            .setItems(packages.toTypedArray()) { _, which ->
                startCalibration(packages[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startCalibration(packagePrefix: String) {
        val steps = buildCalibrationSteps(packagePrefix)
        if (steps.isEmpty()) {
            Toast.makeText(this, "No calibration steps", Toast.LENGTH_SHORT).show()
            return
        }

        startProtectionSession(ServiceMode.AGGRESSIVE, durationTargetMinutes = 12)

        activeCalibration = CalibrationRunState(
            packagePrefix = packagePrefix,
            steps = steps
        )

        Toast.makeText(this, "Calibration started for $packagePrefix (12 min)", Toast.LENGTH_LONG).show()
        runCalibrationStep()
    }

    private fun buildCalibrationSteps(packagePrefix: String): List<CalibrationStep> {
        val profile = PackagePolicy.streamingProfiles.firstOrNull { it.packagePrefix == packagePrefix } ?: return emptyList()
        val zones = max(1, profile.safeZones.size)

        val steps = mutableListOf<CalibrationStep>()
        for (i in 0 until 6) {
            val zoneIndex = i % zones
            val action = if (i % 2 == 0) HeartbeatAction.MICRO_TAP else HeartbeatAction.MICRO_SWIPE
            val mode = if (i < 3) ServiceMode.NORMAL else ServiceMode.AGGRESSIVE
            steps.add(CalibrationStep(zoneIndex = zoneIndex, action = action, mode = mode))
        }
        return steps
    }

    private fun runCalibrationStep() {
        val run = activeCalibration ?: return
        if (run.stepIndex >= run.steps.size) {
            finishCalibration()
            return
        }

        val step = run.steps[run.stepIndex]
        CalibrationManager.saveOverride(
            context = this,
            packagePrefix = run.packagePrefix,
            preferredSafeZoneIndex = step.zoneIndex,
            preferredAction = step.action,
            preferredMode = step.mode
        )
        ProtectionSessionManager.startProtection(this, step.mode)
        ContextCompat.startForegroundService(this, ProtectionSessionService.createStartIntent(this, step.mode))

        val baseline = KeepAliveAccessibilityService.getTelemetrySnapshot()
        run.baselineCompleted = baseline.gesturesCompleted
        run.baselineCancelled = baseline.gesturesCancelled
        run.baselineRejected = baseline.gesturesDispatchRejected
        run.baselineDialogsDismissed = baseline.dialogsDismissed

        val stepDurationMs = max(60_000L, CALIBRATION_TOTAL_MS / run.steps.size.toLong())
        Toast.makeText(
            this,
            "Calibration step ${run.stepIndex + 1}/${run.steps.size}: zone=${step.zoneIndex}, action=${step.action.name}, mode=${step.mode.name}",
            Toast.LENGTH_SHORT
        ).show()

        uiHandler.postDelayed({
            collectCalibrationStepScore()
            run.stepIndex++
            runCalibrationStep()
        }, stepDurationMs)
    }

    private fun collectCalibrationStepScore() {
        val run = activeCalibration ?: return
        val completed = KeepAliveAccessibilityService.getTelemetrySnapshot()
        val step = run.steps[run.stepIndex]

        val key = "z${step.zoneIndex}|${step.action.name}|${step.mode.name}"
        val score = run.scores.getOrPut(key) { CalibrationScore() }

        score.completed += max(0L, completed.gesturesCompleted - run.baselineCompleted)
        score.cancelled += max(0L, completed.gesturesCancelled - run.baselineCancelled)
        score.rejected += max(0L, completed.gesturesDispatchRejected - run.baselineRejected)
        if (completed.activePlaybackPackage.startsWith(run.packagePrefix)) {
            score.playbackSignals++
        }
        score.dialogsDismissed += max(0L, completed.dialogsDismissed - run.baselineDialogsDismissed)
    }

    private fun finishCalibration() {
        val run = activeCalibration ?: return

        val bestEntry = run.scores.maxByOrNull { it.value.score() }
        if (bestEntry != null) {
            val parts = bestEntry.key.split("|")
            val zone = parts.firstOrNull()?.removePrefix("z")?.toIntOrNull() ?: 0
            val action = HeartbeatAction.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: HeartbeatAction.MICRO_TAP
            val mode = ServiceMode.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: ServiceMode.NORMAL

            CalibrationManager.saveOverride(
                context = this,
                packagePrefix = run.packagePrefix,
                preferredSafeZoneIndex = zone,
                preferredAction = action,
                preferredMode = mode
            )

            Toast.makeText(
                this,
                "Calibration done: best zone=$zone action=${action.name} mode=${mode.name}",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Calibration completed with no measurable result", Toast.LENGTH_LONG).show()
        }

        activeCalibration = null
    }

    private fun openNetworkSettings() {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.droidlogic.tv.settings",
                    "com.droidlogic.tv.settings.wifi.HotSpotActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.connectivity.NetworkActivity"
                )
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {
                // try next fallback
            }
        }
    }

    private fun getInstalledVersionText(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "גרסה $versionName ($versionCode)"
        } catch (_: Exception) {
            getString(R.string.version_placeholder)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                if (ensureBatteryOptimizationExemption()) {
                    startProtectionSession(ProtectionSessionManager.currentMode(this))
                }
            } else if (!granted) {
                pendingStartAfterPermission = false
                Toast.makeText(this, getString(R.string.notification_permission_needed), Toast.LENGTH_LONG).show()
            }
        }
    }
}
