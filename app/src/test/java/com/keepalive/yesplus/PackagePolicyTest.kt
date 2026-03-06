package com.keepalive.yesplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagePolicyTest {

    @Test
    fun `ignored package is not streaming`() {
        assertTrue(PackagePolicy.isIgnoredPackage("com.keepalive.yesplus"))
        assertFalse(PackagePolicy.isStreamingPackage("com.keepalive.yesplus"))
        assertFalse(PackagePolicy.isStreamingPackage("com.google.android.tvlauncher"))
    }

    @Test
    fun `known streaming package resolves profile`() {
        val netflixProfile = PackagePolicy.profileForPackage("com.netflix.ninja")
        val youtubeProfile = PackagePolicy.profileForPackage("com.google.android.youtube.tv")

        assertNotNull(netflixProfile)
        assertNotNull(youtubeProfile)
        assertTrue(PackagePolicy.isStreamingPackage("com.netflix.ninja"))
        assertTrue(PackagePolicy.isStreamingPackage("com.google.android.youtube.tv"))
    }

    @Test
    fun `interval is mode aware`() {
        val profile = PackagePolicy.profileForPackage("com.netflix.ninja")!!

        assertEquals(profile.heartbeatIntervalMs, PackagePolicy.intervalFor(profile, ServiceMode.NORMAL))
        assertEquals(profile.aggressiveHeartbeatIntervalMs, PackagePolicy.intervalFor(profile, ServiceMode.AGGRESSIVE))
        val maximum = PackagePolicy.intervalFor(profile, ServiceMode.MAXIMUM)
        // MAXIMUM should be at least the minimum guard and no longer than aggressive.
        assertTrue(maximum <= profile.aggressiveHeartbeatIntervalMs)
        assertTrue(maximum >= 45_000L)
        assertEquals(profile.heartbeatIntervalMs, PackagePolicy.intervalFor(profile, ServiceMode.DIALOG_ONLY))
        assertTrue(PackagePolicy.intervalFor(profile, ServiceMode.MAXIMUM) <= profile.aggressiveHeartbeatIntervalMs)
        assertTrue(PackagePolicy.intervalFor(profile, ServiceMode.MAXIMUM) >= 45_000L)
    }

    @Test
    fun `maximum mode has hard floor and is aggressive`() {
        val profile = PackagePolicy.profileForPackage("com.netflix.ninja")!!
        val maximum = PackagePolicy.intervalFor(profile, ServiceMode.MAXIMUM)

        assertTrue(maximum <= profile.aggressiveHeartbeatIntervalMs)
        assertTrue(maximum >= 45_000L)
    }

    @Test
    fun `dialog profiles include reusable hunter settings`() {
        val netflix = PackagePolicy.profileForPackage("com.netflix.ninja")!!
        val youtube = PackagePolicy.profileForPackage("com.google.android.youtube.tv")!!
        val yes = PackagePolicy.profileForPackage("il.co.yes.tv")!!

        assertTrue(netflix.dialogPriority)
        assertTrue(netflix.dialogObserverIntervalMs in 1_000L..5_000L)
        assertTrue(netflix.dialogPositivePhrases.isNotEmpty())
        assertTrue(netflix.dialogConfirmPhrases.isNotEmpty())
        assertTrue(netflix.dialogNegativePhrases.isNotEmpty())
        assertFalse(netflix.allowGenericFallbackAfterPositiveMatch)

        assertTrue(youtube.dialogPriority)
        assertTrue(youtube.dialogPositivePhrases.isNotEmpty())
        assertTrue(youtube.dialogConfirmPhrases.isNotEmpty())
        assertTrue(yes.dialogPriority)
        assertTrue(yes.dialogPositivePhrases.isNotEmpty())
    }
}
