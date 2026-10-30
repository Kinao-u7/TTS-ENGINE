package com.novelreader.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Record of inter-utterance timing and speaker transitions.
 */
data class UtteranceGapRecord(
    val gapMs: Long,
    val isSpeakerChange: Boolean,
    val previousSpeaker: String?,
    val currentSpeaker: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Session latency and gap metrics.
 */
data class DebugMetrics(
    val firstStartLatencyMs: Long? = null,
    val firstStartLabel: String? = null, // "cold" or "warm"
    val medianGapMs: Long? = null,
    val maxGapMs: Long? = null,
    val totalGapsRecorded: Int = 0,
    val recentGaps: List<UtteranceGapRecord> = emptyList()
)

/**
 * Debug latency tracker for TTS utterance transitions and startup performance.
 */
class TtsDebugTracker(
    private val tag: String = "TTS_DEBUG"
) {
    private val _metrics = MutableStateFlow(DebugMetrics())
    val metrics: StateFlow<DebugMetrics> = _metrics.asStateFlow()

    private var playRequestTimestamp: Long? = null
    private var isEngineWarmOnPlay: Boolean = false
    private var isFirstStartAfterPlay: Boolean = false

    private var lastDoneTimestamp: Long? = null
    private var lastDoneSpeaker: String? = null

    private val allSessionGaps = mutableListOf<Long>()
    private val gapRecords = mutableListOf<UtteranceGapRecord>()

    fun onPlay(isWarm: Boolean) {
        playRequestTimestamp = System.currentTimeMillis()
        isEngineWarmOnPlay = isWarm
        isFirstStartAfterPlay = true
        Log.d(tag, "play() initiated [engine ${if (isWarm) "warm" else "cold"}]")
    }

    fun onStart(utteranceId: String, speakerId: String) {
        val now = System.currentTimeMillis()

        // 1. Time from play() to first onStart
        if (isFirstStartAfterPlay && playRequestTimestamp != null) {
            val startLatency = now - playRequestTimestamp!!
            val label = if (isEngineWarmOnPlay) "warm" else "cold"
            isFirstStartAfterPlay = false

            Log.d(tag, "Time from play() to first onStart: ${startLatency}ms ($label) [utt: $utteranceId]")
            _metrics.value = _metrics.value.copy(
                firstStartLatencyMs = startLatency,
                firstStartLabel = label
            )
        }

        // 2. Time between onDone of previous utterance and onStart of this utterance
        if (lastDoneTimestamp != null) {
            val gap = now - lastDoneTimestamp!!
            val speakerChange = lastDoneSpeaker != null && lastDoneSpeaker != speakerId
            val record = UtteranceGapRecord(
                gapMs = gap,
                isSpeakerChange = speakerChange,
                previousSpeaker = lastDoneSpeaker,
                currentSpeaker = speakerId,
                timestamp = now
            )

            allSessionGaps.add(gap)
            gapRecords.add(0, record) // latest first

            val median = calculateMedian(allSessionGaps)
            val max = allSessionGaps.maxOrNull()

            Log.d(
                tag,
                "Gap onDone->onStart: ${gap}ms (speakerChange=$speakerChange, from='$lastDoneSpeaker' to='$speakerId'). " +
                    "Session median=${median}ms, max=${max}ms"
            )

            _metrics.value = _metrics.value.copy(
                medianGapMs = median,
                maxGapMs = max,
                totalGapsRecorded = allSessionGaps.size,
                recentGaps = gapRecords.take(10)
            )
        }
    }

    fun onDone(utteranceId: String, speakerId: String) {
        lastDoneTimestamp = System.currentTimeMillis()
        lastDoneSpeaker = speakerId
        Log.d(tag, "Utterance done: $utteranceId (speaker: $speakerId)")
    }

    private fun calculateMedian(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[(size / 2) - 1] + sorted[size / 2]) / 2
        }
    }

    fun reset() {
        allSessionGaps.clear()
        gapRecords.clear()
        playRequestTimestamp = null
        lastDoneTimestamp = null
        lastDoneSpeaker = null
        _metrics.value = DebugMetrics()
    }
}
