package com.novelreader.tts.ui

import java.util.Locale

/**
 * Formats a BCP-47 language tag into a human-readable display string.
 */
fun formatReadableLocale(localeTag: String): String {
    return try {
        val tag = localeTag.replace('_', '-')
        val locale = Locale.forLanguageTag(tag)
        val lang = locale.getDisplayLanguage(Locale.ENGLISH)
        val country = locale.getDisplayCountry(Locale.ENGLISH)
        if (country.isNotBlank()) "$lang ($country)" else if (lang.isNotBlank()) lang else localeTag
    } catch (_: Exception) {
        localeTag
    }
}
