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
 * Implements a two-pass algorithm:
 * - Pass 1: Scans the chapter across ALL patterns (speech tags, same-paragraph continuation, action beats)
 *   to collect candidate names, marking names "confirmed" if:
 *   (a) they appear in any strong pattern (speech verb adjacent to quote in same paragraph), or
 *   (b) they match an existing character name or alias.
 * - Pass 2: Assigns speakers using only confirmed names following priority order:
 *   manual override > explicit speech-verb tag > same-paragraph continuation > action beat > Unnamed.
 *
 * Rules:
 * - Tags do not cross paragraph breaks.
 * - Trims trailing lowercase words after a name (e.g. "said Xian softly" -> "Xian").
 * - Weak patterns assign only confirmed names.
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
    // Particle/adverb following verb must be strictly lowercase letters \p{Ll} so it never swallows capitalized names like Chen
    private val postSaidNameRegex = Regex(
        """^\s*[,—–-]?\s*(?:$verbsPattern)(?:\s+(?!(?:to|said)\b)[\p{Ll}]+)?\s+([\p{L}]+(?:\s+[\p{L}]+){0,3})(?:\s+[tT][oO]\s+[\p{L}]+(?:\s+[\p{L}]+)*)?\b"""
    )

    // Pattern 1b: after quote - `," Lin Mo said`, `," Xian called back`
    private val postNameSaidRegex = Regex(
        """^\s*[,—–-]?\s*([\p{L}]+(?:\s+[\p{L}]+){0,3})\s+(?:$verbsPattern)(?:\s+(?!(?:to|said)\b)[\p{Ll}]+)?(?:\s+[tT][oO]\s+[\p{L}]+(?:\s+[\p{L}]+)*)?\b"""
    )

    // Pattern 2: before quote - `Lin Mo said, "..."` or `And Xian said: "..."`
    private val preQuoteRegex = Regex(
        """([\p{L}]+(?:\s+[\p{L}]+){0,3})\s+(?:$verbsPattern)(?:\s+(?!(?:to|said)\b)[\p{Ll}]+)?(?:\s+[tT][oO]\s+[\p{L}]+(?:\s+[\p{L}]+)*)?\s*[,:]?\s*$"""
    )

    /**
     * Attributes speakers for all dialogue segments in [segments].
     *
     * Two passes per chapter:
     * - Pass 1: Collects candidate names from all patterns across the chapter and confirms those with strong tags or existing characters.
     * - Pass 2: Assigns speakers using only confirmed names.
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
        val paragraphIndices = computeParagraphIndices(segments)

        // -------------------------------------------------------------------------
        // PASS 1: Collect candidate names from ALL patterns across the chapter
        // -------------------------------------------------------------------------
        val speechCandidates = Array<String?>(segments.size) { null }
        val continuationCandidates = Array<String?>(segments.size) { null }
        val actionBeatCandidates = Array<String?>(segments.size) { null }

        val strongNames = mutableSetOf<String>()

        // 1. Explicit speech-verb tags (Pattern 1a, 1b, Pattern 2) - Strong Patterns
        for (i in segments.indices) {
            if (segments[i].type == SegmentType.DIALOGUE) {
                val candidateName = findSpeakerCandidate(segments, i)
                if (candidateName != null && !normalizedIgnored.contains(candidateName.trim().lowercase())) {
                    speechCandidates[i] = candidateName
                    strongNames.add(candidateName.trim().lowercase())
                }
            }
        }

        // 2. Same-paragraph continuation (Weak Pattern)
        val dialogueIndicesByPara = segments.indices
            .filter { segments[it].type == SegmentType.DIALOGUE }
            .groupBy { paragraphIndices[it] }

        val parasWithConflictingSpeakers = mutableSetOf<Int>()

        for ((paraIndex, dIndices) in dialogueIndicesByPara) {
            val speechTagSpeakers = dIndices
                .mapNotNull { speechCandidates[it] }
                .distinctBy { it.lowercase() }

            if (speechTagSpeakers.size == 1) {
                val singleSpeakerName = speechTagSpeakers.first()
                for (idx in dIndices) {
                    if (speechCandidates[idx] == null) {
                        continuationCandidates[idx] = singleSpeakerName
                    }
                }
            } else if (speechTagSpeakers.size > 1) {
                parasWithConflictingSpeakers.add(paraIndex)
            }
        }

        // 3. Action beats (Weak Pattern)
        for (i in segments.indices) {
            if (segments[i].type == SegmentType.DIALOGUE && speechCandidates[i] == null && continuationCandidates[i] == null) {
                val para = paragraphIndices[i]
                if (!parasWithConflictingSpeakers.contains(para)) {
                    val candidateName = findActionBeatCandidate(segments, i, characterList, normalizedIgnored)
                    if (candidateName != null) {
                        actionBeatCandidates[i] = candidateName
                    }
                }
            }
        }

        // A candidate is marked "confirmed" if:
        // (a) it appears in any strong pattern (speech verb adjacent to quote), or
        // (b) it matches an existing character name or alias.
        fun isCandidateConfirmed(name: String): Boolean {
            val lower = name.trim().lowercase()
            if (strongNames.contains(lower)) {
                return true
            }
            if (characterList.any { !it.isBuiltIn && it.matchesName(name) }) {
                return true
            }
            return false
        }

        // -------------------------------------------------------------------------
        // PASS 2: Assign dialogue speakers using only confirmed names
        // Weak patterns (action beat, same-paragraph continuation) assign only confirmed names.
        // -------------------------------------------------------------------------
        val assignedSpeakerIds = Array<String?>(segments.size) { null }
        for (i in segments.indices) {
            if (segments[i].type == SegmentType.DIALOGUE) {
                val speechName = speechCandidates[i]
                val contName = continuationCandidates[i]
                val actionName = actionBeatCandidates[i]

                val resolvedName: String? = when {
                    speechName != null -> speechName // Strong pattern: confirmed by rule (a)
                    contName != null && isCandidateConfirmed(contName) -> contName // Weak pattern
                    actionName != null && isCandidateConfirmed(actionName) -> actionName // Weak pattern
                    else -> null
                }

                if (resolvedName != null) {
                    val resolvedChar = resolveOrCreate(resolvedName, characterList)
                    assignedSpeakerIds[i] = resolvedChar.id
                } else {
                    assignedSpeakerIds[i] = null
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
        // Tags must not cross paragraph breaks: inspect text before any newline
        if (dialogueIndex + 1 < segments.size) {
            val nextSeg = segments[dialogueIndex + 1]
            if (nextSeg.type == SegmentType.NARRATION) {
                val nextText = nextSeg.text.substringBefore('\n').substringBefore('\r')
                if (nextText.isNotBlank()) {
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
        }

        // 2. Check text immediately before the quote (narration at dialogueIndex - 1)
        // Tags must not cross paragraph breaks: inspect text after the last newline
        if (dialogueIndex > 0) {
            val prevSeg = segments[dialogueIndex - 1]
            if (prevSeg.type == SegmentType.NARRATION) {
                val prevText = prevSeg.text.substringAfterLast('\n').substringAfterLast('\r')
                if (prevText.isNotBlank()) {
                    val matchPre = preQuoteRegex.find(prevText)
                    if (matchPre != null) {
                        val raw = matchPre.groupValues[1]
                        val validated = cleanAndValidateCandidate(raw)
                        if (validated != null) return validated
                    }
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
        if (preQuoteRegex.containsMatchIn(sentence) || postSaidNameRegex.containsMatchIn(sentence) || postNameSaidRegex.containsMatchIn(sentence)) {
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

        // 2. Candidate of 1 to 3 words at start of sentence, validated through cleanAndValidateCandidate
        // Words in sentence (allow up to 4 raw tokens to handle sentence-initial stopwords like "And Xian")
        val words = sentence.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        for (numWords in minOf(4, words.size) downTo 1) {
            val raw = words.take(numWords).joinToString(" ")
            val validated = cleanAndValidateCandidate(raw)
            if (validated != null) {
                val remainder = words.drop(numWords).joinToString(" ")
                if (isFollowedByVerb(remainder)) {
                    val lower = validated.lowercase()
                    if (recipient != null && lower == recipient) continue
                    if (normalizedIgnored.contains(lower)) continue
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
     * 2. Strips leading stopwords (e.g. "And Xian" -> "Xian").
     * 3. Trims trailing lowercase words after a name (e.g. "Xian softly" -> "Xian").
     * 4. Rejects if any remaining token is in the stoplist.
     * 5. Rejects single-token candidates of two or fewer letters.
     * 6. Case-sensitive: each token must start with an uppercase letter followed by lowercase letters (Unicode-aware).
     */
    fun cleanAndValidateCandidate(rawCandidate: String): String? {
        if (rawCandidate.isBlank()) return null
        val rawTokens = rawCandidate.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return null

        // Strip leading stopwords before validating
        var start = 0
        while (start < rawTokens.size && stopwordSet.contains(rawTokens[start].lowercase())) {
            start++
        }

        // Trim trailing lowercase words after a name and trailing stopwords
        var end = rawTokens.size - 1
        while (end >= start && (stopwordSet.contains(rawTokens[end].lowercase()) || rawTokens[end].firstOrNull()?.isLowerCase() == true)) {
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

        // Irregular verbs list - note: "poked" and "laughed" removed (handled as standard -ed verbs in isVerb)
        private val IRREGULAR_VERBS = setOf(
            "stood", "sat", "spoke", "came", "went", "took", "ran", "held", "fell",
            "saw", "felt", "knew", "thought", "shook", "bent", "blew", "drew", "froze",
            "gave", "grew", "heard", "kept", "lay", "led", "left", "lent", "lit", "lost",
            "made", "met", "put", "read", "rose", "said", "sent", "set", "shot", "shut",
            "slept", "slid", "slit", "spent", "spit", "split", "spread", "spun", "struck",
            "swam", "swung", "tore", "threw", "woke", "wore", "won", "wound", "wrote",
            "had", "was", "were", "did", "began", "caught", "bit", "broke", "brought",
            "built", "bought", "drank", "ate", "fought", "found", "hung", "told"
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
