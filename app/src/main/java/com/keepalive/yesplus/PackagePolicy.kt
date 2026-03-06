package com.keepalive.yesplus

enum class HeartbeatAction {
    MICRO_TAP,
    MICRO_SWIPE,
    HYBRID
}

enum class ServiceMode {
    NORMAL,
    AGGRESSIVE,
    DIALOG_ONLY
}

data class SafeZone(
    val xPercent: Float,
    val yPercent: Float,
    val radiusPx: Int = 8
)

data class StreamingAppProfile(
    val packagePrefix: String,
    val heartbeatIntervalMs: Long,
    val aggressiveHeartbeatIntervalMs: Long,
    val heartbeatJitterMs: Long,
    val preferredHeartbeatAction: HeartbeatAction,
    val safeZones: List<SafeZone>,
    val dialogKeywords: List<String> = emptyList(),
    val confirmKeywords: List<String> = emptyList()
)

object PackagePolicy {
    private const val TWO_MIN_MS = 2L * 60L * 1000L
    private const val THREE_MIN_MS = 3L * 60L * 1000L
    private const val JITTER_12S_MS = 12_000L
    private const val JITTER_15S_MS = 15_000L

    private val defaultSafeZones = listOf(
        SafeZone(xPercent = 0.08f, yPercent = 0.10f, radiusPx = 10),
        SafeZone(xPercent = 0.92f, yPercent = 0.10f, radiusPx = 10),
        SafeZone(xPercent = 0.08f, yPercent = 0.90f, radiusPx = 10)
    )

    val streamingProfiles: List<StreamingAppProfile> = listOf(
        StreamingAppProfile(
            packagePrefix = "com.netflix",
            heartbeatIntervalMs = TWO_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = listOf(
                SafeZone(0.08f, 0.08f, 10),
                SafeZone(0.92f, 0.08f, 10)
            ),
            dialogKeywords = listOf("who's watching", "continue watching"),
            confirmKeywords = listOf("continue", "resume", "keep watching")
        ),
        StreamingAppProfile(
            packagePrefix = "com.google.android.youtube",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_SWIPE,
            safeZones = listOf(
                SafeZone(0.06f, 0.08f, 10),
                SafeZone(0.94f, 0.08f, 10)
            ),
            dialogKeywords = listOf("video paused", "still watching", "are you still there"),
            confirmKeywords = listOf("yes", "continue", "keep watching")
        ),
        StreamingAppProfile(
            packagePrefix = "com.google.android.apps.youtube",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_SWIPE,
            safeZones = listOf(
                SafeZone(0.06f, 0.08f, 10),
                SafeZone(0.94f, 0.08f, 10)
            )
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.yes",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.disney",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.amazon.avod",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.amazon.firetv",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.apple.atve",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_TAP,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.hbo",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.wbd.stream",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.hulu",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.cellcom",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.cellcom",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.partner",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.partner",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.hot",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.hot",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.spotify",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_TAP,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "com.plexapp",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "org.videolan",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_12S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_TAP,
            safeZones = defaultSafeZones
        ),
        StreamingAppProfile(
            packagePrefix = "org.xbmc",
            heartbeatIntervalMs = THREE_MIN_MS,
            aggressiveHeartbeatIntervalMs = TWO_MIN_MS,
            heartbeatJitterMs = JITTER_15S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones
        )
    )

    private val ignoredPackages = listOf(
        "com.keepalive.yesplus",
        "com.android.settings",
        "com.android.tv.settings",
        "com.droidlogic.tv.settings",
        "com.android.systemui",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher"
    )

    fun isIgnoredPackage(packageName: String): Boolean {
        return ignoredPackages.any { packageName.startsWith(it) }
    }

    fun isStreamingPackage(packageName: String): Boolean {
        if (isIgnoredPackage(packageName)) return false
        return profileForPackage(packageName) != null
    }

    fun profileForPackage(packageName: String): StreamingAppProfile? {
        return streamingProfiles
            .filter { packageName.startsWith(it.packagePrefix) }
            .maxByOrNull { it.packagePrefix.length }
    }

    fun intervalFor(profile: StreamingAppProfile, mode: ServiceMode): Long {
        return when (mode) {
            ServiceMode.NORMAL -> profile.heartbeatIntervalMs
            ServiceMode.AGGRESSIVE -> profile.aggressiveHeartbeatIntervalMs
            ServiceMode.DIALOG_ONLY -> profile.heartbeatIntervalMs
        }
    }
}
