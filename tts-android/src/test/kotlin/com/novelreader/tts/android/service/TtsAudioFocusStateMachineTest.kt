package com.novelreader.tts.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TtsAudioFocusStateMachineTest {

    private lateinit var fakeListener: FakeListener
    private lateinit var stateMachine: TtsAudioFocusStateMachine

    class FakeListener : TtsAudioFocusStateMachine.Listener {
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        var requestFocusCount = 0
        var abandonFocusCount = 0
        var shouldGrantFocus = true

        override fun onPausePlayback() {
            pauseCount++
        }

        override fun onResumePlayback() {
            resumeCount++
        }

        override fun onStopPlayback() {
            stopCount++
        }

        override fun onRequestSystemFocus(): Boolean {
            requestFocusCount++
            return shouldGrantFocus
        }

        override fun onAbandonSystemFocus() {
            abandonFocusCount++
        }
    }

    @Before
    fun setUp() {
        fakeListener = FakeListener()
        stateMachine = TtsAudioFocusStateMachine(fakeListener)
    }

    @Test
    fun `play request obtains focus and activates playback`() {
        val canPlay = stateMachine.onPlayRequested()
        assertTrue(canPlay)
        assertTrue(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.hasSystemFocus)
        assertEquals(1, fakeListener.requestFocusCount)
    }

    @Test
    fun `focus loss pauses playback permanently without auto-resume`() {
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Permanent loss
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS)
        assertFalse(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.hasSystemFocus)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeListener.pauseCount)

        // Regaining focus later should NOT auto-resume
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertFalse(stateMachine.isPlaybackActive)
        assertEquals(0, fakeListener.resumeCount)
    }

    @Test
    fun `transient loss pauses playback and auto-resumes on gain`() {
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Transient loss (e.g. phone call or navigation prompt)
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS_TRANSIENT)
        assertFalse(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeListener.pauseCount)

        // Focus regained -> must auto-resume
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertTrue(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeListener.resumeCount)
    }

    @Test
    fun `duck behaves as transient pause and auto-resumes on gain`() {
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Duck event: speech must not duck; treat as transient pause
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeListener.pauseCount)

        // Focus regained -> must auto-resume
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertTrue(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeListener.resumeCount)
    }

    @Test
    fun `becoming noisy pauses playback immediately without auto-resume`() {
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Headphones unplugged
        stateMachine.onBecomingNoisy()
        assertFalse(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeListener.pauseCount)

        // Subsequent gain does not resume
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertFalse(stateMachine.isPlaybackActive)
        assertEquals(0, fakeListener.resumeCount)
    }

    @Test
    fun `stop abandons focus immediately`() {
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.hasSystemFocus)

        stateMachine.onStopRequested()
        assertFalse(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.hasSystemFocus)
        assertEquals(1, fakeListener.abandonFocusCount)
    }

    @Test
    fun `abandon timeout releases focus after pause`() {
        stateMachine.onPlayRequested()
        stateMachine.onPauseRequested()

        assertTrue(stateMachine.hasSystemFocus)
        assertFalse(stateMachine.isPlaybackActive)

        // Timeout triggers
        stateMachine.onAbandonTimeout()
        assertFalse(stateMachine.hasSystemFocus)
        assertEquals(1, fakeListener.abandonFocusCount)
    }
}
