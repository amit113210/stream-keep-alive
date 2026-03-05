package com.keepalive.yesplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagePolicyTest {

    @Test
    fun `ignored package is not streaming`() {
        assertFalse(PackagePolicy.isStreamingPackage("com.keepalive.yesplus"))
        assertFalse(PackagePolicy.isStreamingPackage("com.google.android.tvlauncher"))
    }

    @Test
    fun `known streaming package is detected`() {
        assertTrue(PackagePolicy.isStreamingPackage("com.netflix.ninja"))
        assertTrue(PackagePolicy.isStreamingPackage("com.google.android.youtube.tv"))
    }

    @Test
    fun `interval override and default are resolved`() {
        assertEquals(PackagePolicy.NETFLIX_INTERVAL_MS, PackagePolicy.intervalForPackage("com.netflix.ninja"))
        assertEquals(PackagePolicy.YOUTUBE_INTERVAL_MS, PackagePolicy.intervalForPackage("com.google.android.youtube"))
        assertEquals(PackagePolicy.DEFAULT_INTERVAL_MS, PackagePolicy.intervalForPackage("com.unknown.stream"))
    }
}
