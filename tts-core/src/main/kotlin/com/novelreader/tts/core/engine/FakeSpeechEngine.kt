package com.novelreader.tts.core.engine

import com.novelreader.tts.core.model.VoiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-memory test implementation of [SpeechEngine] with controllable delays, event emission,
 * and call inspection.
 */
class FakeSpeechEngine(
    initialState: EngineState = EngineState.Ready,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    var autoCompleteOnSpeak: Boolean = false
) : SpeechEngine {

    data class SpeakRecord(
        val utteranceId: String,
        val text: String,
        val voiceId: String?,
        val speed: Float,
        val pitch: Float
    )

    private val _engineState = MutableStateFlow(initialState)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    val speakRecords = mutableListOf<SpeakRecord>()
    val prewarmedVoices = mutableListOf<String?>()
    var stopCount = 0
    var pauseCount = 0
    var resumeCount = 0
    var lastUtteranceId: String? = null

    var mockVoices: List<VoiceInfo> = listOf(
        VoiceInfo("voice_en_1", "English (United States) - Male 1", "en-US"),
        VoiceInfo("voice_en_2", "English (United States) - Female 1", "en-US"),
        VoiceInfo("voice_en_3", "English (United Kingdom) - Male 1", "en-GB")
    )

    fun setState(newState: EngineState) {
        _engineState.value = newState
    }

    override fun speak(
        utteranceId: String,
        text: String,
        voiceId: String?,
        speed: Float,
        pitch: Float
    ) {
        val record = SpeakRecord(utteranceId, text, voiceId, speed, pitch)
        speakRecords.add(record)
        lastUtteranceId = utteranceId

        scope.launch {
            _events.emit(SpeechEvent.Started(utteranceId))
            if (autoCompleteOnSpeak) {
                _events.emit(SpeechEvent.Done(utteranceId))
            }
        }
    }

    fun emitRangeStart(utteranceId: String, startOffset: Int, endOffset: Int) {
        scope.launch {
            _events.emit(SpeechEvent.RangeStarted(utteranceId, startOffset, endOffset))
        }
    }

    fun emitDone(utteranceId: String) {
        scope.launch {
            _events.emit(SpeechEvent.Done(utteranceId))
        }
    }

    fun emitError(utteranceId: String, message: String, errorCode: Int = -1) {
        scope.launch {
            _events.emit(SpeechEvent.Error(utteranceId, message, errorCode))
        }
    }

    override fun stop() {
        stopCount++
    }

    override fun pause() {
        pauseCount++
    }

    override fun resume() {
        resumeCount++
    }

    override fun getAvailableVoices(): List<VoiceInfo> = mockVoices

    override fun prewarm(voiceId: String?) {
        prewarmedVoices.add(voiceId)
    }

    override fun shutdown() {
        stop()
        _engineState.value = EngineState.Uninitialized
    }
}
