package com.keepalive.yesplus

import android.content.Context
import android.os.Build
import android.provider.Settings

object ScreenTimeoutManager {
    private const val PREFS_NAME = "screen_timeout_hardening"
    private const val KEY_ORIGINAL_TIMEOUT_MS = "original_timeout_ms"
    private const val KEY_TIMEOUT_APPLIED = "timeout_applied"
    private const val KEY_REQUESTED_TIMEOUT_MS = "requested_timeout_ms"
    private const val KEY_LAST_APPLY_AT = "last_apply_at"
    private const val KEY_LAST_RESTORE_AT = "last_restore_at"

    const val SESSION_TIMEOUT_MS: Long = 24L * 60L * 60L * 1000L

    fun canWriteSystemSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.System.canWrite(context)
    }

    fun readCurrentScreenOffTimeoutMs(context: Context): Long? {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT
            ).toLong()
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun applyLongTimeoutForSession(context: Context): Boolean {
        if (!canWriteSystemSettings(context)) return false

        val prefs = prefs(context)
        if (!prefs.contains(KEY_ORIGINAL_TIMEOUT_MS)) {
            val original = readCurrentScreenOffTimeoutMs(context) ?: return false
            if (original != SESSION_TIMEOUT_MS) {
                prefs.edit().putLong(KEY_ORIGINAL_TIMEOUT_MS, original).apply()
            }
        }

        val applied = writeScreenOffTimeoutMs(context, SESSION_TIMEOUT_MS)
        if (!applied) return false

        prefs.edit()
            .putBoolean(KEY_TIMEOUT_APPLIED, true)
            .putLong(KEY_REQUESTED_TIMEOUT_MS, SESSION_TIMEOUT_MS)
            .putLong(KEY_LAST_APPLY_AT, System.currentTimeMillis())
            .apply()
        return true
    }

    fun restoreOriginalTimeoutIfNeeded(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_ORIGINAL_TIMEOUT_MS)) return false
        if (!canWriteSystemSettings(context)) return false

        val original = prefs.getLong(KEY_ORIGINAL_TIMEOUT_MS, -1L)
        if (original <= 0L) return false

        val restored = writeScreenOffTimeoutMs(context, original)
        if (!restored) return false

        prefs.edit()
            .remove(KEY_ORIGINAL_TIMEOUT_MS)
            .putBoolean(KEY_TIMEOUT_APPLIED, false)
            .remove(KEY_REQUESTED_TIMEOUT_MS)
            .putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis())
            .apply()
        return true
    }

    fun restoreIfSessionInactive(context: Context, sessionActive: Boolean): Boolean {
        if (sessionActive) return false
        if (!isSessionTimeoutApplied(context)) return false
        return restoreOriginalTimeoutIfNeeded(context)
    }

    fun isSessionTimeoutApplied(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TIMEOUT_APPLIED, false)
    }

    fun savedOriginalTimeoutMs(context: Context): Long? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_ORIGINAL_TIMEOUT_MS)) return null
        val value = prefs.getLong(KEY_ORIGINAL_TIMEOUT_MS, -1L)
        return if (value > 0L) value else null
    }

    fun currentRequestedTimeoutMs(context: Context): Long? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_TIMEOUT_APPLIED, false)) return null
        if (!prefs.contains(KEY_REQUESTED_TIMEOUT_MS)) return null
        val value = prefs.getLong(KEY_REQUESTED_TIMEOUT_MS, -1L)
        return if (value > 0L) value else null
    }

    fun lastApplyAt(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_APPLY_AT, 0L)
    }

    fun lastRestoreAt(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_RESTORE_AT, 0L)
    }

    private fun writeScreenOffTimeoutMs(context: Context, timeoutMs: Long): Boolean {
        val bounded = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong())
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                bounded.toInt()
            )
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
