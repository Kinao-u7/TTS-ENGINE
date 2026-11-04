package com.novelreader.tts.core.playback

import com.novelreader.tts.core.engine.FakeSpeechEngine
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.model.VoiceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeEngine: FakeSpeechEngine
    private lateinit var controller: PlaybackController

    private val narratorChar = Character(
        id = Character.NARRATOR_ID,
        name = "Narrator",
        voiceId = "voice_en_narrator"
    )

    private val charElena = Character(
        id = "char_elena",
        name = "Elena",
        voiceId = "voice_en_f1",
        voiceGender = VoiceGender.FEMALE,
        speed = 1.1f,
        pitch = 1.2f
    )

    private val charXian = Character(
        id = "char_xian",
        name = "Xian",
        voiceId = "voice_en_m1",
        voiceGender = VoiceGender.MALE,
        speed = 1.0f,
        pitch = 0.9f
    )

    @Before
    fun setUp() {
        fakeEngine = FakeSpeechEngine(scope = testScope)
        controller = PlaybackController(fakeEngine, scope = testScope)
        controller.updateAvailableVoices(fakeEngine.mockVoices)
        controller.updateCharacters(listOf(narratorChar, Character.Unnamed, charElena, charXian))
    }

    @Test
    fun `refill keeps 3 queued ahead without waiting for completion`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Sentence zero.", 0, 14),
            Segment("seg_1", SegmentType.DIALOGUE, "\"Sentence one.\"", 14, 29, speakerOverride = "char_elena"),
            Segment("seg_2", SegmentType.DIALOGUE, "\"Sentence two.\"", 29, 44, speakerOverride = "char_xian"),
            Segment("seg_3", SegmentType.NARRATION, "Sentence three.", 44, 59),
            Segment("seg_4", SegmentType.NARRATION, "Sentence four.", 59, 73),
            Segment("seg_5", SegmentType.NARRATION, "Sentence five.", 73, 87)
        )

        controller.loadSegments(segments)
        controller.play()

        // With lookaheadCount = 3: sentence 0 plus 3 ahead = 4 sentences enqueued immediately!
        assertEquals(4, fakeEngine.speakRecords.size)
        assertEquals("Sentence zero.", fakeEngine.speakRecords[0].sentence)
        assertEquals("\"Sentence one.\"", fakeEngine.speakRecords[1].sentence)
        assertEquals("\"Sentence two.\"", fakeEngine.speakRecords[2].sentence)
        assertEquals("Sentence three.", fakeEngine.speakRecords[3].sentence)

        // When sentence 0 emits Started -> refilling triggers sentence 4 to be enqueued!
        fakeEngine.emitStarted(fakeEngine.speakRecords[0].utteranceId)
        assertEquals(5, fakeEngine.speakRecords.size)
        assertEquals("Sentence four.", fakeEngine.speakRecords[4].sentence)

        // When sentence 0 emits Done -> do NOT refill on Done!
        fakeEngine.emitDone(fakeEngine.speakRecords[0].utteranceId)
        assertEquals(5, fakeEngine.speakRecords.size)

        // When sentence 1 emits Started -> refilling triggers sentence 5 to be enqueued!
        fakeEngine.emitStarted(fakeEngine.speakRecords[1].utteranceId)
        assertEquals(6, fakeEngine.speakRecords.size)
        assertEquals("Sentence five.", fakeEngine.speakRecords[5].sentence)
    }

    @Test
    fun `skip next and previous`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Line 0.", 0, 7),
            Segment("seg_1", SegmentType.NARRATION, "Line 1.", 7, 14),
            Segment("seg_2", SegmentType.NARRATION, "Line 2.", 14, 21)
        )
        controller.loadSegments(segments)
        controller.play()
        assertEquals(0, controller.readerState.value.currentSentenceIndex)

        // Next
        controller.next()
        assertEquals(1, controller.readerState.value.currentSentenceIndex)

        // Next
        controller.next()
        assertEquals(2, controller.readerState.value.currentSentenceIndex)

        // Previous
        controller.previous()
        assertEquals(1, controller.readerState.value.currentSentenceIndex)
    }

    @Test
    fun `pause and resume restarts the sentence`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "First line.", 0, 11),
            Segment("seg_1", SegmentType.NARRATION, "Second line.", 11, 23)
        )
        controller.loadSegments(segments)
        controller.play()

        // Jump to sentence 1
        controller.jumpToSentence(1)
        assertEquals(1, controller.readerState.value.currentSentenceIndex)
        assertTrue(controller.readerState.value.isPlaying)

        // Pause
        controller.pause()
        assertFalse(controller.readerState.value.isPlaying)
        assertEquals(1, controller.readerState.value.currentSentenceIndex)

        // Resume: must restart from sentence 1!
        controller.resume()
        assertTrue(controller.readerState.value.isPlaying)
        assertEquals(1, controller.readerState.value.currentSentenceIndex)
        val lastRecord = fakeEngine.speakRecords.last()
        assertEquals("Second line.", lastRecord.sentence)
    }

    @Test
    fun `reassigning the playing line flushes and restarts from that segment`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.DIALOGUE, "\"Line zero.\"", 0, 11, speakerOverride = "char_elena"),
            Segment("seg_1", SegmentType.NARRATION, "Line one.", 11, 20)
        )
        controller.loadSegments(segments)
        controller.play()

        val initialStopCount = fakeEngine.stopCount
        val initialRecordCount = fakeEngine.speakRecords.size

        // Currently playing sentence 0 of seg_0 -> reassigning seg_0 must flush and restart
        controller.reassignSpeaker("seg_0", "char_xian")

        assertTrue(fakeEngine.stopCount > initialStopCount)
        val restartedRecord = fakeEngine.speakRecords[initialRecordCount]
        assertEquals("\"Line zero.\"", restartedRecord.sentence)
        assertEquals("voice_en_m1", restartedRecord.voice?.id)
    }

    @Test
    fun `settings edit does not flush the queue`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Sentence zero.", 0, 14),
            Segment("seg_1", SegmentType.NARRATION, "Sentence one.", 14, 27)
        )
        controller.loadSegments(segments)
        controller.play()

        val stopCountBefore = fakeEngine.stopCount

        // Change global speed or character settings
        controller.setGlobalSpeed(1.5f)

        // Must NOT flush or stop the engine!
        assertEquals(stopCountBefore, fakeEngine.stopCount)
    }

    @Test
    fun `blank or whitespace-only segments are skipped`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "First content.", 0, 14),
            Segment("seg_1", SegmentType.NARRATION, "   \n\n\t  ", 14, 23),
            Segment("seg_2", SegmentType.NARRATION, "", 23, 23),
            Segment("seg_3", SegmentType.DIALOGUE, "\"Second content.\"", 23, 40)
        )
        controller.loadSegments(segments)

        // Only 2 non-blank sentences exist
        assertEquals(2, controller.readerState.value.sentenceCount)

        controller.play()
        assertEquals("First content.", fakeEngine.speakRecords[0].sentence)
        assertEquals("\"Second content.\"", fakeEngine.speakRecords[1].sentence)
    }

    @Test
    fun `play before ready is queued and runs after ready`() = runTest {
        val engineNotReady = FakeSpeechEngine(initialReady = false, scope = testScope)
        val testController = PlaybackController(engineNotReady, scope = testScope)

        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Hello world.", 0, 12)
        )
        testController.loadSegments(segments)

        // Play called while engine is not ready
        testController.play()
        assertEquals(0, engineNotReady.speakRecords.size)

        // Engine becomes ready -> queued play automatically executes!
        engineNotReady.setReady(true)
        assertEquals(1, engineNotReady.speakRecords.size)
        assertEquals("Hello world.", engineNotReady.speakRecords[0].sentence)
        assertTrue(testController.readerState.value.isPlaying)
    }

    @Test
    fun `playback generation counter bumps on stop pause jump loadSegments and reassign-restart`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Line 0.", 0, 7),
            Segment("seg_1", SegmentType.NARRATION, "Line 1.", 7, 14),
            Segment("seg_2", SegmentType.DIALOGUE, "\"Line 2.\"", 14, 23)
        )
        controller.loadSegments(segments)
        val genAfterLoad = controller.currentGeneration
        assertTrue(genAfterLoad > 0)

        controller.play()
        assertEquals(genAfterLoad, controller.currentGeneration)

        // Pause bumps generation
        controller.pause()
        val genAfterPause = controller.currentGeneration
        assertTrue(genAfterPause > genAfterLoad)

        // Jump bumps generation
        controller.jumpToSentence(1)
        val genAfterJump = controller.currentGeneration
        assertTrue(genAfterJump > genAfterPause)

        // Stop bumps generation
        controller.stop()
        val genAfterStop = controller.currentGeneration
        assertTrue(genAfterStop > genAfterJump)

        // Reassign currently playing line bumps generation
        controller.play()
        val genBeforeReassign = controller.currentGeneration
        controller.reassignSpeaker("seg_1", "char_elena")
        val genAfterReassign = controller.currentGeneration
        assertTrue(genAfterReassign > genBeforeReassign)
    }

    @Test
    fun `ignores Started Range Done Error events for utterances from older generations`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Line 0.", 0, 7),
            Segment("seg_1", SegmentType.NARRATION, "Line 1.", 7, 14)
        )
        controller.loadSegments(segments)
        controller.play()

        val staleUtteranceId = fakeEngine.speakRecords[0].utteranceId

        // Pause bumps generation and invalidates old utterance IDs
        controller.pause()
        assertFalse(controller.readerState.value.isPlaying)

        // Stale Started event must be ignored
        fakeEngine.emitStarted(staleUtteranceId)
        assertFalse("Stale Started must not resume playing", controller.readerState.value.isPlaying)

        // Stale Range event must be ignored
        fakeEngine.emitRange(0, 3, staleUtteranceId)
        assertNull("Stale Range must not update word highlight", controller.readerState.value.wordHighlight)

        // Stale Done event must be ignored
        fakeEngine.emitDone(staleUtteranceId)
        assertFalse(controller.readerState.value.isPlaying)

        // Stale Error event must be ignored
        fakeEngine.emitError(staleUtteranceId, "Stale error", 1)
        assertNull("Stale Error must not set missingVoiceWarning", controller.readerState.value.missingVoiceWarning)
    }
}
