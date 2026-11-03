package com.novelreader.tts.core.store

/**
 * Storage contract for saving and retrieving reading progress per book and chapter.
 */
interface PositionStore {
    /**
     * Gets the last saved sentence index for the given book and chapter, or null if none saved.
     */
    suspend fun getPosition(bookId: String, chapterId: String): Int?

    /**
     * Persists the current sentence index for the given book and chapter.
     */
    suspend fun savePosition(bookId: String, chapterId: String, sentenceIndex: Int)

    /**
     * Clears the saved position for the given book and chapter.
     */
    suspend fun clearPosition(bookId: String, chapterId: String)
}

/**
 * Helper utility for safely clamping reading position indexes against segment / sentence counts.
 */
object ReadingPositionHelper {
    /**
     * Clamps a sentence index safely within the valid range [0, totalCount - 1].
     * Handles edge cases:
     * - empty list / totalCount <= 0 -> 0
     * - index < 0 or null -> 0
     * - index >= totalCount -> totalCount - 1
     * - index within [0, totalCount - 1] -> index
     */
    fun clampSentenceIndex(index: Int?, totalCount: Int): Int {
        if (index == null || totalCount <= 0 || index < 0) return 0
        if (index >= totalCount) return totalCount - 1
        return index
    }
}
