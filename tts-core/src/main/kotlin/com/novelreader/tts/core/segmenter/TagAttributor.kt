package com.novelreader.tts.core.segmenter

import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.CharacterResolver
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.VoiceGender

/**
 * Result of attribution containing the updated segments and any newly auto-created characters.
 */
data class AttributionResult(
    val segments: List<Segment>,
    val characters: List<Character>
)

/**
 * Pure Kotlin, rule-based dialogue tag attribution without LLM dependencies.
 *
 * Scans context immediately following and preceding dialogue segments for dialogue tags
 * like `"...," said Name` and `Name said, "..."`.
 */
class TagAttributor(
    verbs: List<String> = DEFAULT_VERBS
) {
    private val speechVerbs: Set<String> = verbs.map { it.lowercase() }.toSet()
    private val verbsPattern: String = speechVerbs.joinToString("|") { Regex.escape(it) }

    // Pattern 1a: after quote - `," said Lin` or `," whispered Master Xian`
    private val postSaidNameRegex = Regex(
        """^\s*[,—–-]?\s*(?:$verbsPattern)\s+(?:to\s+[A-Za-z0-9_']+\s+)?([A-Z][a-zA-Z0-9_']*(?:\s+[A-Z][a-zA-Z0-9_']*)*)""",
        RegexOption.IGNORE_CASE
    )

    // Pattern 1b: after quote - `," Lin said` or `," he said`
    private val postNameSaidRegex = Regex(
        """^\s*[,—–-]?\s*([A-Za-z][a-zA-Z0-9_']*(?:\s+[A-Z][a-zA-Z0-9_']*)*)\s+(?:$verbsPattern)\b""",
        RegexOption.IGNORE_CASE
    )

    // Pattern 2: before quote - `Lin said, "..."` or `He said to Xian:\n"..."`
    // Extracts subject before the speech verb. Note: recipient in `said to <Recipient>` is NOT the speaker!
    private val preQuoteRegex = Regex(
        """([A-Za-z][a-zA-Z0-9_']*(?:\s+[A-Z][a-zA-Z0-9_']*)*)\s+(?:$verbsPattern)(?:\s+to\s+[A-Za-z0-9_']+)?\s*[,:]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Attributes speakers for all dialogue segments in [segments].
     *
     * @param segments The list of segments (from [NovelSegmenter]).
     * @param existingCharacters Existing known characters in the book/project.
     * @return [AttributionResult] with updated segments and any newly created characters.
     */
    fun attribute(
        segments: List<Segment>,
        existingCharacters: List<Character> = emptyList()
    ): AttributionResult {
        val characterList = existingCharacters.toMutableList()
        if (characterList.none { it.id == Character.NARRATOR_ID }) {
            characterList.add(Character.Narrator)
        }
        if (characterList.none { it.id == Character.UNNAMED_ID }) {
            characterList.add(Character.Unnamed)
        }

        val resultSegments = segments.mapIndexed { index, segment ->
            if (segment.type != SegmentType.DIALOGUE) {
                segment
            } else {
                val candidateName = findSpeakerCandidate(segments, index)
                if (candidateName != null && !isPronoun(candidateName)) {
                    val resolvedChar = resolveOrCreate(candidateName, characterList)
                    // Set speakerId while leaving speakerOverride untouched
                    segment.copy(speakerId = resolvedChar.id)
                } else {
                    segment
                }
            }
        }

        return AttributionResult(
            segments = resultSegments,
            characters = characterList
        )
    }

    private fun findSpeakerCandidate(segments: List<Segment>, dialogueIndex: Int): String? {
        // 1. Check text immediately after the quote (narration at dialogueIndex + 1)
        if (dialogueIndex + 1 < segments.size) {
            val nextSeg = segments[dialogueIndex + 1]
            if (nextSeg.type == SegmentType.NARRATION) {
                val nextText = nextSeg.text

                // Pattern 1a: `said Name`
                val matchSaidName = postSaidNameRegex.find(nextText)
                if (matchSaidName != null) {
                    val name = matchSaidName.groupValues[1].trim()
                    if (isValidNameCandidate(name)) return name
                }

                // Pattern 1b: `Name said`
                val matchNameSaid = postNameSaidRegex.find(nextText)
                if (matchNameSaid != null) {
                    val name = matchNameSaid.groupValues[1].trim()
                    if (isValidNameCandidate(name)) return name
                }
            }
        }

        // 2. Check text immediately before the quote (narration at dialogueIndex - 1)
        if (dialogueIndex > 0) {
            val prevSeg = segments[dialogueIndex - 1]
            if (prevSeg.type == SegmentType.NARRATION) {
                val prevText = prevSeg.text
                val matchPre = preQuoteRegex.find(prevText)
                if (matchPre != null) {
                    val subject = matchPre.groupValues[1].trim()
                    if (isValidNameCandidate(subject)) return subject
                }
            }
        }

        return null
    }

    private fun isValidNameCandidate(name: String): Boolean {
        if (name.isBlank()) return false
        if (isPronoun(name)) return false
        return true
    }

    private fun resolveOrCreate(name: String, characters: MutableList<Character>): Character {
        val existing = CharacterResolver.resolveCharacterByName(characters, name)
        if (existing != null) {
            return existing
        }

        val baseId = "char_" + name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        var uniqueId = baseId
        var counter = 1
        while (characters.any { it.id == uniqueId }) {
            uniqueId = "${baseId}_$counter"
            counter++
        }

        val newCharacter = Character(
            id = uniqueId,
            name = name,
            voiceGender = VoiceGender.ANY
        )
        characters.add(newCharacter)
        return newCharacter
    }

    companion object {
        val DEFAULT_VERBS = listOf(
            "said", "asked", "replied", "whispered", "shouted",
            "muttered", "answered", "cried", "called", "murmured",
            "exclaimed", "gasped", "screamed", "yelled", "groaned",
            "breathed", "demanded", "hissed", "growled"
        )

        private val PRONOUNS = setOf(
            "he", "she", "they", "it", "we", "i", "you",
            "him", "her", "them", "us", "me",
            "himself", "herself", "themselves", "itself",
            "someone", "somebody", "everyone", "everybody",
            "nobody", "no one", "who", "whoever"
        )

        fun isPronoun(word: String): Boolean {
            return PRONOUNS.contains(word.trim().lowercase())
        }
    }
}
