package com.novelreader.tts.core.model

/**
 * An individual override mapping restored upon merge undo.
 */
data class OverrideRecord(
    val chapterId: String,
    val segmentId: String,
    val previousOverride: String?
)

/**
 * Token encapsulating all changes performed during a merge, enabling exact restoration.
 *
 * @property bookId The identifier of the book where the merge took place.
 * @property deletedCharacter The character that was deleted and must be restored.
 * @property targetCharacterId The character that received the merged aliases and lines.
 * @property previousTargetAliases The target character's aliases before the merge.
 * @property affectedOverrides The list of segment overrides modified by the merge.
 */
data class UndoToken(
    val bookId: String,
    val deletedCharacter: Character,
    val targetCharacterId: String,
    val previousTargetAliases: List<String>,
    val affectedOverrides: List<OverrideRecord>
) {
    val affectedSegmentCount: Int
        get() = affectedOverrides.size
}
