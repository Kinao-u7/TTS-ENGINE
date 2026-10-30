package com.novelreader.tts.core.model

/**
 * Represents a speaker in a novel.
 *
 * @property id Unique identifier.
 * @property name Display name of the character.
 * @property aliases Alternate names or epithets for attribution.
 * @property voiceId Identifier of the system TTS voice assigned to this character.
 * @property speed Playback rate multiplier (default 1.0f).
 * @property pitch Voice pitch multiplier (default 1.0f).
 */
data class Character(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val voiceId: String? = null,
    val speed: Float = DEFAULT_SPEED,
    val pitch: Float = DEFAULT_PITCH
) {
    companion object {
        const val NARRATOR_ID = "narrator"
        const val NARRATOR_NAME = "Narrator"
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_PITCH = 1.0f

        val Narrator = Character(
            id = NARRATOR_ID,
            name = NARRATOR_NAME,
            aliases = emptyList(),
            voiceId = null,
            speed = DEFAULT_SPEED,
            pitch = DEFAULT_PITCH
        )

        fun isNarrator(id: String): Boolean = id.equals(NARRATOR_ID, ignoreCase = true)
    }

    /**
     * Checks if a given query matches this character's name or any of its aliases (case-insensitive).
     */
    fun matchesName(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        if (name.equals(trimmed, ignoreCase = true)) return true
        return aliases.any { it.equals(trimmed, ignoreCase = true) }
    }
}
