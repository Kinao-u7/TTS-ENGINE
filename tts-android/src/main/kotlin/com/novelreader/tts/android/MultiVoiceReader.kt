package com.novelreader.tts.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.novelreader.tts.android.service.AudioFocusMode
import com.novelreader.tts.android.service.TtsPlaybackService
import com.novelreader.tts.android.store.JsonFileCastStore
import com.novelreader.tts.android.store.SharedPreferencesPositionStore
import com.novelreader.tts.core.catalog.VoiceCatalog
import com.novelreader.tts.core.loader.ChapterLoadResult
import com.novelreader.tts.core.loader.ChapterLoader
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.TextRange
import com.novelreader.tts.core.model.VoiceInfo
import com.novelreader.tts.core.model.VoiceSelector
import com.novelreader.tts.core.model.VoiceSettings
import com.novelreader.tts.core.playback.PlaybackController
import com.novelreader.tts.core.playback.ReaderState
import com.novelreader.tts.core.playback.ReadingPositionHelper
import com.novelreader.tts.core.store.CastStore
import com.novelreader.tts.core.store.PositionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * High-level facade orchestrating multi-voice novel reading.
 *
 * Responsibilities:
 * - Owns the [TtsPlaybackService] service binding and lifecycle.
 * - Bridges [PlaybackController] state and audio focus settings.
 * - Owns [CastStore] and [PositionStore] data sources.
 * - Loads chapters into structured segments via [ChapterLoader].
 * - Exposes character stream, current segments, and sentence/word highlight char offsets.
 */
class MultiVoiceReader(
    context: Context,
    val castStore: CastStore = JsonFileCastStore(context.applicationContext),
    val positionStore: PositionStore = SharedPreferencesPositionStore(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val appContext = context.applicationContext

    private var serviceBinder: TtsPlaybackService.LocalBinder? = null

    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    private val _readerState = MutableStateFlow(ReaderState())
    val readerState: StateFlow<ReaderState> = _readerState.asStateFlow()

    private val _sentenceHighlight = MutableStateFlow<TextRange?>(null)
    val sentenceHighlight: StateFlow<TextRange?> = _sentenceHighlight.asStateFlow()

    private val _wordHighlight = MutableStateFlow<TextRange?>(null)
    val wordHighlight: StateFlow<TextRange?> = _wordHighlight.asStateFlow()

    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<VoiceInfo>> = _availableVoices.asStateFlow()

    private val _resumedFromSentence = MutableStateFlow<Int?>(null)
    val resumedFromSentence: StateFlow<Int?> = _resumedFromSentence.asStateFlow()

    private val currentBookIdFlow = MutableStateFlow("default_book")
    var currentBookId: String
        get() = currentBookIdFlow.value
        set(value) {
            currentBookIdFlow.value = value
        }

    var currentChapterId: String = "default_chapter"
        private set

    var currentText: String = ""
        private set

    var autoDetectSpeakers: Boolean = true
    var bookLocale: String = "en-US"

    @OptIn(ExperimentalCoroutinesApi::class)
    val characters: Flow<List<Character>> = currentBookIdFlow.flatMapLatest { bookId ->
        castStore.characters(bookId)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TtsPlaybackService.LocalBinder ?: return
            serviceBinder = binder
            _isBound.value = true

            // Sync available voices
            val voices = binder.ttsEngine.getAvailableVoices().filter { !it.requiresNetwork }
            _availableVoices.value = voices
            binder.controller.updateAvailableVoices(voices)
            binder.controller.setBookLocale(bookLocale)

            // Forward reader state
            scope.launch {
                binder.readerState.collect { state ->
                    _readerState.value = state
                    _sentenceHighlight.value = state.sentenceHighlight
                    _wordHighlight.value = state.wordHighlight
                }
            }

            // Sync characters to controller
            scope.launch {
                characters.collect { chars ->
                    if (chars.isNotEmpty()) {
                        binder.controller.updateCharacters(chars)
                    }
                }
            }

            // If a chapter was loaded prior to binding, re-load segments into controller
            if (_segments.value.isNotEmpty()) {
                binder.controller.loadSegments(_segments.value)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            _isBound.value = false
        }
    }

    init {
        val intent = Intent(appContext, TtsPlaybackService::class.java)
        appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Loads a chapter text into structured, voice-attributed segments.
     */
    suspend fun loadChapter(
        bookId: String,
        chapterId: String,
        text: String,
        chapterTitle: String = "Chapter ${chapterId.takeLast(6)}"
    ): ChapterLoadResult {
        currentBookId = bookId
        currentChapterId = chapterId
        currentText = text

        val binder = serviceBinder
        binder?.controller?.stop()
        binder?.setBookAndChapter(bookId, chapterId, chapterTitle)

        _resumedFromSentence.value = null

        val voiceCatalog = object : VoiceCatalog {
            override fun getAvailableVoices(): List<VoiceInfo> {
                return binder?.ttsEngine?.getAvailableVoices() ?: _availableVoices.value
            }
        }

        val chapterLoader = ChapterLoader(
            castStore = castStore,
            voiceCatalog = voiceCatalog,
            bookLocale = bookLocale
        )

        val result = chapterLoader.load(
            bookId = bookId,
            chapterId = chapterId,
            text = text,
            autoDetectSpeakers = autoDetectSpeakers,
            locale = bookLocale
        )

        _segments.value = result.segments
        binder?.controller?.loadSegments(result.segments)

        // Restore reading position if available
        if (text.isNotBlank() && result.segments.isNotEmpty()) {
            val savedPosition = positionStore.getPosition(bookId, chapterId)
            if (savedPosition != null && savedPosition > 0 && binder != null) {
                val sentenceCount = binder.controller.readerState.value.sentenceCount
                val clamped = ReadingPositionHelper.clampSentenceIndex(savedPosition, sentenceCount)
                if (clamped > 0) {
                    binder.controller.jumpToSentence(clamped)
                    _resumedFromSentence.value = clamped
                }
            }
        }

        return result
    }

    /**
     * Begins or resumes playback, ensuring the foreground service is active.
     */
    fun play() {
        val startIntent = Intent(appContext, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_START_FOREGROUND
        }
        ContextCompat.startForegroundService(appContext, startIntent)
        serviceBinder?.play()
    }

    /**
     * Pauses playback.
     */
    fun pause() {
        serviceBinder?.pause()
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
     * Stops playback and resets state.
     */
    fun stop() {
        serviceBinder?.stop()
    }

    /**
     * Jumps to the next sentence.
     */
    fun next() {
        serviceBinder?.controller?.next()
    }

    /**
     * Jumps to the previous sentence.
     */
    fun previous() {
        serviceBinder?.controller?.previous()
    }

    /**
     * Jumps directly to a given sentence index.
     */
    fun jumpToSentence(sentenceIndex: Int) {
        serviceBinder?.controller?.jumpToSentence(sentenceIndex)
    }

    /**
     * Finds the segment that encompasses the given character offset in the chapter text.
     */
    fun segmentAt(offset: Int): Segment? {
        val segs = _segments.value
        return segs.firstOrNull { offset >= it.startOffset && offset < it.endOffset }
            ?: segs.firstOrNull { offset >= it.startOffset && offset <= it.endOffset }
    }

    /**
     * Assigns a speaker override to a specific segment and begins immediate preview.
     */
    suspend fun assignSpeaker(segmentId: String, characterId: String) {
        castStore.setOverride(currentBookId, currentChapterId, segmentId, characterId)

        serviceBinder?.controller?.reassignSpeaker(segmentId, characterId, isOverride = true)

        val updated = _segments.value.map {
            if (it.id == segmentId) it.copy(speakerOverride = characterId) else it
        }
        _segments.value = updated

        serviceBinder?.controller?.playSegment(segmentId)
    }

    /**
     * Plays a specific segment directly.
     */
    fun playSegment(segmentId: String) {
        serviceBinder?.controller?.playSegment(segmentId)
    }

    /**
     * Updates audio focus behavior in the active service.
     */
    fun setAudioFocusSettings(mode: AudioFocusMode, keepReadingWhenOtherAudioStarts: Boolean) {
        serviceBinder?.setAudioFocusSettings(mode, keepReadingWhenOtherAudioStarts)
    }

    /**
     * Previews a voice by synthesizing a short sample phrase.
     */
    fun previewVoice(character: Character) {
        val binder = serviceBinder ?: return
        val usedVoiceIds = binder.controller.getCharacters().mapNotNull { it.voiceId }.toSet()
        val narratorVoiceId = binder.controller.getCharacters().firstOrNull { it.id == Character.NARRATOR_ID }?.voiceId
        val voiceResult = VoiceSelector.pick(
            character = character,
            availableVoices = _availableVoices.value,
            usedVoices = usedVoiceIds,
            bookLocale = bookLocale,
            narratorVoiceId = narratorVoiceId
        )
        val settings = VoiceSettings.getSettings(character)
        binder.ttsEngine.speak(
            sentence = "Hello, I am ${character.name}.",
            voice = voiceResult.voice,
            settings = settings,
            utteranceId = "preview_${character.id}"
        )
    }

    /**
     * Saves a character to the cast store and updates the controller.
     */
    suspend fun saveCharacter(character: Character) {
        castStore.saveCharacter(currentBookId, character)
        val currentList = serviceBinder?.controller?.getCharacters() ?: emptyList()
        val updatedList = if (currentList.any { it.id == character.id }) {
            currentList.map { if (it.id == character.id) character else it }
        } else {
            currentList + character
        }
        serviceBinder?.controller?.updateCharacters(updatedList)
    }

    /**
     * Deletes a custom character from the cast store.
     */
    suspend fun deleteCharacter(characterId: String) {
        castStore.deleteCharacter(currentBookId, characterId)
        val currentList = serviceBinder?.controller?.getCharacters() ?: emptyList()
        serviceBinder?.controller?.updateCharacters(currentList.filter { it.id != characterId })
    }

    /**
     * Removes all auto-created characters from the cast store.
     */
    suspend fun removeAutoCreatedCharacters() {
        castStore.removeAutoCreatedCharacters(currentBookId)
    }

    /**
     * Unbinds from the playback service and cancels coroutine jobs.
     */
    fun release() {
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {}
        scope.cancel()
    }
}
