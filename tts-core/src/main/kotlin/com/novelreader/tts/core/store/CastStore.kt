package com.novelreader.tts.core.store

import com.novelreader.tts.core.model.Character
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for book character casts, segment speaker overrides,
 * and ignored character names.
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
     * If the character's name or aliases were in the ignored names list, saving it unignores them.
     */
    suspend fun saveCharacter(bookId: String, character: Character)

    /**
     * Deletes a custom character. Built-ins ("narrator", "unnamed") cannot be deleted.
     * When a character is deleted:
     * - Its name and aliases are added to the book's ignored names list so it will not be auto-created on next load.
     * - Its speaker overrides are removed and its lines revert to the default speaker ("unnamed" for dialogue, "narrator" for narration).
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

    /**
     * Returns the set of ignored character names for [bookId] (case-insensitive).
     */
    suspend fun ignoredNames(bookId: String): Set<String>

    /**
     * Unignores a character name for [bookId].
     */
    suspend fun unignoreName(bookId: String, name: String)
}
