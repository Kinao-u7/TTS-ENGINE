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
}
