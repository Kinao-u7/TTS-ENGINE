package com.novelreader.tts.core.model

/**
 * Character span within the chapter text.
 *
 * @property startOffset 0-based character offset (inclusive).
 * @property endOffset 0-based character offset (exclusive).
 */
data class TextRange(
    val startOffset: Int,
    val endOffset: Int
) {
    val length: Int get() = (endOffset - startOffset).coerceAtLeast(0)

    fun contains(offset: Int): Boolean = offset in startOffset until endOffset

    companion object {
        val Empty = TextRange(0, 0)
    }
}
