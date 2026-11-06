package com.novelreader.tts.core.catalog

import com.novelreader.tts.core.model.VoiceInfo

/**
 * Interface providing access to available offline and system TTS voices.
 */
interface VoiceCatalog {
    fun getAvailableVoices(): List<VoiceInfo>
}
