package com.novelreader.tts.core.store

import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.VoiceGender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryCastStoreTest {

    private lateinit var store: CastStore
    private val bookId = "test_book"
    private val chapterId = "ch_1"

    @Before
    fun setUp() {
        store = InMemoryCastStore()
    }

    @Test
    fun `save and load a character`() = runTest {
        val initialChars = store.characters(bookId).first()
        assertTrue(initialChars.any { it.id == Character.NARRATOR_ID })
        assertTrue(initialChars.any { it.id == Character.UNNAMED_ID })

        val custom = Character(
            id = "char_lin",
            name = "Lin",
            voiceId = "en-gb-x-lin",
            voiceGender = VoiceGender.FEMALE,
            speed = 1.2f,
            pitch = 0.9f,
            readByNarrator = false
        )
        store.saveCharacter(bookId, custom)

        val updatedChars = store.characters(bookId).first()
        val loaded = updatedChars.firstOrNull { it.id == "char_lin" }
        assertEquals("Lin", loaded?.name)
        assertEquals("en-gb-x-lin", loaded?.voiceId)
        assertEquals(VoiceGender.FEMALE, loaded?.voiceGender)
        assertEquals(1.2f, loaded?.speed)
        assertEquals(0.9f, loaded?.pitch)
        assertFalse(loaded?.readByNarrator ?: true)

        // Update character
        val modified = custom.copy(name = "Lin Dan", speed = 1.5f)
        store.saveCharacter(bookId, modified)

        val afterUpdate = store.characters(bookId).first()
        val reloaded = afterUpdate.first { it.id == "char_lin" }
        assertEquals("Lin Dan", reloaded.name)
        assertEquals(1.5f, reloaded.speed)
    }

    @Test
    fun `deleting a character leaves its lines on the default speaker`() = runTest {
        val custom = Character(id = "char_lin", name = "Lin", voiceGender = VoiceGender.FEMALE)
        store.saveCharacter(bookId, custom)
        store.setOverride(bookId, chapterId, "seg_dialogue_1", "char_lin")
        store.setOverride(bookId, chapterId, "seg_narration_1", "char_lin")

        val dialogueSegment = Segment(
            id = "seg_dialogue_1",
            type = SegmentType.DIALOGUE,
            text = "\"Run!\"",
            startOffset = 0,
            endOffset = 6,
            speakerOverride = "char_lin"
        )
        val narrationSegment = Segment(
            id = "seg_narration_1",
            type = SegmentType.NARRATION,
            text = " he said.",
            startOffset = 6,
            endOffset = 15,
            speakerOverride = "char_lin"
        )

        assertEquals("char_lin", dialogueSegment.effectiveSpeakerId)
        assertEquals("char_lin", narrationSegment.effectiveSpeakerId)

        // Delete character
        store.deleteCharacter(bookId, "char_lin")

        val chars = store.characters(bookId).first()
        assertFalse(chars.any { it.id == "char_lin" })

        // Overrides pointing to the deleted character must be removed
        val activeOverrides = store.overrides(bookId, chapterId)
        assertNull(activeOverrides["seg_dialogue_1"])
        assertNull(activeOverrides["seg_narration_1"])

        // Apply updated overrides to the segments
        val updatedDialogue = dialogueSegment.copy(speakerOverride = activeOverrides["seg_dialogue_1"])
        val updatedNarration = narrationSegment.copy(speakerOverride = activeOverrides["seg_narration_1"])

        // Dialogue line reverts to "unnamed", narration line reverts to "narrator"
        assertEquals(Character.UNNAMED_ID, updatedDialogue.effectiveSpeakerId)
        assertEquals(Character.NARRATOR_ID, updatedNarration.effectiveSpeakerId)
    }

    @Test
    fun `built-ins cannot be deleted`() = runTest {
        store.deleteCharacter(bookId, Character.NARRATOR_ID)
        store.deleteCharacter(bookId, Character.UNNAMED_ID)

        val chars = store.characters(bookId).first()
        assertTrue("Narrator must still exist", chars.any { it.id == Character.NARRATOR_ID })
        assertTrue("Unnamed must still exist", chars.any { it.id == Character.UNNAMED_ID })
    }

    @Test
    fun `override set and clear`() = runTest {
        store.setOverride(bookId, chapterId, "seg_1", "char_xian")
        val overrides1 = store.overrides(bookId, chapterId)
        assertEquals("char_xian", overrides1["seg_1"])

        // Passing null clears the override
        store.setOverride(bookId, chapterId, "seg_1", null)
        val overrides2 = store.overrides(bookId, chapterId)
        assertNull(overrides2["seg_1"])
        assertFalse(overrides2.containsKey("seg_1"))
    }

    @Test
    fun `override beats speakerId`() {
        val segment = Segment(
            id = "seg_1",
            type = SegmentType.DIALOGUE,
            text = "\"Let's go.\"",
            startOffset = 0,
            endOffset = 10,
            speakerId = "char_lin",
            speakerOverride = "char_xian"
        )
        // speakerOverride must take precedence over speakerId
        assertEquals("char_xian", segment.effectiveSpeakerId)

        val segmentWithoutOverride = segment.copy(speakerOverride = null)
        assertEquals("char_lin", segmentWithoutOverride.effectiveSpeakerId)
    }

    @Test
    fun `overrides for unknown segment IDs are ignored`() = runTest {
        store.setOverride(bookId, chapterId, "seg_non_existent", "char_xian")
        store.setOverride(bookId, chapterId, "seg_1", "char_lin")

        val chapterSegments = listOf(
            Segment(id = "seg_1", type = SegmentType.DIALOGUE, text = "\"A\"", startOffset = 0, endOffset = 3),
            Segment(id = "seg_2", type = SegmentType.DIALOGUE, text = "\"B\"", startOffset = 3, endOffset = 6)
        )

        val loadedOverrides = store.overrides(bookId, chapterId)

        val appliedSegments = chapterSegments.map { seg ->
            val override = loadedOverrides[seg.id]
            if (override != null) seg.copy(speakerOverride = override) else seg
        }

        assertEquals("char_lin", appliedSegments[0].speakerOverride)
        assertNull(appliedSegments[1].speakerOverride)
        assertEquals(2, appliedSegments.size)
    }
}
