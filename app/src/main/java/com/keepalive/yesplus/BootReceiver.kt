package com.keepalive.yesplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot receiver to ensure the accessibility service status is checked on device boot.
 * Note: Android automatically re-enables accessibility services that were enabled before reboot.
 * This receiver serves as a safety net and logging mechanism.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StreamKeepAliveBoot"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val safeContext = context ?: return
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ProtectionSessionManager.markResumeReminderPending(safeContext, pending = true)
            Log.i(
                TAG,
                "Device booted. Protection session is not auto-started; user can resume manually from app UI."
            )
        }
    }
}
