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
    private val verbsPattern: String = speechVerbs
        .flatMap { listOf(it.lowercase(), it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }, it.uppercase()) }
        .distinct()
        .joinToString("|") { Regex.escape(it) }

    // Pattern 1a: after quote - `," said Lin`, `," asked Xian`, `," whispered Chen Yu`, `," called back Xian`
    // Name is 1-3 capitalized words. Optional trailing particle after verb must be lowercase and not "to".
    private val postSaidNameRegex = Regex(
        """^\s*[,—–-]?\s*(?:$verbsPattern)(?:\s+(?!to\b)[a-z][a-z0-9_']*)?\s+([A-Z][a-zA-Z0-9_']*(?:\s+[A-Z][a-zA-Z0-9_']*){0,2})(?:\s+to\s+[A-Za-z0-9_']+(?:\s+[A-Za-z0-9_']+)*)?\b"""
    )

    // Pattern 1b: after quote - `," Lin Mo said`, `," Xian called back`, `," he said`
    // Subject is 1-3 capitalized words or single lowercase pronoun.
    private val postNameSaidRegex = Regex(
        """^\s*[,—–-]?\s*([A-Z][a-zA-Z0-9_']*(?:\s+[A-Z][a-zA-Z0-9_']*){0,2}|[a-z]+)\s+(?:$verbsPattern)(?:\s+(?!to\b)[a-z][a-z0-9_']*)?(?:\s+to\s+[A-Za-z0-9_']+(?:\s+[A-Za-z0-9_']+)*)?\b"""
    )

    // Pattern 2: before quote - `Lin Mo said, "..."` or `Xian called back: "..."` or `He said to Xian:\n"..."`
    // Extracts subject before the speech verb. Note: recipient in `said to <Recipient>` is NOT the speaker!
    // Action beats like `Xian poked the embers.` do not end with a speech verb, so they do not match.
    private val preQuoteRegex = Regex(
        """([A-Z][a-zA-Z0-9_']*(?:\s+[A-Z][a-zA-Z0-9_']*){0,2}|[a-z]+)\s+(?:$verbsPattern)(?:\s+(?!to\b)[a-z][a-z0-9_']*)?(?:\s+to\s+[A-Za-z0-9_']+(?:\s+[A-Za-z0-9_']+)*)?\s*[,:]?\s*$"""
    )

    private enum class TagSource {
        SPEECH_TAG,
        CONTINUATION,
        ACTION_BEAT
    }

    /**
     * Attributes speakers for all dialogue segments in [segments].
     *
     * Priority: manual override > explicit speech-verb tag > same-paragraph continuation > action beat > Unnamed.
     * Never overwrites a higher-priority source.
     *
     * @param segments The list of segments (from [NovelSegmenter]).
     * @param existingCharacters Existing known characters in the book/project.
     * @param ignoredNames Names that should not be auto-detected or assigned.
     * @return [AttributionResult] with updated segments and any newly created characters.
     */
    fun attribute(
        segments: List<Segment>,
        existingCharacters: List<Character> = emptyList(),
        ignoredNames: Set<String> = emptySet()
    ): AttributionResult {
        val characterList = existingCharacters.toMutableList()
        if (characterList.none { it.id == Character.NARRATOR_ID }) {
            characterList.add(Character.Narrator)
        }
        if (characterList.none { it.id == Character.UNNAMED_ID }) {
            characterList.add(Character.Unnamed)
        }

        val normalizedIgnored = ignoredNames.map { it.trim().lowercase() }.toSet()
        val assignedSpeakerIds = Array<String?>(segments.size) { null }
        val assignmentSources = Array<TagSource?>(segments.size) { null }

        // Compute paragraph indices for all segments based on newline boundaries
        val paragraphIndices = computeParagraphIndices(segments)

        // -------------------------------------------------------------------------
        // Priority 1: Explicit speech-verb tag (Pattern 1a, 1b, Pattern 2)
        // -------------------------------------------------------------------------
        for (i in segments.indices) {
            if (segments[i].type == SegmentType.DIALOGUE) {
                val candidateName = findSpeakerCandidate(segments, i)
                if (candidateName != null && !isPronoun(candidateName) && !normalizedIgnored.contains(candidateName.trim().lowercase())) {
                    val resolvedChar = resolveOrCreate(candidateName, characterList)
                    assignedSpeakerIds[i] = resolvedChar.id
                    assignmentSources[i] = TagSource.SPEECH_TAG
                }
            }
        }

        // -------------------------------------------------------------------------
        // Priority 2: Same-paragraph continuation
        // If a paragraph contains exactly one distinct named speaker (from a speech-verb tag),
        // every other untagged dialogue segment in that same paragraph gets that speaker.
        // If the paragraph has two different named speakers, leave untagged ones Unnamed.
        // -------------------------------------------------------------------------
        val dialogueIndicesByPara = segments.indices
            .filter { segments[it].type == SegmentType.DIALOGUE }
            .groupBy { paragraphIndices[it] }

        val parasWithConflictingSpeakers = mutableSetOf<Int>()

        for ((paraIndex, dIndices) in dialogueIndicesByPara) {
            val speechTagSpeakerIds = dIndices
                .filter { assignmentSources[it] == TagSource.SPEECH_TAG }
                .mapNotNull { assignedSpeakerIds[it] }
                .distinct()

            if (speechTagSpeakerIds.size == 1) {
                val singleSpeakerId = speechTagSpeakerIds.first()
                for (idx in dIndices) {
                    if (assignedSpeakerIds[idx] == null) {
                        assignedSpeakerIds[idx] = singleSpeakerId
                        assignmentSources[idx] = TagSource.CONTINUATION
                    }
                }
            } else if (speechTagSpeakerIds.size > 1) {
                parasWithConflictingSpeakers.add(paraIndex)
            }
        }

        // -------------------------------------------------------------------------
        // Priority 3: Action beat
        // If the sentence immediately before a quote (same paragraph) starts with a
        // capitalized name (1 to 3 words, or a name matching an existing character or alias)
        // followed by a verb, and the quote has no tag of its own, assign that name.
        // Pronouns stay Unnamed. Sentences containing "said to <Name>" or "told <Name>" never assign recipient.
        // Untagged dialogue in paragraphs with multiple conflicting speech tags remain Unnamed.
        // -------------------------------------------------------------------------
        for (i in segments.indices) {
            if (segments[i].type == SegmentType.DIALOGUE && assignedSpeakerIds[i] == null) {
                val para = paragraphIndices[i]
                if (!parasWithConflictingSpeakers.contains(para)) {
                    val candidateName = findActionBeatCandidate(segments, i, characterList, normalizedIgnored)
                    if (candidateName != null) {
                        val resolvedChar = resolveOrCreate(candidateName, characterList)
                        assignedSpeakerIds[i] = resolvedChar.id
                        assignmentSources[i] = TagSource.ACTION_BEAT
                    }
                }
            }
        }

        // -------------------------------------------------------------------------
        // Build final segments preserving speakerOverride
        // -------------------------------------------------------------------------
        val resultSegments = segments.mapIndexed { index, segment ->
            if (segment.type == SegmentType.DIALOGUE) {
                segment.copy(speakerId = assignedSpeakerIds[index])
            } else {
                segment
            }
        }

        return AttributionResult(
            segments = resultSegments,
            characters = characterList
        )
    }

    private fun computeParagraphIndices(segments: List<Segment>): IntArray {
        val result = IntArray(segments.size)
        var currentPara = 0
        for (i in segments.indices) {
            result[i] = currentPara
            val text = segments[i].text
            var j = 0
            while (j < text.length) {
                val ch = text[j]
                if (ch == '\r') {
                    currentPara++
                    if (j + 1 < text.length && text[j + 1] == '\n') {
                        j++
                    }
                } else if (ch == '\n') {
                    currentPara++
                }
                j++
            }
        }
        return result
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

    private fun findActionBeatCandidate(
        segments: List<Segment>,
        dialogueIndex: Int,
        characterList: List<Character>,
        normalizedIgnored: Set<String>
    ): String? {
        if (dialogueIndex <= 0) return null
        val prevSeg = segments[dialogueIndex - 1]
        if (prevSeg.type != SegmentType.NARRATION) return null

        // Must be in the same paragraph as the quote (no newline between preceding sentence and quote)
        val textInSamePara = prevSeg.text.substringAfterLast('\n').substringAfterLast('\r')
        if (textInSamePara.isBlank()) return null

        // Extract the sentence immediately before the quote
        val sentence = extractLastSentence(textInSamePara)
        if (sentence.isBlank()) return null

        // A sentence containing "said to <Name>" or "told <Name>" never assigns the recipient
        val recipientMatch = Regex("""\b(?:said\s+to|told)\s+([A-Za-z0-9_']+)""", RegexOption.IGNORE_CASE).find(sentence)
        val recipient = recipientMatch?.groupValues?.get(1)?.trim()?.lowercase()

        // If this sentence is itself a speech-verb tag for the quote or prior dialogue, it's not an action beat
        if (postSaidNameRegex.containsMatchIn(sentence) || postNameSaidRegex.containsMatchIn(sentence)) {
            return null
        }

        // Check existing characters and aliases (case-insensitive)
        val knownCandidates = characterList
            .filter { it.id != Character.NARRATOR_ID && it.id != Character.UNNAMED_ID }
            .flatMap { char -> (listOf(char.name) + char.aliases).map { it to char } }
            .sortedByDescending { it.first.length }

        for ((knownName, char) in knownCandidates) {
            val pattern = Regex("""^${Regex.escape(knownName)}\b""", RegexOption.IGNORE_CASE)
            val match = pattern.find(sentence)
            if (match != null) {
                val remainder = sentence.substring(match.range.last + 1).trimStart()
                if (isFollowedByVerb(remainder)) {
                    if (recipient != null && char.name.lowercase() == recipient) return null
                    if (normalizedIgnored.contains(char.name.lowercase())) return null
                    return char.name
                }
            }
        }

        // Match 1 to 3 capitalized words at start of sentence
        val capMatch = Regex("""^([A-Z][a-zA-Z0-9_']*(?:\s+[A-Z][a-zA-Z0-9_']*){0,2})\b""").find(sentence)
        if (capMatch != null) {
            val rawName = capMatch.groupValues[1].trim()
            val lower = rawName.lowercase()
            if (isPronoun(lower)) return null
            if (NON_NAME_WORDS.contains(lower)) return null
            val words = rawName.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (words.any { it.length < 2 || NON_NAME_WORDS.contains(it.lowercase()) || isPronoun(it.lowercase()) }) return null
            if (recipient != null && lower == recipient) return null
            if (normalizedIgnored.contains(lower)) return null

            val remainder = sentence.substring(capMatch.range.last + 1).trimStart()
            if (isFollowedByVerb(remainder)) {
                return rawName
            }
        }

        return null
    }

    private fun extractLastSentence(textInSamePara: String): String {
        val trimmed = textInSamePara.trim()
        if (trimmed.isEmpty()) return ""
        val content = if (trimmed.last() in ".!?") trimmed.dropLast(1).trimEnd() else trimmed
        if (content.isEmpty()) return ""
        val lastTerminator = content.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (lastTerminator != -1) {
            content.substring(lastTerminator + 1).trim()
        } else {
            content
        }
    }

    private fun isFollowedByVerb(remainder: String): Boolean {
        if (remainder.isBlank()) return false
        val words = remainder.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val firstWord = words[0].replace(Regex("""[^a-zA-Z]"""), "")
        if (isVerb(firstWord)) return true
        if (words.size > 1 && (firstWord.endsWith("ly") || firstWord.lowercase() in ADVERB_MARKERS)) {
            val secondWord = words[1].replace(Regex("""[^a-zA-Z]"""), "")
            if (isVerb(secondWord)) return true
        }
        return false
    }

    private fun isValidNameCandidate(name: String): Boolean {
        if (name.isBlank()) return false
        if (isPronoun(name)) return false
        val lower = name.trim().lowercase()
        if (NON_NAME_WORDS.contains(lower)) return false
        val words = name.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.any { it.length < 2 || NON_NAME_WORDS.contains(it.lowercase()) || isPronoun(it.lowercase()) }) return false
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

        private val IRREGULAR_VERBS = setOf(
            "stood", "sat", "spoke", "came", "went", "took", "ran", "held", "fell",
            "saw", "felt", "knew", "thought", "shook", "bent", "blew", "drew", "froze",
            "gave", "grew", "heard", "kept", "lay", "led", "left", "lent", "lit", "lost",
            "made", "met", "put", "read", "rose", "said", "sent", "set", "shot", "shut",
            "slept", "slid", "slit", "spent", "spit", "split", "spread", "spun", "struck",
            "swam", "swung", "tore", "threw", "woke", "wore", "won", "wound", "wrote",
            "had", "was", "were", "did", "began", "caught", "bit", "broke", "brought",
            "built", "bought", "drank", "ate", "fought", "found", "hung", "told", "poked", "laughed"
        )

        private val ADVERB_MARKERS = setOf(
            "just", "then", "suddenly", "gently", "softly", "quietly", "slowly", "calmly", "faintly"
        )

        private val PRONOUNS = setOf(
            "he", "she", "they", "it", "we", "i", "you",
            "him", "her", "them", "us", "me",
            "himself", "herself", "themselves", "itself",
            "myself", "yourself", "ourselves",
            "someone", "somebody", "everyone", "everybody",
            "nobody", "no one", "who", "whoever", "whom", "whose", "which", "what"
        )

        private val NON_NAME_WORDS = setOf(
            "a", "an", "the", "this", "that", "these", "those",
            "some", "all", "every", "no", "any", "each", "either", "neither", "another",
            "there", "here", "then", "now", "suddenly", "finally", "meanwhile",
            "however", "instead", "still", "soon", "later", "again", "once",
            "just", "even", "already", "perhaps", "maybe", "surely", "slowly",
            "quickly", "quietly", "silently", "voice", "voices", "something",
            "nothing", "anything", "everything"
        )

        fun isPronoun(word: String): Boolean {
            return PRONOUNS.contains(word.trim().lowercase())
        }

        fun isVerb(rawWord: String): Boolean {
            val word = rawWord.lowercase().trimEnd { !it.isLetter() }
            if (word.isEmpty()) return false
            if (DEFAULT_VERBS.contains(word)) return true
            if (IRREGULAR_VERBS.contains(word)) return true
            if (word.endsWith("ed") && word.length >= 4) return true
            if (word.endsWith("ing") && word.length >= 5) return true
            if (word.endsWith("es") && word.length >= 4) return true
            if (word.endsWith("s") && word.length >= 3 && !word.endsWith("ss")) return true
            return false
        }
    }
}
