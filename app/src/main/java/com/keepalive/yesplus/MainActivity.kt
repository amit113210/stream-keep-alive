package com.keepalive.yesplus

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
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
        private const val SPEED_PREFS = "speed_test_prefs"
        private const val SPEED_LAST_RESULT = "last_result"
        private const val SPEED_LAST_AT = "last_at"
        private const val QR_PREFS = "qr_prefs"
        private const val QR_SAVED_SSID = "qr_saved_ssid"
        private const val QR_SAVED_PASS = "qr_saved_pass"
    }

    private data class InternetStatus(
        val connected: Boolean,
        val type: String,
        val ssid: String,
        val ip: String
    )

    private data class HotspotStatus(
        val state: String,
        val ssid: String,
        val note: String,
        val password: String = ""
    )

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

    private lateinit var versionText: TextView
    private lateinit var debugTitleText: TextView
    private lateinit var networkSettingsButton: Button
    private lateinit var toggleHotspotButton: Button
    private lateinit var refreshInfoButton: Button
    private lateinit var moreActionsButton: Button
    private lateinit var readinessWarningContainer: LinearLayout
    private lateinit var readinessWarningText: TextView
    private lateinit var openBatterySettingsButton: Button
    private lateinit var openWriteSettingsButton: Button
    private lateinit var debugTelemetryText: TextView
    private lateinit var internetStatusValue: TextView
    private lateinit var hotspotStatusValue: TextView
    private lateinit var speedStatusValue: TextView
    private lateinit var runSpeedTestButton: Button
    private lateinit var showQrCodeButton: Button
    
    // Inline Speed Test Views
    private lateinit var speedMetricsContainer: LinearLayout
    private lateinit var pingResultText: TextView
    private lateinit var downloadResultText: TextView
    private lateinit var uploadResultText: TextView
    private lateinit var speedTestProgressBar: ProgressBar
    private lateinit var speedTestStatusText: TextView

    private var speedTestThread: Thread? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var telemetryRunnable: Runnable? = null
    private var pendingStartAfterPermission = false
    private var activeCalibration: CalibrationRunState? = null
    private var debugVisible = false
    private var runtimeDetailsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        versionText = findViewById(R.id.versionText)
        debugTitleText = findViewById(R.id.debugTitleText)
        networkSettingsButton = findViewById(R.id.networkSettingsButton)
        toggleHotspotButton = findViewById(R.id.toggleHotspotButton)
        refreshInfoButton = findViewById(R.id.refreshInfoButton)
        moreActionsButton = findViewById(R.id.moreActionsButton)
        readinessWarningContainer = findViewById(R.id.readinessWarningContainer)
        readinessWarningText = findViewById(R.id.readinessWarningText)
        openBatterySettingsButton = findViewById(R.id.openBatterySettingsButton)
        openWriteSettingsButton = findViewById(R.id.openWriteSettingsButton)
        debugTelemetryText = findViewById(R.id.debugTelemetryText)
        internetStatusValue = findViewById(R.id.internetStatusValue)
        hotspotStatusValue = findViewById(R.id.hotspotStatusValue)
        speedStatusValue = findViewById(R.id.speedStatusValue)
        runSpeedTestButton = findViewById(R.id.runSpeedTestButton)
        showQrCodeButton = findViewById(R.id.showQrCodeButton)
        speedMetricsContainer = findViewById(R.id.speedMetricsContainer)
        pingResultText = findViewById(R.id.pingResultText)
        downloadResultText = findViewById(R.id.downloadResultText)
        uploadResultText = findViewById(R.id.uploadResultText)
        speedTestProgressBar = findViewById(R.id.speedTestProgressBar)
        speedTestStatusText = findViewById(R.id.speedTestStatusText)

        versionText.text = getInstalledVersionText()

        networkSettingsButton.setOnClickListener { openNetworkSettings() }
        toggleHotspotButton.setOnClickListener { openHotspotSettings() }
        
        refreshInfoButton.setOnClickListener { updateServiceStatus() }
        moreActionsButton.setOnClickListener { openMoreActionsMenu() }
        openBatterySettingsButton.setOnClickListener { openBatteryOptimizationSettings() }
        openWriteSettingsButton.setOnClickListener { openWriteSettingsScreen() }
        runSpeedTestButton.setOnClickListener { onRunSpeedTestClicked() }

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
            getString(R.string.more_network_settings),
            getString(R.string.more_hotspot),
            getString(R.string.more_bluetooth_settings),
            getString(R.string.more_power_system),
            getString(if (runtimeDetailsVisible) R.string.more_hide_runtime else R.string.more_show_runtime),
            getString(if (debugVisible) R.string.more_hide_debug else R.string.more_show_debug),
            getString(R.string.more_app_info),
            getString(R.string.more_legacy_tools)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.button_more_actions))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openNetworkSettings()
                    1 -> openHotspotSettings()
                    2 -> openBluetoothSettings()
                    3 -> openPowerSettingsHelper()
                    4 -> toggleRuntimeDetails()
                    5 -> toggleDebugPanel()
                    6 -> openAppDetailsSettings()
                    7 -> openLegacyToolsMenu()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openLegacyToolsMenu() {
        val items = arrayOf(
            getString(R.string.more_accessibility_settings),
            getString(R.string.more_notification_access),
            getString(R.string.more_start_session),
            getString(R.string.more_stop_session),
            getString(R.string.more_cycle_mode),
            getString(R.string.more_calibration)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.more_legacy_tools))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openAccessibilitySettings()
                    1 -> openNotificationListenerSettings()
                    2 -> onStartProtectionClicked()
                    3 -> stopProtectionSession()
                    4 -> cycleProtectionMode()
                    5 -> openCalibrationPackagePicker()
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
        // legacy, keep empty for backwards compat
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
        val telemetry = KeepAliveAccessibilityService.getTelemetrySnapshot()
        val notificationAccessEnabled = isNotificationListenerEnabled()
        val batteryExempt = isBatteryOptimizationExempt()
        val writeSettingsGranted = canWriteSystemSettings()
        val timeoutOverrideActive = telemetry.screenTimeoutOverrideActive

        val internet = readInternetStatus()
        val hotspot = readHotspotStatus()
        internetStatusValue.text = buildInternetCardText(internet)
        hotspotStatusValue.text = buildHotspotCardText(hotspot)
        speedStatusValue.text = buildSpeedCardText()

        toggleHotspotButton.text = getString(R.string.button_hotspot_settings)
        if (hotspot.state == getString(R.string.hotspot_state_active)) {
            showQrCodeButton.visibility = View.VISIBLE
            if (hotspot.ssid != "-" && hotspot.ssid.isNotBlank()) {
                showQrCodeButton.text = getString(R.string.button_show_qr_code)
                showQrCodeButton.setOnClickListener { showQrCodeDialog(hotspot.ssid, hotspot.password) }
            } else {
                showQrCodeButton.text = getString(R.string.button_config_qr_code)
                showQrCodeButton.setOnClickListener { showManualQrConfigDialog() }
            }
        } else {
            showQrCodeButton.visibility = View.GONE
            showQrCodeButton.setOnClickListener(null)
        }

        val warningLines = mutableListOf<String>()
        if (!internet.connected) warningLines.add(getString(R.string.issue_no_internet))
        
        // Only show system warnings if the user has actively turned on the utility session
        if (protectionActive) {
            if (!batteryExempt) warningLines.add(getString(R.string.warning_battery_missing))
            if (!writeSettingsGranted) warningLines.add(getString(R.string.warning_write_settings_missing))
            if (!timeoutOverrideActive && writeSettingsGranted) warningLines.add(getString(R.string.warning_timeout_override_missing))
        }

        if (warningLines.isNotEmpty()) {
            readinessWarningContainer.visibility = View.VISIBLE
            readinessWarningText.text = warningLines.joinToString("\n")
        } else {
            readinessWarningContainer.visibility = View.GONE
            readinessWarningText.text = getString(R.string.readiness_warning_default)
        }

        openBatterySettingsButton.visibility = if (!batteryExempt && protectionActive) View.VISIBLE else View.GONE
        openWriteSettingsButton.visibility = if (!writeSettingsGranted && protectionActive) View.VISIBLE else View.GONE
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

    private fun onRunSpeedTestClicked() {
        if (speedTestThread != null) {
            // Already running
            return
        }
        
        speedMetricsContainer.visibility = View.VISIBLE
        speedStatusValue.visibility = View.GONE
        runSpeedTestButton.isEnabled = false
        
        pingResultText.text = "-- ms"
        downloadResultText.text = "-- Mbps"
        uploadResultText.text = "-- Mbps"
        speedTestStatusText.text = "Starting test..."
        speedTestProgressBar.progress = 0

        speedTestThread = Thread {
            runNativeSpeedTest()
        }.apply { start() }
    }

    private fun runNativeSpeedTest() {
        var pingMs = "--"
        var downMbps = "--"
        var upMbps = "--"

        try {
            // Stage 1: Ping
            updateSpeedUi(0, "Testing Ping...")
            
            var icmpPing = getIcmpPing("1.1.1.1") // Cloudflare DNS
            if (icmpPing < 0) icmpPing = getIcmpPing("8.8.8.8") // Google DNS fallback
            
            if (icmpPing >= 0) {
                pingMs = icmpPing.toString()
                Thread.sleep(500) // Brief pause for UI fluidity
            } else {
                // Fallback to HTTP overhead ping if ICMP is blocked by network
                val pingStart = System.currentTimeMillis()
                val pingConn = java.net.URL("https://speed.cloudflare.com/cdn-cgi/trace").openConnection() as java.net.HttpURLConnection
                pingConn.requestMethod = "GET"
                pingConn.connectTimeout = 3000
                pingConn.readTimeout = 3000
                pingConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                pingConn.setRequestProperty("Origin", "https://speed.cloudflare.com")
                pingConn.setRequestProperty("Referer", "https://speed.cloudflare.com/")
                pingConn.inputStream.use { it.readBytes() }
                val pingEnd = System.currentTimeMillis()
                pingMs = (pingEnd - pingStart).toString()
            }
            
            uiHandler.post { pingResultText.text = "$pingMs ms" }
            if (Thread.interrupted()) return

            // Stage 2: Download
            updateSpeedUi(33, "Testing Download...")
            val downStart = System.currentTimeMillis()
            val downConn = java.net.URL("https://speed.cloudflare.com/__down?bytes=100000000").openConnection() as java.net.HttpURLConnection
            downConn.requestMethod = "GET"
            downConn.connectTimeout = 5000
            downConn.readTimeout = 10000
            downConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            downConn.setRequestProperty("Origin", "https://speed.cloudflare.com")
            downConn.setRequestProperty("Referer", "https://speed.cloudflare.com/")
            
            var downloadedBytes = 0L
            val buffer = ByteArray(8192)
            val maxTestTimeMs = 8000L
            var lastDownUiUpdate = 0L
            downConn.inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (Thread.interrupted()) return
                    downloadedBytes += bytesRead
                    val curTime = System.currentTimeMillis()
                    
                    if (curTime - lastDownUiUpdate > 500) {
                        val elapsed = (curTime - downStart) / 1000.0
                        if (elapsed > 0) {
                            val currentMbps = ((downloadedBytes * 8) / elapsed) / 1000000.0
                            uiHandler.post { downloadResultText.text = "${String.format(java.util.Locale.US, "%.1f", currentMbps)} Mbps" }
                        }
                        lastDownUiUpdate = curTime
                    }

                    if (curTime - downStart > maxTestTimeMs) break
                }
            }
            val downEnd = System.currentTimeMillis()
            val downTimeSecs = (downEnd - downStart) / 1000.0
            val downBits = downloadedBytes * 8
            val dMbps = if (downTimeSecs > 0) (downBits / downTimeSecs) / 1000000.0 else 0.0
            downMbps = String.format(java.util.Locale.US, "%.1f", dMbps)

            uiHandler.post { downloadResultText.text = "$downMbps Mbps" }
            if (Thread.interrupted()) return

            // Stage 3: Upload
            updateSpeedUi(66, "Testing Upload...")
            val payloadSize = 25 * 1024 * 1024
            val upStart = System.currentTimeMillis()
            val upConn = java.net.URL("https://speed.cloudflare.com/__up").openConnection() as java.net.HttpURLConnection
            upConn.requestMethod = "POST"
            upConn.connectTimeout = 5000
            upConn.readTimeout = 10000
            upConn.doOutput = true
            upConn.setChunkedStreamingMode(8192)
            upConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            upConn.setRequestProperty("Origin", "https://speed.cloudflare.com")
            upConn.setRequestProperty("Referer", "https://speed.cloudflare.com/")
            
            var uploaded = 0L
            var lastUpUiUpdate = 0L
            try {
                upConn.outputStream.use { output ->
                    val chunk = ByteArray(8192)
                    while (uploaded < payloadSize) {
                        if (Thread.interrupted()) return
                        val toWrite = java.lang.Math.min(chunk.size.toLong(), payloadSize - uploaded).toInt()
                        output.write(chunk, 0, toWrite)
                        uploaded += toWrite

                        val curTime = System.currentTimeMillis()
                        if (curTime - lastUpUiUpdate > 500) {
                            val elapsed = (curTime - upStart) / 1000.0
                            if (elapsed > 0) {
                                val currentMbps = ((uploaded * 8) / elapsed) / 1000000.0
                                uiHandler.post { uploadResultText.text = "${String.format(java.util.Locale.US, "%.1f", currentMbps)} Mbps" }
                            }
                            lastUpUiUpdate = curTime
                        }
                        if (curTime - upStart > maxTestTimeMs) break
                    }
                }
            } catch (e: Exception) {
                // Breaking output stream early can cause IOException on some server configurations
            }
            
            val upEnd = System.currentTimeMillis()
            val upTimeSecs = (upEnd - upStart) / 1000.0
            val upBits = uploaded * 8
            val uMbps = if (upTimeSecs > 0) (upBits / upTimeSecs) / 1000000.0 else 0.0
            upMbps = String.format(java.util.Locale.US, "%.1f", uMbps)

            uiHandler.post { uploadResultText.text = "$upMbps Mbps" }
            updateSpeedUi(100, "Done")

            val qualityStr = when {
                dMbps >= 25.0 -> getString(R.string.speed_quality_4k)
                dMbps >= 5.0 -> getString(R.string.speed_quality_hd)
                dMbps > 0.0 -> getString(R.string.speed_quality_sd)
                else -> getString(R.string.speed_quality_error)
            }

            // Save result
            val resultStr = "Quality: $qualityStr\nP: ${pingMs}ms | D: ${downMbps}M | U: ${upMbps}M"
            val now = System.currentTimeMillis()
            getSharedPreferences(SPEED_PREFS, Context.MODE_PRIVATE).edit()
                .putString(SPEED_LAST_RESULT, resultStr)
                .putLong(SPEED_LAST_AT, now)
                .apply()

            uiHandler.post { 
                speedTestThread = null
                runSpeedTestButton.isEnabled = true
                speedMetricsContainer.visibility = View.GONE
                speedStatusValue.visibility = View.VISIBLE
                updateServiceStatus()
            }

        } catch (e: Exception) {
            updateSpeedUi(100, "Error: ${e.message}")
            uiHandler.post {
                speedTestThread = null
                runSpeedTestButton.isEnabled = true
            }
        }
    }

    private fun updateSpeedUi(progress: Int, status: String) {
        uiHandler.post {
            speedTestProgressBar.progress = progress
            speedTestStatusText.text = status
        }
    }

    private fun getIcmpPing(host: String): Int {
        try {
            val process = Runtime.getRuntime().exec("ping -c 3 -W 2 $host")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            val times = mutableListOf<Double>()
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("time=")) {
                    val timeStr = line!!.substringAfter("time=").substringBefore(" ms")
                    timeStr.toDoubleOrNull()?.let { times.add(it) }
                }
            }
            process.waitFor()
            if (times.isNotEmpty()) {
                return times.average().toInt()
            }
        } catch (e: Exception) {
            // Ignored, will fallback
        }
        return -1
    }

    // --- End Speed Test Logic ---
    private fun readInternetStatus(): InternetStatus {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return InternetStatus(false, "unknown", "-", "-")
        val active = cm.activeNetwork ?: return InternetStatus(false, "unknown", "-", "-")
        val caps = cm.getNetworkCapabilities(active) ?: return InternetStatus(false, "unknown", "-", "-")
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Unknown"
        }

        val ip = try {
            cm.getLinkProperties(active)
                ?.linkAddresses
                ?.firstOrNull { !it.address.isLoopbackAddress }
                ?.address
                ?.hostAddress ?: "-"
        } catch (_: Exception) {
            "-"
        }

        val ssid = if (type == "Wi-Fi") {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wm?.connectionInfo
                val raw = info?.ssid ?: "-"
                if (raw == "<unknown ssid>") "-" else raw.trim('"')
            } catch (_: Exception) {
                "-"
            }
        } else {
            "-"
        }

        return InternetStatus(connected = connected, type = type, ssid = ssid, ip = ip)
    }

    private fun readHotspotStatus(): HotspotStatus {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val apMethod = wm?.javaClass?.getDeclaredMethod("isWifiApEnabled")
            apMethod?.isAccessible = true
            val enabled = apMethod?.invoke(wm) as? Boolean ?: false
            
            var apSsid = "-"
            var apPass = ""
            if (enabled) {
                try {
                    val apConfigMethod = wm?.javaClass?.getDeclaredMethod("getWifiApConfiguration")
                    apConfigMethod?.isAccessible = true
                    val config = apConfigMethod?.invoke(wm) as? WifiConfiguration
                    if (config != null) {
                        apSsid = config.SSID ?: "-"
                        apPass = config.preSharedKey ?: ""
                    }
                } catch (_: Exception) {}
                
                if (apSsid == "-" && Build.VERSION.SDK_INT >= 30) {
                     try {
                         val softApMethod = wm?.javaClass?.getDeclaredMethod("getSoftApConfiguration")
                         softApMethod?.isAccessible = true
                         val softApConfig = softApMethod?.invoke(wm)
                         apSsid = softApConfig?.javaClass?.getMethod("getSsid")?.invoke(softApConfig) as? String ?: "-"
                         apPass = softApConfig?.javaClass?.getMethod("getPassphrase")?.invoke(softApConfig) as? String ?: ""
                     } catch (_: Exception) {}
                }
                
                if (apSsid == "-" || apSsid.isBlank()) {
                    val prefs = getSharedPreferences(QR_PREFS, Context.MODE_PRIVATE)
                    apSsid = prefs.getString(QR_SAVED_SSID, "-") ?: "-"
                    apPass = prefs.getString(QR_SAVED_PASS, "") ?: ""
                }
                
                val displayNote = if (apSsid == "-") getString(R.string.hotspot_note_active) else getString(R.string.hotspot_note_active) + " (Stored)"
                HotspotStatus(state = getString(R.string.hotspot_state_active), ssid = apSsid, note = displayNote, password = apPass)
            } else {
                HotspotStatus(state = getString(R.string.hotspot_state_inactive), ssid = "-", note = getString(R.string.hotspot_note_inactive))
            }
        } catch (_: Exception) {
            HotspotStatus(state = getString(R.string.hotspot_state_unavailable), ssid = "-", note = getString(R.string.hotspot_note_unavailable))
        }
    }

    private fun showQrCodeDialog(ssid: String, password: String?) {
        val wifiFormat = if (password.isNullOrEmpty()) {
            "WIFI:T:nopass;S:$ssid;P:;;"
        } else {
            "WIFI:T:WPA;S:$ssid;P:$password;;"
        }
        
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(wifiFormat, BarcodeFormat.QR_CODE, 600, 600)
            val imageView = ImageView(this).apply {
                setImageBitmap(bitmap)
                setPadding(32, 32, 32, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_qr_code_title))
                .setView(imageView)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not generate QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManualQrConfigDialog() {
        val prefs = getSharedPreferences(QR_PREFS, Context.MODE_PRIVATE)
        val currentSsid = prefs.getString(QR_SAVED_SSID, "") ?: ""
        val currentPass = prefs.getString(QR_SAVED_PASS, "") ?: ""

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val ssidInput = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_ssid)
            setText(currentSsid)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val passInput = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_password)
            setText(currentPass)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(ssidInput)
        layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hotspot_manual_config_title))
            .setMessage(getString(R.string.hotspot_manual_config_msg))
            .setView(layout)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                prefs.edit()
                    .putString(QR_SAVED_SSID, ssidInput.text.toString().trim())
                    .putString(QR_SAVED_PASS, passInput.text.toString())
                    .apply()
                updateServiceStatus()
            }
            .setNeutralButton(getString(R.string.action_clear)) { _, _ ->
                prefs.edit().clear().apply()
                updateServiceStatus()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun buildInternetCardText(status: InternetStatus): String {
        val state = if (status.connected) getString(R.string.internet_connected) else getString(R.string.internet_disconnected)
        return getString(
            R.string.internet_card_format,
            state,
            status.type,
            status.ssid,
            status.ip
        )
    }

    private fun buildHotspotCardText(status: HotspotStatus): String {
        return getString(R.string.hotspot_card_format, status.state, status.ssid, status.note)
    }

    private fun buildSpeedCardText(): String {
        val prefs = getSharedPreferences(SPEED_PREFS, Context.MODE_PRIVATE)
        val lastResult = prefs.getString(SPEED_LAST_RESULT, "") ?: ""
        val lastAt = prefs.getLong(SPEED_LAST_AT, 0L)
        if (lastAt <= 0L || lastResult.isBlank()) {
            return getString(R.string.speed_not_run_yet)
        }
        val dateStr = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastAt))
        return getString(R.string.speed_last_result_format, lastResult, dateStr)
    }

    private fun openNetworkSettings() {
        val intents = listOf(
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

    private fun openHotspotSettings() {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.droidlogic.tv.settings",
                    "com.droidlogic.tv.settings.wifi.HotSpotActivity"
                )
            },
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
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

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
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
            getString(R.string.installed_version_format, versionName, versionCode)
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
