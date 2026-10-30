package com.novelreader.tts.core.segmenter

/**
 * Configuration options for [NovelSegmenter].
 *
 * @property singleQuoteDialogue Whether single quotation marks ('...' and ‘...’) should be detected as dialogue.
 *                               Defaults to false to prevent English apostrophes/possessives from being misclassified.
 * @property enableSingleQuotes Backwards-compatible alias for [singleQuoteDialogue].
 * @property idPrefix Prefix for generated segment identifiers.
 */
data class SegmenterOptions(
    val singleQuoteDialogue: Boolean = false,
    val enableSingleQuotes: Boolean = singleQuoteDialogue,
    val idPrefix: String = ""
) {
    /**
     * Effective setting whether single quote dialogue is enabled.
     */
    val effectiveSingleQuoteDialogue: Boolean
        get() = singleQuoteDialogue || enableSingleQuotes

    companion object {
        val Default = SegmenterOptions()
    }
}
