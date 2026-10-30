package com.novelreader.tts.android.store

import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.store.CastStore
import com.novelreader.tts.core.store.InMemoryCastStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contract test suite verifying compliance for any [CastStore] implementation.
 */
abstract class CastStoreContractTest {

    protected lateinit var store: CastStore
    protected val bookId = "contract_test_book"
    protected val chapterId = "chapter_contract_1"

    abstract fun createStore(): CastStore

    @Before
    fun initStore() {
        store = createStore()
    }

    @Test
    fun `save and load character`() = runTest {
        val initialChars = store.characters(bookId).first()
        assertTrue("Narrator must exist initially", initialChars.any { it.id == Character.NARRATOR_ID })
        assertTrue("Unnamed must exist initially", initialChars.any { it.id == Character.UNNAMED_ID })

        val custom = Character(
            id = "char_lin",
            name = "Lin",
            voiceId = "en-gb-voice-1",
            voiceGender = VoiceGender.FEMALE,
            speed = 1.1f,
            pitch = 0.95f,
            readByNarrator = false
        )
        store.saveCharacter(bookId, custom)

        val updatedChars = store.characters(bookId).first()
        val loaded = updatedChars.firstOrNull { it.id == "char_lin" }
        assertNotNull(loaded)
        assertEquals("Lin", loaded?.name)
        assertEquals("en-gb-voice-1", loaded?.voiceId)
        assertEquals(VoiceGender.FEMALE, loaded?.voiceGender)
        assertEquals(1.1f, loaded?.speed)
        assertEquals(0.95f, loaded?.pitch)
        assertFalse(loaded?.readByNarrator ?: true)

        // Modify character
        val updated = custom.copy(name = "General Lin", speed = 1.3f)
        store.saveCharacter(bookId, updated)

        val reloaded = store.characters(bookId).first().first { it.id == "char_lin" }
        assertEquals("General Lin", reloaded.name)
        assertEquals(1.3f, reloaded.speed)
    }

    @Test
    fun `deleting a character leaves its lines on the default speaker`() = runTest {
        val custom = Character(id = "char_xian", name = "Xian", voiceGender = VoiceGender.MALE)
        store.saveCharacter(bookId, custom)

        store.setOverride(bookId, chapterId, "seg_d1", "char_xian")
        store.setOverride(bookId, chapterId, "seg_n1", "char_xian")

        val dialogueSegment = Segment(
            id = "seg_d1",
            type = SegmentType.DIALOGUE,
            text = "\"Advance!\"",
            startOffset = 0,
            endOffset = 10,
            speakerOverride = "char_xian"
        )
        val narrationSegment = Segment(
            id = "seg_n1",
            type = SegmentType.NARRATION,
            text = " he ordered.",
            startOffset = 10,
            endOffset = 22,
            speakerOverride = "char_xian"
        )

        assertEquals("char_xian", dialogueSegment.effectiveSpeakerId)
        assertEquals("char_xian", narrationSegment.effectiveSpeakerId)

        store.deleteCharacter(bookId, "char_xian")

        val currentChars = store.characters(bookId).first()
        assertFalse(currentChars.any { it.id == "char_xian" })

        // Overrides for deleted character must be cleared
        val currentOverrides = store.overrides(bookId, chapterId)
        assertNull(currentOverrides["seg_d1"])
        assertNull(currentOverrides["seg_n1"])

        // Applying cleared overrides causes lines to revert to default speaker
        val revertedDialogue = dialogueSegment.copy(speakerOverride = currentOverrides["seg_d1"])
        val revertedNarration = narrationSegment.copy(speakerOverride = currentOverrides["seg_n1"])

        assertEquals(Character.UNNAMED_ID, revertedDialogue.effectiveSpeakerId)
        assertEquals(Character.NARRATOR_ID, revertedNarration.effectiveSpeakerId)
    }

    @Test
    fun `built-ins cannot be deleted`() = runTest {
        store.deleteCharacter(bookId, Character.NARRATOR_ID)
        store.deleteCharacter(bookId, Character.UNNAMED_ID)

        val chars = store.characters(bookId).first()
        assertTrue("Narrator cannot be deleted", chars.any { it.id == Character.NARRATOR_ID })
        assertTrue("Unnamed cannot be deleted", chars.any { it.id == Character.UNNAMED_ID })
    }

    @Test
    fun `override set and clear`() = runTest {
        store.setOverride(bookId, chapterId, "seg_100", "char_lin")
        val overrides1 = store.overrides(bookId, chapterId)
        assertEquals("char_lin", overrides1["seg_100"])

        // Clear override
        store.setOverride(bookId, chapterId, "seg_100", null)
        val overrides2 = store.overrides(bookId, chapterId)
        assertNull(overrides2["seg_100"])
        assertFalse(overrides2.containsKey("seg_100"))
    }

    @Test
    fun `override beats speakerId`() {
        val segmentWithOverride = Segment(
            id = "seg_test",
            type = SegmentType.DIALOGUE,
            text = "\"Sample\"",
            startOffset = 0,
            endOffset = 8,
            speakerId = "char_auto_detected",
            speakerOverride = "char_user_chosen"
        )
        assertEquals("char_user_chosen", segmentWithOverride.effectiveSpeakerId)

        val segmentWithoutOverride = segmentWithOverride.copy(speakerOverride = null)
        assertEquals("char_auto_detected", segmentWithoutOverride.effectiveSpeakerId)
    }

    @Test
    fun `overrides for unknown segment IDs are ignored`() = runTest {
        store.setOverride(bookId, chapterId, "seg_unknown_orphan", "char_target")
        store.setOverride(bookId, chapterId, "seg_known", "char_target")

        val chapterSegments = listOf(
            Segment(id = "seg_known", type = SegmentType.DIALOGUE, text = "\"Known\"", startOffset = 0, endOffset = 7),
            Segment(id = "seg_other", type = SegmentType.DIALOGUE, text = "\"Other\"", startOffset = 7, endOffset = 14)
        )

        val overridesMap = store.overrides(bookId, chapterId)
        val resultSegments = chapterSegments.map { seg ->
            val override = overridesMap[seg.id]
            if (override != null) seg.copy(speakerOverride = override) else seg
        }

        assertEquals("char_target", resultSegments[0].speakerOverride)
        assertNull(resultSegments[1].speakerOverride)
        assertEquals(2, resultSegments.size)
    }
}

class InMemoryCastStoreContractTest : CastStoreContractTest() {
    override fun createStore(): CastStore = InMemoryCastStore()
}

class JsonFileCastStoreContractTest : CastStoreContractTest() {
    @get:Rule
    val tempFolder = TemporaryFolder()

    override fun createStore(): CastStore {
        return JsonFileCastStore(tempFolder.newFolder())
    }
}

class JsonFileCastStoreSpecificTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `round trip through a temp directory`() = runTest {
        val dir = tempFolder.newFolder("cast_storage")
        val store1 = JsonFileCastStore(dir)
        val bookId = "novel_123"
        val chapterId = "ch_5"

        val character = Character(
            id = "char_hero",
            name = "Hero",
            voiceId = "en-us-hero",
            voiceGender = VoiceGender.MALE,
            readByNarrator = false,
            speed = 1.25f,
            pitch = 0.9f
        )
        store1.saveCharacter(bookId, character)
        store1.setOverride(bookId, chapterId, "seg_501", "char_hero")

        // Simulate app restart by creating a brand new instance pointing to the same directory
        val store2 = JsonFileCastStore(dir)

        val restoredChars = store2.characters(bookId).first()
        val loadedHero = restoredChars.firstOrNull { it.id == "char_hero" }
        assertNotNull("Character must survive restart", loadedHero)
        assertEquals("Hero", loadedHero?.name)
        assertEquals("en-us-hero", loadedHero?.voiceId)
        assertEquals(VoiceGender.MALE, loadedHero?.voiceGender)
        assertEquals(1.25f, loadedHero?.speed)
        assertEquals(0.9f, loadedHero?.pitch)

        val restoredOverrides = store2.overrides(bookId, chapterId)
        assertEquals("char_hero", restoredOverrides["seg_501"])
    }

    @Test
    fun `corrupt file loads as defaults instead of crashing`() = runTest {
        val dir = tempFolder.newFolder("corrupt_test")
        val corruptFile = File(dir, "corrupt_book.json")
        corruptFile.writeText("{ this is definitely not valid json ][#@!$%^&*")

        val store = JsonFileCastStore(dir)
        val chars = store.characters("corrupt_book").first()

        assertNotNull(chars)
        assertTrue(chars.any { it.id == Character.NARRATOR_ID })
        assertTrue(chars.any { it.id == Character.UNNAMED_ID })

        val overrides = store.overrides("corrupt_book", "ch_1")
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `empty file loads as defaults instead of crashing`() = runTest {
        val dir = tempFolder.newFolder("empty_test")
        val emptyFile = File(dir, "empty_book.json")
        emptyFile.writeText("   \n\t  ")

        val store = JsonFileCastStore(dir)
        val chars = store.characters("empty_book").first()

        assertNotNull(chars)
        assertTrue(chars.any { it.id == Character.NARRATOR_ID })
        assertTrue(chars.any { it.id == Character.UNNAMED_ID })

        val overrides = store.overrides("empty_book", "ch_1")
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `missing file loads as defaults`() = runTest {
        val dir = tempFolder.newFolder("missing_test")
        val store = JsonFileCastStore(dir)
        val chars = store.characters("non_existent_book").first()

        assertNotNull(chars)
        assertTrue(chars.any { it.id == Character.NARRATOR_ID })
        assertTrue(chars.any { it.id == Character.UNNAMED_ID })

        val overrides = store.overrides("non_existent_book", "ch_1")
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `auto-created character can be edited and voice change persists across a new store instance`() = runTest {
        val dir = tempFolder.newFolder("edit_persist_test")
        val store1 = JsonFileCastStore(dir)
        val bookId = "novel_edit"

        // Simulate auto-creation of a character (created through saveCharacter)
        val autoCreated = Character(
            id = "char_lin_mo",
            name = "Lin Mo",
            voiceId = "en-us-voice-1",
            speed = 1.0f,
            pitch = 1.0f
        )
        store1.saveCharacter(bookId, autoCreated)

        // Edit character: change voice and speed
        val edited = autoCreated.copy(voiceId = "en-gb-voice-2", speed = 1.3f)
        store1.saveCharacter(bookId, edited)

        // Create new store instance pointing to same storage
        val store2 = JsonFileCastStore(dir)
        val loadedChars = store2.characters(bookId).first()
        val loaded = loadedChars.firstOrNull { it.id == "char_lin_mo" }

        assertNotNull(loaded)
        assertEquals("en-gb-voice-2", loaded?.voiceId)
        assertEquals(1.3f, loaded?.speed)
    }

    @Test
    fun `auto-created character can be deleted - lines revert and name not recreated on reload`() = runTest {
        val dir = tempFolder.newFolder("delete_revert_test")
        val store = JsonFileCastStore(dir)
        val bookId = "novel_delete"
        val chapterText = "\"Run!\" said Lin Mo.\n\"Hurry!\" muttered Lin Mo."

        // 1. Initial load & segmentation
        val segments = com.novelreader.tts.core.segmenter.NovelSegmenter.segment(chapterText)
        val attributor = com.novelreader.tts.core.segmenter.TagAttributor()
        val attribution = attributor.attribute(segments)

        // Auto-create character Lin Mo in store
        val detectedLin = attribution.characters.first { it.name == "Lin Mo" }
        store.saveCharacter(bookId, detectedLin)

        // Verify initial attribution
        assertEquals(detectedLin.id, attribution.segments[0].speakerId)
        assertEquals(detectedLin.id, attribution.segments[0].effectiveSpeakerId)

        // 2. Delete character
        store.deleteCharacter(bookId, detectedLin.id)

        // Verify character is removed from store
        val currentChars = store.characters(bookId).first()
        assertFalse(currentChars.any { it.id == detectedLin.id })

        // Verify name is recorded in ignoredNames
        val ignored = store.ignoredNames(bookId)
        assertTrue(ignored.contains("lin mo"))

        // 3. Reload chapter text: name must NOT be recreated and lines revert to Unnamed
        val reloadedSegments = com.novelreader.tts.core.segmenter.NovelSegmenter.segment(chapterText)
        val reattribution = attributor.attribute(
            segments = reloadedSegments,
            existingCharacters = currentChars,
            ignoredNames = ignored
        )

        // Deleted name is NOT recreated
        assertFalse(reattribution.characters.any { it.name == "Lin Mo" })

        // Lines revert to Unnamed
        assertNull(reattribution.segments[0].speakerId)
        assertEquals(Character.UNNAMED_ID, reattribution.segments[0].effectiveSpeakerId)
        assertNull(reattribution.segments[2].speakerId)
        assertEquals(Character.UNNAMED_ID, reattribution.segments[2].effectiveSpeakerId)
    }

    @Test
    fun `auto-created character can be renamed - alias resolves to the same character with no duplicate`() = runTest {
        val dir = tempFolder.newFolder("rename_alias_test")
        val store = JsonFileCastStore(dir)
        val bookId = "novel_rename"
        val chapterText = "\"Run!\" said Lin Mo."

        // 1. Initial auto-detection
        val segments1 = com.novelreader.tts.core.segmenter.NovelSegmenter.segment(chapterText)
        val attributor = com.novelreader.tts.core.segmenter.TagAttributor()
        val attribution1 = attributor.attribute(segments1)
        val originalChar = attribution1.characters.first { it.name == "Lin Mo" }
        store.saveCharacter(bookId, originalChar)

        // 2. Rename character: new name "General Lin", old name "Lin Mo" kept in aliases
        val renamed = originalChar.copy(
            name = "General Lin",
            aliases = (originalChar.aliases + originalChar.name).distinctBy { it.lowercase() }
        )
        store.saveCharacter(bookId, renamed)

        // 3. Reload chapter text containing "said Lin Mo": alias must map to renamed character
        val charsInStore = store.characters(bookId).first()
        val segments2 = com.novelreader.tts.core.segmenter.NovelSegmenter.segment(chapterText)
        val attribution2 = attributor.attribute(segments2, charsInStore)

        // Must resolve to same character ID
        assertEquals(renamed.id, attribution2.segments[0].speakerId)
        assertEquals(renamed.id, attribution2.segments[0].effectiveSpeakerId)

        // Must NOT create a duplicate Lin Mo character
        val linMoMatches = attribution2.characters.filter { it.name.equals("Lin Mo", ignoreCase = true) }
        assertTrue("No duplicate character named Lin Mo should exist", linMoMatches.isEmpty())

        val generalLinMatches = attribution2.characters.filter { it.id == renamed.id }
        assertEquals(1, generalLinMatches.size)
        assertEquals("General Lin", generalLinMatches[0].name)
    }
}
