package com.keepalive.yesplus

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class PlaybackStateNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "PlaybackNotifListener"
        private const val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS_KEY
            ) ?: return false
            val expected = listenerComponent(context)
            return flat.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it.packageName == expected.packageName && it.className == expected.className }
        }

        fun listenerComponent(context: Context): ComponentName {
            return ComponentName(context, PlaybackStateNotificationListenerService::class.java)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        PlaybackStateManager.updateCapabilities(
            mediaSessionAccessAvailable = PlaybackStateManager.snapshot().mediaSessionAccessAvailable,
            notificationListenerEnabled = true
        )
        Log.i(TAG, "[PLAYBACK] notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        PlaybackStateManager.updateCapabilities(
            mediaSessionAccessAvailable = PlaybackStateManager.snapshot().mediaSessionAccessAvailable,
            notificationListenerEnabled = false
        )
        Log.w(TAG, "[PLAYBACK] notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val safe = sbn ?: return
        processNotification(safe)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val safe = sbn ?: return
        processNotification(safe)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        if (!PackagePolicy.isStreamingPackage(packageName)) return

        val n = sbn.notification ?: return
        val isMedia =
            n.category == Notification.CATEGORY_TRANSPORT ||
                n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        if (!isMedia) return

        val playingLikely = sbn.isOngoing
        val changed = PlaybackStateManager.updateFromNotification(
            packageName = packageName,
            playingLikely = playingLikely,
            listenerEnabled = true,
            nowElapsed = SystemClock.elapsedRealtime()
        )

        if (changed) {
            val resolver = PlaybackSignalResolver(this)
            val probe = resolver.probeFromMediaSession(listenerComponent(this))
            PlaybackStateManager.updateFromMediaSession(
                packageName = probe.packageName,
                friendlyState = probe.friendlyState,
                accessAvailable = probe.accessAvailable,
                nowElapsed = SystemClock.elapsedRealtime()
            )
            PlaybackStateManager.updateCapabilities(
                mediaSessionAccessAvailable = probe.accessAvailable,
                notificationListenerEnabled = true
            )
            Log.i(
                TAG,
                "[PLAYBACK] state=${PlaybackStateManager.snapshot().friendlyState} source=${PlaybackStateManager.snapshot().source} confidence=${PlaybackStateManager.snapshot().confidence}"
            )
        }
    }
}
