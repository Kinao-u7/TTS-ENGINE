package com.novelreader.tts.android.engine

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.novelreader.tts.core.engine.EngineState
import com.novelreader.tts.core.engine.SpeechEngine
import com.novelreader.tts.core.engine.SpeechEvent
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
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android platform implementation of [SpeechEngine] backed by system [TextToSpeech].
 *
 * - Initializes TextToSpeech eagerly when created.
 * - Exposes isReady StateFlow.
 * - Speaks a silent warm-up utterance upon initialization.
 * - Queues any speak requests made before ready and drains them once ready.
 * - Applies setVoice, setPitch, setSpeechRate right before each speak(..., QUEUE_ADD, ...).
 * - Implements UtteranceProgressListener.onRangeStart for word ranges.
 * - Only shuts down on release().
 * - Optional gapMs setting for speaker changes and paragraph breaks.
 */
class SystemTtsEngine(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    val targetEnginePackage: String? = null,
    var gapMs: Long = 0L
) : SpeechEngine {

    private data class QueuedSpeak(
        val sentence: String,
        val voice: VoiceInfo?,
        val settings: VoiceSettings,
        val utteranceId: String,
        val isSpeakerChange: Boolean
    )

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null

    private val pendingQueue = ConcurrentLinkedQueue<QueuedSpeak>()
    private var lastSpokenVoiceId: String? = null

    init {
        initializeTtsEagerly(targetEnginePackage)
    }

    private fun initializeTtsEagerly(enginePackage: String?) {
        _engineState.value = EngineState.Initializing

        textToSpeech = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureProgressListener()
                _engineState.value = EngineState.Ready

                // Speak silent warm-up utterance upon init success
                warmUp()

                _isReady.value = true

                // Drain queued requests
                drainPendingQueue()
            } else {
                _isReady.value = false
                _engineState.value = EngineState.Error("TextToSpeech initialization failed with status $status")
            }
        }, enginePackage)
    }

    private fun configureProgressListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null && !utteranceId.startsWith("warmup_") && !utteranceId.startsWith("gap_")) {
                    scope.launch {
                        _events.emit(SpeechEvent.Started(utteranceId))
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && !utteranceId.startsWith("warmup_") && !utteranceId.startsWith("gap_")) {
                    scope.launch {
                        _events.emit(SpeechEvent.Done(utteranceId))
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != null && !utteranceId.startsWith("warmup_") && !utteranceId.startsWith("gap_")) {
                    val errorDesc = getErrorDescription(errorCode)
                    scope.launch {
                        _events.emit(SpeechEvent.Error(utteranceId, errorDesc, errorCode))
                    }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != null && start >= 0 && end >= start &&
                    !utteranceId.startsWith("warmup_") && !utteranceId.startsWith("gap_")
                ) {
                    scope.launch {
                        _events.emit(SpeechEvent.Range(start = start, end = end, utteranceId = utteranceId))
                    }
                }
            }
        })
    }

    override fun speak(
        sentence: String,
        voice: VoiceInfo?,
        settings: VoiceSettings,
        utteranceId: String
    ) {
        val isSpeakerChange = lastSpokenVoiceId != null && voice?.id != lastSpokenVoiceId

        if (!_isReady.value || textToSpeech == null) {
            pendingQueue.add(QueuedSpeak(sentence, voice, settings, utteranceId, isSpeakerChange))
            return
        }

        executeSpeak(sentence, voice, settings, utteranceId, isSpeakerChange)
    }

    private fun executeSpeak(
        sentence: String,
        voice: VoiceInfo?,
        settings: VoiceSettings,
        utteranceId: String,
        isSpeakerChange: Boolean
    ) {
        val tts = textToSpeech ?: run {
            scope.launch {
                _events.emit(SpeechEvent.Error(utteranceId, "TextToSpeech engine not available"))
            }
            return
        }

        try {
            // 1. Right before speak, set voice
            if (voice?.id != null) {
                val matchingVoice = tts.voices?.firstOrNull { it.name == voice.id }
                if (matchingVoice != null) {
                    tts.voice = matchingVoice
                }
            }

            // 2. Set pitch and speed rate
            val clampedSpeed = settings.speed.coerceIn(0.1f, 3.0f)
            val clampedPitch = settings.pitch.coerceIn(0.1f, 2.0f)
            tts.setSpeechRate(clampedSpeed)
            tts.setPitch(clampedPitch)

            // 3. Optional gap insertion at speaker changes
            if (gapMs > 0 && isSpeakerChange) {
                tts.playSilentUtterance(gapMs, TextToSpeech.QUEUE_ADD, "gap_$utteranceId")
            }

            // 4. Enqueue utterance using QUEUE_ADD for seamless lookahead playback
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val result = tts.speak(sentence, TextToSpeech.QUEUE_ADD, params, utteranceId)

            lastSpokenVoiceId = voice?.id

            if (result != TextToSpeech.SUCCESS) {
                scope.launch {
                    _events.emit(
                        SpeechEvent.Error(
                            utteranceId = utteranceId,
                            message = "TextToSpeech.speak QUEUE_ADD failed with code $result",
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

    private fun drainPendingQueue() {
        while (true) {
            val item = pendingQueue.poll() ?: break
            executeSpeak(item.sentence, item.voice, item.settings, item.utteranceId, item.isSpeakerChange)
        }
    }

    override fun stop() {
        pendingQueue.clear()
        textToSpeech?.stop()
    }

    override fun pause() {
        // Do not shut the engine down on pause, only stop active audio
        textToSpeech?.stop()
    }

    override fun warmUp(voice: VoiceInfo?) {
        val tts = textToSpeech ?: return
        try {
            if (voice?.id != null) {
                tts.voices?.firstOrNull { it.name == voice.id }?.let { tts.voice = it }
            }
            // Silent warm-up utterance with narrator voice
            tts.playSilentUtterance(50, TextToSpeech.QUEUE_FLUSH, "warmup_silent_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    override fun release() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        _isReady.value = false
        _engineState.value = EngineState.Uninitialized
    }

    override fun getAvailableVoices(): List<VoiceInfo> {
        val tts = textToSpeech ?: return emptyList()
        val systemVoices = tts.voices ?: return emptyList()

        return systemVoices.map { voice ->
            val gender = detectVoiceGender(voice)
            VoiceInfo(
                id = voice.name,
                name = formatVoiceName(voice),
                locale = voice.locale.toLanguageTag(),
                gender = gender,
                quality = voice.quality,
                latency = voice.latency,
                requiresNetwork = voice.isNetworkConnectionRequired
            )
        }.sortedWith(compareBy({ it.locale }, { it.name }))
    }

    private fun detectVoiceGender(voice: Voice): VoiceGender {
        val nameLower = voice.name.lowercase()
        return when {
            nameLower.contains("male") && !nameLower.contains("female") -> VoiceGender.MALE
            nameLower.contains("female") -> VoiceGender.FEMALE
            nameLower.contains("#male") -> VoiceGender.MALE
            nameLower.contains("#female") -> VoiceGender.FEMALE
            else -> VoiceGender.ANY
        }
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
