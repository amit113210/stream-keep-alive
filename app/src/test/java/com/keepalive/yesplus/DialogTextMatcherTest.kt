package com.keepalive.yesplus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogTextMatcherTest {

    @Test
    fun `dialog keyword detection supports hebrew and english`() {
        assertTrue(DialogTextMatcher.containsDialogKeyword("Are you still watching?"))
        assertTrue(DialogTextMatcher.containsDialogKeyword("האם אתם עדיין צופים"))
        assertFalse(DialogTextMatcher.containsDialogKeyword("settings"))
    }

    @Test
    fun `confirm detection works with text or description`() {
        assertTrue(DialogTextMatcher.containsConfirmKeyword("Continue watching", null))
        assertTrue(DialogTextMatcher.containsConfirmKeyword(null, "כן"))
        assertFalse(DialogTextMatcher.containsConfirmKeyword("cancel", "dismiss"))
    }

    @Test
    fun `event hint detects dialog from class name and text`() {
        assertTrue(
            DialogTextMatcher.eventLooksLikeDialog(
                className = "android.app.AlertDialog",
                eventText = emptyList(),
                contentDescription = null
            )
        )
        assertTrue(
            DialogTextMatcher.eventLooksLikeDialog(
                className = "android.view.View",
                eventText = listOf("Still watching?"),
                contentDescription = null
            )
        )
        assertFalse(
            DialogTextMatcher.eventLooksLikeDialog(
                className = "android.view.View",
                eventText = listOf("Playback controls"),
                contentDescription = "Player overlay"
            )
        )
    }
}
