package com.keepalive.yesplus

object DialogTextMatcher {
    val dialogKeywords = listOf(
        "עדיין צופים",
        "האם אתם עדיין צופים",
        "ממשיכים לצפות",
        "האם את/ה עדיין צופה",
        "עדיין כאן",
        "עדיין פה",
        "להמשיך בצפייה",
        "האם להמשיך",
        "still watching",
        "are you still watching",
        "are you still there",
        "continue watching",
        "continue playing",
        "playing without asking",
        "video paused",
        "still there",
        "are you still listening",
        "inactivity"
    )

    val confirmKeywords = listOf(
        "כן",
        "המשך",
        "המשך צפייה",
        "אישור",
        "אוקיי",
        "המשך לצפות",
        "נגן מבלי לשאול שוב",
        "yes",
        "continue",
        "continue watching",
        "keep watching",
        "ok",
        "i'm here",
        "yes, continue",
        "resume",
        "play",
        "still here",
        "play without asking again"
    )

    val negativeKeywords = listOf(
        "ביטול",
        "לא",
        "לא עכשיו",
        "סגור",
        "סגירה",
        "יציאה",
        "חזור",
        "cancel",
        "dismiss",
        "close",
        "exit",
        "no",
        "no thanks",
        "not now",
        "stop"
    )

    fun containsDialogKeyword(value: CharSequence?): Boolean {
        val normalized = value?.toString()?.lowercase() ?: return false
        return dialogKeywords.any { normalized.contains(it.lowercase()) }
    }

    fun containsConfirmKeyword(text: CharSequence?, contentDesc: CharSequence?): Boolean {
        val normalizedText = text?.toString()?.lowercase().orEmpty()
        val normalizedDesc = contentDesc?.toString()?.lowercase().orEmpty()
        return confirmKeywords.any { keyword ->
            val kw = keyword.lowercase()
            normalizedText.contains(kw) || normalizedDesc.contains(kw)
        }
    }

    fun containsNegativeKeyword(text: CharSequence?, contentDesc: CharSequence?): Boolean {
        val normalizedText = text?.toString()?.lowercase().orEmpty()
        val normalizedDesc = contentDesc?.toString()?.lowercase().orEmpty()
        return negativeKeywords.any { keyword ->
            val kw = keyword.lowercase()
            normalizedText.contains(kw) || normalizedDesc.contains(kw)
        }
    }

    fun eventLooksLikeDialog(
        className: CharSequence?,
        eventText: List<CharSequence>?,
        contentDescription: CharSequence?
    ): Boolean {
        val classStr = className?.toString()?.lowercase().orEmpty()
        if (classStr.contains("dialog") || classStr.contains("alert")) return true

        if (containsDialogKeyword(contentDescription)) return true

        if (eventText != null) {
            for (entry in eventText) {
                if (containsDialogKeyword(entry)) return true
            }
        }

        return false
    }
}
