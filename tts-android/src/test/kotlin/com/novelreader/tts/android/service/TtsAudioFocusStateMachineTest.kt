package com.novelreader.tts.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TtsAudioFocusStateMachineTest {

    private lateinit var fakeCallback: FakeFocusCallback
    private lateinit var stateMachine: TtsAudioFocusStateMachine

    class FakeFocusCallback : TtsFocusCallback {
        var pauseCount = 0
        var resumeCount = 0
        var requestedGainType: Int? = null
        var requestFocusCount = 0
        var abandonFocusCount = 0
        var shouldGrantFocus = true
        var currentAudioMode: Int = AudioFocusConstants.MODE_NORMAL

        override fun onPausePlayback() {
            pauseCount++
        }

        override fun onResumePlayback() {
            resumeCount++
        }

        override fun onRequestSystemFocus(focusGain: Int): Boolean {
            requestFocusCount++
            requestedGainType = focusGain
            return shouldGrantFocus
        }

        override fun onAbandonSystemFocus() {
            abandonFocusCount++
        }

        override fun getAudioMode(): Int = currentAudioMode
    }

    @Before
    fun setUp() {
        fakeCallback = FakeFocusCallback()
        stateMachine = TtsAudioFocusStateMachine(fakeCallback)
    }

    // 1. Mode request type tests

    @Test
    fun `PAUSE_OTHERS mode requests AUDIOFOCUS_GAIN on play`() {
        stateMachine.mode = AudioFocusMode.PAUSE_OTHERS
        val canPlay = stateMachine.onPlayRequested()

        assertTrue(canPlay)
        assertTrue(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.hasSystemFocus)
        assertEquals(1, fakeCallback.requestFocusCount)
        assertEquals(AudioFocusConstants.GAIN, fakeCallback.requestedGainType)
    }

    @Test
    fun `DUCK_OTHERS mode requests AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK on play`() {
        stateMachine.mode = AudioFocusMode.DUCK_OTHERS
        val canPlay = stateMachine.onPlayRequested()

        assertTrue(canPlay)
        assertTrue(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.hasSystemFocus)
        assertEquals(1, fakeCallback.requestFocusCount)
        assertEquals(AudioFocusConstants.GAIN_TRANSIENT_MAY_DUCK, fakeCallback.requestedGainType)
    }

    @Test
    fun `MIX mode makes no audio focus request`() {
        stateMachine.mode = AudioFocusMode.MIX
        val canPlay = stateMachine.onPlayRequested()

        assertTrue(canPlay)
        assertTrue(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.hasSystemFocus)
        assertEquals(0, fakeCallback.requestFocusCount)
    }

    // 2. Toggle OFF (keepReading = false): standard behavior

    @Test
    fun `toggle OFF - LOSS pauses playback permanently without auto-resume`() {
        stateMachine.keepReadingWhenOtherAudioStarts = false
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Permanent loss (another media player takes focus)
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS)
        assertFalse(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.hasSystemFocus)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeCallback.pauseCount)

        // Focus regained later -> should NOT auto-resume
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertFalse(stateMachine.isPlaybackActive)
        assertEquals(0, fakeCallback.resumeCount)
    }

    @Test
    fun `toggle OFF - LOSS_TRANSIENT pauses and auto-resumes on GAIN`() {
        stateMachine.keepReadingWhenOtherAudioStarts = false
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Transient loss (short notification sound or temporary audio)
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS_TRANSIENT)
        assertFalse(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.hasSystemFocus)
        assertTrue(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeCallback.pauseCount)

        // Focus returns -> auto-resume
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertTrue(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.hasSystemFocus)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeCallback.resumeCount)
    }

    // 3. Toggle ON (keepReading = true): ignore loss unless in call/ringtone

    @Test
    fun `toggle ON - LOSS and LOSS_TRANSIENT are ignored when other audio starts`() {
        stateMachine.keepReadingWhenOtherAudioStarts = true
        fakeCallback.currentAudioMode = AudioFocusConstants.MODE_NORMAL
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // LOSS ignored
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS)
        assertTrue(stateMachine.isPlaybackActive)
        assertEquals(0, fakeCallback.pauseCount)

        // LOSS_TRANSIENT ignored
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS_TRANSIENT)
        assertTrue(stateMachine.isPlaybackActive)
        assertEquals(0, fakeCallback.pauseCount)
    }

    @Test
    fun `toggle ON - MODE_IN_CALL pauses on LOSS and resumes afterward`() {
        stateMachine.keepReadingWhenOtherAudioStarts = true
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Phone call mode active
        fakeCallback.currentAudioMode = AudioFocusConstants.MODE_IN_CALL

        // Focus loss during phone call must pause
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS)
        assertFalse(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeCallback.pauseCount)

        // Call ends, normal mode restored and focus regained
        fakeCallback.currentAudioMode = AudioFocusConstants.MODE_NORMAL
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertTrue(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeCallback.resumeCount)
    }

    @Test
    fun `toggle ON - MODE_RINGTONE pauses on LOSS_TRANSIENT and resumes afterward`() {
        stateMachine.keepReadingWhenOtherAudioStarts = true
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.isPlaybackActive)

        // Incoming ringtone mode active
        fakeCallback.currentAudioMode = AudioFocusConstants.MODE_RINGTONE

        // Focus loss during ringtone must pause
        stateMachine.onAudioFocusChange(AudioFocusConstants.LOSS_TRANSIENT)
        assertFalse(stateMachine.isPlaybackActive)
        assertTrue(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeCallback.pauseCount)

        // Ringtone stops, focus regained
        fakeCallback.currentAudioMode = AudioFocusConstants.MODE_NORMAL
        stateMachine.onAudioFocusChange(AudioFocusConstants.GAIN)
        assertTrue(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.isPausedByTransientLoss)
        assertEquals(1, fakeCallback.resumeCount)
    }

    // 4. ACTION_AUDIO_BECOMING_NOISY pause in all modes

    @Test
    fun `becoming noisy pauses playback in all modes even when keepReading is true`() {
        for (mode in AudioFocusMode.values()) {
            stateMachine.mode = mode
            stateMachine.keepReadingWhenOtherAudioStarts = true
            stateMachine.onPlayRequested()
            assertTrue(stateMachine.isPlaybackActive)

            val initialPauses = fakeCallback.pauseCount
            stateMachine.onBecomingNoisy()
            assertFalse(stateMachine.isPlaybackActive)
            assertEquals(initialPauses + 1, fakeCallback.pauseCount)
        }
    }

    // 5. Abandon focus on pause and stop

    @Test
    fun `abandon focus on pause and stop`() {
        stateMachine.mode = AudioFocusMode.DUCK_OTHERS
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.hasSystemFocus)

        // Pause abandons focus
        stateMachine.onPauseRequested()
        assertFalse(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.hasSystemFocus)
        assertEquals(1, fakeCallback.abandonFocusCount)

        // Play again and stop
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.hasSystemFocus)

        stateMachine.onStopRequested()
        assertFalse(stateMachine.isPlaybackActive)
        assertFalse(stateMachine.hasSystemFocus)
        assertEquals(2, fakeCallback.abandonFocusCount)
    }

    @Test
    fun `updateSettings live abandons focus when switching to MIX`() {
        stateMachine.mode = AudioFocusMode.PAUSE_OTHERS
        stateMachine.onPlayRequested()
        assertTrue(stateMachine.hasSystemFocus)

        // Switch to MIX while playing
        stateMachine.updateSettings(AudioFocusMode.MIX, false)
        assertFalse(stateMachine.hasSystemFocus)
        assertEquals(1, fakeCallback.abandonFocusCount)
    }
}
