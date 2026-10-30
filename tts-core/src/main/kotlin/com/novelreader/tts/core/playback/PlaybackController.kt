package com.novelreader.tts.core.playback

import com.novelreader.tts.core.engine.EngineState
import com.novelreader.tts.core.engine.SpeechEngine
import com.novelreader.tts.core.engine.SpeechEvent
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.TextRange
import com.novelreader.tts.core.model.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Controller coordinating segment progression, voice attribution, and speech synthesis.
 *
 * Automatically skips blank and whitespace-only segments.
 */
class PlaybackController(
    private val speechEngine: SpeechEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate)
) {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var segments: List<Segment> = emptyList()
    private var characters: Map<String, Character> = mapOf(Character.NARRATOR_ID to Character.Narrator)
    private var globalSpeed: Float? = null

    private var currentSegmentIndex: Int = 0
    private var activeUtteranceId: String? = null
    private var currentRangeInSegment: TextRange? = null

    private var eventCollectorJob: Job? = null

    init {
        startListeningToSpeechEvents()
    }

    private fun startListeningToSpeechEvents() {
        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            speechEngine.events.collect { event ->
                handleSpeechEvent(event)
            }
        }
    }

    /**
     * Loads a new sequence of chapter segments.
     *
     * @param newSegments The segmented chapter content.
     * @param initialIndex The starting segment index (defaults to 0 or first non-blank segment).
     */
    fun loadSegments(newSegments: List<Segment>, initialIndex: Int = 0) {
        speechEngine.stop()
        segments = newSegments
        activeUtteranceId = null
        currentRangeInSegment = null

        val firstNonBlank = findNextNonBlankIndex(initialIndex.coerceAtLeast(0))
        currentSegmentIndex = firstNonBlank ?: 0
        _state.value = if (firstNonBlank != null) PlaybackState.Idle else PlaybackState.Stopped
    }

    /**
     * Updates character definitions used for voice, pitch, and speed lookup.
     */
    fun updateCharacters(characterList: List<Character>) {
        val map = characterList.associateBy { it.id }.toMutableMap()
        if (!map.containsKey(Character.NARRATOR_ID)) {
            map[Character.NARRATOR_ID] = Character.Narrator
        }
        characters = map
    }

    /**
     * Sets a global playback speed multiplier. If null, per-character speed is used.
     */
    fun setGlobalSpeed(speed: Float?) {
        globalSpeed = speed
        // If currently playing, restart segment with new speed
        if (_state.value is PlaybackState.Playing) {
            speakCurrentSegment()
        }
    }

    /**
     * Begins or resumes playback.
     */
    fun play() {
        if (segments.isEmpty()) return

        when (val s = _state.value) {
            is PlaybackState.Playing -> return
            is PlaybackState.Paused -> resume()
            is PlaybackState.Idle, is PlaybackState.Stopped, is PlaybackState.Error, is PlaybackState.Buffering -> {
                val nonBlank = findNextNonBlankIndex(currentSegmentIndex)
                if (nonBlank != null) {
                    currentSegmentIndex = nonBlank
                    speakCurrentSegment()
                } else {
                    _state.value = PlaybackState.Stopped
                }
            }
        }
    }

    /**
     * Pauses playback.
     */
    fun pause() {
        when (_state.value) {
            is PlaybackState.Playing, is PlaybackState.Buffering -> {
                speechEngine.pause()
                _state.value = PlaybackState.Paused(currentSegmentIndex, currentRangeInSegment)
            }
            else -> {}
        }
    }

    /**
     * Resumes playback if currently paused.
     */
    fun resume() {
        if (_state.value is PlaybackState.Paused) {
            speechEngine.resume()
            _state.value = PlaybackState.Playing(currentSegmentIndex, currentRangeInSegment)
        } else {
            play()
        }
    }

    /**
     * Toggles between play and pause.
     */
    fun togglePlayPause() {
        if (_state.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    /**
     * Stops playback and resets state.
     */
    fun stop() {
        activeUtteranceId = null
        currentRangeInSegment = null
        speechEngine.stop()
        _state.value = PlaybackState.Stopped
    }

    /**
     * Jumps to the next non-blank segment.
     */
    fun next() {
        val nextIdx = findNextNonBlankIndex(currentSegmentIndex + 1)
        if (nextIdx != null) {
            currentSegmentIndex = nextIdx
            if (_state.value.isPlaying || _state.value is PlaybackState.Buffering) {
                speakCurrentSegment()
            } else {
                _state.value = PlaybackState.Paused(currentSegmentIndex, null)
            }
        } else {
            stop()
        }
    }

    /**
     * Jumps to the previous non-blank segment.
     */
    fun previous() {
        val prevIdx = findPreviousNonBlankIndex(currentSegmentIndex - 1)
        if (prevIdx != null) {
            currentSegmentIndex = prevIdx
            if (_state.value.isPlaying || _state.value is PlaybackState.Buffering) {
                speakCurrentSegment()
            } else {
                _state.value = PlaybackState.Paused(currentSegmentIndex, null)
            }
        }
    }

    /**
     * Seeks to a specific segment index, automatically adjusting to the nearest non-blank segment.
     */
    fun seekTo(index: Int) {
        val bounded = index.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
        val targetIndex = if (isSegmentBlank(bounded)) {
            findNextNonBlankIndex(bounded) ?: findPreviousNonBlankIndex(bounded) ?: bounded
        } else {
            bounded
        }

        currentSegmentIndex = targetIndex
        currentRangeInSegment = null

        if (_state.value.isPlaying) {
            speakCurrentSegment()
        } else {
            _state.value = PlaybackState.Paused(currentSegmentIndex, null)
        }
    }

    private fun speakCurrentSegment() {
        if (currentSegmentIndex !in segments.indices) {
            _state.value = PlaybackState.Stopped
            return
        }

        val segment = segments[currentSegmentIndex]
        if (segment.text.isBlank()) {
            // Automatically skip blank or whitespace-only segments
            val nextIdx = findNextNonBlankIndex(currentSegmentIndex + 1)
            if (nextIdx != null) {
                currentSegmentIndex = nextIdx
                speakCurrentSegment()
            } else {
                _state.value = PlaybackState.Stopped
            }
            return
        }

        val speaker = characters[segment.effectiveSpeakerId] ?: Character.Narrator
        val settings = VoiceSettings.getSettings(speaker, globalSpeed)

        val utteranceId = "utt_${segment.id}_${System.currentTimeMillis()}"
        activeUtteranceId = utteranceId
        currentRangeInSegment = null

        _state.value = PlaybackState.Buffering(currentSegmentIndex)

        // Pre-warm the next non-blank segment's voice
        val nextIdx = findNextNonBlankIndex(currentSegmentIndex + 1)
        if (nextIdx != null && nextIdx in segments.indices) {
            val nextSegment = segments[nextIdx]
            val nextSpeaker = characters[nextSegment.effectiveSpeakerId] ?: Character.Narrator
            speechEngine.prewarm(nextSpeaker.voiceId)
        }

        speechEngine.speak(
            utteranceId = utteranceId,
            text = segment.text,
            voiceId = speaker.voiceId,
            speed = settings.speed,
            pitch = settings.pitch
        )
    }

    private fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Started -> {
                if (event.utteranceId == activeUtteranceId) {
                    _state.value = PlaybackState.Playing(currentSegmentIndex, currentRangeInSegment)
                }
            }
            is SpeechEvent.RangeStarted -> {
                if (event.utteranceId == activeUtteranceId) {
                    val range = TextRange(event.startOffset, event.endOffset)
                    currentRangeInSegment = range
                    _state.value = PlaybackState.Playing(currentSegmentIndex, range)
                }
            }
            is SpeechEvent.Done -> {
                if (event.utteranceId == activeUtteranceId) {
                    // Auto-advance to the next non-blank segment
                    val nextIdx = findNextNonBlankIndex(currentSegmentIndex + 1)
                    if (nextIdx != null) {
                        currentSegmentIndex = nextIdx
                        speakCurrentSegment()
                    } else {
                        activeUtteranceId = null
                        currentRangeInSegment = null
                        _state.value = PlaybackState.Stopped
                    }
                }
            }
            is SpeechEvent.Error -> {
                if (event.utteranceId == activeUtteranceId) {
                    activeUtteranceId = null
                    currentRangeInSegment = null
                    _state.value = PlaybackState.Error(
                        message = "Speech synthesis error [${event.errorCode}]: ${event.message}"
                    )
                }
            }
        }
    }

    private fun isSegmentBlank(index: Int): Boolean {
        if (index !in segments.indices) return true
        return segments[index].text.isBlank()
    }

    private fun findNextNonBlankIndex(fromIndex: Int): Int? {
        for (i in fromIndex until segments.size) {
            if (segments[i].text.isNotBlank()) return i
        }
        return null
    }

    private fun findPreviousNonBlankIndex(fromIndex: Int): Int? {
        for (i in fromIndex downTo 0) {
            if (i in segments.indices && segments[i].text.isNotBlank()) return i
        }
        return null
    }

    fun release() {
        stop()
        eventCollectorJob?.cancel()
        speechEngine.shutdown()
    }
}
