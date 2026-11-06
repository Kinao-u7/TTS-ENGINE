package com.novelreader.tts.core.loader

import com.novelreader.tts.core.catalog.VoiceCatalog
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.model.VoiceInfo
import com.novelreader.tts.core.store.InMemoryCastStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeVoiceCatalog(
    private val voices: List<VoiceInfo> = emptyList()
) : VoiceCatalog {
    override fun getAvailableVoices(): List<VoiceInfo> = voices
}

class ChapterLoaderTest {

    private lateinit var store: InMemoryCastStore
    private lateinit var catalog: FakeVoiceCatalog
    private val bookId = "test_book_1"
    private val chapterId = "ch_1"

    private val testVoices = listOf(
        VoiceInfo(id = "voice_us_1", name = "US Voice 1", locale = "en-US", gender = VoiceGender.ANY),
        VoiceInfo(id = "voice_us_2", name = "US Voice 2", locale = "en-US", gender = VoiceGender.MALE),
        VoiceInfo(id = "voice_us_3", name = "US Voice 3", locale = "en-US", gender = VoiceGender.FEMALE),
        VoiceInfo(id = "voice_gb_1", name = "GB Voice 1", locale = "en-GB", gender = VoiceGender.ANY)
    )

    @Before
    fun setUp() {
        store = InMemoryCastStore()
        catalog = FakeVoiceCatalog(testVoices)
    }

    @Test
    fun `segments and attributes chapter dialogue and creates new characters with assigned voices`() = runTest {
        val chapterText = "\"Where are we headed?\" asked Xian.\n\"To the border,\" Lin Mo said."
        val loader = ChapterLoader(store, catalog)

        val result = loader.load(bookId, chapterId, chapterText)
        val dialogueSegments = result.segments.filter { it.type == SegmentType.DIALOGUE }

        assertEquals(2, dialogueSegments.size)

        // Check characters
        val xianChar = result.characters.firstOrNull { it.name == "Xian" }
        assertNotNull("Xian should be created", xianChar)
        assertNotNull("Xian should have a voice assigned", xianChar?.voiceId)

        val linMoChar = result.characters.firstOrNull { it.name == "Lin Mo" }
        assertNotNull("Lin Mo should be created", linMoChar)
        assertNotNull("Lin Mo should have a voice assigned", linMoChar?.voiceId)

        // Stored characters should match
        val storedChars = store.characters(bookId).first()
        assertTrue(storedChars.any { it.name == "Xian" })
        assertTrue(storedChars.any { it.name == "Lin Mo" })
    }

    @Test
    fun `assigns voices once to narrator and unnamed on first load if missing`() = runTest {
        val loader = ChapterLoader(store, catalog)
        val result = loader.load(bookId, chapterId, "A dark and stormy night.")

        val narrator = result.characters.first { it.id == Character.NARRATOR_ID }
        val unnamed = result.characters.first { it.id == Character.UNNAMED_ID }

        assertNotNull("Narrator voice should be assigned", narrator.voiceId)
        assertNotNull("Unnamed voice should be assigned", unnamed.voiceId)
        assertTrue("Narrator and unnamed should get distinct voices", narrator.voiceId != unnamed.voiceId)

        // Verify persisted to store
        val stored = store.characters(bookId).first()
        val storedNarrator = stored.first { it.id == Character.NARRATOR_ID }
        val storedUnnamed = stored.first { it.id == Character.UNNAMED_ID }
        assertEquals(narrator.voiceId, storedNarrator.voiceId)
        assertEquals(unnamed.voiceId, storedUnnamed.voiceId)
    }

    @Test
    fun `applies stored overrides to segments`() = runTest {
        val text = "Lin said, \"Run.\""
        // First dry run to find segment id
        val initialLoader = ChapterLoader(store, catalog)
        val firstResult = initialLoader.load(bookId, chapterId, text)
        val dialogueId = firstResult.segments.first { it.type == SegmentType.DIALOGUE }.id

        // Set override in store
        store.setOverride(bookId, chapterId, dialogueId, "char_custom_hero")

        // Reload
        val reloadedResult = initialLoader.load(bookId, chapterId, text)
        val reloadedDialogue = reloadedResult.segments.first { it.type == SegmentType.DIALOGUE }

        assertEquals("char_custom_hero", reloadedDialogue.speakerOverride)
        assertEquals("char_custom_hero", reloadedDialogue.effectiveSpeakerId)
    }

    @Test
    fun `handles blank text gracefully`() = runTest {
        val loader = ChapterLoader(store, catalog)
        val result = loader.load(bookId, chapterId, "   \n\t  ")

        assertTrue(result.segments.isEmpty())
        assertTrue(result.characters.isNotEmpty())
    }

    @Test
    fun `respects autoDetectSpeakers flag`() = runTest {
        val text = "\"Where are we headed?\" asked Xian."
        val loader = ChapterLoader(store, catalog)
        val result = loader.load(bookId, chapterId, text, autoDetectSpeakers = false)

        val dialogue = result.segments.first { it.type == SegmentType.DIALOGUE }
        assertNull("SpeakerId should be null when autoDetectSpeakers is false", dialogue.speakerId)
        assertEquals(Character.UNNAMED_ID, dialogue.effectiveSpeakerId)
    }

    @Test
    fun `bound constructor works as expected`() = runTest {
        val text = "Hello world."
        val loader = ChapterLoader(store, catalog, bookId, chapterId, text)
        val result = loader.load()

        assertEquals(1, result.segments.size)
        assertEquals(SegmentType.NARRATION, result.segments[0].type)
    }
}
