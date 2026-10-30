package com.novelreader.tts.core.store

import com.novelreader.tts.core.model.Character
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for book character casts and segment speaker overrides.
 *
 * Implementations must ensure that built-in characters ("narrator" and "unnamed")
 * always exist on first load and cannot be deleted.
 */
interface CastStore {

    /**
     * Observable flow emitting the active character list for [bookId].
     * Always includes built-in characters [Character.NARRATOR_ID] and [Character.UNNAMED_ID].
     */
    fun characters(bookId: String): Flow<List<Character>>

    /**
     * Creates or updates a character (name, voiceId, voiceGender, speed, pitch, readByNarrator).
     */
    suspend fun saveCharacter(bookId: String, character: Character)

    /**
     * Deletes a custom character. Built-ins ("narrator", "unnamed") cannot be deleted.
     * When a character is deleted, its speaker overrides are removed and its lines
     * revert to the default speaker ("narrator" for narration, "unnamed" for dialogue).
     */
    suspend fun deleteCharacter(bookId: String, characterId: String)

    /**
     * Sets or clears a speaker override for a segment in a chapter.
     * Passing null for [characterId] clears the override.
     */
    suspend fun setOverride(bookId: String, chapterId: String, segmentId: String, characterId: String?)

    /**
     * Returns all active segment speaker overrides for [chapterId] in [bookId],
     * mapping segmentId to characterId.
     */
    suspend fun overrides(bookId: String, chapterId: String): Map<String, String>
}
