package com.novelreader.tts.core.segmenter

import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.SegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TagAttributorTest {

    private lateinit var attributor: TagAttributor

    @Before
    fun setUp() {
        attributor = TagAttributor()
    }

    @Test
    fun `post-dialogue said Lin assigns Lin as speaker`() {
        val text = "\"Run,\" said Lin."
        val segments = NovelSegmenter.segment(text)
        assertEquals(2, segments.size)

        val result = attributor.attribute(segments)
        val dialogue = result.segments[0]
        assertEquals(SegmentType.DIALOGUE, dialogue.type)
        assertNotNull(dialogue.speakerId)

        val linChar = result.characters.firstOrNull { it.id == dialogue.speakerId }
        assertNotNull(linChar)
        assertEquals("Lin", linChar?.name)
        assertEquals(dialogue.speakerId, dialogue.effectiveSpeakerId)
    }

    @Test
    fun `pre-dialogue Lin said assigns Lin as speaker`() {
        val text = "Lin said, \"Run.\""
        val segments = NovelSegmenter.segment(text)
        assertEquals(2, segments.size)

        val result = attributor.attribute(segments)
        val dialogue = result.segments[1]
        assertEquals(SegmentType.DIALOGUE, dialogue.type)
        assertNotNull(dialogue.speakerId)

        val linChar = result.characters.firstOrNull { it.id == dialogue.speakerId }
        assertNotNull(linChar)
        assertEquals("Lin", linChar?.name)
    }

    @Test
    fun `pronoun he said stays unnamed`() {
        val text = "\"Run,\" he said."
        val segments = NovelSegmenter.segment(text)
        assertEquals(2, segments.size)

        val result = attributor.attribute(segments)
        val dialogue = result.segments[0]
        assertEquals(SegmentType.DIALOGUE, dialogue.type)

        // Pronoun ignored -> speakerId remains null -> effective speaker is unnamed
        assertNull(dialogue.speakerId)
        assertEquals(Character.UNNAMED_ID, dialogue.effectiveSpeakerId)
    }

    @Test
    fun `He said to Xian followed by quote on next line stays unnamed and Xian is NOT speaker`() {
        val text = "He said to Xian:\n\"We leave at dawn.\""
        val segments = NovelSegmenter.segment(text)
        assertEquals(2, segments.size)

        val knownCharacters = listOf(
            Character(id = "char_xian", name = "Xian")
        )
        val result = attributor.attribute(segments, knownCharacters)
        val dialogue = result.segments[1]
        assertEquals(SegmentType.DIALOGUE, dialogue.type)

        // Must stay unnamed, and Xian must NOT become the speaker!
        assertNull(dialogue.speakerId)
        assertEquals(Character.UNNAMED_ID, dialogue.effectiveSpeakerId)
    }

    @Test
    fun `alias resolution maps aliases to existing character`() {
        val existingChar = Character(
            id = "char_xian",
            name = "Xian",
            aliases = listOf("Master Xian", "The Wanderer")
        )
        val text = "\"Prepare the carriage,\" muttered Master Xian."
        val segments = NovelSegmenter.segment(text)

        val result = attributor.attribute(segments, listOf(existingChar))
        val dialogue = result.segments[0]

        assertEquals("char_xian", dialogue.speakerId)
        assertEquals("char_xian", dialogue.effectiveSpeakerId)
    }

    @Test
    fun `override untouched when speakerOverride is set`() {
        val text = "\"Run,\" said Lin."
        val segments = NovelSegmenter.segment(text)
        // Set user override explicitly
        val overriddenSegment = segments[0].copy(speakerOverride = "char_user_override")
        val inputSegments = listOf(overriddenSegment, segments[1])

        val result = attributor.attribute(inputSegments)
        val dialogue = result.segments[0]

        // speakerOverride must remain untouched!
        assertEquals("char_user_override", dialogue.speakerOverride)
        assertEquals("char_user_override", dialogue.effectiveSpeakerId)
    }

    @Test
    fun `test paragraph attribution for asked Xian, Lin Mo said, Xian called back, whispered Chen Yu, untagged, pronoun, He said to Xian, reuse, and override`() {
        val testParagraph = """
            "Where are we headed?" asked Xian.
            "To the border," Lin Mo said.
            "Wait for me!" Xian called back.
            "Keep your voices down," whispered Chen Yu.
            "We must hurry."
            "Are you sure?" he said.
            He said to Xian:
            "Do not make another sound."
        """.trimIndent()

        val segments = NovelSegmenter.segment(testParagraph)
        val dialogueSegments = segments.filter { it.type == SegmentType.DIALOGUE }
        assertEquals(7, dialogueSegments.size)

        // Pre-seed an existing character for Xian to verify reuse without duplication
        val existingXian = Character(id = "char_xian_pre_existing", name = "Xian")
        val knownCharacters = listOf(existingXian)

        val result = attributor.attribute(segments, knownCharacters)
        val attributedDialogue = result.segments.filter { it.type == SegmentType.DIALOGUE }
        assertEquals(7, attributedDialogue.size)

        // 1. "Where are we headed?" asked Xian -> assigned to existing Xian
        assertEquals(existingXian.id, attributedDialogue[0].speakerId)
        assertEquals(existingXian.id, attributedDialogue[0].effectiveSpeakerId)

        // 2. "To the border," Lin Mo said -> assigned to newly created Lin Mo
        assertNotNull(attributedDialogue[1].speakerId)
        val linMoChar = result.characters.firstOrNull { it.id == attributedDialogue[1].speakerId }
        assertEquals("Lin Mo", linMoChar?.name)
        assertEquals(linMoChar?.id, attributedDialogue[1].effectiveSpeakerId)

        // 3. "Wait for me!" Xian called back -> assigned to existing Xian (reused!)
        assertEquals(existingXian.id, attributedDialogue[2].speakerId)
        assertEquals(existingXian.id, attributedDialogue[2].effectiveSpeakerId)

        // 4. "Keep your voices down," whispered Chen Yu -> assigned to newly created Chen Yu
        assertNotNull(attributedDialogue[3].speakerId)
        val chenYuChar = result.characters.firstOrNull { it.id == attributedDialogue[3].speakerId }
        assertEquals("Chen Yu", chenYuChar?.name)
        assertEquals(chenYuChar?.id, attributedDialogue[3].effectiveSpeakerId)

        // 5. "We must hurry." -> untagged -> stays Unnamed
        assertNull(attributedDialogue[4].speakerId)
        assertEquals(Character.UNNAMED_ID, attributedDialogue[4].effectiveSpeakerId)

        // 6. "Are you sure?" he said -> pronoun -> stays Unnamed
        assertNull(attributedDialogue[5].speakerId)
        assertEquals(Character.UNNAMED_ID, attributedDialogue[5].effectiveSpeakerId)

        // 7. He said to Xian: "Do not make another sound." -> stays Unnamed, Xian NOT assigned
        assertNull(attributedDialogue[6].speakerId)
        assertEquals(Character.UNNAMED_ID, attributedDialogue[6].effectiveSpeakerId)

        // Verify existing character with same name is reused, not duplicated
        val xianCharacters = result.characters.filter { it.name.equals("Xian", ignoreCase = true) }
        assertEquals("Xian must be reused and not duplicated", 1, xianCharacters.size)
        assertEquals(existingXian.id, xianCharacters[0].id)

        // Verify manual override still wins over speakerId
        val lineWithOverride = attributedDialogue[1].copy(speakerOverride = "char_manual_override")
        assertEquals("char_manual_override", lineWithOverride.effectiveSpeakerId)
    }
}

