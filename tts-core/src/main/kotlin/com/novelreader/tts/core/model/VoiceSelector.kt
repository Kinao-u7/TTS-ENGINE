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

        fun matchesGender(voice: VoiceInfo): Boolean {
            return character.voiceGender == VoiceGender.ANY ||
                voice.gender == VoiceGender.ANY ||
                voice.gender == character.voiceGender
        }

        fun isExactLocale(voice: VoiceInfo): Boolean {
            return voice.locale.equals(bookLocale, ignoreCase = true)
        }

        fun isSameLanguageVariant(voice: VoiceInfo): Boolean {
            return extractLanguage(voice.locale).equals(bookLang, ignoreCase = true) && !isExactLocale(voice)
        }

        // Priority a: matching gender + exact locale + unused
        val unusedExact = localVoices.firstOrNull { matchesGender(it) && isExactLocale(it) && it.id !in usedVoices }
        if (unusedExact != null) return VoiceSelectionResult(voice = unusedExact)

        // Priority b: matching gender + different locale variant of same language + unused
        val unusedVariant = localVoices.firstOrNull { matchesGender(it) && isSameLanguageVariant(it) && it.id !in usedVoices }
        if (unusedVariant != null) return VoiceSelectionResult(voice = unusedVariant)

        // Priority c: matching gender + any unused in any locale
        val unusedAnyLocale = localVoices.firstOrNull { matchesGender(it) && it.id !in usedVoices }
        if (unusedAnyLocale != null) return VoiceSelectionResult(voice = unusedAnyLocale)

        // Priority d: reuse voice with exact locale + matching gender
        val reusedExact = localVoices.firstOrNull { matchesGender(it) && isExactLocale(it) }
        if (reusedExact != null) return VoiceSelectionResult(voice = reusedExact)

        // Priority e: reuse voice with different locale variant + matching gender
        val reusedVariant = localVoices.firstOrNull { matchesGender(it) && isSameLanguageVariant(it) }
        if (reusedVariant != null) return VoiceSelectionResult(voice = reusedVariant)

        // Priority f: reuse any voice with matching gender
        val reusedGender = localVoices.firstOrNull { matchesGender(it) }
        if (reusedGender != null) return VoiceSelectionResult(voice = reusedGender)

        // Priority g: any unused voice regardless of gender
        val anyUnused = localVoices.firstOrNull { it.id !in usedVoices }
        if (anyUnused != null) return VoiceSelectionResult(voice = anyUnused)

        // Fall back to narrator voice
        return VoiceSelectionResult(voice = narratorVoice)
    }

    private fun extractLanguage(localeTag: String): String {
        return localeTag.split("-", "_").firstOrNull()?.lowercase() ?: localeTag.lowercase()
    }
}
