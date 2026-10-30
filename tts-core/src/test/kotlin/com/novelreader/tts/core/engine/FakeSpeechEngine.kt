package com.novelreader.tts.core.engine

import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.model.VoiceInfo
import com.novelreader.tts.core.model.VoiceSettings
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
 * In-memory test implementation of [SpeechEngine] with controllable readiness, event emission,
 * and call inspection for kotlinx-coroutines-test.
 */
class FakeSpeechEngine(
    initialReady: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    var autoCompleteOnSpeak: Boolean = false
) : SpeechEngine {

    data class SpeakRecord(
        val utteranceId: String,
        val sentence: String,
        val voice: VoiceInfo?,
        val settings: VoiceSettings
    )

    private val _isReady = MutableStateFlow(initialReady)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    val speakRecords = mutableListOf<SpeakRecord>()
    val queuedSpeaksBeforeReady = mutableListOf<SpeakRecord>()
    val warmedUpVoices = mutableListOf<VoiceInfo?>()
    var stopCount = 0
    var releaseCount = 0
    var warmUpCount = 0

    var mockVoices: List<VoiceInfo> = listOf(
        VoiceInfo("voice_en_narrator", "English Narrator", "en-US", VoiceGender.ANY),
        VoiceInfo("voice_en_m1", "English Male 1", "en-US", VoiceGender.MALE),
        VoiceInfo("voice_en_f1", "English Female 1", "en-US", VoiceGender.FEMALE),
        VoiceInfo("voice_gb_m1", "British Male 1", "en-GB", VoiceGender.MALE),
        VoiceInfo("voice_gb_f1", "British Female 1", "en-GB", VoiceGender.FEMALE),
        VoiceInfo("voice_net_f1", "Network Female", "en-US", VoiceGender.FEMALE, requiresNetwork = true)
    )

    fun setReady(ready: Boolean) {
        _isReady.value = ready
        if (ready && queuedSpeaksBeforeReady.isNotEmpty()) {
            val toDrain = queuedSpeaksBeforeReady.toList()
            queuedSpeaksBeforeReady.clear()
            for (item in toDrain) {
                speak(item.sentence, item.voice, item.settings, item.utteranceId)
            }
        }
    }

    override fun speak(
        sentence: String,
        voice: VoiceInfo?,
        settings: VoiceSettings,
        utteranceId: String
    ) {
        val record = SpeakRecord(utteranceId, sentence, voice, settings)
        if (!_isReady.value) {
            queuedSpeaksBeforeReady.add(record)
            return
        }

        speakRecords.add(record)

        if (autoCompleteOnSpeak) {
            scope.launch {
                _events.emit(SpeechEvent.Started(utteranceId))
                _events.emit(SpeechEvent.Done(utteranceId))
            }
        }
    }

    fun emitStarted(utteranceId: String) {
        scope.launch {
            _events.emit(SpeechEvent.Started(utteranceId))
        }
    }

    fun emitRange(start: Int, end: Int, utteranceId: String) {
        scope.launch {
            _events.emit(SpeechEvent.Range(start = start, end = end, utteranceId = utteranceId))
        }
    }

    fun emitDone(utteranceId: String) {
        scope.launch {
            _events.emit(SpeechEvent.Done(utteranceId))
        }
    }

    fun emitError(utteranceId: String, message: String, errorCode: Int = -1) {
        scope.launch {
            _events.emit(SpeechEvent.Error(utteranceId = utteranceId, message = message, errorCode = errorCode))
        }
    }

    override fun stop() {
        stopCount++
    }

    override fun warmUp(voice: VoiceInfo?) {
        warmUpCount++
        warmedUpVoices.add(voice)
    }

    override fun release() {
        releaseCount++
        _isReady.value = false
    }

    override fun getAvailableVoices(): List<VoiceInfo> = mockVoices
}
