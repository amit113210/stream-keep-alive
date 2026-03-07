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
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

/**
 * Main Activity for TV Connectivity Hub.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
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
    private lateinit var debugTitleText: TextView
    private lateinit var modeSelectorButton: Button
    private lateinit var moreActionsButton: Button
    private lateinit var startProtectionButton: Button
    private lateinit var stopProtectionButton: Button
    private lateinit var readinessWarningContainer: LinearLayout
    private lateinit var readinessWarningText: TextView
    private lateinit var openBatterySettingsButton: Button
    private lateinit var openWriteSettingsButton: Button
    private lateinit var debugTelemetryText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var telemetryRunnable: Runnable? = null
    private var pendingStartAfterPermission = false
    private var activeCalibration: CalibrationRunState? = null
    private var debugVisible = false
    private var runtimeDetailsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        protectionStatusText = findViewById(R.id.protectionStatusText)
        descriptionText = findViewById(R.id.descriptionText)
        versionText = findViewById(R.id.versionText)
        debugTitleText = findViewById(R.id.debugTitleText)
        modeSelectorButton = findViewById(R.id.modeSelectorButton)
        moreActionsButton = findViewById(R.id.moreActionsButton)
        startProtectionButton = findViewById(R.id.startProtectionButton)
        stopProtectionButton = findViewById(R.id.stopProtectionButton)
        readinessWarningContainer = findViewById(R.id.readinessWarningContainer)
        readinessWarningText = findViewById(R.id.readinessWarningText)
        openBatterySettingsButton = findViewById(R.id.openBatterySettingsButton)
        openWriteSettingsButton = findViewById(R.id.openWriteSettingsButton)
        debugTelemetryText = findViewById(R.id.debugTelemetryText)

        versionText.text = getInstalledVersionText()

        startProtectionButton.setOnClickListener { onStartProtectionClicked() }
        modeSelectorButton.setOnClickListener { cycleProtectionMode() }
        moreActionsButton.setOnClickListener { openMoreActionsMenu() }
        stopProtectionButton.setOnClickListener { stopProtectionSession() }
        openBatterySettingsButton.setOnClickListener { openBatteryOptimizationSettings() }
        openWriteSettingsButton.setOnClickListener { openWriteSettingsScreen() }

        updateModeButtonLabel(ProtectionSessionManager.currentMode(this))
        applyDebugVisibility()
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        showBootResumeReminderIfNeeded()
        restoreScreenTimeoutIfSessionInactive()
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

    private fun openMoreActionsMenu() {
        val items = arrayOf(
            getString(R.string.more_accessibility_settings),
            getString(R.string.more_notification_access),
            getString(R.string.more_power_system),
            getString(R.string.more_hotspot),
            getString(R.string.more_calibration),
            getString(if (runtimeDetailsVisible) R.string.more_hide_runtime else R.string.more_show_runtime),
            getString(if (debugVisible) R.string.more_hide_debug else R.string.more_show_debug),
            getString(R.string.more_app_info)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.button_more_actions))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openAccessibilitySettings()
                    1 -> openNotificationListenerSettings()
                    2 -> openPowerSettingsHelper()
                    3 -> openNetworkSettings()
                    4 -> openCalibrationPackagePicker()
                    5 -> toggleRuntimeDetails()
                    6 -> toggleDebugPanel()
                    7 -> openAppDetailsSettings()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onStartProtectionClicked() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, getString(R.string.toast_enable_accessibility_first), Toast.LENGTH_SHORT).show()
            return
        }

        if (!ensureNotificationPermission()) {
            pendingStartAfterPermission = true
            return
        }

        if (!ensureBatteryOptimizationExemption()) {
            return
        }

        if (!ensureWriteSettingsPermission()) {
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
            .setPositiveButton(getString(R.string.action_allow)) { _, _ ->
                openBatteryOptimizationSettings()
            }
            .setNeutralButton(getString(R.string.action_continue_anyway)) { _, _ ->
                if (ensureWriteSettingsPermission()) {
                    startProtectionSession(ProtectionSessionManager.currentMode(this))
                }
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
            getString(R.string.power_option_timeout_guidance),
            getString(R.string.power_option_battery_optimization),
            getString(R.string.power_option_screen_timeout_permission),
            getString(R.string.power_option_accessibility_settings),
            getString(R.string.power_option_notification_access),
            getString(R.string.power_option_app_info)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.button_power_settings))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPowerTimeoutGuidanceDialog()
                    1 -> openBatteryOptimizationSettings()
                    2 -> openWriteSettingsScreen()
                    3 -> openAccessibilitySettings()
                    4 -> openNotificationListenerSettings()
                    5 -> openAppDetailsSettings()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPowerTimeoutGuidanceDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.power_guidance_title))
            .setMessage(getString(R.string.power_guidance_message))
            .setPositiveButton(getString(R.string.power_guidance_open_settings)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    // no-op
                }
            }
            .setNeutralButton(android.R.string.ok, null)
            .show()
    }

    private fun canWriteSystemSettings(): Boolean {
        return ScreenTimeoutManager.canWriteSystemSettings(this)
    }

    private fun openWriteSettingsScreen() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun ensureWriteSettingsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (canWriteSystemSettings()) return true

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.screen_timeout_permission_title))
            .setMessage(getString(R.string.screen_timeout_permission_message))
            .setPositiveButton(getString(R.string.action_allow)) { _, _ ->
                openWriteSettingsScreen()
            }
            .setNeutralButton(getString(R.string.action_continue_anyway)) { _, _ ->
                startProtectionSession(ProtectionSessionManager.currentMode(this))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return false
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
        applyScreenTimeoutHardeningIfPossible()
        updateServiceStatus()
        Toast.makeText(this, getString(R.string.toast_utility_started, mode.name), Toast.LENGTH_SHORT).show()
    }

    private fun stopProtectionSession() {
        try {
            startService(ProtectionSessionService.createStopIntent(this))
        } catch (_: Exception) {
            // no-op
        }
        ProtectionSessionManager.stopProtection(this)
        restoreScreenTimeoutIfPossible(reason = "ui-stop")
        updateServiceStatus()
        Toast.makeText(this, getString(R.string.toast_utility_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun applyScreenTimeoutHardeningIfPossible() {
        if (!canWriteSystemSettings()) {
            Log.i(TAG, "[SCREEN] write settings missing; skip apply")
            return
        }
        val applied = ScreenTimeoutManager.applyLongTimeoutForSession(this)
        Log.i(TAG, "[SCREEN] apply from ui success=$applied")
    }

    private fun restoreScreenTimeoutIfPossible(reason: String) {
        if (!ScreenTimeoutManager.isSessionTimeoutApplied(this)) return
        if (!canWriteSystemSettings()) {
            Log.w(TAG, "[SCREEN] cannot restore now, missing permission reason=$reason")
            return
        }
        val restored = ScreenTimeoutManager.restoreOriginalTimeoutIfNeeded(this)
        Log.i(TAG, "[SCREEN] restore reason=$reason success=$restored")
    }

    private fun restoreScreenTimeoutIfSessionInactive() {
        val restored = ScreenTimeoutManager.restoreIfSessionInactive(
            context = this,
            sessionActive = ProtectionSessionManager.isProtectionActive(this)
        )
        if (restored) {
            Log.i(TAG, "[SCREEN] restored stale override on app resume")
        }
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
        val writeSettingsGranted = canWriteSystemSettings()
        val timeoutOverrideActive = telemetry.screenTimeoutOverrideActive
        val originalTimeoutText = if (telemetry.originalScreenTimeoutMs > 0L) {
            "${telemetry.originalScreenTimeoutMs}ms"
        } else {
            "-"
        }
        val requestedTimeoutText = if (telemetry.currentRequestedScreenTimeoutMs > 0L) {
            "${telemetry.currentRequestedScreenTimeoutMs}ms"
        } else {
            "-"
        }
        val playbackSignalsAvailable = telemetry.mediaSessionAccessAvailable || telemetry.notificationListenerEnabled
        val playbackActive = telemetry.playbackStateFriendly == PlaybackFriendlyState.PLAYING_ACTIVE.name
        val heartbeatAllowed = telemetry.shouldRunHeartbeatNow
        val heartbeatReason = telemetry.heartbeatSuppressedReason.ifEmpty { "-" }
        val gestureHealth = telemetry.gestureEngineHealth
        val dialogHunterState = when {
            telemetry.currentPackage.startsWith("com.netflix").not() &&
                telemetry.activePlaybackPackage.startsWith("com.netflix").not() -> "IDLE"
            telemetry.lastDialogPositivePhrase.isNotEmpty() && telemetry.lastDialogDismissAt > 0L -> "MATCHING"
            telemetry.lastDialogNoTargetReason.isNotEmpty() -> "FAILED"
            else -> "MATCHING"
        }
        val dialogTargetWindowText = if (telemetry.lastDialogTargetWindowIndex >= 0) {
            telemetry.lastDialogTargetWindowIndex.toString()
        } else {
            "-"
        }
        val dialogPackageText = telemetry.lastDialogPackage.ifEmpty { "-" }
        val dialogPositivePhraseText = telemetry.lastDialogPositivePhrase.ifEmpty { "-" }
        val dialogConfirmPhraseText = telemetry.lastDialogConfirmPhrase.ifEmpty { "-" }
        val dialogNegativeMatchText = telemetry.lastDialogNegativeMatch.ifEmpty { "-" }
        val dialogNoTargetReasonText = telemetry.lastDialogNoTargetReason.ifEmpty { "-" }
        val dialogBlockReasonText = telemetry.lastDialogNegativeBlockReason.ifEmpty { "-" }
        val dialogTargetText = telemetry.lastDialogTargetText.ifEmpty { "-" }
        val dialogClickMethod = telemetry.lastDialogClickMethod.ifEmpty { "NONE" }

        val accessibilityText = if (accessibilityEnabled) getString(R.string.status_yes) else getString(R.string.status_no)
        val protectionText = if (protectionActive) getString(R.string.status_yes) else getString(R.string.status_no)
        val notificationText = if (notificationAccessEnabled) getString(R.string.status_yes) else getString(R.string.status_no)
        val foregroundText = if (telemetry.foregroundServiceRunning) getString(R.string.status_yes) else getString(R.string.status_no)
        val batteryText = if (batteryExempt) getString(R.string.status_yes) else getString(R.string.status_no)
        val writeSettingsText = if (writeSettingsGranted) getString(R.string.status_yes) else getString(R.string.status_no)
        val timeoutOverrideText = if (timeoutOverrideActive) getString(R.string.status_yes) else getString(R.string.status_no)
        val playbackSignalsText = if (playbackSignalsAvailable) getString(R.string.status_yes) else getString(R.string.status_no)
        val playbackText = if (playbackActive) getString(R.string.status_yes) else getString(R.string.status_no)
        val heartbeatAllowedText = if (heartbeatAllowed) getString(R.string.status_yes) else getString(R.string.status_no)

        descriptionText.text = if (runtimeDetailsVisible) {
            String.format(
                Locale.US,
                "Checklist\n" +
                    "• Accessibility: %s\n" +
                    "• Utility Session: %s\n" +
                    "• Notification Access: %s\n" +
                    "• Foreground Companion: %s\n" +
                    "• Battery Optimization Exempt: %s\n" +
                    "• Write Settings Permission: %s\n" +
                    "• Screen Timeout Override Active: %s\n" +
                    "• Playback Signals Available: %s\n" +
                    "• Active Playback: %s\n" +
                    "• Gesture Engine Health: %s\n" +
                    "• Dialog Hunter: %s\n\n" +
                    "Runtime\n" +
                    "• Display hardening: best effort only\n" +
                    "• Selected Mode: %s\n" +
                    "• Heartbeat Allowed: %s\n" +
                    "• Heartbeat Gate Reason: %s\n" +
                    "• Original Screen Timeout: %s\n" +
                    "• Current Screen Timeout Override: %s\n" +
                    "• Package: %s\n" +
                    "• Profile: %s\n" +
                    "• Mode: %s\n" +
                    "• Playback Source: %s\n" +
                    "• Playback Confidence: %s\n" +
                    "• Last Gesture Attempt Package: %s\n" +
                    "• Last Gesture Zone Index: %d\n" +
                    "• Last Gesture Coordinates: %s\n" +
                    "• Last Gesture Dialog Driven: %s\n" +
                    "• Last Gesture Heartbeat Driven: %s\n" +
                    "• Last Gesture Cancel Reason: %s\n" +
                    "• Consecutive Gesture Cancels: %d\n" +
                    "• Last Dialog Windows: %d\n" +
                    "• Last Dialog Package: %s\n" +
                    "• Last Dialog Positive Phrase: %s\n" +
                    "• Last Dialog Confirm Phrase: %s\n" +
                    "• Last Dialog Negative Match: %s\n" +
                    "• Last Dialog No-Target Reason: %s\n" +
                    "• Last Dialog Block Reason: %s\n" +
                    "• Last Dialog Target: %s\n" +
                    "• Last Dialog Target Window: %s\n" +
                    "• Last Dialog Click Method: %s",
                accessibilityText,
                protectionText,
                notificationText,
                foregroundText,
                batteryText,
                writeSettingsText,
                timeoutOverrideText,
                playbackSignalsText,
                playbackText,
                gestureHealth,
                dialogHunterState,
                selectedMode.name,
                heartbeatAllowedText,
                heartbeatReason,
                originalTimeoutText,
                requestedTimeoutText,
                telemetry.currentPackage.ifEmpty { "-" },
                telemetry.currentProfile.ifEmpty { "-" },
                telemetry.currentMode,
                telemetry.playbackSignalSource,
                telemetry.playbackConfidence,
                telemetry.lastGestureAttemptPackage.ifEmpty { "-" },
                telemetry.lastGestureZoneIndex,
                telemetry.lastGestureCoordinates.ifEmpty { "-" },
                telemetry.lastGestureWasDialogDriven.toString(),
                telemetry.lastGestureWasHeartbeatDriven.toString(),
                telemetry.lastGestureCancelReasonHint.ifEmpty { "-" },
                telemetry.consecutiveGestureCancels,
                telemetry.lastDialogWindowCount,
                dialogPackageText,
                dialogPositivePhraseText,
                dialogConfirmPhraseText,
                dialogNegativeMatchText,
                dialogNoTargetReasonText,
                dialogBlockReasonText,
                dialogTargetText,
                dialogTargetWindowText,
                dialogClickMethod
            )
        } else {
            String.format(
                Locale.US,
                getString(R.string.quick_status_format),
                accessibilityText,
                protectionText,
                selectedMode.name,
                playbackText,
                heartbeatAllowedText,
                gestureHealth,
                dialogHunterState
            )
        }
        updateModeButtonLabel(selectedMode)

        val warningLines = mutableListOf<String>()
        if (!batteryExempt) warningLines.add(getString(R.string.warning_battery_missing))
        if (!writeSettingsGranted) warningLines.add(getString(R.string.warning_write_settings_missing))
        if (!timeoutOverrideActive && protectionActive && writeSettingsGranted) {
            warningLines.add(getString(R.string.warning_timeout_override_missing))
        }
        if (telemetry.gestureEngineHealth == "BROKEN") {
            warningLines.add(getString(R.string.warning_gesture_broken))
        } else if (telemetry.gestureEngineHealth == "DEGRADED") {
            warningLines.add(getString(R.string.warning_gesture_degraded))
        }

        if (warningLines.isNotEmpty()) {
            readinessWarningContainer.visibility = View.VISIBLE
            readinessWarningText.text = warningLines.joinToString("\n")
        } else {
            readinessWarningContainer.visibility = View.GONE
            readinessWarningText.text = getString(R.string.readiness_warning_default)
        }
        openBatterySettingsButton.visibility = if (!batteryExempt) View.VISIBLE else View.GONE
        openWriteSettingsButton.visibility = if (!writeSettingsGranted) View.VISIBLE else View.GONE

        startProtectionButton.isEnabled = !protectionActive
        stopProtectionButton.isEnabled = protectionActive
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

    private fun toggleDebugPanel() {
        debugVisible = !debugVisible
        applyDebugVisibility()
    }

    private fun toggleRuntimeDetails() {
        runtimeDetailsVisible = !runtimeDetailsVisible
        updateServiceStatus()
    }

    private fun applyDebugVisibility() {
        debugTitleText.visibility = if (debugVisible) View.VISIBLE else View.GONE
        debugTelemetryText.visibility = if (debugVisible) View.VISIBLE else View.GONE
    }

    private fun updateTelemetryPanel() {
        if (!debugVisible) return
        val t = KeepAliveAccessibilityService.getTelemetrySnapshot()
        debugTelemetryText.text = String.format(
            Locale.US,
            "Session: active=%s startedAt=%d fgs=%s notifPerm=%s notifAccess=%s\n" +
                "Playback: pkg=%s state=%s src=%s conf=%s changedAt=%d mediaAccess=%s\n" +
                "Gate: runNow=%s reason=%s\n" +
                "Current: pkg=%s profile=%s mode=%s interval=%dms esc=%d burst=%s\n" +
                "DialogWin: pkg=%s count=%d positive=%s confirm=%s negative=%s noTarget=%s block=%s target=%s win=%d click=%s sample=%s\n" +
                "Heartbeat: scheduled=%d executed=%d\n" +
                "Gesture: result=%s dispatchReturned=%s action=%s attemptPkg=%s FGPkg=%s windows=%d dialog=%s hb=%s zone=%d coords=%s completion=%d cancelAt=%d cancelHint=%s health=%s fail=%d cancel=%d\n" +
                "Dialog: detectedAt=%d dismissedAt=%d strategy=%s stats=%d/%d/%d\n" +
                "Gestures: sent=%d done=%d cancel=%d reject=%d\n" +
                "Calibration: mode=%s action=%s zone=%d\n" +
                "Wake: acquire=%d release=%d held=%s\n" +
                "Power: batteryExempt=%s writeSettings=%s timeoutOverride=%s originalTimeout=%d requestedTimeout=%d appliedAt=%d restoredAt=%d\n" +
                "DialogPending: override=%s\n" +
                "Poke: active=%s count=%d lastAt=%d\n" +
                "Screensaver: escapes=%d lastDetect=%d lastEscape=%d",
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
            t.lastDialogPackage.ifEmpty { "-" },
            t.lastDialogWindowCount,
            t.lastDialogPositivePhrase.ifEmpty { "-" },
            t.lastDialogConfirmPhrase.ifEmpty { "-" },
            t.lastDialogNegativeMatch.ifEmpty { "-" },
            t.lastDialogNoTargetReason.ifEmpty { "-" },
            t.lastDialogNegativeBlockReason.ifEmpty { "-" },
            t.lastDialogTargetText.ifEmpty { "-" },
            t.lastDialogTargetWindowIndex,
            t.lastDialogClickMethod.ifEmpty { "NONE" },
            t.lastDialogVisibleTextsSample.ifEmpty { "-" },
            t.lastHeartbeatScheduledAt,
            t.lastHeartbeatExecutedAt,
            t.lastGestureDispatchResult.ifEmpty { "-" },
            t.lastGestureDispatchReturned,
            t.lastGestureAction.ifEmpty { "-" },
            t.lastGestureAttemptPackage.ifEmpty { "-" },
            t.lastGestureAttemptWhileForegroundPackage.ifEmpty { "-" },
            t.lastGestureWindowCount,
            t.lastGestureWasDialogDriven.toString(),
            t.lastGestureWasHeartbeatDriven.toString(),
            t.lastGestureZoneIndex,
            t.lastGestureCoordinates.ifEmpty { "-" },
            t.lastGestureCompletionAt,
            t.lastGestureCancelAt,
            t.lastGestureCancelReasonHint.ifEmpty { "-" },
            t.gestureEngineHealth,
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
            t.batteryOptimizationExempt,
            t.writeSettingsGranted,
            t.screenTimeoutOverrideActive,
            t.originalScreenTimeoutMs,
            t.currentRequestedScreenTimeoutMs,
            t.lastScreenTimeoutApplyAt,
            t.lastScreenTimeoutRestoreAt,
            t.dialogPendingHeartbeatOverride,
            t.antiScreensaverPokeActive,
            t.antiScreensaverPokeCount,
            t.lastAntiScreensaverPokeAt,
            t.screensaverEscapeCount,
            t.lastScreensaverDetectedAt,
            t.lastScreensaverEscapeAt
        )
    }

    private fun openCalibrationPackagePicker() {
        val packages = PackagePolicy.streamingProfiles
            .map { it.packagePrefix }
            .distinct()
            .sorted()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calibration_package_title))
            .setItems(packages.toTypedArray()) { _, which ->
                startCalibration(packages[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startCalibration(packagePrefix: String) {
        val steps = buildCalibrationSteps(packagePrefix)
        if (steps.isEmpty()) {
            Toast.makeText(this, getString(R.string.calibration_no_steps), Toast.LENGTH_SHORT).show()
            return
        }

        startProtectionSession(ServiceMode.AGGRESSIVE, durationTargetMinutes = 12)

        activeCalibration = CalibrationRunState(
            packagePrefix = packagePrefix,
            steps = steps
        )

        Toast.makeText(this, getString(R.string.calibration_started, packagePrefix), Toast.LENGTH_LONG).show()
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
            getString(
                R.string.calibration_step,
                run.stepIndex + 1,
                run.steps.size,
                step.zoneIndex,
                step.action.name,
                step.mode.name
            ),
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
                getString(R.string.calibration_done_best, zone, action.name, mode.name),
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, getString(R.string.calibration_done_empty), Toast.LENGTH_LONG).show()
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
                    if (ensureWriteSettingsPermission()) {
                        startProtectionSession(ProtectionSessionManager.currentMode(this))
                    }
                }
            } else if (!granted) {
                pendingStartAfterPermission = false
                Toast.makeText(this, getString(R.string.notification_permission_needed), Toast.LENGTH_LONG).show()
            }
        }
    }
}
