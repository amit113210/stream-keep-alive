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
        assertEquals(profile.heartbeatIntervalMs, PackagePolicy.intervalFor(profile, ServiceMode.DIALOG_ONLY))
    }
}
