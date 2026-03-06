package com.keepalive.yesplus

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

class PlaybackSignalResolver(
    private val context: Context
) {

    data class PlaybackProbeResult(
        val packageName: String?,
        val friendlyState: PlaybackFriendlyState,
        val accessAvailable: Boolean
    )

    fun probeFromMediaSession(listenerComponent: ComponentName?): PlaybackProbeResult {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return PlaybackProbeResult(null, PlaybackFriendlyState.UNKNOWN, accessAvailable = false)

        val sessions = try {
            manager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            return PlaybackProbeResult(null, PlaybackFriendlyState.UNKNOWN, accessAvailable = false)
        } catch (_: Exception) {
            return PlaybackProbeResult(null, PlaybackFriendlyState.UNKNOWN, accessAvailable = false)
        }

        if (sessions.isEmpty()) {
            return PlaybackProbeResult(null, PlaybackFriendlyState.UNKNOWN, accessAvailable = true)
        }

        val supported = sessions
            .filter { PackagePolicy.isStreamingPackage(it.packageName ?: "") }
            .ifEmpty { return PlaybackProbeResult(null, PlaybackFriendlyState.UNKNOWN, accessAvailable = true) }

        val best = supported.maxByOrNull { statePriority(it.playbackState?.state) }
            ?: return PlaybackProbeResult(null, PlaybackFriendlyState.UNKNOWN, accessAvailable = true)

        val friendly = toFriendlyState(best.playbackState)
        return PlaybackProbeResult(best.packageName, friendly, accessAvailable = true)
    }

    private fun toFriendlyState(playbackState: PlaybackState?): PlaybackFriendlyState {
        val state = playbackState?.state ?: return PlaybackFriendlyState.UNKNOWN
        return when (state) {
            PlaybackState.STATE_PLAYING -> PlaybackFriendlyState.PLAYING_ACTIVE
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> PlaybackFriendlyState.PLAYING_ACTIVE
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> PlaybackFriendlyState.PAUSED_OR_IDLE
            else -> PlaybackFriendlyState.UNKNOWN
        }
    }

    private fun statePriority(state: Int?): Int {
        return when (state) {
            PlaybackState.STATE_PLAYING -> 100
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> 90
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> 80
            PlaybackState.STATE_PAUSED -> 50
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR,
            PlaybackState.STATE_NONE -> 20
            else -> 10
        }
    }
}
