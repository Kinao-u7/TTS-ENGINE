package com.novelreader.tts.core.playback

import com.novelreader.tts.core.model.TextRange

/**
 * Observable state of the reader playback controller.
 */
sealed interface PlaybackState {

    /**
     * Nothing loaded or waiting to start.
     */
    data object Idle : PlaybackState

    /**
     * Engine is preparing audio synthesis for [segmentIndex].
     */
    data class Buffering(val segmentIndex: Int) : PlaybackState

    /**
     * Actively playing speech for [segmentIndex].
     *
     * @property segmentIndex The 0-based index of the currently active segment.
     * @property rangeInSegment Optional character span relative to the start of this segment
     *                          for word/character level highlighting when supported by TTS.
     */
    data class Playing(
        val segmentIndex: Int,
        val rangeInSegment: TextRange? = null
    ) : PlaybackState

    /**
     * Playback temporarily suspended at [segmentIndex].
     */
    data class Paused(
        val segmentIndex: Int,
        val rangeInSegment: TextRange? = null
    ) : PlaybackState

    /**
     * Playback explicitly stopped by user or reaching chapter end.
     */
    data object Stopped : PlaybackState

    /**
     * An error occurred during synthesis or playback.
     */
    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : PlaybackState

    val currentSegmentIndex: Int?
        get() = when (this) {
            is Buffering -> segmentIndex
            is Playing -> segmentIndex
            is Paused -> segmentIndex
            Idle, Stopped, is Error -> null
        }

    val isPlaying: Boolean
        get() = this is Playing

    val isPaused: Boolean
        get() = this is Paused
}
