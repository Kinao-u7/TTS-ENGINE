package com.novelreader.tts.core.model

/**
 * Result of voice selection, including the chosen voice and an optional missing voice warning.
 */
data class VoiceSelectionResult(
    val voice: VoiceInfo?,
    val missingVoiceWarning: String? = null
)

/**
 * Pure Kotlin voice selector following multi-voice novel reading assignment rules:
 * - Assigns distinct voices to characters.
 * - Prefers a different locale, then a different voice name.
 * - Ignores gender when the engine doesn't report it.
 * - Uses the book's locale rather than a hardcoded default.
 */
object VoiceSelector {

    /**
     * Picks an appropriate voice for [character] from [availableVoices].
     */
    fun pick(
        character: Character,
        availableVoices: List<VoiceInfo>,
        usedVoices: Set<String> = emptySet(),
        bookLocale: String = "en-US",
        narratorVoiceId: String? = null
    ): VoiceSelectionResult {
        val localVoices = availableVoices.filter { !it.requiresNetwork }
        val narratorVoice = localVoices.firstOrNull { it.id == narratorVoiceId }
            ?: localVoices.firstOrNull { it.locale.equals(bookLocale, ignoreCase = true) }
            ?: localVoices.firstOrNull()

        // 1. Character designated to be read by narrator
        if (character.readByNarrator) {
            return VoiceSelectionResult(voice = narratorVoice)
        }

        // 2. Explicit voice ID configured
        if (character.voiceId != null) {
            val matched = localVoices.firstOrNull { it.id == character.voiceId }
            return if (matched != null) {
                VoiceSelectionResult(voice = matched)
            } else {
                VoiceSelectionResult(
                    voice = narratorVoice,
                    missingVoiceWarning = "Voice '${character.voiceId}' for '${character.name}' is missing on this device. Falling back to narrator voice."
                )
            }
        }

        if (localVoices.isEmpty()) {
            return VoiceSelectionResult(
                voice = null,
                missingVoiceWarning = "No offline voices available on device."
            )
        }

        // 3. Automated voice resolution
        // "The selector prefers a different locale, then a different voice name, and ignores gender when the engine doesn't report it."
        val engineReportsGender = localVoices.any { it.gender != VoiceGender.ANY }
        val targetGender = if (engineReportsGender) character.voiceGender else VoiceGender.ANY

        val bookLang = extractLanguage(bookLocale)
        val usedVoiceObjects = localVoices.filter { it.id in usedVoices }
        val usedLocales = usedVoiceObjects.map { it.locale.lowercase() }.toSet()
        val usedNames = usedVoiceObjects.map { it.name.lowercase() }.toSet()

        fun isExactLocale(voice: VoiceInfo): Boolean {
            return voice.locale.equals(bookLocale, ignoreCase = true)
        }

        fun isSameLanguageVariant(voice: VoiceInfo): Boolean {
            return extractLanguage(voice.locale).equals(bookLang, ignoreCase = true) && !isExactLocale(voice)
        }

        fun isSameLanguage(voice: VoiceInfo): Boolean {
            return extractLanguage(voice.locale).equals(bookLang, ignoreCase = true)
        }

        // Gender-constrained pool if engine reports gender and character specifies gender
        if (targetGender != VoiceGender.ANY) {
            val genderPool = localVoices.filter { it.gender == targetGender && it.id !in usedVoices }
            if (genderPool.isNotEmpty()) {
                // If character is Unnamed or narrator voice already used, prefer different locale
                if (character.id == Character.UNNAMED_ID && usedLocales.isNotEmpty()) {
                    genderPool.firstOrNull { it.locale.lowercase() !in usedLocales && isSameLanguage(it) }?.let {
                        return VoiceSelectionResult(voice = it)
                    }
                }

                // Priority 1a: exact gender + exact locale + unused
                genderPool.firstOrNull { isExactLocale(it) }?.let {
                    return VoiceSelectionResult(voice = it)
                }

                // Priority 1b: exact gender + different locale variant + unused
                genderPool.firstOrNull { isSameLanguageVariant(it) }?.let {
                    return VoiceSelectionResult(voice = it)
                }

                // Priority 1c: exact gender + any locale + unused
                genderPool.firstOrNull()?.let {
                    return VoiceSelectionResult(voice = it)
                }
            }

            // Fallback for specified gender across reused voices
            val reusedGenderPool = localVoices.filter { it.gender == targetGender }
            reusedGenderPool.firstOrNull { isExactLocale(it) }?.let {
                return VoiceSelectionResult(voice = it)
            }
            reusedGenderPool.firstOrNull { isSameLanguageVariant(it) }?.let {
                return VoiceSelectionResult(voice = it)
            }
            reusedGenderPool.firstOrNull()?.let {
                return VoiceSelectionResult(voice = it)
            }
        }

        // When gender is ANY (or engine does not report gender):
        // "The selector prefers a different locale, then a different voice name, and ignores gender when the engine doesn't report it."
        val unusedVoices = localVoices.filter { it.id !in usedVoices }
        if (unusedVoices.isNotEmpty()) {
            if (usedLocales.isNotEmpty()) {
                // 1. Different locale, same book language
                unusedVoices.firstOrNull { it.locale.lowercase() !in usedLocales && isSameLanguage(it) }?.let {
                    return VoiceSelectionResult(voice = it)
                }
                // 2. Different locale, any language
                unusedVoices.firstOrNull { it.locale.lowercase() !in usedLocales }?.let {
                    return VoiceSelectionResult(voice = it)
                }
                // 3. Different voice name (same locale)
                unusedVoices.firstOrNull { it.name.lowercase() !in usedNames && isExactLocale(it) }?.let {
                    return VoiceSelectionResult(voice = it)
                }
                // 4. Any unused voice with different name
                unusedVoices.firstOrNull { it.name.lowercase() !in usedNames }?.let {
                    return VoiceSelectionResult(voice = it)
                }
            } else {
                // First run / no used voices (e.g. picking for Narrator):
                // Prefer exact book locale
                unusedVoices.firstOrNull { isExactLocale(it) }?.let {
                    return VoiceSelectionResult(voice = it)
                }
                // Same language variant
                unusedVoices.firstOrNull { isSameLanguageVariant(it) }?.let {
                    return VoiceSelectionResult(voice = it)
                }
            }

            unusedVoices.firstOrNull()?.let {
                return VoiceSelectionResult(voice = it)
            }
        }

        // Reused voices fallback
        if (usedLocales.isNotEmpty()) {
            localVoices.firstOrNull { it.locale.lowercase() !in usedLocales && isSameLanguage(it) }?.let {
                return VoiceSelectionResult(voice = it)
            }
        }
        localVoices.firstOrNull { isExactLocale(it) }?.let {
            return VoiceSelectionResult(voice = it)
        }

        return VoiceSelectionResult(voice = narratorVoice)
    }

    private fun extractLanguage(localeTag: String): String {
        return localeTag.split("-", "_").firstOrNull()?.lowercase() ?: localeTag.lowercase()
    }
}
