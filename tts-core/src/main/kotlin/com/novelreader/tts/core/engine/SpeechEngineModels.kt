package com.novelreader.tts.core.engine

/**
 * Lifecycle states of a [SpeechEngine].
 */
sealed interface EngineState {
    data object Uninitialized : EngineState
    data object Initializing : EngineState
    data object Ready : EngineState
    data class Error(val message: String, val throwable: Throwable? = null) : EngineState
}

/**
 * Real-time speech synthesis progress events.
 */
sealed interface SpeechEvent {
    data class Started(val utteranceId: String) : SpeechEvent

    data class RangeStarted(
        val utteranceId: String,
        val startOffset: Int,
        val endOffset: Int
    ) : SpeechEvent

    data class Done(val utteranceId: String) : SpeechEvent

    data class Error(
        val utteranceId: String,
        val message: String,
        val errorCode: Int = 0
    ) : SpeechEvent
}
