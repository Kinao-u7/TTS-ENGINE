package com.novelreader.tts.core.engine

import com.novelreader.tts.core.model.VoiceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction layer for text-to-speech synthesis engines.
 */
interface SpeechEngine {

    /**
     * Observable lifecycle state of this engine.
     */
    val engineState: StateFlow<EngineState>

    /**
     * Stream of utterance synthesis events (started, range started, done, error).
     */
    val events: Flow<SpeechEvent>

    /**
     * Synthesizes and plays the provided text.
     *
     * @param utteranceId Unique ID to correlate speech events.
     * @param text The plain text to speak.
     * @param voiceId The voice identifier to use (or null for default).
     * @param speed Playback rate multiplier (1.0f = normal).
     * @param pitch Pitch multiplier (1.0f = normal).
     */
    fun speak(
        utteranceId: String,
        text: String,
        voiceId: String? = null,
        speed: Float = 1.0f,
        pitch: Float = 1.0f
    )

    /**
     * Stops current speech synthesis immediately.
     */
    fun stop()

    /**
     * Pauses current playback if supported by the engine.
     */
    fun pause()

    /**
     * Resumes playback if paused.
     */
    fun resume()

    /**
     * Returns the list of currently installed and available voices.
     */
    fun getAvailableVoices(): List<VoiceInfo>

    /**
     * Optional optimization hint to warm up voice assets ahead of time.
     */
    fun prewarm(voiceId: String?) {}

    /**
     * Releases resources held by the engine.
     */
    fun shutdown() {}
}
