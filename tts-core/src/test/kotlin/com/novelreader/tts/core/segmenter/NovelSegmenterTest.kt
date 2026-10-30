package com.novelreader.tts.core.segmenter

import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.CharacterResolver
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSegmenterTest {

    @Test
    fun `quote on its own line`() {
        val text = "He paused.\n\"Let's move out.\"\nThey began walking."
        val segments = NovelSegmenter.segment(text)

        assertEquals(3, segments.size)

        // 1. Narration
        assertEquals(SegmentType.NARRATION, segments[0].type)
        assertEquals("He paused.\n", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(11, segments[0].endOffset)
        assertEquals(Character.NARRATOR_ID, segments[0].effectiveSpeakerId)
        assertTrue(segments[0].id.startsWith("n0-"))

        // 2. Dialogue
        assertEquals(SegmentType.DIALOGUE, segments[1].type)
        assertEquals("\"Let's move out.\"", segments[1].text)
        assertEquals(11, segments[1].startOffset)
        assertEquals(28, segments[1].endOffset)
        // Default speaker for unassigned dialogue is "unnamed"
        assertEquals(Character.UNNAMED_ID, segments[1].effectiveSpeakerId)
        assertTrue(segments[1].id.startsWith("0-"))

        // 3. Narration
        assertEquals(SegmentType.NARRATION, segments[2].type)
        assertEquals("\nThey began walking.", segments[2].text)
        assertEquals(28, segments[2].startOffset)
        assertEquals(text.length, segments[2].endOffset)
        assertEquals(Character.NARRATOR_ID, segments[2].effectiveSpeakerId)
        assertTrue(segments[2].id.startsWith("n1-"))

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `possessives and plural possessives and contractions in plain narration stay NARRATION`() {
        val text = "the dogs' bones, Xian's sword, 'tis"
        val segments = NovelSegmenter.segment(text)

        assertEquals(1, segments.size)
        assertEquals(SegmentType.NARRATION, segments[0].type)
        assertEquals(text, segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(text.length, segments[0].endOffset)
        assertEquals(Character.NARRATOR_ID, segments[0].effectiveSpeakerId)
    }

    @Test
    fun `singleQuoteDialogue false by default keeps archaic contractions as narration`() {
        val options = SegmenterOptions()
        assertFalse(options.singleQuoteDialogue)
        assertFalse(options.enableSingleQuotes)

        val text = "The dogs' bones were found near Xian's sword. 'tis true."
        val segments = NovelSegmenter.segment(text, options)

        assertEquals(1, segments.size)
        assertEquals(SegmentType.NARRATION, segments[0].type)
        assertEquals(text, segments[0].text)
    }

    @Test
    fun `segment IDs follow dialogueIndex-hash and remain stable across small edits`() {
        val originalText = "\"Hello there!\" said Xian. \"Let's go.\""
        val segments1 = NovelSegmenter.segment(originalText)
        val secondDialogueId1 = segments1.first { it.text.contains("Let's go") }.id

        // Edit earlier narration by inserting words
        val modifiedEarlierNarration = "\"Hello there!\" said Master Xian quickly and loudly. \"Let's go.\""
        val segments2 = NovelSegmenter.segment(modifiedEarlierNarration)
        val secondDialogueId2 = segments2.first { it.text.contains("Let's go") }.id

        // Format is "<dialogueIndexInChapter>-<short hash of segment text>"
        assertTrue(secondDialogueId1.startsWith("1-"))
        assertEquals(secondDialogueId1, secondDialogueId2)
    }

    @Test
    fun `built in characters narrator and unnamed cannot be deleted or used as merge source`() {
        assertTrue(Character.Narrator.isBuiltIn)
        assertFalse(Character.Narrator.isDeletable)
        assertFalse(Character.Narrator.canBeMergeSource)

        assertTrue(Character.Unnamed.isBuiltIn)
        assertFalse(Character.Unnamed.isDeletable)
        assertFalse(Character.Unnamed.canBeMergeSource)

        val customChar = Character(id = "char_lin", name = "Lin")
        assertFalse(customChar.isBuiltIn)
        assertTrue(customChar.isDeletable)
        assertTrue(customChar.canBeMergeSource)
    }

    @Test
    fun `effective speaker resolution rule`() {
        // 1. Dialogue defaults to "unnamed"
        val dialogue = Segment(id = "0-abc", type = SegmentType.DIALOGUE, text = "\"Hi\"", startOffset = 0, endOffset = 4)
        assertEquals(Character.UNNAMED_ID, dialogue.effectiveSpeakerId)

        // 2. Narration defaults to "narrator"
        val narration = Segment(id = "n0-abc", type = SegmentType.NARRATION, text = "He walked.", startOffset = 4, endOffset = 14)
        assertEquals(Character.NARRATOR_ID, narration.effectiveSpeakerId)

        // 3. speakerId takes precedence over defaults
        val attributedDialogue = dialogue.copy(speakerId = "char_lin")
        assertEquals("char_lin", attributedDialogue.effectiveSpeakerId)

        // 4. speakerOverride takes precedence over speakerId
        val overriddenDialogue = attributedDialogue.copy(speakerOverride = "char_xian")
        assertEquals("char_xian", overriddenDialogue.effectiveSpeakerId)
    }

    @Test
    fun `character readByNarrator flag`() {
        val narratorLineChar = Character(
            id = "char_whisper",
            name = "Inner Voice",
            readByNarrator = true
        )
        assertTrue(narratorLineChar.readByNarrator)
    }

    @Test
    fun `orphan closing straight quote produces single dialogue segment`() {
        val text = "this... for real? We absolutely have to pick Desert Eagle in Infinite Firepower mode!\""
        val segments = NovelSegmenter.segment(text)

        assertEquals(1, segments.size)
        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals(text, segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(text.length, segments[0].endOffset)
        assertEquals(Character.UNNAMED_ID, segments[0].effectiveSpeakerId)
        assertTrue(segments[0].id.startsWith("0-"))

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `orphan closing quote followed by narration paragraph with normal quote pair`() {
        val text = "this... for real? We absolutely have to pick Desert Eagle in Infinite Firepower mode!\"\n\nThe captain frowned. \"Let's move out.\""
        val segments = NovelSegmenter.segment(text)

        assertEquals(3, segments.size)

        // 1. Orphan quote line -> DIALOGUE
        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("this... for real? We absolutely have to pick Desert Eagle in Infinite Firepower mode!\"", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(86, segments[0].endOffset)
        assertEquals(Character.UNNAMED_ID, segments[0].effectiveSpeakerId)
        assertTrue(segments[0].id.startsWith("0-"))

        // 2. Narration paragraph -> NARRATION
        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals("\n\nThe captain frowned. ", segments[1].text)
        assertEquals(86, segments[1].startOffset)
        assertEquals(109, segments[1].endOffset)
        assertEquals(Character.NARRATOR_ID, segments[1].effectiveSpeakerId)
        assertTrue(segments[1].id.startsWith("n0-"))

        // 3. Normal quote pair -> DIALOGUE
        assertEquals(SegmentType.DIALOGUE, segments[2].type)
        assertEquals("\"Let's move out.\"", segments[2].text)
        assertEquals(109, segments[2].startOffset)
        assertEquals(text.length, segments[2].endOffset)
        assertEquals(Character.UNNAMED_ID, segments[2].effectiveSpeakerId)
        assertTrue(segments[2].id.startsWith("1-"))

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `orphan closing straight quote with narration tag`() {
        val text = "Stop!\" he said."
        val segments = NovelSegmenter.segment(text)

        assertEquals(2, segments.size)

        // 1. DIALOGUE Stop!"
        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("Stop!\"", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(6, segments[0].endOffset)
        assertEquals(Character.UNNAMED_ID, segments[0].effectiveSpeakerId)
        assertTrue(segments[0].id.startsWith("0-"))

        // 2. NARRATION  he said.
        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals(" he said.", segments[1].text)
        assertEquals(6, segments[1].startOffset)
        assertEquals(15, segments[1].endOffset)
        assertEquals(Character.NARRATOR_ID, segments[1].effectiveSpeakerId)
        assertTrue(segments[1].id.startsWith("n0-"))

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `normal pair still works and possessive is unaffected`() {
        val normalText = "\"Stop!\" he said."
        val normalSegments = NovelSegmenter.segment(normalText)

        assertEquals(2, normalSegments.size)
        assertEquals(SegmentType.DIALOGUE, normalSegments[0].type)
        assertEquals("\"Stop!\"", normalSegments[0].text)
        assertEquals(0, normalSegments[0].startOffset)
        assertEquals(7, normalSegments[0].endOffset)

        assertEquals(SegmentType.NARRATION, normalSegments[1].type)
        assertEquals(" he said.", normalSegments[1].text)
        assertEquals(7, normalSegments[1].startOffset)
        assertEquals(16, normalSegments[1].endOffset)

        assertSegmentsPartitionOriginalText(normalText, normalSegments)

        val possessiveText = "the dogs' bones were scattered across the field."
        val possessiveSegments = NovelSegmenter.segment(possessiveText)

        assertEquals(1, possessiveSegments.size)
        assertEquals(SegmentType.NARRATION, possessiveSegments[0].type)
        assertEquals(possessiveText, possessiveSegments[0].text)
        assertEquals(0, possessiveSegments[0].startOffset)
        assertEquals(possessiveText.length, possessiveSegments[0].endOffset)
        assertEquals(Character.NARRATOR_ID, possessiveSegments[0].effectiveSpeakerId)

        assertSegmentsPartitionOriginalText(possessiveText, possessiveSegments)
    }

    @Test
    fun `curly closing quote orphan close`() {
        val text = "Stop!” he said."
        val segments = NovelSegmenter.segment(text)

        assertEquals(2, segments.size)

        // 1. DIALOGUE Stop!”
        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("Stop!”", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(6, segments[0].endOffset)
        assertEquals(Character.UNNAMED_ID, segments[0].effectiveSpeakerId)
        assertTrue(segments[0].id.startsWith("0-"))

        // 2. NARRATION  he said.
        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals(" he said.", segments[1].text)
        assertEquals(6, segments[1].startOffset)
        assertEquals(15, segments[1].endOffset)
        assertEquals(Character.NARRATOR_ID, segments[1].effectiveSpeakerId)
        assertTrue(segments[1].id.startsWith("n0-"))

        assertSegmentsPartitionOriginalText(text, segments)
    }

    private fun assertSegmentsPartitionOriginalText(originalText: String, segments: List<Segment>) {
        val reconstructed = segments.joinToString(separator = "") { it.text }
        assertEquals("Segments must reconstruct the original text exactly", originalText, reconstructed)

        var expectedOffset = 0
        for (seg in segments) {
            assertEquals("Start offset mismatch for segment ${seg.id}", expectedOffset, seg.startOffset)
            assertEquals("Segment text must equal substring of original", originalText.substring(seg.startOffset, seg.endOffset), seg.text)
            expectedOffset = seg.endOffset
        }
        assertEquals("End offset of last segment must equal text length", originalText.length, expectedOffset)
    }
}
