package com.keepalive.yesplus

import android.os.SystemClock

enum class PlaybackSignalSource {
    MEDIA_SESSION,
    NOTIFICATION,
    FALLBACK,
    NONE
}

enum class PlaybackConfidence {
    HIGH,
    MEDIUM,
    LOW
}

enum class PlaybackFriendlyState {
    PLAYING_ACTIVE,
    PAUSED_OR_IDLE,
    UNKNOWN
}

data class PlaybackSnapshot(
    val activePlaybackPackage: String? = null,
    val friendlyState: PlaybackFriendlyState = PlaybackFriendlyState.UNKNOWN,
    val source: PlaybackSignalSource = PlaybackSignalSource.NONE,
    val confidence: PlaybackConfidence = PlaybackConfidence.LOW,
    val lastStateChangeAt: Long = 0L,
    val lastPackageReported: String? = null,
    val mediaSessionAccessAvailable: Boolean = false,
    val notificationListenerEnabled: Boolean = false
)

object PlaybackStateManager {
    private const val STALE_PLAYBACK_MS = 3L * 60L * 1000L
    private const val MEDIUM_FALLBACK_WINDOW_MS = 90_000L

    @Volatile
    private var snapshot = PlaybackSnapshot()

    fun snapshot(): PlaybackSnapshot = snapshot

    fun isPlaybackActive(): Boolean {
        val s = clearStalePlaybackState(SystemClock.elapsedRealtime())
        return s.friendlyState == PlaybackFriendlyState.PLAYING_ACTIVE
    }

    fun activePlaybackPackage(): String? {
        return clearStalePlaybackState(SystemClock.elapsedRealtime()).activePlaybackPackage
    }

    fun updateFromMediaSession(
        packageName: String?,
        friendlyState: PlaybackFriendlyState,
        accessAvailable: Boolean,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val nextConfidence = when (friendlyState) {
            PlaybackFriendlyState.PLAYING_ACTIVE -> PlaybackConfidence.HIGH
            PlaybackFriendlyState.PAUSED_OR_IDLE -> PlaybackConfidence.MEDIUM
            PlaybackFriendlyState.UNKNOWN -> PlaybackConfidence.LOW
        }
        return update(
            packageName = packageName,
            friendlyState = friendlyState,
            source = PlaybackSignalSource.MEDIA_SESSION,
            confidence = nextConfidence,
            nowElapsed = nowElapsed,
            accessAvailable = accessAvailable,
            notificationEnabled = snapshot.notificationListenerEnabled
        )
    }

    fun updateFromNotification(
        packageName: String,
        playingLikely: Boolean,
        listenerEnabled: Boolean,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val state = if (playingLikely) PlaybackFriendlyState.PLAYING_ACTIVE else PlaybackFriendlyState.PAUSED_OR_IDLE
        val confidence = if (playingLikely) PlaybackConfidence.MEDIUM else PlaybackConfidence.LOW
        return update(
            packageName = packageName,
            friendlyState = state,
            source = PlaybackSignalSource.NOTIFICATION,
            confidence = confidence,
            nowElapsed = nowElapsed,
            accessAvailable = snapshot.mediaSessionAccessAvailable,
            notificationEnabled = listenerEnabled
        )
    }

    fun updateFromFallback(
        packageName: String,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val current = snapshot
        val changed =
            current.activePlaybackPackage != packageName ||
                current.friendlyState != PlaybackFriendlyState.UNKNOWN ||
                current.source != PlaybackSignalSource.FALLBACK ||
                current.confidence != PlaybackConfidence.LOW

        if (changed) {
            snapshot = current.copy(
                activePlaybackPackage = packageName,
                friendlyState = PlaybackFriendlyState.UNKNOWN,
                source = PlaybackSignalSource.FALLBACK,
                confidence = PlaybackConfidence.LOW,
                lastStateChangeAt = nowElapsed,
                lastPackageReported = packageName
            )
        }
        return changed
    }

    fun updateCapabilities(
        mediaSessionAccessAvailable: Boolean,
        notificationListenerEnabled: Boolean
    ) {
        snapshot = snapshot.copy(
            mediaSessionAccessAvailable = mediaSessionAccessAvailable,
            notificationListenerEnabled = notificationListenerEnabled
        )
    }

    fun shouldRunHeartbeatNow(
        sessionActive: Boolean,
        supportedForegroundPackage: String?,
        dialogPendingOverride: Boolean = false
    ): Pair<Boolean, String> {
        if (!sessionActive) return false to "session_inactive"
        val foreground = supportedForegroundPackage ?: return false to "unsupported_package"

        val s = clearStalePlaybackState(SystemClock.elapsedRealtime())
        val packageMatch = s.activePlaybackPackage == null || s.activePlaybackPackage == foreground

        return when (s.friendlyState) {
            PlaybackFriendlyState.PLAYING_ACTIVE -> {
                if (packageMatch) true to "playing_high_confidence" else false to "playing_other_package"
            }
            PlaybackFriendlyState.PAUSED_OR_IDLE -> {
                if (dialogPendingOverride) {
                    true to "paused_but_dialog_pending"
                } else {
                    false to "paused_or_idle"
                }
            }
            PlaybackFriendlyState.UNKNOWN -> {
                val age = SystemClock.elapsedRealtime() - s.lastStateChangeAt
                if (s.source == PlaybackSignalSource.MEDIA_SESSION && age <= MEDIUM_FALLBACK_WINDOW_MS && packageMatch) {
                    true to "recent_media_signal_medium_confidence"
                } else {
                    true to "fallback_package_only_low_confidence"
                }
            }
        }
    }

    fun clearStalePlaybackState(nowElapsed: Long): PlaybackSnapshot {
        val current = snapshot
        if (current.lastStateChangeAt == 0L) return current

        val stale = nowElapsed - current.lastStateChangeAt > STALE_PLAYBACK_MS
        if (!stale) return current

        val cleared = current.copy(
            activePlaybackPackage = null,
            friendlyState = PlaybackFriendlyState.UNKNOWN,
            source = PlaybackSignalSource.NONE,
            confidence = PlaybackConfidence.LOW,
            lastStateChangeAt = nowElapsed
        )
        snapshot = cleared
        return cleared
    }

    private fun update(
        packageName: String?,
        friendlyState: PlaybackFriendlyState,
        source: PlaybackSignalSource,
        confidence: PlaybackConfidence,
        nowElapsed: Long,
        accessAvailable: Boolean,
        notificationEnabled: Boolean
    ): Boolean {
        val current = snapshot
        val changed =
            current.activePlaybackPackage != packageName ||
                current.friendlyState != friendlyState ||
                current.source != source ||
                current.confidence != confidence ||
                current.mediaSessionAccessAvailable != accessAvailable ||
                current.notificationListenerEnabled != notificationEnabled

        if (changed) {
            snapshot = PlaybackSnapshot(
                activePlaybackPackage = packageName,
                friendlyState = friendlyState,
                source = source,
                confidence = confidence,
                lastStateChangeAt = nowElapsed,
                lastPackageReported = packageName ?: current.lastPackageReported,
                mediaSessionAccessAvailable = accessAvailable,
                notificationListenerEnabled = notificationEnabled
            )
        }

        return changed
    }
}
