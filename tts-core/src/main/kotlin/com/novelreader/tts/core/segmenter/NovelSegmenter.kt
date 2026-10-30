package com.novelreader.tts.core.segmenter

import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType

/**
 * Text segmenter that splits novel text into contiguous NARRATION and DIALOGUE segments
 * with precise character offsets into the original text.
 *
 * Handles:
 * - Straight double quotes ("...")
 * - Curly double quotes (“...”)
 * - French angle quotes / guillemets («...»)
 * - Single quotes ('...' and ‘...’) when [SegmenterOptions.singleQuoteDialogue] is true (disabled by default)
 * - Contractions and apostrophes inside quotes (e.g. don't, it's, Xian's)
 * - Quotes on their own lines
 * - Narration followed by dialogue on a subsequent line
 * - Multi-paragraph quotes where an opening quote is not closed before a new paragraph begins with a quote
 * - Unclosed opening quotes (gracefully terminated at the end of the paragraph or input)
 * - Orphan closing quotes with no matching opening quote (marks text from start of paragraph or last sentence terminator as DIALOGUE)
 * - Stable segment IDs: "<dialogueIndexInChapter>-<short hash of segment text>", never raw offsets
 * - Effective speaker defaults to "narrator" for narration and "unnamed" for dialogue.
 */
object NovelSegmenter {

    private data class DialogueSpan(
        val startOffset: Int,
        val endOffset: Int
    )

    /**
     * Segments the input text into a list of [Segment]s.
     * The returned segments partition the entire string from 0 to text.length without gaps or overlaps.
     *
     * @param text The chapter text to segment.
     * @param options Configuration options for quote detection and ID generation.
     */
    fun segment(
        text: String,
        options: SegmenterOptions = SegmenterOptions.Default
    ): List<Segment> {
        if (text.isEmpty()) return emptyList()

        val dialogueSpans = findDialogueSpans(text, options)
        val segments = mutableListOf<Segment>()
        var cursor = 0
        var dialogueIndex = 0
        var narrationIndex = 0

        for (span in dialogueSpans) {
            if (span.startOffset > cursor) {
                // Intervening narration
                val narrationText = text.substring(cursor, span.startOffset)
                val id = generateSegmentId(options.idPrefix, SegmentType.NARRATION, narrationIndex, narrationText)
                segments.add(
                    Segment(
                        id = id,
                        type = SegmentType.NARRATION,
                        text = narrationText,
                        startOffset = cursor,
                        endOffset = span.startOffset,
                        speakerId = null,
                        speakerOverride = null
                    )
                )
                narrationIndex++
            }

            // Dialogue segment
            val dialogueText = text.substring(span.startOffset, span.endOffset)
            val id = generateSegmentId(options.idPrefix, SegmentType.DIALOGUE, dialogueIndex, dialogueText)
            segments.add(
                Segment(
                    id = id,
                    type = SegmentType.DIALOGUE,
                    text = dialogueText,
                    startOffset = span.startOffset,
                    endOffset = span.endOffset,
                    speakerId = null,
                    speakerOverride = null
                )
            )
            dialogueIndex++
            cursor = span.endOffset
        }

        if (cursor < text.length) {
            val trailingText = text.substring(cursor)
            val id = generateSegmentId(options.idPrefix, SegmentType.NARRATION, narrationIndex, trailingText)
            segments.add(
                Segment(
                    id = id,
                    type = SegmentType.NARRATION,
                    text = trailingText,
                    startOffset = cursor,
                    endOffset = text.length,
                    speakerId = null,
                    speakerOverride = null
                )
            )
        }

        return segments
    }

    /**
     * Overload for backwards compatibility with explicit idPrefix.
     */
    fun segment(text: String, idPrefix: String): List<Segment> {
        return segment(text, SegmenterOptions(idPrefix = idPrefix))
    }

    fun computeShortHash(text: String): String {
        val trimmed = text.trim()
        val hash = (trimmed.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        return hash.padStart(8, '0').takeLast(6)
    }

    private fun generateSegmentId(
        prefix: String,
        type: SegmentType,
        index: Int,
        text: String
    ): String {
        val hash = computeShortHash(text)
        val baseId = if (type == SegmentType.DIALOGUE) {
            "$index-$hash"
        } else {
            "n$index-$hash"
        }
        return if (prefix.isNotBlank()) "${prefix}_$baseId" else baseId
    }

    private fun findDialogueSpans(text: String, options: SegmenterOptions): List<DialogueSpan> {
        val spans = mutableListOf<DialogueSpan>()
        var i = 0
        val length = text.length

        while (i < length) {
            val ch = text[i]

            if (isOpeningQuote(text, i, options)) {
                val span = extractDialogue(text, i, ch, options)
                spans.add(span)
                i = span.endOffset
            } else if (isClosingQuoteOnly(text, i, options)) {
                // Orphan closing quote with no matching opening quote
                val prevSpanEnd = spans.lastOrNull()?.endOffset ?: 0
                val lastParaBreak = findLastParagraphBreakBefore(text, i, prevSpanEnd)
                val startBound = maxOf(lastParaBreak, prevSpanEnd)
                val startOffset = findDialogueStartForOrphanClose(text, startBound, i)

                val span = DialogueSpan(startOffset = startOffset, endOffset = i + 1)
                spans.add(span)
                i = i + 1
            } else {
                i++
            }
        }

        return spans
    }

    private fun isOpeningQuote(text: String, index: Int, options: SegmenterOptions): Boolean {
        val ch = text[index]
        return when (ch) {
            '“', '«' -> true
            '"' -> isStraightQuoteOpening(text, index)
            '\'', '‘' -> options.effectiveSingleQuoteDialogue && isSingleQuoteOpening(text, index)
            else -> false
        }
    }

    private fun isClosingQuoteOnly(text: String, index: Int, options: SegmenterOptions): Boolean {
        val ch = text[index]
        return when (ch) {
            '”', '»' -> true
            '"' -> isStraightQuoteClosing(text, index) && !isStraightQuoteOpening(text, index)
            '\'', '’' -> options.effectiveSingleQuoteDialogue && isSingleQuoteClosing(text, index) && !isSingleQuoteOpening(text, index)
            else -> false
        }
    }

    private fun isStraightQuoteOpening(text: String, index: Int): Boolean {
        val prev = if (index > 0) text[index - 1] else null
        val next = if (index + 1 < text.length) text[index + 1] else null

        val isPrevStartOrWhitespace = prev == null || prev.isWhitespace() || prev in "([{\n\r—–-«“"
        val isNextLetterOrDigitOrSymbol = next != null && (next.isLetterOrDigit() || next in "...—–-“«'‘?!”\"")

        return isPrevStartOrWhitespace && isNextLetterOrDigitOrSymbol
    }

    private fun isStraightQuoteClosing(text: String, index: Int): Boolean {
        val prev = if (index > 0) text[index - 1] else null
        val next = if (index + 1 < text.length) text[index + 1] else null

        val isPrevNonWhitespace = prev != null && !prev.isWhitespace()
        val isNextWhitespaceOrPunctuationOrEnd = next == null || next.isWhitespace() || isPunctuation(next)

        return isPrevNonWhitespace && isNextWhitespaceOrPunctuationOrEnd
    }

    private fun isSingleQuoteClosing(text: String, index: Int): Boolean {
        if (isApostrophe(text, index)) return false
        val prev = if (index > 0) text[index - 1] else null
        val next = if (index + 1 < text.length) text[index + 1] else null

        val isPrevNonWhitespace = prev != null && !prev.isWhitespace()
        val isNextWhitespaceOrPunctuationOrEnd = next == null || next.isWhitespace() || isPunctuation(next)

        return isPrevNonWhitespace && isNextWhitespaceOrPunctuationOrEnd
    }

    private fun isPunctuation(ch: Char): Boolean {
        return ch in ".,;:!?—–-)}]»”\"'\n\r"
    }

    private fun isSingleQuoteOpening(text: String, index: Int): Boolean {
        if (index + 1 >= text.length) return false
        val nextChar = text[index + 1]
        if (nextChar.isWhitespace() || nextChar == '\'' || nextChar == '’') return false

        if (index == 0) return true
        val prevChar = text[index - 1]

        if (prevChar.isLetter()) return false

        return prevChar.isWhitespace() || prevChar in "([{\n\r—–-\"«"
    }

    private fun isApostrophe(text: String, index: Int): Boolean {
        if (index > 0 && index + 1 < text.length) {
            if (text[index - 1].isLetter() && text[index + 1].isLetter()) {
                return true
            }
        }
        return false
    }

    private fun findLastParagraphBreakBefore(text: String, index: Int, minBound: Int): Int {
        var k = index - 1
        while (k >= minBound) {
            if (text[k] == '\n' || text[k] == '\r') {
                return k + 1
            }
            k--
        }
        return minBound
    }

    private fun findDialogueStartForOrphanClose(text: String, startBound: Int, closeQuoteIndex: Int): Int {
        // 1. Narration lead-in ending in comma or colon (6 words or fewer, first comma/colon in paragraph)
        val leadInStart = findLeadInDialogueStart(text, startBound, closeQuoteIndex)
        if (leadInStart != -1) {
            return leadInStart
        }

        // 2. Sentence terminator (. or :) within paragraph
        var lastTerminatorEnd = -1

        var k = startBound
        while (k < closeQuoteIndex) {
            val ch = text[k]
            if (ch == '.' && isSinglePeriod(text, k)) {
                if (k + 1 < closeQuoteIndex && text[k + 1].isWhitespace()) {
                    lastTerminatorEnd = k + 1
                }
            } else if (ch == ':' && k + 1 < closeQuoteIndex && text[k + 1].isWhitespace()) {
                lastTerminatorEnd = k + 1
            }
            k++
        }

        if (lastTerminatorEnd != -1) {
            val candidateStart = findNextNonWhitespace(text, lastTerminatorEnd)
            if (candidateStart < closeQuoteIndex) {
                return candidateStart
            }
        }

        return startBound
    }

    private fun findLeadInDialogueStart(text: String, startBound: Int, closeQuoteIndex: Int): Int {
        var firstPunctIndex = -1
        var k = startBound
        while (k < closeQuoteIndex) {
            val ch = text[k]
            if (ch == ',' || ch == ':') {
                firstPunctIndex = k
                break
            }
            k++
        }

        if (firstPunctIndex != -1) {
            val leadInText = text.substring(startBound, firstPunctIndex).trim()
            if (leadInText.isNotEmpty()) {
                val words = leadInText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.size in 1..6) {
                    val candidateStart = findNextNonWhitespace(text, firstPunctIndex + 1)
                    if (candidateStart < closeQuoteIndex) {
                        return candidateStart
                    }
                }
            }
        }

        return -1
    }

    private fun isSinglePeriod(text: String, index: Int): Boolean {
        val prev = if (index > 0) text[index - 1] else null
        val next = if (index + 1 < text.length) text[index + 1] else null
        return prev != '.' && next != '.'
    }

    private fun extractDialogue(
        text: String,
        startIndex: Int,
        openChar: Char,
        options: SegmenterOptions
    ): DialogueSpan {
        var j = startIndex + 1
        val length = text.length

        while (j < length) {
            val ch = text[j]

            if (matchesClosingQuote(openChar, ch)) {
                if ((ch == '\'' || ch == '’') && isApostrophe(text, j)) {
                    j++
                    continue
                }
                return DialogueSpan(startIndex, j + 1)
            }

            if (ch == '\n' || ch == '\r') {
                val lineBreakEnd = skipLineBreak(text, j)
                val nextNonWs = findNextNonWhitespace(text, lineBreakEnd)

                // 1. Multi-paragraph quote check
                if (nextNonWs < length && isOpeningQuote(text, nextNonWs, options)) {
                    return DialogueSpan(startIndex, j)
                }

                // 2. Double line-break check
                val hasBlankLine = hasDoubleLineBreak(text, j)
                if (hasBlankLine) {
                    val hasClosingQuoteLater = hasMatchingQuoteAhead(text, lineBreakEnd, openChar)
                    if (!hasClosingQuoteLater) {
                        return DialogueSpan(startIndex, j)
                    }
                }

                // 3. Unclosed quote check
                val hasClosingQuoteAhead = hasMatchingQuoteAhead(text, lineBreakEnd, openChar)
                if (!hasClosingQuoteAhead) {
                    return DialogueSpan(startIndex, j)
                }
            }

            j++
        }

        return DialogueSpan(startIndex, length)
    }

    private fun matchesClosingQuote(openChar: Char, closeCandidate: Char): Boolean {
        return when (openChar) {
            '"' -> closeCandidate == '"'
            '“' -> closeCandidate == '”' || closeCandidate == '"'
            '«' -> closeCandidate == '»'
            '\'' -> closeCandidate == '\''
            '‘' -> closeCandidate == '’' || closeCandidate == '\''
            else -> false
        }
    }

    private fun hasMatchingQuoteAhead(text: String, fromIndex: Int, openChar: Char): Boolean {
        var k = fromIndex
        val len = text.length
        while (k < len) {
            val c = text[k]
            if (matchesClosingQuote(openChar, c)) {
                if ((c == '\'' || c == '’') && isApostrophe(text, k)) {
                    k++
                    continue
                }
                return true
            }
            k++
        }
        return false
    }

    private fun skipLineBreak(text: String, index: Int): Int {
        var k = index
        if (k < text.length && text[k] == '\r') k++
        if (k < text.length && text[k] == '\n') k++
        return k
    }

    private fun hasDoubleLineBreak(text: String, index: Int): Boolean {
        var k = index
        var newlineCount = 0
        while (k < text.length && (text[k] == '\r' || text[k] == '\n' || text[k].isWhitespace())) {
            if (text[k] == '\n') newlineCount++
            if (newlineCount >= 2) return true
            k++
        }
        return false
    }

    private fun findNextNonWhitespace(text: String, startIndex: Int): Int {
        var k = startIndex
        while (k < text.length && text[k].isWhitespace()) {
            k++
        }
        return k
    }
}
