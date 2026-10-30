package com.novelreader.tts.core.model

object CharacterResolver {
    /**
     * Resolves a character by matching name or any alias case-insensitively.
     * Exact name match takes precedence over alias match.
     */
    fun resolveCharacterByName(characters: Iterable<Character>, name: String): Character? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        // 1. Exact name match (case-insensitive)
        characters.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }

        // 2. Alias match (case-insensitive)
        return characters.firstOrNull { char ->
            char.aliases.any { it.equals(trimmed, ignoreCase = true) }
        }
    }
}
