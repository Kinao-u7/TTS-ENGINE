package com.novelreader.tts.core.model

/**
 * A continuous slice of text classified as narration or dialogue.
 *
 * @property id Stable identifier for this segment.
 * @property type Whether this segment is NARRATION or DIALOGUE.
 * @property text The exact substring from the source text.
 * @property startOffset 0-based character offset into the original text (inclusive).
 * @property endOffset 0-based character offset into the original text (exclusive).
 * @property speakerId Character ID resolved by automated attribution or character mapping.
 * @property speakerOverride Character ID explicitly chosen by the user.
 */
data class Segment(
    val id: String,
    val type: SegmentType,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val speakerId: String? = null,
    val speakerOverride: String? = null
) {
    /**
     * Effective speaker resolution rule:
     * speakerOverride ?: speakerId ?: (DIALOGUE -> "unnamed", NARRATION -> "narrator")
     */
    val effectiveSpeakerId: String
        get() = speakerOverride ?: speakerId ?: if (type == SegmentType.DIALOGUE) {
            Character.UNNAMED_ID
        } else {
            Character.NARRATOR_ID
        }

    init {
        require(startOffset >= 0) { "startOffset must be >= 0: $startOffset" }
        require(endOffset >= startOffset) { "endOffset must be >= startOffset: $endOffset < $startOffset" }
    }
}
