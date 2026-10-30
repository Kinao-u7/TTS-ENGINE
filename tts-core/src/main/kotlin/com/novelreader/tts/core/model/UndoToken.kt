package com.novelreader.tts.core.model

/**
 * An individual override mapping restored upon merge undo (for backwards compatibility).
 */
data class OverrideRecord(
    val chapterId: String,
    val segmentId: String,
    val previousOverride: String?
)

/**
 * An individual segment record restored upon merge undo, covering both
 * speakerOverride and speakerId that were repointed during a merge.
 */
data class SegmentSpeakerRecord(
    val chapterId: String,
    val segmentId: String,
    val previousSpeakerId: String? = null,
    val previousSpeakerOverride: String? = null
)

/**
 * Token encapsulating all changes performed during a merge, enabling exact restoration.
 *
 * @property bookId The identifier of the book where the merge took place.
 * @property deletedCharacter The character that was deleted and must be restored.
 * @property targetCharacterId The character that received the merged aliases and lines.
 * @property previousTargetAliases The target character's aliases before the merge.
 * @property affectedRecords The list of segment records (covering speakerOverride and speakerId) modified by the merge.
 * @property affectedOverrides Backwards-compatible alias for affected overrides.
 */
data class UndoToken(
    val bookId: String,
    val deletedCharacter: Character,
    val targetCharacterId: String,
    val previousTargetAliases: List<String>,
    val affectedRecords: List<SegmentSpeakerRecord> = emptyList(),
    val affectedOverrides: List<OverrideRecord> = emptyList()
) {
    val affectedSegmentCount: Int
        get() = if (affectedRecords.isNotEmpty()) affectedRecords.size else affectedOverrides.size
}
