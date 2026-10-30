package com.novelreader.tts.core.model

/**
 * Speech rate, pitch, and voice settings.
 *
 * @property speed Speed rate multiplier (1.0f = normal).
 * @property pitch Pitch multiplier (1.0f = normal).
 * @property voiceId The chosen TTS voice identifier.
 */
data class VoiceSettings(
    val speed: Float = Character.DEFAULT_SPEED,
    val pitch: Float = Character.DEFAULT_PITCH,
    val voiceId: String? = null
) {
    companion object {
        val Default = VoiceSettings(Character.DEFAULT_SPEED, Character.DEFAULT_PITCH, null)

        /**
         * Resolves voice settings for a character, wiring the chosen voice ID, speed, and pitch.
         */
        fun getSettings(character: Character, globalSpeed: Float? = null): VoiceSettings {
            return VoiceSettings(
                speed = globalSpeed ?: character.speed,
                pitch = character.pitch,
                voiceId = character.voiceId
            )
        }
    }
}
