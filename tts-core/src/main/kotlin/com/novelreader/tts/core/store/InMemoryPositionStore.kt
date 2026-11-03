package com.novelreader.tts.core.store

/**
 * In-memory implementation of [PositionStore] for testing and transient usage.
 */
class InMemoryPositionStore : PositionStore {
    private val positions = mutableMapOf<String, Int>()

    override suspend fun getPosition(bookId: String, chapterId: String): Int? {
        return positions["$bookId:$chapterId"]
    }

    override suspend fun savePosition(bookId: String, chapterId: String, sentenceIndex: Int) {
        positions["$bookId:$chapterId"] = sentenceIndex
    }

    override suspend fun clearPosition(bookId: String, chapterId: String) {
        positions.remove("$bookId:$chapterId")
    }
}
