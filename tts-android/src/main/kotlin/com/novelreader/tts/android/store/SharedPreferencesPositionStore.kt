package com.novelreader.tts.android.store

import android.content.Context
import android.content.SharedPreferences
import com.novelreader.tts.core.store.PositionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SharedPreferences-backed implementation of [PositionStore].
 */
class SharedPreferencesPositionStore(
    context: Context,
    prefsName: String = "novel_reader_positions"
) : PositionStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun getPosition(bookId: String, chapterId: String): Int? = withContext(Dispatchers.IO) {
        val key = keyFor(bookId, chapterId)
        if (prefs.contains(key)) {
            prefs.getInt(key, 0)
        } else {
            null
        }
    }

    override suspend fun savePosition(bookId: String, chapterId: String, sentenceIndex: Int) = withContext(Dispatchers.IO) {
        prefs.edit().putInt(keyFor(bookId, chapterId), sentenceIndex).apply()
    }

    override suspend fun clearPosition(bookId: String, chapterId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(keyFor(bookId, chapterId)).apply()
    }

    private fun keyFor(bookId: String, chapterId: String): String = "pos_${bookId}_$chapterId"
}
