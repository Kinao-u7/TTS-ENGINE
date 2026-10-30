package com.novelreader.tts.android.engine

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.novelreader.tts.core.engine.EngineState
import com.novelreader.tts.core.engine.SpeechEngine
import com.novelreader.tts.core.engine.SpeechEvent
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
import java.util.Locale

/**
 * Android platform implementation of [SpeechEngine] backed by [TextToSpeech].
 */
class SystemTtsEngine(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    targetEnginePackage: String? = null
) : SpeechEngine {

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null

    private var activeUtteranceId: String? = null
    private var pausedText: String? = null
    private var pausedVoiceId: String? = null
    private var pausedSpeed: Float = 1.0f
    private var pausedPitch: Float = 1.0f

    init {
        initializeTts(targetEnginePackage)
    }

    private fun initializeTts(enginePackage: String?) {
        _engineState.value = EngineState.Initializing

        textToSpeech = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureProgressListener()
                _engineState.value = EngineState.Ready
            } else {
                _engineState.value = EngineState.Error("TextToSpeech initialization failed with status $status")
            }
        }, enginePackage)
    }

    private fun configureProgressListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let { id ->
                    scope.launch {
                        _events.emit(SpeechEvent.Started(id))
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id ->
                    scope.launch {
                        _events.emit(SpeechEvent.Done(id))
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { id ->
                    val errorDesc = getErrorDescription(errorCode)
                    scope.launch {
                        _events.emit(SpeechEvent.Error(id, errorDesc, errorCode))
                    }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != null && start >= 0 && end >= start) {
                    scope.launch {
                        _events.emit(SpeechEvent.RangeStarted(utteranceId, start, end))
                    }
                }
            }
        })
    }

    override fun speak(
        utteranceId: String,
        text: String,
        voiceId: String?,
        speed: Float,
        pitch: Float
    ) {
        val tts = textToSpeech ?: run {
            scope.launch {
                _events.emit(SpeechEvent.Error(utteranceId, "TextToSpeech engine not initialized"))
            }
            return
        }

        activeUtteranceId = utteranceId
        pausedText = text
        pausedVoiceId = voiceId
        pausedSpeed = speed
        pausedPitch = pitch

        try {
            // Apply voice selection
            if (voiceId != null) {
                val matchingVoice = tts.voices?.firstOrNull { it.name == voiceId }
                if (matchingVoice != null) {
                    tts.voice = matchingVoice
                }
            }

            // Map and clamp speed & pitch to Android TTS supported ranges (0.1f - 3.0f)
            val clampedSpeed = speed.coerceIn(0.1f, 3.0f)
            val clampedPitch = pitch.coerceIn(0.1f, 2.0f)

            tts.setSpeechRate(clampedSpeed)
            tts.setPitch(clampedPitch)

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                scope.launch {
                    _events.emit(
                        SpeechEvent.Error(
                            utteranceId = utteranceId,
                            message = "TextToSpeech.speak call failed with code $result",
                            errorCode = result
                        )
                    )
                }
            }
        } catch (e: Exception) {
            scope.launch {
                _events.emit(
                    SpeechEvent.Error(
                        utteranceId = utteranceId,
                        message = e.localizedMessage ?: "Unknown TTS exception",
                        errorCode = TextToSpeech.ERROR
                    )
                )
            }
        }
    }

    override fun stop() {
        textToSpeech?.stop()
        activeUtteranceId = null
    }

    override fun pause() {
        textToSpeech?.stop()
    }

    override fun resume() {
        val utteranceId = activeUtteranceId
        val text = pausedText
        if (utteranceId != null && text != null) {
            speak(utteranceId, text, pausedVoiceId, pausedSpeed, pausedPitch)
        }
    }

    override fun getAvailableVoices(): List<VoiceInfo> {
        val tts = textToSpeech ?: return emptyList()
        val systemVoices = tts.voices ?: return emptyList()

        return systemVoices.map { voice ->
            VoiceInfo(
                id = voice.name,
                name = formatVoiceName(voice),
                locale = voice.locale.toLanguageTag(),
                quality = voice.quality,
                latency = voice.latency,
                requiresNetwork = voice.isNetworkConnectionRequired
            )
        }.sortedWith(compareBy({ it.locale }, { it.name }))
    }

    override fun prewarm(voiceId: String?) {
        if (voiceId == null) return
        val tts = textToSpeech ?: return
        tts.voices?.firstOrNull { it.name == voiceId }?.let { voice ->
            // Pre-selecting the voice warms up voice data in the TTS service process
            tts.voice = voice
        }
    }

    override fun shutdown() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        _engineState.value = EngineState.Uninitialized
    }

    private fun formatVoiceName(voice: Voice): String {
        val displayName = voice.locale.getDisplayName(Locale.getDefault())
        val namePart = voice.name.substringAfterLast("/")
        return "$displayName ($namePart)"
    }

    private fun getErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            TextToSpeech.ERROR_SYNTHESIS -> "Synthesis failure"
            TextToSpeech.ERROR_SERVICE -> "TTS service failure"
            TextToSpeech.ERROR_OUTPUT -> "Audio output error"
            TextToSpeech.ERROR_NETWORK -> "Network error during voice download"
            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            TextToSpeech.ERROR_INVALID_REQUEST -> "Invalid TTS request"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Voice data not yet installed"
            else -> "General TTS error (code $errorCode)"
        }
    }
}
