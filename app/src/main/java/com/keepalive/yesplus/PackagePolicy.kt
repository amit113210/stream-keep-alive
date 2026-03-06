package com.keepalive.yesplus

enum class HeartbeatAction {
    MICRO_TAP,
    MICRO_SWIPE,
    HYBRID
}

enum class ServiceMode {
    NORMAL,
    AGGRESSIVE,
    DIALOG_ONLY,
    // Personal-use ultra mode: shorter heartbeat intervals for maximum persistence attempts.
    MAXIMUM
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
    val dialogPriority: Boolean = false,
    val dialogObserverIntervalMs: Long = 2_500L,
    val dialogRecentPlaybackGraceMs: Long = 8_000L,
    val dialogPositivePhrases: List<String> = emptyList(),
    val dialogConfirmPhrases: List<String> = emptyList(),
    val dialogNegativePhrases: List<String> = emptyList(),
    val allowGenericFallbackAfterPositiveMatch: Boolean = true,
    val useBoundsTapFallback: Boolean = true,
    val dialogKeywords: List<String> = emptyList(),
    val confirmKeywords: List<String> = emptyList()
)

object PackagePolicy {
    private const val NETFLIX_NORMAL_MS = 90_000L
    private const val NETFLIX_AGGRESSIVE_MS = 70_000L
    private const val YOUTUBE_NORMAL_MS = 120_000L
    private const val YOUTUBE_AGGRESSIVE_MS = 90_000L
    private const val DEFAULT_NORMAL_MS = 135_000L
    private const val DEFAULT_AGGRESSIVE_MS = 105_000L
    private const val DEFAULT_MAXIMUM_MIN_MS = 45_000L
    private const val JITTER_6S_MS = 6_000L
    private const val JITTER_8S_MS = 8_000L
    private const val OBSERVER_1S_MS = 1_000L
    private const val OBSERVER_2S_MS = 2_000L
    private const val OBSERVER_3S_MS = 3_000L
    private const val OBSERVER_GRACE_6S_MS = 6_000L
    private const val OBSERVER_GRACE_8S_MS = 8_000L
    private const val OBSERVER_GRACE_10S_MS = 10_000L

    private val defaultSafeZones = listOf(
        SafeZone(xPercent = 0.08f, yPercent = 0.10f, radiusPx = 10),
        SafeZone(xPercent = 0.92f, yPercent = 0.10f, radiusPx = 10),
        SafeZone(xPercent = 0.08f, yPercent = 0.90f, radiusPx = 10)
    )

    val genericDialogPositivePhrases = listOf(
        "still watching",
        "are you still watching",
        "continue watching",
        "resume watching",
        "video paused",
        "inactivity",
        "עדיין צופה",
        "עדיין צופה בכותרת",
        "האם אתה עדיין צופה",
        "המשך צפייה",
        "להמשיך לצפות"
    )

    val genericDialogConfirmPhrases = listOf(
        "continue",
        "resume",
        "yes",
        "keep watching",
        "watch now",
        "המשך",
        "המשך צפייה",
        "אישור",
        "כן",
        "הפעל בלי לשאול שוב",
        "בלי לשאול שוב"
    )

    val genericDialogNegativePhrases = listOf(
        "cancel",
        "close",
        "not now",
        "later",
        "exit",
        "done",
        "לא עכשיו",
        "מאוחר יותר",
        "סיימתי",
        "שאל אותי שוב מאוחר יותר"
    )

    private val netflixDialogPositivePhrases = listOf(
        "עדיין צופה",
        "עדיין צופה בכותרת",
        "האם אתה עדיין צופה",
        "still watching",
        "continue watching"
    )

    private val netflixDialogConfirmPhrases = listOf(
        "הפעל בלי לשאול שוב",
        "בלי לשאול שוב",
        "הפעל ללא שאלות נוספות",
        "הפעל תמיד ללא שאלות",
        "continue",
        "keep watching"
    )

    private val netflixDialogNegativePhrases = listOf(
        "שאל אותי שוב מאוחר יותר",
        "מאוחר יותר",
        "סיימתי",
        "not now",
        "later"
    )

    private val youtubeDialogPositivePhrases = listOf(
        "video paused",
        "still watching",
        "are you still there",
        "continue watching",
        "inactivity",
        "עדיין צופה",
        "המשך צפייה"
    )

    private val youtubeDialogConfirmPhrases = listOf(
        "yes",
        "continue",
        "keep watching",
        "resume",
        "כן",
        "המשך"
    )

    val streamingProfiles: List<StreamingAppProfile> = listOf(
        StreamingAppProfile(
            packagePrefix = "com.netflix",
            heartbeatIntervalMs = NETFLIX_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = NETFLIX_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_6S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = listOf(
                SafeZone(0.08f, 0.08f, 10),
                SafeZone(0.92f, 0.08f, 10)
            ),
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_1S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_8S_MS,
            dialogPositivePhrases = netflixDialogPositivePhrases,
            dialogConfirmPhrases = netflixDialogConfirmPhrases,
            dialogNegativePhrases = netflixDialogNegativePhrases,
            allowGenericFallbackAfterPositiveMatch = false,
            useBoundsTapFallback = true,
            dialogKeywords = listOf("who's watching", "continue watching"),
            confirmKeywords = listOf("continue", "resume", "keep watching")
        ),
        StreamingAppProfile(
            packagePrefix = "com.google.android.youtube",
            heartbeatIntervalMs = YOUTUBE_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = YOUTUBE_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_SWIPE,
            safeZones = listOf(
                SafeZone(0.06f, 0.08f, 10),
                SafeZone(0.94f, 0.08f, 10)
            ),
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = youtubeDialogPositivePhrases,
            dialogConfirmPhrases = youtubeDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases,
            dialogKeywords = listOf("video paused", "still watching", "are you still there"),
            confirmKeywords = listOf("yes", "continue", "keep watching")
        ),
        StreamingAppProfile(
            packagePrefix = "com.google.android.apps.youtube",
            heartbeatIntervalMs = YOUTUBE_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = YOUTUBE_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_SWIPE,
            safeZones = listOf(
                SafeZone(0.06f, 0.08f, 10),
                SafeZone(0.94f, 0.08f, 10)
            ),
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = youtubeDialogPositivePhrases,
            dialogConfirmPhrases = youtubeDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.yes",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.disney",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.amazon.avod",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.amazon.firetv",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.apple.atve",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_TAP,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.hbo",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.wbd.stream",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.hulu",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.cellcom",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.cellcom",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.partner",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.partner",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "il.co.hot",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.hot",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_2S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.spotify",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_TAP,
            safeZones = defaultSafeZones,
            dialogPriority = false,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_6S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "com.plexapp",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "org.videolan",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.MICRO_TAP,
            safeZones = defaultSafeZones,
            dialogPriority = false,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_6S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
        ),
        StreamingAppProfile(
            packagePrefix = "org.xbmc",
            heartbeatIntervalMs = DEFAULT_NORMAL_MS,
            aggressiveHeartbeatIntervalMs = DEFAULT_AGGRESSIVE_MS,
            heartbeatJitterMs = JITTER_8S_MS,
            preferredHeartbeatAction = HeartbeatAction.HYBRID,
            safeZones = defaultSafeZones,
            dialogPriority = true,
            dialogObserverIntervalMs = OBSERVER_3S_MS,
            dialogRecentPlaybackGraceMs = OBSERVER_GRACE_10S_MS,
            dialogPositivePhrases = genericDialogPositivePhrases,
            dialogConfirmPhrases = genericDialogConfirmPhrases,
            dialogNegativePhrases = genericDialogNegativePhrases
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
            ServiceMode.MAXIMUM -> maxOf(
                DEFAULT_MAXIMUM_MIN_MS,
                (profile.aggressiveHeartbeatIntervalMs * 0.6f).toLong()
            )
            ServiceMode.DIALOG_ONLY -> profile.heartbeatIntervalMs
        }
    }
}
