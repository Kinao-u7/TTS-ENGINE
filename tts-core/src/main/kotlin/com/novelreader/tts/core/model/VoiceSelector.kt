package com.novelreader.tts.core.model

/**
 * Result of voice selection, including the chosen voice and an optional missing voice warning.
 */
data class VoiceSelectionResult(
    val voice: VoiceInfo?,
    val missingVoiceWarning: String? = null
)

/**
 * Pure Kotlin voice selector following multi-voice novel reading assignment rules.
 */
object VoiceSelector {

    /**
     * Picks an appropriate voice for [character] from [availableVoices].
     *
     * Selection rules:
     * 1. Skip network-required voices (offline only).
     * 2. If character.readByNarrator is true, always use the narrator's voice.
     * 3. If character has an explicit voiceId:
     *    - Use it if present in available voices.
     *    - If missing on the device, fall back to the narrator's voice and set [VoiceSelectionResult.missingVoiceWarning].
     * 4. Otherwise, match [Character.voiceGender] and the book's [bookLocale]:
     *    - Prefer voices not in [usedVoices].
     *    - Fall back to a different locale variant (e.g., en-GB vs en-AU) before reusing an already-used voice.
     *    - Reuse a voice only if all matching variants are exhausted.
     *    - Fall back to the narrator's voice if no suitable voice exists.
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

        // 3. Automated voice resolution based on gender and locale
        val bookLang = extractLanguage(bookLocale)

        fun isExactLocale(voice: VoiceInfo): Boolean {
            return voice.locale.equals(bookLocale, ignoreCase = true)
        }

        fun isSameLanguageVariant(voice: VoiceInfo): Boolean {
            return extractLanguage(voice.locale).equals(bookLang, ignoreCase = true) && !isExactLocale(voice)
        }

        if (character.voiceGender != VoiceGender.ANY) {
            // Try exact gender match first
            val exactGenderVoices = localVoices.filter { it.gender == character.voiceGender }

            // Priority 1a: exact gender + exact locale + unused
            exactGenderVoices.firstOrNull { isExactLocale(it) && it.id !in usedVoices }?.let {
                return VoiceSelectionResult(voice = it)
            }

            // Priority 1b: exact gender + different locale variant + unused
            exactGenderVoices.firstOrNull { isSameLanguageVariant(it) && it.id !in usedVoices }?.let {
                return VoiceSelectionResult(voice = it)
            }

            // Priority 1c: exact gender + any locale + unused
            exactGenderVoices.firstOrNull { it.id !in usedVoices }?.let {
                return VoiceSelectionResult(voice = it)
            }

            // Priority 1d: exact gender + exact locale (reused)
            exactGenderVoices.firstOrNull { isExactLocale(it) }?.let {
                return VoiceSelectionResult(voice = it)
            }

            // Priority 1e: exact gender + different locale variant (reused)
            exactGenderVoices.firstOrNull { isSameLanguageVariant(it) }?.let {
                return VoiceSelectionResult(voice = it)
            }

            // Priority 1f: exact gender + any locale (reused)
            exactGenderVoices.firstOrNull()?.let {
                return VoiceSelectionResult(voice = it)
            }
        }

        // Priority 2: ANY gender voices or character.voiceGender == ANY
        val anyGenderVoices = localVoices.filter { it.gender == VoiceGender.ANY }

        // Priority 2a: ANY gender + exact locale + unused
        anyGenderVoices.firstOrNull { isExactLocale(it) && it.id !in usedVoices }?.let {
            return VoiceSelectionResult(voice = it)
        }

        // Priority 2b: ANY gender + different locale variant + unused
        anyGenderVoices.firstOrNull { isSameLanguageVariant(it) && it.id !in usedVoices }?.let {
            return VoiceSelectionResult(voice = it)
        }

        // Priority 2c: ANY gender + any locale + unused
        anyGenderVoices.firstOrNull { it.id !in usedVoices }?.let {
            return VoiceSelectionResult(voice = it)
        }

        // Priority 2d: ANY gender (reused)
        anyGenderVoices.firstOrNull { isExactLocale(it) }?.let {
            return VoiceSelectionResult(voice = it)
        }

        // Priority 3: Any unused voice
        localVoices.firstOrNull { it.id !in usedVoices }?.let {
            return VoiceSelectionResult(voice = it)
        }

        // Fall back to narrator voice
        return VoiceSelectionResult(voice = narratorVoice)
    }

    private fun extractLanguage(localeTag: String): String {
        return localeTag.split("-", "_").firstOrNull()?.lowercase() ?: localeTag.lowercase()
    }
}
