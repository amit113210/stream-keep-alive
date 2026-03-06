package com.keepalive.yesplus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

class ProtectionSessionService : Service() {

    companion object {
        private const val TAG = "ProtectionSession"
        private const val NOTIFICATION_ID = 4041

        const val CHANNEL_ID = "protection_session"
        const val ACTION_START_PROTECTION = "com.keepalive.yesplus.action.START_PROTECTION"
        const val ACTION_STOP_PROTECTION = "com.keepalive.yesplus.action.STOP_PROTECTION"
        const val ACTION_REFRESH_STATE = "com.keepalive.yesplus.action.REFRESH_STATE"
        const val EXTRA_MODE = "extra_mode"

        fun createStartIntent(context: Context, mode: ServiceMode): Intent {
            return Intent(context, ProtectionSessionService::class.java).apply {
                action = ACTION_START_PROTECTION
                putExtra(EXTRA_MODE, mode.name)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ProtectionSessionService::class.java).apply {
                action = ACTION_STOP_PROTECTION
            }
        }

        fun createRefreshIntent(context: Context): Intent {
            return Intent(context, ProtectionSessionService::class.java).apply {
                action = ACTION_REFRESH_STATE
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_REFRESH_STATE

        when (action) {
            ACTION_START_PROTECTION -> {
                val mode = parseMode(intent?.getStringExtra(EXTRA_MODE))
                ProtectionSessionManager.startProtection(this, mode)
                ProtectionSessionManager.setForegroundServiceRunning(this, true)
                maybeApplyScreenTimeoutOverride(reason = "start")
                startForegroundWithNotification(mode)
                Log.i(TAG, "[SESSION] started mode=${mode.name}")
            }

            ACTION_STOP_PROTECTION -> {
                Log.i(TAG, "[SESSION] stop requested")
                ProtectionSessionManager.stopProtection(this)
                ProtectionSessionManager.setForegroundServiceRunning(this, false)
                maybeRestoreScreenTimeoutOverride(reason = "stop")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_STATE -> {
                val active = ProtectionSessionManager.isProtectionActive(this)
                if (!active) {
                    ProtectionSessionManager.setForegroundServiceRunning(this, false)
                    maybeRestoreScreenTimeoutOverride(reason = "refresh-inactive")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                val mode = ProtectionSessionManager.currentMode(this)
                ProtectionSessionManager.setForegroundServiceRunning(this, true)
                maybeApplyScreenTimeoutOverride(reason = "refresh-active")
                startForegroundWithNotification(mode)
                Log.i(TAG, "[SESSION] refreshed mode=${mode.name}")
            }
        }

        // START_STICKY here is intentional: if process is reclaimed while session is active,
        // system has a path to recreate this companion service and keep session continuity.
        return START_STICKY
    }

    override fun onDestroy() {
        ProtectionSessionManager.setForegroundServiceRunning(this, false)
        super.onDestroy()
    }

    private fun parseMode(raw: String?): ServiceMode {
        return ServiceMode.entries.firstOrNull { it.name == raw } ?: ProtectionSessionManager.currentMode(this)
    }

    private fun maybeApplyScreenTimeoutOverride(reason: String) {
        if (!ScreenTimeoutManager.canWriteSystemSettings(this)) {
            Log.i(TAG, "[SCREEN] write settings permission missing; skip apply reason=$reason")
            return
        }
        val applied = ScreenTimeoutManager.applyLongTimeoutForSession(this)
        Log.i(TAG, "[SCREEN] apply reason=$reason success=$applied")
    }

    private fun maybeRestoreScreenTimeoutOverride(reason: String) {
        if (!ScreenTimeoutManager.isSessionTimeoutApplied(this)) {
            return
        }
        if (!ScreenTimeoutManager.canWriteSystemSettings(this)) {
            Log.w(TAG, "[SCREEN] cannot restore yet (missing write settings) reason=$reason")
            return
        }
        val restored = ScreenTimeoutManager.restoreOriginalTimeoutIfNeeded(this)
        Log.i(TAG, "[SCREEN] restore reason=$reason success=$restored")
    }

    private fun startForegroundWithNotification(mode: ServiceMode) {
        val notification = buildNotification(mode)

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            Log.i(TAG, "[SESSION] foreground companion alive")
        } catch (e: SecurityException) {
            Log.e(TAG, "[SESSION] failed to enter foreground: ${e.message}", e)
            ProtectionSessionManager.setForegroundServiceRunning(this, false)
            stopSelf()
        }
    }

    private fun buildNotification(mode: ServiceMode) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle(getString(R.string.notification_protection_title))
            .setContentText(
                getString(R.string.notification_protection_text, mode.name)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(createMainActivityPendingIntent())
            .addAction(
                0,
                getString(R.string.notification_action_stop),
                createStopPendingIntent()
            )
            .build()

    private fun createMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            201,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createStopPendingIntent(): PendingIntent {
        val intent = createStopIntent(this)
        return PendingIntent.getService(
            this,
            202,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!enabled) {
            Log.w(TAG, "[SESSION] notifications disabled by user/app settings")
        }
    }
}
