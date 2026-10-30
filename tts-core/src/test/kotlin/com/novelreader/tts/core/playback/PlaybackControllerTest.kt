package com.novelreader.tts.core.playback

import com.novelreader.tts.core.engine.FakeSpeechEngine
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.TextRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeEngine: FakeSpeechEngine
    private lateinit var controller: PlaybackController

    private val charElena = Character(
        id = "char_elena",
        name = "Elena",
        voiceId = "voice_en_2",
        speed = 1.1f,
        pitch = 1.2f
    )

    private val charXian = Character(
        id = "char_xian",
        name = "Xian",
        voiceId = "voice_en_1",
        speed = 1.0f,
        pitch = 0.9f
    )

    @Before
    fun setUp() {
        fakeEngine = FakeSpeechEngine(scope = testScope)
        controller = PlaybackController(fakeEngine, scope = testScope)
        controller.updateCharacters(listOf(Character.Narrator, charElena, charXian))
    }

    @Test
    fun `play sequence of 3 segments asserts state transitions and voice changes`() = runTest {
        val segments = listOf(
            Segment(
                id = "seg_n0_111",
                type = SegmentType.NARRATION,
                text = "He looked at the sky.",
                startOffset = 0,
                endOffset = 21
            ),
            Segment(
                id = "seg_d0_222",
                type = SegmentType.DIALOGUE,
                text = "\"Prepare the carriage,\"",
                startOffset = 21,
                endOffset = 44,
                speakerOverride = "char_elena"
            ),
            Segment(
                id = "seg_d1_333",
                type = SegmentType.DIALOGUE,
                text = "\"At once!\"",
                startOffset = 44,
                endOffset = 54,
                speakerOverride = "char_xian"
            )
        )

        controller.loadSegments(segments)
        assertEquals(PlaybackState.Idle, controller.state.value)

        // 1. Start playback on Segment 0 (Narrator)
        controller.play()
        assertEquals(1, fakeEngine.speakRecords.size)
        val rec0 = fakeEngine.speakRecords[0]
        assertEquals("He looked at the sky.", rec0.text)
        assertNull(rec0.voiceId) // Narrator default voice
        assertTrue(controller.state.value is PlaybackState.Playing)
        assertEquals(0, controller.state.value.currentSegmentIndex)

        // 2. Complete Segment 0 -> auto-advance to Segment 1 (Elena)
        fakeEngine.emitDone(rec0.utteranceId)
        assertEquals(2, fakeEngine.speakRecords.size)
        val rec1 = fakeEngine.speakRecords[1]
        assertEquals("\"Prepare the carriage,\"", rec1.text)
        assertEquals("voice_en_2", rec1.voiceId)
        assertEquals(1.1f, rec1.speed)
        assertEquals(1.2f, rec1.pitch)
        assertEquals(1, controller.state.value.currentSegmentIndex)

        // Pre-warming verification: next speaker (Xian) should have been pre-warmed
        assertTrue(fakeEngine.prewarmedVoices.contains("voice_en_1"))

        // 3. Complete Segment 1 -> auto-advance to Segment 2 (Xian)
        fakeEngine.emitDone(rec1.utteranceId)
        assertEquals(3, fakeEngine.speakRecords.size)
        val rec2 = fakeEngine.speakRecords[2]
        assertEquals("\"At once!\"", rec2.text)
        assertEquals("voice_en_1", rec2.voiceId)
        assertEquals(1.0f, rec2.speed)
        assertEquals(0.9f, rec2.pitch)
        assertEquals(2, controller.state.value.currentSegmentIndex)

        // 4. Complete Segment 2 -> end of chapter -> transition to Stopped
        fakeEngine.emitDone(rec2.utteranceId)
        assertEquals(PlaybackState.Stopped, controller.state.value)
    }

    @Test
    fun `pause and resume flow`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "First line.", 0, 11)
        )
        controller.loadSegments(segments)
        controller.play()

        assertTrue(controller.state.value.isPlaying)

        // Pause
        controller.pause()
        assertEquals(1, fakeEngine.pauseCount)
        assertTrue(controller.state.value is PlaybackState.Paused)
        assertEquals(0, controller.state.value.currentSegmentIndex)

        // Resume
        controller.resume()
        assertEquals(1, fakeEngine.resumeCount)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun `skip next and previous`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Line 0", 0, 6),
            Segment("seg_1", SegmentType.NARRATION, "Line 1", 6, 12),
            Segment("seg_2", SegmentType.NARRATION, "Line 2", 12, 18)
        )
        controller.loadSegments(segments)
        controller.play()
        assertEquals(0, controller.state.value.currentSegmentIndex)

        // Skip to next
        controller.next()
        assertEquals(1, controller.state.value.currentSegmentIndex)
        assertEquals("Line 1", fakeEngine.speakRecords.last().text)

        // Skip to next
        controller.next()
        assertEquals(2, controller.state.value.currentSegmentIndex)
        assertEquals("Line 2", fakeEngine.speakRecords.last().text)

        // Skip back to previous
        controller.previous()
        assertEquals(1, controller.state.value.currentSegmentIndex)
        assertEquals("Line 1", fakeEngine.speakRecords.last().text)
    }

    @Test
    fun `seek to specific segment`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Line 0", 0, 6),
            Segment("seg_1", SegmentType.NARRATION, "Line 1", 6, 12),
            Segment("seg_2", SegmentType.NARRATION, "Line 2", 12, 18)
        )
        controller.loadSegments(segments)
        controller.seekTo(2)
        assertEquals(2, controller.state.value.currentSegmentIndex)
    }

    @Test
    fun `stop halts playback and updates state`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Line 0", 0, 6)
        )
        controller.loadSegments(segments)
        controller.play()
        assertTrue(controller.state.value.isPlaying)

        controller.stop()
        assertTrue(fakeEngine.stopCount > 0)
        assertEquals(PlaybackState.Stopped, controller.state.value)
    }

    @Test
    fun `blank or whitespace-only segments are automatically skipped`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Opening narration.", 0, 18),
            Segment("seg_1", SegmentType.NARRATION, "\n\n   \t  \n", 18, 27), // Whitespace only
            Segment("seg_2", SegmentType.NARRATION, "", 27, 27), // Completely empty
            Segment("seg_3", SegmentType.DIALOGUE, "\"Dialogue after blanks.\"", 27, 51)
        )
        controller.loadSegments(segments)
        controller.play()

        // Plays segment 0
        assertEquals(0, controller.state.value.currentSegmentIndex)
        assertEquals("Opening narration.", fakeEngine.speakRecords[0].text)

        // Done on segment 0 -> skips 1 and 2, auto-plays segment 3!
        fakeEngine.emitDone(fakeEngine.speakRecords[0].utteranceId)

        assertEquals(2, fakeEngine.speakRecords.size)
        assertEquals("\"Dialogue after blanks.\"", fakeEngine.speakRecords[1].text)
        assertEquals(3, controller.state.value.currentSegmentIndex)
    }

    @Test
    fun `errors are surfaced and never swallowed silently`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Error trigger line.", 0, 19)
        )
        controller.loadSegments(segments)
        controller.play()

        val activeId = fakeEngine.speakRecords[0].utteranceId
        fakeEngine.emitError(activeId, "Hardware codec failure", errorCode = 500)

        val currentState = controller.state.value
        assertTrue("State must be Error: $currentState", currentState is PlaybackState.Error)
        val errorState = currentState as PlaybackState.Error
        assertTrue(errorState.message.contains("Hardware codec failure"))
        assertTrue(errorState.message.contains("500"))
    }

    @Test
    fun `range start events update character highlight range`() = runTest {
        val segments = listOf(
            Segment("seg_0", SegmentType.NARRATION, "Word-by-word highlight.", 0, 23)
        )
        controller.loadSegments(segments)
        controller.play()

        val activeId = fakeEngine.speakRecords[0].utteranceId
        fakeEngine.emitRangeStart(activeId, startOffset = 5, endOffset = 12)

        val currentState = controller.state.value
        assertTrue(currentState is PlaybackState.Playing)
        val playingState = currentState as PlaybackState.Playing
        assertEquals(TextRange(5, 12), playingState.rangeInSegment)
    }
}
