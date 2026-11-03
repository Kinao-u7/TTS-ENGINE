package com.novelreader.tts.android.service

/**
 * Audio focus change integer constants mirroring Android AudioManager values,
 * decoupling pure state machine logic for clean JVM testing.
 */
object AudioFocusConstants {
    const val GAIN = 1
    const val LOSS = -1
    const val LOSS_TRANSIENT = -2
    const val LOSS_TRANSIENT_CAN_DUCK = -3
}

/**
 * Pure state machine managing audio focus states, transient interruptions,
 * ducking policy (speech must pause, never duck), and becoming noisy events.
 */
class TtsAudioFocusStateMachine(
    private val listener: Listener
) {
    interface Listener {
        fun onPausePlayback()
        fun onResumePlayback()
        fun onStopPlayback()
        fun onRequestSystemFocus(): Boolean
        fun onAbandonSystemFocus()
    }

    var isPlaybackActive: Boolean = false
        private set

    var isPausedByTransientLoss: Boolean = false
        private set

    var hasSystemFocus: Boolean = false
        private set

    /**
     * Called when playback is about to begin. Requests system audio focus if not already held.
     * Returns true if audio focus was granted and playback can proceed.
     */
    fun onPlayRequested(): Boolean {
        if (!hasSystemFocus) {
            val granted = listener.onRequestSystemFocus()
            if (!granted) {
                return false
            }
            hasSystemFocus = true
        }
        isPlaybackActive = true
        isPausedByTransientLoss = false
        return true
    }

    /**
     * Called when the user explicitly pauses playback.
     */
    fun onPauseRequested() {
        isPlaybackActive = false
        isPausedByTransientLoss = false
    }

    /**
     * Called when playback is stopped. Abandons system audio focus immediately.
     */
    fun onStopRequested() {
        isPlaybackActive = false
        isPausedByTransientLoss = false
        if (hasSystemFocus) {
            hasSystemFocus = false
            listener.onAbandonSystemFocus()
        }
    }

    /**
     * Called when ACTION_AUDIO_BECOMING_NOISY is received (e.g. headphones unplugged).
     * Pauses playback immediately so audio does not blast out of the speaker.
     */
    fun onBecomingNoisy() {
        if (isPlaybackActive) {
            isPlaybackActive = false
            isPausedByTransientLoss = false
            listener.onPausePlayback()
        }
    }

    /**
     * Handles system audio focus changes.
     */
    fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioFocusConstants.LOSS -> {
                hasSystemFocus = false
                isPausedByTransientLoss = false
                if (isPlaybackActive) {
                    isPlaybackActive = false
                    listener.onPausePlayback()
                }
            }
            AudioFocusConstants.LOSS_TRANSIENT,
            AudioFocusConstants.LOSS_TRANSIENT_CAN_DUCK -> {
                // Speech reading is unintelligible when ducked; treat ducking as transient loss and pause.
                hasSystemFocus = false
                if (isPlaybackActive) {
                    isPlaybackActive = false
                    isPausedByTransientLoss = true
                    listener.onPausePlayback()
                }
            }
            AudioFocusConstants.GAIN -> {
                hasSystemFocus = true
                if (isPausedByTransientLoss) {
                    isPausedByTransientLoss = false
                    isPlaybackActive = true
                    listener.onResumePlayback()
                }
            }
        }
    }

    /**
     * Called when paused for longer than the abandon timeout threshold (e.g. 5 seconds).
     * Abandons focus if currently held while paused.
     */
    fun onAbandonTimeout() {
        if (!isPlaybackActive && hasSystemFocus) {
            hasSystemFocus = false
            isPausedByTransientLoss = false
            listener.onAbandonSystemFocus()
        }
    }
}
