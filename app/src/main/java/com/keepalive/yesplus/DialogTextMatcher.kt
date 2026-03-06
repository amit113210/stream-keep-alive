package com.keepalive.yesplus

object DialogTextMatcher {
    val dialogKeywords = listOf(
        "עדיין צופים",
        "האם אתם עדיין צופים",
        "ממשיכים לצפות",
        "האם את/ה עדיין צופה",
        "עדיין צופה",
        "עדיין צופה בכותרת",
        "האם אתה עדיין צופה",
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
        "הפעל בלי לשאול שוב",
        "הפעל ללא שאלות נוספות",
        "הפעל תמיד ללא שאלות",
        "בלי לשאול שוב",
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
        "אל תציג שוב",
        "שאל אותי שוב מאוחר יותר",
        "מאוחר יותר",
        "סיימתי",
        "later",
        "cancel",
        "dismiss",
        "close",
        "exit",
        "no",
        "no thanks",
        "not now",
        "stop",
        "skip"
    )

    fun findDialogPhrase(value: CharSequence?, additionalKeywords: List<String> = emptyList()): String? {
        return firstMatchingPhrase(value, mergeKeywords(dialogKeywords, additionalKeywords))
    }

    fun findDialogPhrase(visibleTexts: List<String>, additionalKeywords: List<String> = emptyList()): String? {
        if (visibleTexts.isEmpty()) return null
        val merged = mergeKeywords(dialogKeywords, additionalKeywords)
        for (text in visibleTexts) {
            val matched = firstMatchingPhrase(text, merged)
            if (matched != null) return matched
        }
        return null
    }

    fun containsDialogKeyword(value: CharSequence?, additionalKeywords: List<String> = emptyList()): Boolean {
        return findDialogPhrase(value, additionalKeywords) != null
    }

    fun findConfirmPhrase(
        text: CharSequence?,
        contentDesc: CharSequence?,
        additionalKeywords: List<String> = emptyList()
    ): String? {
        val merged = mergeKeywords(confirmKeywords, additionalKeywords)
        val textMatch = firstMatchingPhrase(text, merged)
        if (textMatch != null) return textMatch
        return firstMatchingPhrase(contentDesc, merged)
    }

    fun containsConfirmKeyword(
        text: CharSequence?,
        contentDesc: CharSequence?,
        additionalKeywords: List<String> = emptyList()
    ): Boolean {
        return findConfirmPhrase(text, contentDesc, additionalKeywords) != null
    }

    fun findNegativePhrase(
        text: CharSequence?,
        contentDesc: CharSequence?,
        additionalKeywords: List<String> = emptyList()
    ): String? {
        val merged = mergeKeywords(negativeKeywords, additionalKeywords)
        val textMatch = firstMatchingPhrase(text, merged)
        if (textMatch != null) return textMatch
        return firstMatchingPhrase(contentDesc, merged)
    }

    fun containsNegativeKeyword(
        text: CharSequence?,
        contentDesc: CharSequence?,
        additionalKeywords: List<String> = emptyList()
    ): Boolean {
        return findNegativePhrase(text, contentDesc, additionalKeywords) != null
    }

    fun eventLooksLikeDialog(
        className: CharSequence?,
        eventText: List<CharSequence>?,
        contentDescription: CharSequence?,
        additionalKeywords: List<String> = emptyList()
    ): Boolean {
        val classStr = className?.toString()?.lowercase().orEmpty()
        if (classStr.contains("dialog") || classStr.contains("alert")) return true

        if (containsDialogKeyword(contentDescription, additionalKeywords)) return true

        if (eventText != null) {
            for (entry in eventText) {
                if (containsDialogKeyword(entry, additionalKeywords)) return true
            }
        }

        return false
    }

    private fun mergeKeywords(primary: List<String>, secondary: List<String>): List<String> {
        if (secondary.isEmpty()) return primary
        return primary + secondary
    }

    private fun firstMatchingPhrase(value: CharSequence?, phrases: List<String>): String? {
        val normalized = value?.toString()?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return null
        return phrases.firstOrNull { phrase -> normalized.contains(phrase.lowercase()) }
    }
}
