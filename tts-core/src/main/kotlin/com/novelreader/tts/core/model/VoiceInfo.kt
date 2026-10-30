package com.novelreader.tts.core.model

/**
 * Information describing a synthesized voice.
 *
 * @property id Unique voice identifier (e.g. system voice name or identifier).
 * @property name Human-readable display name.
 * @property locale BCP-47 language tag or locale string (e.g. "en-US").
 * @property quality Quality rating (e.g. normal, high).
 * @property latency Latency rating.
 * @property requiresNetwork Whether this voice requires an active internet connection.
 */
data class VoiceInfo(
    val id: String,
    val name: String,
    val locale: String,
    val quality: Int = QUALITY_NORMAL,
    val latency: Int = LATENCY_NORMAL,
    val requiresNetwork: Boolean = false
) {
    companion object {
        const val QUALITY_VERY_LOW = 100
        const val QUALITY_LOW = 200
        const val QUALITY_NORMAL = 300
        const val QUALITY_HIGH = 400
        const val QUALITY_VERY_HIGH = 500

        const val LATENCY_VERY_LOW = 100
        const val LATENCY_LOW = 200
        const val LATENCY_NORMAL = 300
        const val LATENCY_HIGH = 400
        const val LATENCY_VERY_HIGH = 500
    }
}
