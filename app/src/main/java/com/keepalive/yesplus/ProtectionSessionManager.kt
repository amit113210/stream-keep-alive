package com.keepalive.yesplus

import android.content.Context
import android.content.SharedPreferences

object ProtectionSessionManager {
    private const val PREFS_NAME = "protection_session"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_MODE = "mode"
    private const val KEY_DURATION_TARGET_MIN = "duration_target_min"
    private const val KEY_FOREGROUND_RUNNING = "foreground_running"
    private const val KEY_RESUME_REMINDER_PENDING = "resume_reminder_pending"

    fun isProtectionActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ACTIVE, false)
    }

    fun protectionStartedAt(context: Context): Long {
        return prefs(context).getLong(KEY_STARTED_AT, 0L)
    }

    fun currentMode(context: Context): ServiceMode {
        val raw = prefs(context).getString(KEY_MODE, ServiceMode.NORMAL.name) ?: ServiceMode.NORMAL.name
        return ServiceMode.entries.firstOrNull { it.name == raw } ?: ServiceMode.NORMAL
    }

    fun targetDurationMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_DURATION_TARGET_MIN, 0)
    }

    fun setMode(context: Context, mode: ServiceMode) {
        prefs(context).edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun startProtection(
        context: Context,
        mode: ServiceMode,
        durationTargetMinutes: Int = 0
    ) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_AT, now)
            .putString(KEY_MODE, mode.name)
            .putInt(KEY_DURATION_TARGET_MIN, durationTargetMinutes)
            .apply()
    }

    fun stopProtection(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_STARTED_AT, 0L)
            .putInt(KEY_DURATION_TARGET_MIN, 0)
            .putBoolean(KEY_FOREGROUND_RUNNING, false)
            .apply()
    }

    fun setForegroundServiceRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOREGROUND_RUNNING, running).apply()
    }

    fun isForegroundServiceRunning(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FOREGROUND_RUNNING, false)
    }

    fun markResumeReminderPending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESUME_REMINDER_PENDING, pending).apply()
    }

    fun consumeResumeReminderPending(context: Context): Boolean {
        val pending = prefs(context).getBoolean(KEY_RESUME_REMINDER_PENDING, false)
        if (pending) {
            prefs(context).edit().putBoolean(KEY_RESUME_REMINDER_PENDING, false).apply()
        }
        return pending
    }

    fun registerSessionListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterSessionListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
