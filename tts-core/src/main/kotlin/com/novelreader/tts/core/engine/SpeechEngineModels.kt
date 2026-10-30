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
    val utteranceId: String

    data class Started(override val utteranceId: String) : SpeechEvent

    data class Range(
        val start: Int,
        val end: Int,
        override val utteranceId: String = ""
    ) : SpeechEvent

    data class Done(override val utteranceId: String) : SpeechEvent

    data class Error(
        override val utteranceId: String,
        val message: String,
        val errorCode: Int = 0
    ) : SpeechEvent
}
