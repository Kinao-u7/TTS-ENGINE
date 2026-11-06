package com.novelreader.tts.core.loader

import com.novelreader.tts.core.catalog.VoiceCatalog
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.VoiceSelector
import com.novelreader.tts.core.segmenter.NovelSegmenter
import com.novelreader.tts.core.segmenter.TagAttributor
import com.novelreader.tts.core.store.CastStore
import kotlinx.coroutines.flow.first

/**
 * Result of loading and preparing a chapter.
 */
data class ChapterLoadResult(
    val segments: List<Segment>,
    val characters: List<Character>
)

/**
 * Core domain service for loading a chapter into structured, voice-attributed segments.
 *
 * Responsibilities:
 * 1. Segments chapter text using [NovelSegmenter].
 * 2. Runs dialogue tag attribution via [TagAttributor].
 * 3. Resolves existing or auto-creates new characters.
 * 4. Assigns distinct voices once to new characters (and built-in characters if unassigned) and persists them.
 * 5. Applies saved speaker overrides from [CastStore].
 */
class ChapterLoader(
    private val castStore: CastStore,
    private val voiceCatalog: VoiceCatalog,
    private val tagAttributor: TagAttributor = TagAttributor(),
    private val bookLocale: String = "en-US"
) {
    private var boundBookId: String? = null
    private var boundChapterId: String? = null
    private var boundText: String? = null

    /**
     * Secondary constructor binding bookId, chapterId, and text directly.
     */
    constructor(
        castStore: CastStore,
        voiceCatalog: VoiceCatalog,
        bookId: String,
        chapterId: String,
        text: String,
        tagAttributor: TagAttributor = TagAttributor(),
        bookLocale: String = "en-US"
    ) : this(castStore, voiceCatalog, tagAttributor, bookLocale) {
        this.boundBookId = bookId
        this.boundChapterId = chapterId
        this.boundText = text
    }

    /**
     * Executes chapter loading using the bound bookId, chapterId, and text.
     */
    suspend fun load(autoDetectSpeakers: Boolean = true, locale: String = bookLocale): ChapterLoadResult {
        val bId = checkNotNull(boundBookId) { "bookId must be provided" }
        val cId = checkNotNull(boundChapterId) { "chapterId must be provided" }
        val txt = checkNotNull(boundText) { "text must be provided" }
        return load(bId, cId, txt, autoDetectSpeakers, locale)
    }

    /**
     * Loads, segments, attributes, and assigns voices for a chapter.
     */
    suspend fun load(
        bookId: String,
        chapterId: String,
        text: String,
        autoDetectSpeakers: Boolean = true,
        locale: String = bookLocale
    ): ChapterLoadResult {
        val initialChars = castStore.characters(bookId).first()
        val currentChars = if (initialChars.isEmpty()) {
            listOf(Character.Narrator, Character.Unnamed)
        } else {
            initialChars
        }

        if (text.isBlank()) {
            return ChapterLoadResult(
                segments = emptyList(),
                characters = currentChars
            )
        }

        val rawSegments = NovelSegmenter.segment(text)
        val availableVoices = voiceCatalog.getAvailableVoices().filter { !it.requiresNetwork }
        val usedVoiceIds = currentChars.mapNotNull { it.voiceId }.toMutableSet()
        var narratorVoiceId = currentChars.firstOrNull { it.id == Character.NARRATOR_ID }?.voiceId

        // Ensure built-in characters have voices assigned once
        val workingChars = currentChars.toMutableList()
        val narratorIndex = workingChars.indexOfFirst { it.id == Character.NARRATOR_ID }
        if (narratorIndex >= 0 && workingChars[narratorIndex].voiceId == null && availableVoices.isNotEmpty()) {
            val pick = VoiceSelector.pick(
                character = workingChars[narratorIndex],
                availableVoices = availableVoices,
                usedVoices = usedVoiceIds,
                bookLocale = locale
            )
            pick.voice?.id?.let { vId ->
                val updated = workingChars[narratorIndex].copy(voiceId = vId)
                workingChars[narratorIndex] = updated
                usedVoiceIds.add(vId)
                narratorVoiceId = vId
                castStore.saveCharacter(bookId, updated)
            }
        }

        val unnamedIndex = workingChars.indexOfFirst { it.id == Character.UNNAMED_ID }
        if (unnamedIndex >= 0 && workingChars[unnamedIndex].voiceId == null && availableVoices.isNotEmpty()) {
            val pick = VoiceSelector.pick(
                character = workingChars[unnamedIndex],
                availableVoices = availableVoices,
                usedVoices = usedVoiceIds,
                bookLocale = locale,
                narratorVoiceId = narratorVoiceId
            )
            pick.voice?.id?.let { vId ->
                val updated = workingChars[unnamedIndex].copy(voiceId = vId)
                workingChars[unnamedIndex] = updated
                usedVoiceIds.add(vId)
                castStore.saveCharacter(bookId, updated)
            }
        }

        val attributedSegments = if (autoDetectSpeakers) {
            val ignoredNames = castStore.ignoredNames(bookId)
            val attribution = tagAttributor.attribute(rawSegments, workingChars, ignoredNames)

            // For newly created characters from TagAttributor, pick a distinct voice once and save
            for (newChar in attribution.characters) {
                if (workingChars.none { it.id == newChar.id }) {
                    val voiceResult = VoiceSelector.pick(
                        character = newChar,
                        availableVoices = availableVoices,
                        usedVoices = usedVoiceIds,
                        bookLocale = locale,
                        narratorVoiceId = narratorVoiceId
                    )
                    val charWithVoice = newChar.copy(voiceId = voiceResult.voice?.id)
                    voiceResult.voice?.id?.let { usedVoiceIds.add(it) }
                    castStore.saveCharacter(bookId, charWithVoice)
                    workingChars.add(charWithVoice)
                }
            }
            attribution.segments
        } else {
            rawSegments
        }

        // Apply stored overrides for this book & chapterId
        val overridesMap = castStore.overrides(bookId, chapterId)
        val finalSegments = attributedSegments.map { seg ->
            val overrideId = overridesMap[seg.id]
            if (overrideId != null) {
                seg.copy(speakerOverride = overrideId)
            } else {
                seg
            }
        }

        return ChapterLoadResult(
            segments = finalSegments,
            characters = workingChars
        )
    }

    /**
     * Alias for [load] taking bookId, chapterId, and text.
     */
    suspend fun loadChapter(
        bookId: String,
        chapterId: String,
        text: String,
        autoDetectSpeakers: Boolean = true,
        locale: String = bookLocale
    ): ChapterLoadResult = load(bookId, chapterId, text, autoDetectSpeakers, locale)
}
