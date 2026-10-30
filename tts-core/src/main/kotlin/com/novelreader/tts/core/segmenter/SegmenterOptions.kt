package com.novelreader.tts.core.segmenter

/**
 * Configuration options for [NovelSegmenter].
 *
 * @property enableSingleQuotes Whether single quotation marks ('...' and ‘...’) should be detected as dialogue.
 *                             Defaults to false to prevent English apostrophes/possessives from being misclassified.
 * @property idPrefix Prefix for generated segment identifiers.
 */
data class SegmenterOptions(
    val enableSingleQuotes: Boolean = false,
    val idPrefix: String = "seg"
) {
    companion object {
        val Default = SegmenterOptions()
    }
}
