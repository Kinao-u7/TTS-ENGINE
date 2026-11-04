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
    verbs: List<String> = DEFAULT_VERBS,
    stopwords: Set<String> = DEFAULT_STOPWORDS
) {
    private val speechVerbs: Set<String> = verbs.map { it.lowercase() }.toSet()
    private val stopwordSet: Set<String> = stopwords.map { it.trim().lowercase() }.toSet()

    private val verbsPattern: String = speechVerbs
        .flatMap { listOf(it.lowercase(), it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }, it.uppercase()) }
        .distinct()
        .joinToString("|") { Regex.escape(it) }

    // Pattern 1a: after quote - `," said Lin`, `," asked Xian`, `," whispered Chen Yu`, `," called back Xian`
    // (?i) applied only to the verb part, NOT to the candidate name part.
    private val postSaidNameRegex = Regex(
        """^\s*[,—–-]?\s*(?i:$verbsPattern)(?:\s+(?i:(?!to\b)[a-z]+))?\s+([\p{L}]+(?:\s+[\p{L}]+){0,3})(?:\s+(?i:to)\s+[\p{L}]+(?:\s+[\p{L}]+)*)?\b"""
    )

    // Pattern 1b: after quote - `," Lin Mo said`, `," Xian called back`, `," And Xian said`
    // (?i) applied only to the verb part, NOT to the candidate name part.
    private val postNameSaidRegex = Regex(
        """^\s*[,—–-]?\s*([\p{L}]+(?:\s+[\p{L}]+){0,3})\s+(?i:$verbsPattern)(?:\s+(?i:(?!to\b)[a-z]+))?(?:\s+(?i:to)\s+[\p{L}]+(?:\s+[\p{L}]+)*)?\b"""
    )

    // Pattern 2: before quote - `Lin Mo said, "..."` or `And Xian said: "..."`
    // (?i) applied only to the verb part, NOT to the candidate name part.
    private val preQuoteRegex = Regex(
        """([\p{L}]+(?:\s+[\p{L}]+){0,3})\s+(?i:$verbsPattern)(?:\s+(?i:(?!to\b)[a-z]+))?(?:\s+(?i:to)\s+[\p{L}]+(?:\s+[\p{L}]+)*)?\s*[,:]?\s*$"""
    )

    private enum class TagSource {
        SPEECH_TAG,
        CONTINUATION,
        ACTION_BEAT
    }

    private data class CandidateInfo(
        val name: String,
        val source: TagSource,
        val hasSpeechVerb: Boolean
    )

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
        val candidates = Array<CandidateInfo?>(segments.size) { null }

        // Compute paragraph indices for all segments based on newline boundaries
        val paragraphIndices = computeParagraphIndices(segments)

        // -------------------------------------------------------------------------
        // Priority 1: Explicit speech-verb tag (Pattern 1a, 1b, Pattern 2)
        // -------------------------------------------------------------------------
        for (i in segments.indices) {
            if (segments[i].type == SegmentType.DIALOGUE) {
                val candidateName = findSpeakerCandidate(segments, i)
                if (candidateName != null && !normalizedIgnored.contains(candidateName.trim().lowercase())) {
                    candidates[i] = CandidateInfo(
                        name = candidateName,
                        source = TagSource.SPEECH_TAG,
                        hasSpeechVerb = true
                    )
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
            val speechTagSpeakers = dIndices
                .mapNotNull { idx -> candidates[idx]?.takeIf { it.source == TagSource.SPEECH_TAG }?.name }
                .distinctBy { it.lowercase() }

            if (speechTagSpeakers.size == 1) {
                val singleSpeakerName = speechTagSpeakers.first()
                for (idx in dIndices) {
                    if (candidates[idx] == null) {
                        candidates[idx] = CandidateInfo(
                            name = singleSpeakerName,
                            source = TagSource.CONTINUATION,
                            hasSpeechVerb = false
                        )
                    }
                }
            } else if (speechTagSpeakers.size > 1) {
                parasWithConflictingSpeakers.add(paraIndex)
            }
        }

        // -------------------------------------------------------------------------
        // Priority 3: Action beat
        // If the sentence immediately before a quote (same paragraph) starts with a
        // capitalized name (1 to 3 words, or a name matching an existing character or alias)
        // followed by a verb, and the quote has no tag of its own, assign that name.
        // -------------------------------------------------------------------------
        for (i in segments.indices) {
            if (segments[i].type == SegmentType.DIALOGUE && candidates[i] == null) {
                val para = paragraphIndices[i]
                if (!parasWithConflictingSpeakers.contains(para)) {
                    val candidateName = findActionBeatCandidate(segments, i, characterList, normalizedIgnored)
                    if (candidateName != null) {
                        candidates[i] = CandidateInfo(
                            name = candidateName,
                            source = TagSource.ACTION_BEAT,
                            hasSpeechVerb = false
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------------------
        // Priority 4: Confirmation Threshold & Character Creation
        // A new name is only created as a Character after it is attributed at least
        // twice in the same chapter, OR once if it matches an existing character name
        // or alias, OR if it appears in the strong pattern '"..." said Name' with a speech verb.
        // Otherwise leave the segment as Unnamed and don't create anything.
        // -------------------------------------------------------------------------
        val candidateCounts = mutableMapOf<String, Int>()
        for (c in candidates) {
            if (c != null) {
                val key = c.name.lowercase()
                candidateCounts[key] = (candidateCounts[key] ?: 0) + 1
            }
        }

        fun isCandidateConfirmed(name: String): Boolean {
            // 1. Matches an existing character name or alias
            if (characterList.any { it.matchesName(name) && !it.isBuiltIn }) {
                return true
            }
            // 2. Appears in strong pattern with a speech verb
            val hasSpeechVerb = candidates.any {
                it != null && it.name.equals(name, ignoreCase = true) && it.hasSpeechVerb
            }
            if (hasSpeechVerb) {
                return true
            }
            // 3. Attributed at least twice in the same chapter
            val count = candidateCounts[name.lowercase()] ?: 0
            if (count >= 2) {
                return true
            }
            return false
        }

        val assignedSpeakerIds = Array<String?>(segments.size) { null }
        for (i in segments.indices) {
            val cand = candidates[i]
            if (cand != null && isCandidateConfirmed(cand.name)) {
                val resolvedChar = resolveOrCreate(cand.name, characterList)
                assignedSpeakerIds[i] = resolvedChar.id
            } else {
                assignedSpeakerIds[i] = null
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
                    val raw = matchSaidName.groupValues[1]
                    val validated = cleanAndValidateCandidate(raw)
                    if (validated != null) return validated
                }

                // Pattern 1b: `Name said`
                val matchNameSaid = postNameSaidRegex.find(nextText)
                if (matchNameSaid != null) {
                    val raw = matchNameSaid.groupValues[1]
                    val validated = cleanAndValidateCandidate(raw)
                    if (validated != null) return validated
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
                    val raw = matchPre.groupValues[1]
                    val validated = cleanAndValidateCandidate(raw)
                    if (validated != null) return validated
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
        val recipientMatch = Regex("""\b(?:said\s+to|told)\s+([\p{L}]+)""", RegexOption.IGNORE_CASE).find(sentence)
        val recipient = recipientMatch?.groupValues?.get(1)?.trim()?.lowercase()

        // If this sentence is itself a speech-verb tag for the quote or prior dialogue, it's not an action beat
        if (postSaidNameRegex.containsMatchIn(sentence) || postNameSaidRegex.containsMatchIn(sentence)) {
            return null
        }

        // 1. Check existing characters and aliases (case-insensitive)
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

        // 2. Match 1 to 4 words at start of sentence, validated through cleanAndValidateCandidate
        val capMatch = Regex("""^([\p{L}]+(?:\s+[\p{L}]+){0,3})\b""").find(sentence)
        if (capMatch != null) {
            val rawName = capMatch.groupValues[1]
            val validated = cleanAndValidateCandidate(rawName)
            if (validated != null) {
                val lower = validated.lowercase()
                if (recipient != null && lower == recipient) return null
                if (normalizedIgnored.contains(lower)) return null

                val remainder = sentence.substring(capMatch.range.last + 1).trimStart()
                if (isFollowedByVerb(remainder)) {
                    return validated
                }
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

    /**
     * Cleans and validates a candidate name string according to all rules:
     * 1. Multi-word: accepts up to 3 capitalized tokens.
     * 2. Strips leading and trailing stopwords before validating (e.g. "And Xian" -> "Xian").
     * 3. Rejects if any remaining token is in the stoplist.
     * 4. Rejects single-token candidates of two or fewer letters.
     * 5. Case-sensitive: each token must start with an uppercase letter followed by lowercase letters (Unicode-aware).
     */
    fun cleanAndValidateCandidate(rawCandidate: String): String? {
        if (rawCandidate.isBlank()) return null
        val rawTokens = rawCandidate.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return null

        // Strip leading and trailing stopwords before validating
        var start = 0
        while (start < rawTokens.size && stopwordSet.contains(rawTokens[start].lowercase())) {
            start++
        }
        var end = rawTokens.size - 1
        while (end >= start && stopwordSet.contains(rawTokens[end].lowercase())) {
            end--
        }

        if (start > end) return null

        val tokens = rawTokens.subList(start, end + 1)

        // Accept up to three capitalized tokens
        if (tokens.isEmpty() || tokens.size > 3) return null

        // Reject candidates that are a single token of two or fewer letters
        if (tokens.size == 1 && tokens[0].length <= 2) return null

        // Each token must start with an uppercase letter followed by lowercase letters (Unicode-aware)
        for (token in tokens) {
            if (!NAME_TOKEN_REGEX.matches(token)) return null
        }

        // Reject a candidate if any token (case-insensitive) is in the stoplist
        for (token in tokens) {
            if (stopwordSet.contains(token.lowercase())) return null
        }

        return tokens.joinToString(" ")
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
            voiceGender = VoiceGender.ANY,
            autoCreated = true
        )
        characters.add(newCharacter)
        return newCharacter
    }

    companion object {
        private val NAME_TOKEN_REGEX = Regex("""^\p{Lu}\p{Ll}+$""")

        val DEFAULT_VERBS = listOf(
            "said", "asked", "replied", "whispered", "shouted",
            "muttered", "answered", "cried", "called", "murmured",
            "exclaimed", "gasped", "screamed", "yelled", "groaned",
            "breathed", "demanded", "hissed", "growled"
        )

        val DEFAULT_STOPWORDS: Set<String> = setOf(
            // pronouns
            "he", "she", "they", "it", "we", "you", "i",
            "him", "her", "them", "us", "me", "his", "their", "our", "my", "your",
            "himself", "herself", "themselves", "itself", "myself", "yourself", "ourselves",
            "someone", "somebody", "everyone", "everybody", "nobody", "no one",
            "who", "whoever", "whom", "whose", "which", "what",
            // articles and conjunctions
            "a", "an", "the", "and", "or", "but", "so", "yet", "nor", "as", "if", "then", "than", "when", "while", "because", "though", "although",
            // prepositions
            "to", "of", "in", "on", "at", "by", "for", "with", "from", "into", "over", "under", "after", "before", "seeing",
            // determiners
            "this", "that", "these", "those", "some", "any", "all", "each", "every", "no",
            // common sentence openers
            "suddenly", "finally", "however", "meanwhile", "now", "here", "there", "just", "still", "also", "even", "only"
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

        fun isPronoun(word: String): Boolean {
            return DEFAULT_STOPWORDS.contains(word.trim().lowercase())
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
