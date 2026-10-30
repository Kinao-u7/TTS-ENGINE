package com.novelreader.tts.core.store

import com.novelreader.tts.core.model.Character
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory implementation of [CastStore] suitable for testing and preview.
 */
class InMemoryCastStore : CastStore {

    private val mutex = Mutex()
    private val charactersFlows = mutableMapOf<String, MutableStateFlow<List<Character>>>()
    private val charactersData = mutableMapOf<String, MutableMap<String, Character>>()
    private val overridesData = mutableMapOf<String, MutableMap<String, MutableMap<String, String>>>()

    private fun getOrCreateFlowLocked(bookId: String): MutableStateFlow<List<Character>> {
        return charactersFlows.getOrPut(bookId) {
            val bookChars = charactersData.getOrPut(bookId) { createDefaultCharacters() }
            MutableStateFlow(bookChars.values.toList())
        }
    }

    private fun createDefaultCharacters(): MutableMap<String, Character> {
        val map = linkedMapOf<String, Character>()
        map[Character.NARRATOR_ID] = Character.Narrator
        map[Character.UNNAMED_ID] = Character.Unnamed
        return map
    }

    override fun characters(bookId: String): Flow<List<Character>> {
        val flow = synchronized(charactersFlows) {
            charactersFlows.getOrPut(bookId) {
                val bookChars = charactersData.getOrPut(bookId) { createDefaultCharacters() }
                MutableStateFlow(bookChars.values.toList())
            }
        }
        return flow.asStateFlow()
    }

    override suspend fun saveCharacter(bookId: String, character: Character) = mutex.withLock {
        val bookChars = charactersData.getOrPut(bookId) { createDefaultCharacters() }
        bookChars[character.id] = character
        ensureBuiltInsExist(bookChars)

        val flow = getOrCreateFlowLocked(bookId)
        flow.value = bookChars.values.toList()
    }

    override suspend fun deleteCharacter(bookId: String, characterId: String) = mutex.withLock {
        if (Character.isBuiltIn(characterId)) {
            // Built-ins cannot be deleted
            return@withLock
        }

        val bookChars = charactersData.getOrPut(bookId) { createDefaultCharacters() }
        bookChars.remove(characterId)
        ensureBuiltInsExist(bookChars)

        // Revert lines: remove any overrides in this book pointing to the deleted character
        overridesData[bookId]?.values?.forEach { chapterMap ->
            val iterator = chapterMap.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value == characterId) {
                    iterator.remove()
                }
            }
        }

        val flow = getOrCreateFlowLocked(bookId)
        flow.value = bookChars.values.toList()
    }

    override suspend fun setOverride(
        bookId: String,
        chapterId: String,
        segmentId: String,
        characterId: String?
    ) = mutex.withLock {
        val bookOverrides = overridesData.getOrPut(bookId) { mutableMapOf() }
        val chapterOverrides = bookOverrides.getOrPut(chapterId) { mutableMapOf() }
        if (characterId == null) {
            chapterOverrides.remove(segmentId)
        } else {
            chapterOverrides[segmentId] = characterId
        }
    }

    override suspend fun overrides(bookId: String, chapterId: String): Map<String, String> = mutex.withLock {
        overridesData[bookId]?.get(chapterId)?.toMap() ?: emptyMap()
    }

    private fun ensureBuiltInsExist(map: MutableMap<String, Character>) {
        if (!map.containsKey(Character.NARRATOR_ID)) {
            map[Character.NARRATOR_ID] = Character.Narrator
        }
        if (!map.containsKey(Character.UNNAMED_ID)) {
            map[Character.UNNAMED_ID] = Character.Unnamed
        }
    }
}
