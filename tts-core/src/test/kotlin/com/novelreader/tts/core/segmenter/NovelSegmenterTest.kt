package com.novelreader.tts.core.segmenter

import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.CharacterResolver
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import org.junit.Assert.assertEquals
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
        assertTrue(segments[0].id.startsWith("seg_n0_"))

        // 2. Dialogue
        assertEquals(SegmentType.DIALOGUE, segments[1].type)
        assertEquals("\"Let's move out.\"", segments[1].text)
        assertEquals(11, segments[1].startOffset)
        assertEquals(28, segments[1].endOffset)
        assertEquals(Character.NARRATOR_ID, segments[1].effectiveSpeakerId)
        assertTrue(segments[1].id.startsWith("seg_d0_"))

        // 3. Narration
        assertEquals(SegmentType.NARRATION, segments[2].type)
        assertEquals("\nThey began walking.", segments[2].text)
        assertEquals(28, segments[2].startOffset)
        assertEquals(text.length, segments[2].endOffset)
        assertTrue(segments[2].id.startsWith("seg_n1_"))

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `he said to Xian followed by a quote on the next line`() {
        val text = "He said to Xian:\n\"We leave at dawn.\""
        val segments = NovelSegmenter.segment(text)

        assertEquals(2, segments.size)

        // Narration
        assertEquals(SegmentType.NARRATION, segments[0].type)
        assertEquals("He said to Xian:\n", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(17, segments[0].endOffset)

        // Dialogue
        assertEquals(SegmentType.DIALOGUE, segments[1].type)
        assertEquals("\"We leave at dawn.\"", segments[1].text)
        assertEquals(17, segments[1].startOffset)
        assertEquals(36, segments[1].endOffset)

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `multi-paragraph quotes that open without closing`() {
        val text = "\"The path ahead is dangerous.\n\"We must be prepared.\""
        val segments = NovelSegmenter.segment(text)

        assertEquals(3, segments.size)

        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("\"The path ahead is dangerous.", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(29, segments[0].endOffset)

        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals("\n", segments[1].text)
        assertEquals(29, segments[1].startOffset)
        assertEquals(30, segments[1].endOffset)

        assertEquals(SegmentType.DIALOGUE, segments[2].type)
        assertEquals("\"We must be prepared.\"", segments[2].text)
        assertEquals(30, segments[2].startOffset)
        assertEquals(52, segments[2].endOffset)

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `multi-paragraph quotes with empty line in between`() {
        val text = "\"First paragraph.\n\n\"Second paragraph.\""
        val segments = NovelSegmenter.segment(text)

        assertEquals(3, segments.size)
        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("\"First paragraph.", segments[0].text)

        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals("\n\n", segments[1].text)

        assertEquals(SegmentType.DIALOGUE, segments[2].type)
        assertEquals("\"Second paragraph.\"", segments[2].text)

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `apostrophes inside double quotes`() {
        val text = "\"Don't worry, it's Xian's plan,\" she said."
        val segments = NovelSegmenter.segment(text)

        assertEquals(2, segments.size)

        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("\"Don't worry, it's Xian's plan,\"", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(32, segments[0].endOffset)

        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals(" she said.", segments[1].text)
        assertEquals(32, segments[1].startOffset)
        assertEquals(text.length, segments[1].endOffset)

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `possessives and plural possessives in plain narration stay NARRATION with default options`() {
        // By default enableSingleQuotes is false, so possessives and contractions never produce dialogue
        val text = "The cat's tail twitched as the dogs' barking echoed down the boys' club near James' house."
        val segments = NovelSegmenter.segment(text)

        assertEquals(1, segments.size)
        assertEquals(SegmentType.NARRATION, segments[0].type)
        assertEquals(text, segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(text.length, segments[0].endOffset)
    }

    @Test
    fun `single quote dialogue is detected when enableSingleQuotes is explicitly enabled`() {
        val options = SegmenterOptions(enableSingleQuotes = true)
        val text = "'Don\'t worry, it\'s fine,' he whispered."
        val segments = NovelSegmenter.segment(text, options)

        assertEquals(2, segments.size)
        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("'Don\'t worry, it\'s fine,'", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(25, segments[0].endOffset)

        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals(" he whispered.", segments[1].text)
        assertEquals(25, segments[1].startOffset)
        assertEquals(text.length, segments[1].endOffset)

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `curly versus straight quotes and guillemets`() {
        // Straight double
        val straight = "\"Hello,\" he said."
        val segsStraight = NovelSegmenter.segment(straight)
        assertEquals(2, segsStraight.size)
        assertEquals("\"Hello,\"", segsStraight[0].text)
        assertEquals(SegmentType.DIALOGUE, segsStraight[0].type)

        // Curly double
        val curlyDouble = "“Hello,” he said."
        val segsCurly = NovelSegmenter.segment(curlyDouble)
        assertEquals(2, segsCurly.size)
        assertEquals("“Hello,”", segsCurly[0].text)
        assertEquals(SegmentType.DIALOGUE, segsCurly[0].type)

        // Guillemets
        val guillemet = "« Bonjour le monde, » dit-il."
        val segsGuillemet = NovelSegmenter.segment(guillemet)
        assertEquals(2, segsGuillemet.size)
        assertEquals("« Bonjour le monde, »", segsGuillemet[0].text)
        assertEquals(SegmentType.DIALOGUE, segsGuillemet[0].type)
        assertEquals(" dit-il.", segsGuillemet[1].text)
        assertEquals(SegmentType.NARRATION, segsGuillemet[1].type)
    }

    @Test
    fun `unclosed quote at the end of text`() {
        val text = "He shouted, \"Wait for me!"
        val segments = NovelSegmenter.segment(text)

        assertEquals(2, segments.size)

        assertEquals(SegmentType.NARRATION, segments[0].type)
        assertEquals("He shouted, ", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(12, segments[0].endOffset)

        assertEquals(SegmentType.DIALOGUE, segments[1].type)
        assertEquals("\"Wait for me!", segments[1].text)
        assertEquals(12, segments[1].startOffset)
        assertEquals(text.length, segments[1].endOffset)

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `unclosed quote followed by narration on next line`() {
        val text = "\"Wait for me!\nThe train pulled away."
        val segments = NovelSegmenter.segment(text)

        assertEquals(2, segments.size)

        assertEquals(SegmentType.DIALOGUE, segments[0].type)
        assertEquals("\"Wait for me!", segments[0].text)
        assertEquals(0, segments[0].startOffset)
        assertEquals(13, segments[0].endOffset)

        assertEquals(SegmentType.NARRATION, segments[1].type)
        assertEquals("\nThe train pulled away.", segments[1].text)
        assertEquals(13, segments[1].startOffset)
        assertEquals(text.length, segments[1].endOffset)

        assertSegmentsPartitionOriginalText(text, segments)
    }

    @Test
    fun `segment IDs are stable across small text edits`() {
        val originalText = "\"Hello there!\" said Xian. \"Let's go.\""
        val segments1 = NovelSegmenter.segment(originalText)
        val secondDialogueId1 = segments1.first { it.text.contains("Let's go") }.id

        // Edit earlier narration by adding words
        val modifiedEarlierNarration = "\"Hello there!\" said Master Xian quickly. \"Let's go.\""
        val segments2 = NovelSegmenter.segment(modifiedEarlierNarration)
        val secondDialogueId2 = segments2.first { it.text.contains("Let's go") }.id

        // The second dialogue ID must be identical because dialogueIndex (1) and content hash ("Let's go.") did not change
        assertEquals(secondDialogueId1, secondDialogueId2)
    }

    @Test
    fun `speaker resolution order override beats speaker_id beats narrator`() {
        val defaultSeg = Segment(
            id = "seg_1",
            type = SegmentType.DIALOGUE,
            text = "\"Hello\"",
            startOffset = 0,
            endOffset = 7
        )
        assertEquals(Character.NARRATOR_ID, defaultSeg.effectiveSpeakerId)

        val autoAttributedSeg = defaultSeg.copy(speakerId = "char_xian")
        assertEquals("char_xian", autoAttributedSeg.effectiveSpeakerId)

        val userOverriddenSeg = autoAttributedSeg.copy(speakerOverride = "char_elena")
        assertEquals("char_elena", userOverriddenSeg.effectiveSpeakerId)
    }

    @Test
    fun `character resolver matches name and aliases case-insensitively`() {
        val characters = listOf(
            Character.Narrator,
            Character(
                id = "char_xian",
                name = "Xian",
                aliases = listOf("The Wanderer", "Master Xian")
            ),
            Character(
                id = "char_elena",
                name = "Elena",
                aliases = listOf("Lady Elena")
            )
        )

        assertEquals("char_xian", CharacterResolver.resolveCharacterByName(characters, "xian")?.id)
        assertEquals("char_xian", CharacterResolver.resolveCharacterByName(characters, "XIAN")?.id)
        assertEquals("char_xian", CharacterResolver.resolveCharacterByName(characters, "the wanderer")?.id)
        assertEquals("char_xian", CharacterResolver.resolveCharacterByName(characters, "MASTER XIAN")?.id)
        assertEquals("char_elena", CharacterResolver.resolveCharacterByName(characters, "lady elena")?.id)
        assertNull(CharacterResolver.resolveCharacterByName(characters, "NonExistent"))
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
