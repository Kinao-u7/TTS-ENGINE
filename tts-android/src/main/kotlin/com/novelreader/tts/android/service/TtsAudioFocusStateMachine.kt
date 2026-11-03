package com.novelreader.tts.android.service

/**
 * Three user-configurable audio focus modes.
 */
enum class AudioFocusMode {
    /** AudioFocusRequest with AUDIOFOCUS_GAIN. Other apps pause. */
    PAUSE_OTHERS,

    /**
     * AudioFocusRequest with AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioAttributes usage=MEDIA,
     * contentType=SPEECH. Hold the focus for the whole playback session, including gaps between
     * sentences. Abandon focus on pause and stop.
     */
    DUCK_OTHERS,

    /** Request no audio focus. Mix with other audio. */
    MIX
}

/**
 * Integer constants matching Android AudioManager values,
 * decoupling pure state machine logic for clean JVM testing.
 */
object AudioFocusConstants {
    const val GAIN = 1 // AudioManager.AUDIOFOCUS_GAIN
    const val LOSS = -1 // AudioManager.AUDIOFOCUS_LOSS
    const val LOSS_TRANSIENT = -2 // AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    const val LOSS_TRANSIENT_CAN_DUCK = -3 // AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
    const val GAIN_TRANSIENT_MAY_DUCK = 3 // AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

    const val MODE_NORMAL = 0 // AudioManager.MODE_NORMAL
    const val MODE_RINGTONE = 1 // AudioManager.MODE_RINGTONE
    const val MODE_IN_CALL = 2 // AudioManager.MODE_IN_CALL
}

/**
 * Focus callback interface providing controls for playback and system audio focus.
 */
interface TtsFocusCallback {
    fun onPausePlayback()
    fun onResumePlayback()
    fun onRequestSystemFocus(focusGain: Int): Boolean
    fun onAbandonSystemFocus()
    fun getAudioMode(): Int
}

/**
 * Small, testable class encapsulating audio focus policies, modes, and event handling.
 */
class TtsAudioFocusStateMachine(
    private val callback: TtsFocusCallback,
    var mode: AudioFocusMode = AudioFocusMode.PAUSE_OTHERS,
    var keepReadingWhenOtherAudioStarts: Boolean = false
) {
    var isPlaybackActive: Boolean = false
        private set

    var isPausedByTransientLoss: Boolean = false
        private set

    var hasSystemFocus: Boolean = false
        private set

    /**
     * Updates settings live.
     */
    fun updateSettings(newMode: AudioFocusMode, newKeepReading: Boolean) {
        val modeChanged = this.mode != newMode
        this.mode = newMode
        this.keepReadingWhenOtherAudioStarts = newKeepReading

        if (modeChanged && isPlaybackActive) {
            if (newMode == AudioFocusMode.MIX) {
                if (hasSystemFocus) {
                    hasSystemFocus = false
                    callback.onAbandonSystemFocus()
                }
            } else {
                val gainType = when (newMode) {
                    AudioFocusMode.PAUSE_OTHERS -> AudioFocusConstants.GAIN
                    AudioFocusMode.DUCK_OTHERS -> AudioFocusConstants.GAIN_TRANSIENT_MAY_DUCK
                    AudioFocusMode.MIX -> 0
                }
                val granted = callback.onRequestSystemFocus(gainType)
                hasSystemFocus = granted
            }
        }
    }

    /**
     * Called when playback is about to begin.
     * In MIX mode: requests no audio focus.
     * In PAUSE_OTHERS mode: requests AUDIOFOCUS_GAIN.
     * In DUCK_OTHERS mode: requests AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK.
     * Returns true if playback can proceed.
     */
    fun onPlayRequested(): Boolean {
        if (mode == AudioFocusMode.MIX) {
            isPlaybackActive = true
            isPausedByTransientLoss = false
            return true
        }

        if (!hasSystemFocus) {
            val gainType = when (mode) {
                AudioFocusMode.PAUSE_OTHERS -> AudioFocusConstants.GAIN
                AudioFocusMode.DUCK_OTHERS -> AudioFocusConstants.GAIN_TRANSIENT_MAY_DUCK
                AudioFocusMode.MIX -> 0
            }
            val granted = callback.onRequestSystemFocus(gainType)
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
     * Called on pause. Abandons system focus immediately.
     */
    fun onPauseRequested() {
        isPlaybackActive = false
        isPausedByTransientLoss = false
        if (hasSystemFocus) {
            hasSystemFocus = false
            callback.onAbandonSystemFocus()
        }
    }

    /**
     * Called on stop. Abandons system focus immediately.
     */
    fun onStopRequested() {
        isPlaybackActive = false
        isPausedByTransientLoss = false
        if (hasSystemFocus) {
            hasSystemFocus = false
            callback.onAbandonSystemFocus()
        }
    }

    /**
     * ACTION_AUDIO_BECOMING_NOISY always pauses playback in all modes.
     */
    fun onBecomingNoisy() {
        if (isPlaybackActive) {
            isPlaybackActive = false
            isPausedByTransientLoss = false
            callback.onPausePlayback()
        }
    }

    /**
     * Handles system audio focus changes.
     */
    fun onAudioFocusChange(focusChange: Int) {
        if (mode == AudioFocusMode.MIX) {
            return
        }

        when (focusChange) {
            AudioFocusConstants.LOSS -> {
                val audioMode = callback.getAudioMode()
                val isCallOrRingtone = audioMode == AudioFocusConstants.MODE_IN_CALL ||
                        audioMode == AudioFocusConstants.MODE_RINGTONE

                if (keepReadingWhenOtherAudioStarts && !isCallOrRingtone) {
                    // Ignore focus loss
                    return
                }

                hasSystemFocus = false
                if (isCallOrRingtone) {
                    // Phone call or ringtone pauses and resumes afterward
                    if (isPlaybackActive) {
                        isPlaybackActive = false
                        isPausedByTransientLoss = true
                        callback.onPausePlayback()
                    }
                } else {
                    // Standard LOSS: pause permanently without auto-resume
                    isPausedByTransientLoss = false
                    if (isPlaybackActive) {
                        isPlaybackActive = false
                        callback.onPausePlayback()
                    }
                }
            }

            AudioFocusConstants.LOSS_TRANSIENT,
            AudioFocusConstants.LOSS_TRANSIENT_CAN_DUCK -> {
                val audioMode = callback.getAudioMode()
                val isCallOrRingtone = audioMode == AudioFocusConstants.MODE_IN_CALL ||
                        audioMode == AudioFocusConstants.MODE_RINGTONE

                if (keepReadingWhenOtherAudioStarts && !isCallOrRingtone) {
                    // Ignore transient focus loss
                    return
                }

                hasSystemFocus = false
                if (isPlaybackActive) {
                    isPlaybackActive = false
                    isPausedByTransientLoss = true
                    callback.onPausePlayback()
                }
            }

            AudioFocusConstants.GAIN -> {
                hasSystemFocus = true
                if (isPausedByTransientLoss) {
                    isPausedByTransientLoss = false
                    isPlaybackActive = true
                    callback.onResumePlayback()
                }
            }
        }
    }
}
