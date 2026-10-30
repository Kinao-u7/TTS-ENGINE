package com.novelreader.tts.core.engine

import com.novelreader.tts.core.model.VoiceInfo
import com.novelreader.tts.core.model.VoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction layer for text-to-speech synthesis engines.
 *
 * Nothing outside the engine implementation may touch platform TextToSpeech.
 */
interface SpeechEngine {

    /**
     * Observable readiness state of the engine.
     */
    val isReady: StateFlow<Boolean>

    /**
     * Stream of utterance synthesis events (Started, Range(start, end), Done, Error).
     */
    val events: Flow<SpeechEvent>

    /**
     * Synthesizes and plays the provided sentence.
     *
     * @param sentence The plain text sentence to speak.
     * @param voice The voice profile to use (or null for default narrator voice).
     * @param settings Pitch and speed rate multipliers.
     * @param utteranceId Identifier to correlate speech events.
     */
    fun speak(
        sentence: String,
        voice: VoiceInfo? = null,
        settings: VoiceSettings = VoiceSettings.Default,
        utteranceId: String = "utt_${System.currentTimeMillis()}_${(0..99999).random()}"
    )

    /**
     * Stops current speech synthesis immediately and clears pending utterances in engine.
     */
    fun stop()

    /**
     * Pre-warms the engine or specific voice assets ahead of playback.
     */
    fun warmUp(voice: VoiceInfo? = null)

    /**
     * Releases system resources held by the engine.
     */
    fun release()

    /**
     * Backward-compatible helper for available voices.
     */
    fun getAvailableVoices(): List<VoiceInfo> = emptyList()

    /**
     * Backward-compatible lifecycle state.
     */
    val engineState: StateFlow<EngineState>? get() = null

    /**
     * Backward-compatible pause support.
     */
    fun pause() {
        stop()
    }

    /**
     * Backward-compatible resume support.
     */
    fun resume() {}

    /**
     * Backward-compatible shutdown alias for release.
     */
    fun shutdown() {
        release()
    }

    /**
     * Backward-compatible prewarm alias.
     */
    fun prewarm(voiceId: String?) {}
}
