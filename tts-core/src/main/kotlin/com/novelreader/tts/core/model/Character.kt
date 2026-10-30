package com.novelreader.tts.core.model

/**
 * Gender designation for voice assignment.
 */
enum class VoiceGender {
    MALE,
    FEMALE,
    ANY
}

/**
 * Represents a speaker in a novel.
 *
 * @property id Unique identifier.
 * @property name Display name of the character.
 * @property aliases Alternate names or epithets for attribution.
 * @property voiceId Optional explicit identifier of the system TTS voice assigned to this character.
 * @property voiceGender Voice gender preference (MALE/FEMALE/ANY).
 * @property readByNarrator If true, this character's lines use the narrator's voice.
 * @property speed Playback rate multiplier (default 1.0f).
 * @property pitch Voice pitch multiplier (default 1.0f).
 */
data class Character(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val voiceId: String? = null,
    val voiceGender: VoiceGender = VoiceGender.ANY,
    val readByNarrator: Boolean = false,
    val speed: Float = DEFAULT_SPEED,
    val pitch: Float = DEFAULT_PITCH
) {
    /**
     * Built-in characters ("narrator" and "unnamed") are neither deletable
     * nor usable as a merge source.
     */
    val isBuiltIn: Boolean
        get() = isBuiltIn(id)

    val isDeletable: Boolean
        get() = !isBuiltIn

    val canBeMergeSource: Boolean
        get() = !isBuiltIn

    companion object {
        const val NARRATOR_ID = "narrator"
        const val NARRATOR_NAME = "Narrator"

        const val UNNAMED_ID = "unnamed"
        const val UNNAMED_NAME = "Unnamed"

        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_PITCH = 1.0f

        val Narrator = Character(
            id = NARRATOR_ID,
            name = NARRATOR_NAME,
            aliases = emptyList(),
            voiceId = null,
            voiceGender = VoiceGender.ANY,
            readByNarrator = false,
            speed = DEFAULT_SPEED,
            pitch = DEFAULT_PITCH
        )

        val Unnamed = Character(
            id = UNNAMED_ID,
            name = UNNAMED_NAME,
            aliases = emptyList(),
            voiceId = null,
            voiceGender = VoiceGender.ANY,
            readByNarrator = false,
            speed = DEFAULT_SPEED,
            pitch = DEFAULT_PITCH
        )

        fun isNarrator(id: String): Boolean = id.equals(NARRATOR_ID, ignoreCase = true)

        fun isUnnamed(id: String): Boolean = id.equals(UNNAMED_ID, ignoreCase = true)

        fun isBuiltIn(id: String): Boolean = isNarrator(id) || isUnnamed(id)
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
