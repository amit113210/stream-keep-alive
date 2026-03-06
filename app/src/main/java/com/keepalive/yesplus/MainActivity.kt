package com.keepalive.yesplus

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Main Activity for Stream Keep Alive.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusIndicator: ImageView
    private lateinit var statusText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var versionText: TextView
    private lateinit var settingsButton: Button
    private lateinit var hotspotButton: Button
    private lateinit var debugTelemetryText: TextView
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private var telemetryRunnable: Runnable? = null

    // Accessibility settings needs triple-click
    private var dpadPressCount = 0
    private val REQUIRED_PRESSES = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        descriptionText = findViewById(R.id.descriptionText)
        versionText = findViewById(R.id.versionText)
        settingsButton = findViewById(R.id.settingsButton)
        hotspotButton = findViewById(R.id.hotspotButton)
        debugTelemetryText = findViewById(R.id.debugTelemetryText)
        versionText.text = getInstalledVersionText()

        // Accessibility settings button requires triple click
        settingsButton.setOnClickListener {
            dpadPressCount++
            if (dpadPressCount >= REQUIRED_PRESSES) {
                dpadPressCount = 0
                openAccessibilitySettings()
            } else {
                Toast.makeText(
                    this,
                    "לחץ עוד ${REQUIRED_PRESSES - dpadPressCount} פעמים לפתיחת הגדרות נגישות",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Hotspot button opens Network Settings
        hotspotButton.setOnClickListener {
            openNetworkSettings()
        }

        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        dpadPressCount = 0
        updateServiceStatus()
        startTelemetryUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopTelemetryUpdates()
    }

    /**
     * Open Hotspot Settings directly via the hidden droidlogic activity.
     */
    private fun openNetworkSettings() {
        val intents = listOf(
            // Direct hotspot settings — exact name from pm dump
            Intent().apply {
                component = ComponentName(
                    "com.droidlogic.tv.settings",
                    "com.droidlogic.tv.settings.wifi.HotSpotActivity"
                )
            },
            // Fallback: TV network settings
            Intent().apply {
                component = ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.connectivity.NetworkActivity"
                )
            },
            // Last resort: general settings
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (e: Exception) {
                continue
            }
        }
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            statusIndicator.setImageResource(R.drawable.ic_status_active)
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(getColor(R.color.status_active))
            descriptionText.text = getString(R.string.description_active)
        } else {
            statusIndicator.setImageResource(R.drawable.ic_status_inactive)
            statusText.text = getString(R.string.status_inactive)
            statusText.setTextColor(getColor(R.color.status_inactive))
            descriptionText.text = getString(R.string.description_inactive)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${KeepAliveAccessibilityService::class.java.canonicalName}"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(serviceName)
        } catch (e: Exception) {
            return KeepAliveAccessibilityService.isRunning
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startTelemetryUpdates() {
        stopTelemetryUpdates()
        telemetryRunnable = object : Runnable {
            override fun run() {
                updateTelemetryPanel()
                telemetryHandler.postDelayed(this, 2000L)
            }
        }
        telemetryHandler.post(telemetryRunnable!!)
    }

    private fun stopTelemetryUpdates() {
        telemetryRunnable?.let { telemetryHandler.removeCallbacks(it) }
        telemetryRunnable = null
    }

    private fun updateTelemetryPanel() {
        val telemetry = KeepAliveAccessibilityService.getTelemetrySnapshot()
        debugTelemetryText.text = String.format(
            Locale.US,
            "Package: %s\nProfile/Mode: %s / %s\nInterval: %d sec\nHB: last=%d action=%s\nDialogs: scan=%d detect=%d dismiss=%d (%s)\nGestures: sent=%d done=%d cancel=%d reject=%d\nWakeLock: acquire=%d release=%d",
            telemetry.activePackage.ifEmpty { "-" },
            telemetry.activeProfilePrefix.ifEmpty { "-" },
            telemetry.serviceMode,
            telemetry.currentFixedIntervalMs / 1000L,
            telemetry.lastHeartbeatTimestampMs,
            telemetry.lastGestureAction.ifEmpty { "-" },
            telemetry.dialogScans,
            telemetry.dialogsDetected,
            telemetry.dialogsDismissed,
            telemetry.lastDialogDismissStrategy.ifEmpty { "-" },
            telemetry.gesturesDispatched,
            telemetry.gesturesCompleted,
            telemetry.gesturesCancelled,
            telemetry.gesturesDispatchRejected,
            telemetry.wakeAcquires,
            telemetry.wakeReleases
        )
    }

    private fun getInstalledVersionText(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "?"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "גרסה $versionName ($versionCode)"
        } catch (e: Exception) {
            getString(R.string.version_placeholder)
        }
    }
}
