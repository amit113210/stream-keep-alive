package com.keepalive.yesplus

object PackagePolicy {
    const val DEFAULT_INTERVAL_MS = 30L * 60L * 1000L
    const val NETFLIX_INTERVAL_MS = 45L * 60L * 1000L
    const val YOUTUBE_INTERVAL_MS = 25L * 60L * 1000L
    const val MAX_BACKOFF_INTERVAL_MS = 120L * 60L * 1000L

    private val streamingPackages = listOf(
        "il.co.yes",
        "com.netflix",
        "com.google.android.youtube",
        "com.google.android.apps.youtube",
        "com.disney",
        "com.amazon.avod",
        "com.amazon.firetv",
        "com.apple.atve",
        "com.hbo",
        "com.wbd.stream",
        "com.hulu",
        "com.cellcom",
        "il.co.cellcom",
        "com.partner",
        "il.co.partner",
        "il.co.hot",
        "com.hot",
        "com.spotify",
        "com.plexapp",
        "org.videolan",
        "org.xbmc"
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

    private val intervalOverrides = mapOf(
        "com.netflix" to NETFLIX_INTERVAL_MS,
        "com.google.android.youtube" to YOUTUBE_INTERVAL_MS,
        "com.google.android.apps.youtube" to YOUTUBE_INTERVAL_MS
    )

    fun isIgnoredPackage(packageName: String): Boolean {
        return ignoredPackages.any { packageName.startsWith(it) }
    }

    fun isStreamingPackage(packageName: String): Boolean {
        if (isIgnoredPackage(packageName)) return false
        return streamingPackages.any { packageName.startsWith(it) }
    }

    fun intervalForPackage(packageName: String): Long {
        for ((prefix, intervalMs) in intervalOverrides) {
            if (packageName.startsWith(prefix)) return intervalMs
        }
        return DEFAULT_INTERVAL_MS
    }
}
