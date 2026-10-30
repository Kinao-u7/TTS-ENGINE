package com.novelreader.tts.core.playback

import com.novelreader.tts.core.engine.SpeechEngine
import com.novelreader.tts.core.engine.SpeechEvent
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.TextRange
import com.novelreader.tts.core.model.VoiceInfo
import com.novelreader.tts.core.model.VoiceSelectionResult
import com.novelreader.tts.core.model.VoiceSelector
import com.novelreader.tts.core.model.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Speaker chip information exposed to the reader player UI.
 */
data class SpeakerChipInfo(
    val id: String,
    val displayName: String
)

/**
 * Observable reader state exposing sentence progression, character highlights,
 * speaker chip metadata, and time remaining.
 */
data class ReaderState(
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val currentSentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val estimatedTimeRemainingMs: Long = 0L,
    val sentenceHighlight: TextRange? = null,
    val wordHighlight: TextRange? = null,
    val currentSpeaker: SpeakerChipInfo? = null,
    val missingVoiceWarning: String? = null
)

/**
 * Individual sentence token with character offsets into the chapter text and segment.
 */
data class Sentence(
    val index: Int,
    val segmentId: String,
    val segmentType: SegmentType,
    val text: String,
    val sentenceStartInChapter: Int,
    val sentenceEndInChapter: Int,
    val sentenceStartInSegment: Int,
    val sentenceEndInSegment: Int,
    val initialSpeakerId: String
)

/**
 * High-performance playback controller coordinating sentence lookahead queueing,
 * dynamic voice attribution, sentence/word highlights, and seamless multi-voice synthesis.
 */
class PlaybackController(
    private val speechEngine: SpeechEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
    private val bookLocale: String = "en-US",
    private val lookaheadCount: Int = DEFAULT_LOOKAHEAD_COUNT
) {
    private val _readerState = MutableStateFlow(ReaderState())
    val readerState: StateFlow<ReaderState> = _readerState.asStateFlow()

    // Backwards-compatible legacy state flow
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var segments: List<Segment> = emptyList()
    private var sentences: List<Sentence> = emptyList()

    private var characters: Map<String, Character> = mapOf(
        Character.NARRATOR_ID to Character.Narrator,
        Character.UNNAMED_ID to Character.Unnamed
    )
    private var availableVoices: List<VoiceInfo> = emptyList()
    private var globalSpeed: Float? = null

    private var currentSentenceIndex: Int = 0
    private var nextToEnqueueIndex: Int = 0
    private var playRequestedWhenReady: Boolean = false

    private var eventCollectorJob: Job? = null
    private var readyCollectorJob: Job? = null

    init {
        availableVoices = speechEngine.getAvailableVoices()
        startListeningToEngine()
    }

    private fun startListeningToEngine() {
        readyCollectorJob?.cancel()
        readyCollectorJob = scope.launch {
            speechEngine.isReady.collect { ready ->
                _readerState.value = _readerState.value.copy(isReady = ready)
                if (ready && playRequestedWhenReady) {
                    playRequestedWhenReady = false
                    startPlayingFromCurrent()
                }
            }
        }

        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            speechEngine.events.collect { event ->
                handleSpeechEvent(event)
            }
        }
    }

    /**
     * Loads chapter segments, splits them into sentences, and prepares the reader.
     * Blank or whitespace-only segments and sentences are automatically skipped.
     */
    fun loadSegments(newSegments: List<Segment>, initialSentenceIndex: Int = 0) {
        speechEngine.stop()
        segments = newSegments
        sentences = splitSegmentsIntoSentences(newSegments)

        currentSentenceIndex = initialSentenceIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        nextToEnqueueIndex = currentSentenceIndex
        playRequestedWhenReady = false

        updateReaderState(isPlaying = false)
        _state.value = if (sentences.isNotEmpty()) PlaybackState.Idle else PlaybackState.Stopped
    }

    /**
     * Updates character definitions. Settings take effect from the next enqueued sentence
     * without flushing the existing queue.
     */
    fun updateCharacters(characterList: List<Character>) {
        val map = characterList.associateBy { it.id }.toMutableMap()
        if (!map.containsKey(Character.NARRATOR_ID)) {
            map[Character.NARRATOR_ID] = Character.Narrator
        }
        if (!map.containsKey(Character.UNNAMED_ID)) {
            map[Character.UNNAMED_ID] = Character.Unnamed
        }
        characters = map
    }

    /**
     * Sets available voices for voice selection.
     */
    fun updateAvailableVoices(voices: List<VoiceInfo>) {
        availableVoices = voices
    }

    /**
     * Sets a global playback speed multiplier. Applied from the next enqueued sentence
     * without flushing the queue.
     */
    fun setGlobalSpeed(speed: Float?) {
        globalSpeed = speed
    }

    /**
     * Begins or resumes playback. If engine is not yet ready, queues play request.
     */
    fun play() {
        if (sentences.isEmpty()) return

        if (!speechEngine.isReady.value) {
            playRequestedWhenReady = true
            return
        }

        if (_readerState.value.isPlaying) return

        startPlayingFromCurrent()
    }

    private fun startPlayingFromCurrent() {
        if (sentences.isEmpty()) return

        nextToEnqueueIndex = currentSentenceIndex
        updateReaderState(isPlaying = true)
        _state.value = PlaybackState.Playing(getSegmentIndexForSentence(currentSentenceIndex), null)

        fillLookaheadQueue()
    }

    /**
     * Pauses playback. Stops the engine and remembers current sentence index.
     * Resume will restart from the beginning of this sentence.
     */
    fun pause() {
        if (!_readerState.value.isPlaying && !playRequestedWhenReady) return

        playRequestedWhenReady = false
        speechEngine.stop()
        nextToEnqueueIndex = currentSentenceIndex

        updateReaderState(isPlaying = false, clearWordHighlight = true)
        _state.value = PlaybackState.Paused(getSegmentIndexForSentence(currentSentenceIndex), null)
    }

    /**
     * Resumes playback from the beginning of the remembered sentence.
     */
    fun resume() {
        play()
    }

    /**
     * Toggles between play and pause.
     */
    fun togglePlayPause() {
        if (_readerState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    /**
     * Stops playback and resets highlights.
     */
    fun stop() {
        playRequestedWhenReady = false
        speechEngine.stop()
        nextToEnqueueIndex = currentSentenceIndex

        updateReaderState(isPlaying = false, clearSentenceHighlight = true, clearWordHighlight = true)
        _state.value = PlaybackState.Stopped
    }

    /**
     * Jumps to the next sentence without extra delay.
     */
    fun next() {
        jumpToSentence(currentSentenceIndex + 1)
    }

    /**
     * Jumps to the previous sentence without extra delay.
     */
    fun previous() {
        jumpToSentence(currentSentenceIndex - 1)
    }

    /**
     * Seeks to a specific sentence index. Stops audio, updates index, and speaks immediately if playing.
     */
    fun jumpToSentence(targetIndex: Int) {
        if (sentences.isEmpty()) return

        val boundedIndex = targetIndex.coerceIn(0, sentences.size - 1)
        val wasPlaying = _readerState.value.isPlaying || playRequestedWhenReady

        speechEngine.stop()
        currentSentenceIndex = boundedIndex
        nextToEnqueueIndex = currentSentenceIndex

        if (wasPlaying) {
            startPlayingFromCurrent()
        } else {
            updateReaderState(isPlaying = false, clearWordHighlight = true)
            _state.value = PlaybackState.Paused(getSegmentIndexForSentence(currentSentenceIndex), null)
        }
    }

    /**
     * Backwards-compatible seek to segment index.
     */
    fun seekTo(segmentIndex: Int) {
        val targetSentence = sentences.firstOrNull { it.segmentId == segments.getOrNull(segmentIndex)?.id }
        if (targetSentence != null) {
            jumpToSentence(targetSentence.index)
        }
    }

    /**
     * Reassigns a speaker for a segment.
     * Only reassigning the currently playing line flushes the engine queue and restarts
     * from that segment. Other edits apply without flushing.
     */
    fun reassignSpeaker(segmentId: String, newSpeakerId: String, isOverride: Boolean = true) {
        // Update segment in memory
        segments = segments.map { seg ->
            if (seg.id == segmentId) {
                if (isOverride) seg.copy(speakerOverride = newSpeakerId) else seg.copy(speakerId = newSpeakerId)
            } else {
                seg
            }
        }

        val currentlyPlayingSentence = sentences.getOrNull(currentSentenceIndex)
        val isCurrentlyPlaying = currentlyPlayingSentence?.segmentId == segmentId

        if (isCurrentlyPlaying && _readerState.value.isPlaying) {
            // Stop, drop queued sentences, and restart from this segment
            speechEngine.stop()
            val segmentStartIndex = sentences.indexOfFirst { it.segmentId == segmentId }
            if (segmentStartIndex >= 0) {
                currentSentenceIndex = segmentStartIndex
            }
            startPlayingFromCurrent()
        }
    }

    /**
     * Fills the lookahead queue: always keeps at least [lookaheadCount] sentences enqueued ahead.
     */
    private fun fillLookaheadQueue() {
        if (!_readerState.value.isPlaying || sentences.isEmpty()) return

        val targetCount = minOf(sentences.size, currentSentenceIndex + 1 + lookaheadCount)
        while (nextToEnqueueIndex < targetCount) {
            enqueueSentence(sentences[nextToEnqueueIndex])
            nextToEnqueueIndex++
        }
    }

    private fun refillNext() {
        if (!_readerState.value.isPlaying || sentences.isEmpty()) return
        if (nextToEnqueueIndex < sentences.size) {
            enqueueSentence(sentences[nextToEnqueueIndex])
            nextToEnqueueIndex++
        }
    }

    private fun enqueueSentence(sentence: Sentence) {
        val segment = segments.firstOrNull { it.id == sentence.segmentId }
        val speakerId = segment?.effectiveSpeakerId ?: sentence.initialSpeakerId
        val speaker = characters[speakerId] ?: characters[Character.UNNAMED_ID] ?: Character.Narrator

        // Select voice using VoiceSelector rules
        val usedVoiceIds = characters.values.mapNotNull { it.voiceId }.toSet()
        val narratorVoiceId = characters[Character.NARRATOR_ID]?.voiceId
        val voiceResult = VoiceSelector.pick(
            character = speaker,
            availableVoices = availableVoices,
            usedVoices = usedVoiceIds,
            bookLocale = bookLocale,
            narratorVoiceId = narratorVoiceId
        )

        if (voiceResult.missingVoiceWarning != null) {
            _readerState.value = _readerState.value.copy(missingVoiceWarning = voiceResult.missingVoiceWarning)
        }

        // Settings come only from getSettings(character)
        val settings = VoiceSettings.getSettings(speaker, globalSpeed)
        val utteranceId = makeUtteranceId(sentence.index, sentence.segmentId)

        speechEngine.speak(
            sentence = sentence.text,
            voice = voiceResult.voice,
            settings = settings,
            utteranceId = utteranceId
        )
    }

    private fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Started -> {
                val sentenceIndex = parseSentenceIndex(event.utteranceId) ?: return
                currentSentenceIndex = sentenceIndex

                val sentence = sentences.getOrNull(sentenceIndex)
                val speakerChip = sentence?.let {
                    val speakerId = segments.firstOrNull { seg -> seg.id == it.segmentId }?.effectiveSpeakerId ?: it.initialSpeakerId
                    val char = characters[speakerId] ?: characters[Character.UNNAMED_ID] ?: Character.Narrator
                    SpeakerChipInfo(id = char.id, displayName = char.name)
                }

                updateReaderState(
                    isPlaying = true,
                    sentenceHighlight = sentence?.let { TextRange(it.sentenceStartInChapter, it.sentenceEndInChapter) },
                    currentSpeaker = speakerChip,
                    clearWordHighlight = true
                )

                _state.value = PlaybackState.Playing(
                    segmentIndex = getSegmentIndexForSentence(sentenceIndex),
                    rangeInSegment = sentence?.let { TextRange(it.sentenceStartInSegment, it.sentenceEndInSegment) }
                )

                // Refill queue on onStart of earlier utterances without waiting for completion
                refillNext()
            }

            is SpeechEvent.Range -> {
                val sentenceIndex = parseSentenceIndex(event.utteranceId) ?: currentSentenceIndex
                val sentence = sentences.getOrNull(sentenceIndex)
                if (sentence != null) {
                    val wordRangeInChapter = TextRange(
                        startOffset = sentence.sentenceStartInChapter + event.start,
                        endOffset = sentence.sentenceStartInChapter + event.end
                    )
                    _readerState.value = _readerState.value.copy(wordHighlight = wordRangeInChapter)

                    val wordRangeInSegment = TextRange(
                        startOffset = sentence.sentenceStartInSegment + event.start,
                        endOffset = sentence.sentenceStartInSegment + event.end
                    )
                    _state.value = PlaybackState.Playing(
                        segmentIndex = getSegmentIndexForSentence(sentenceIndex),
                        rangeInSegment = wordRangeInSegment
                    )
                }
            }

            is SpeechEvent.Done -> {
                val doneIndex = parseSentenceIndex(event.utteranceId)
                if (doneIndex != null && doneIndex == sentences.size - 1) {
                    // Reached end of chapter
                    updateReaderState(isPlaying = false, clearSentenceHighlight = true, clearWordHighlight = true)
                    _state.value = PlaybackState.Stopped
                } else {
                    // Refill queue on onDone of earlier utterances
                    refillNext()
                }
            }

            is SpeechEvent.Error -> {
                _readerState.value = _readerState.value.copy(
                    isPlaying = false,
                    missingVoiceWarning = "Speech synthesis error [${event.errorCode}]: ${event.message}"
                )
                _state.value = PlaybackState.Error(
                    message = "Speech error: ${event.message}"
                )
            }
        }
    }

    private fun updateReaderState(
        isPlaying: Boolean,
        sentenceHighlight: TextRange? = null,
        currentSpeaker: SpeakerChipInfo? = null,
        clearSentenceHighlight: Boolean = false,
        clearWordHighlight: Boolean = false
    ) {
        val currentSentence = sentences.getOrNull(currentSentenceIndex)
        val activeSpeaker = currentSpeaker ?: currentSentence?.let {
            val speakerId = segments.firstOrNull { seg -> seg.id == it.segmentId }?.effectiveSpeakerId ?: it.initialSpeakerId
            val char = characters[speakerId] ?: characters[Character.UNNAMED_ID] ?: Character.Narrator
            SpeakerChipInfo(char.id, char.name)
        }

        val remainingWords = sentences.drop(currentSentenceIndex).sumOf { it.text.split(Regex("\\s+")).size }
        val speedMultiplier = globalSpeed ?: 1.0f
        val wordsPerMinute = 150.0f * speedMultiplier
        val estimatedMs = if (wordsPerMinute > 0) ((remainingWords / wordsPerMinute) * 60000).toLong() else 0L

        _readerState.value = _readerState.value.copy(
            isPlaying = isPlaying,
            currentSentenceIndex = currentSentenceIndex,
            sentenceCount = sentences.size,
            estimatedTimeRemainingMs = estimatedMs,
            sentenceHighlight = if (clearSentenceHighlight) null else (sentenceHighlight ?: _readerState.value.sentenceHighlight),
            wordHighlight = if (clearWordHighlight) null else _readerState.value.wordHighlight,
            currentSpeaker = activeSpeaker
        )
    }

    private fun getSegmentIndexForSentence(sentenceIndex: Int): Int {
        val s = sentences.getOrNull(sentenceIndex) ?: return 0
        val idx = segments.indexOfFirst { it.id == s.segmentId }
        return if (idx >= 0) idx else 0
    }

    private fun splitSegmentsIntoSentences(segmentsList: List<Segment>): List<Sentence> {
        val result = mutableListOf<Sentence>()
        var globalIndex = 0

        val sentenceBoundaryRegex = Regex("""[^.!?…\n]+(?:[.!?…]+["'”’»]?|\n+|$)""")

        for (segment in segmentsList) {
            val text = segment.text
            if (text.isBlank()) continue

            val matches = sentenceBoundaryRegex.findAll(text)
            for (match in matches) {
                val raw = match.value
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) {
                    val leadingWhitespace = raw.indexOf(trimmed)
                    val startInSeg = match.range.first + leadingWhitespace
                    val endInSeg = startInSeg + trimmed.length

                    result.add(
                        Sentence(
                            index = globalIndex,
                            segmentId = segment.id,
                            segmentType = segment.type,
                            text = trimmed,
                            sentenceStartInChapter = segment.startOffset + startInSeg,
                            sentenceEndInChapter = segment.startOffset + endInSeg,
                            sentenceStartInSegment = startInSeg,
                            sentenceEndInSegment = endInSeg,
                            initialSpeakerId = segment.effectiveSpeakerId
                        )
                    )
                    globalIndex++
                }
            }
        }
        return result
    }

    private fun makeUtteranceId(index: Int, segmentId: String): String {
        return "utt_${index}_${segmentId}_${System.nanoTime()}"
    }

    private fun parseSentenceIndex(utteranceId: String): Int? {
        val parts = utteranceId.split("_")
        if (parts.size >= 2 && parts[0] == "utt") {
            return parts[1].toIntOrNull()
        }
        return null
    }

    fun release() {
        stop()
        readyCollectorJob?.cancel()
        eventCollectorJob?.cancel()
        speechEngine.release()
    }

    companion object {
        const val DEFAULT_LOOKAHEAD_COUNT = 3
    }
}
