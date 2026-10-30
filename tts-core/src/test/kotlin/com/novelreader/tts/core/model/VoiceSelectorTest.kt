package com.novelreader.tts.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSelectorTest {

    private val fakeVoices = listOf(
        VoiceInfo(id = "voice_narrator", name = "Narrator Voice", locale = "en-US", gender = VoiceGender.ANY),
        VoiceInfo(id = "voice_us_male_1", name = "US Male 1", locale = "en-US", gender = VoiceGender.MALE),
        VoiceInfo(id = "voice_us_male_2", name = "US Male 2", locale = "en-US", gender = VoiceGender.MALE),
        VoiceInfo(id = "voice_us_female_1", name = "US Female 1", locale = "en-US", gender = VoiceGender.FEMALE),
        VoiceInfo(id = "voice_gb_male_1", name = "GB Male 1", locale = "en-GB", gender = VoiceGender.MALE),
        VoiceInfo(id = "voice_au_male_1", name = "AU Male 1", locale = "en-AU", gender = VoiceGender.MALE),
        VoiceInfo(id = "voice_network_only", name = "Network Male", locale = "en-US", gender = VoiceGender.MALE, requiresNetwork = true)
    )

    @Test
    fun `uses explicit voiceId if present on device`() {
        val char = Character(
            id = "char_lin",
            name = "Lin",
            voiceId = "voice_us_female_1"
        )
        val result = VoiceSelector.pick(char, fakeVoices)

        assertEquals("voice_us_female_1", result.voice?.id)
        assertNull(result.missingVoiceWarning)
    }

    @Test
    fun `missing voiceId falls back to narrator voice and sets missingVoiceWarning`() {
        val char = Character(
            id = "char_lin",
            name = "Lin",
            voiceId = "non_existent_voice"
        )
        val result = VoiceSelector.pick(
            character = char,
            availableVoices = fakeVoices,
            narratorVoiceId = "voice_narrator"
        )

        assertEquals("voice_narrator", result.voice?.id)
        assertNotNull(result.missingVoiceWarning)
        assertTrue(result.missingVoiceWarning!!.contains("non_existent_voice"))
    }

    @Test
    fun `readByNarrator character uses narrator voice`() {
        val char = Character(
            id = "char_inner",
            name = "Inner Monologue",
            voiceId = "voice_us_male_1",
            readByNarrator = true
        )
        val result = VoiceSelector.pick(
            character = char,
            availableVoices = fakeVoices,
            narratorVoiceId = "voice_narrator"
        )

        assertEquals("voice_narrator", result.voice?.id)
        assertNull(result.missingVoiceWarning)
    }

    @Test
    fun `matches voiceGender and book locale and prefers unused voices`() {
        val maleChar1 = Character(
            id = "char_xian",
            name = "Xian",
            voiceGender = VoiceGender.MALE
        )
        val res1 = VoiceSelector.pick(maleChar1, fakeVoices, usedVoices = emptySet(), bookLocale = "en-US")
        assertEquals("voice_us_male_1", res1.voice?.id)

        // For second male character, voice_us_male_1 is used -> should pick voice_us_male_2
        val maleChar2 = Character(
            id = "char_elder",
            name = "Elder",
            voiceGender = VoiceGender.MALE
        )
        val res2 = VoiceSelector.pick(maleChar2, fakeVoices, usedVoices = setOf("voice_us_male_1"), bookLocale = "en-US")
        assertEquals("voice_us_male_2", res2.voice?.id)
    }

    @Test
    fun `falls back to different locale variant before reusing a voice`() {
        // Both US male voices are used
        val used = setOf("voice_us_male_1", "voice_us_male_2")
        val maleChar = Character(
            id = "char_guard",
            name = "Guard",
            voiceGender = VoiceGender.MALE
        )
        val res = VoiceSelector.pick(maleChar, fakeVoices, usedVoices = used, bookLocale = "en-US")

        // Should pick en-GB or en-AU before reusing US male voices
        assertTrue(res.voice?.id == "voice_gb_male_1" || res.voice?.id == "voice_au_male_1")
    }

    @Test
    fun `skips network required voices`() {
        val maleChar = Character(
            id = "char_soldier",
            name = "Soldier",
            voiceGender = VoiceGender.MALE
        )
        val onlyNetworkList = listOf(
            VoiceInfo(id = "voice_narrator", name = "Narrator", locale = "en-US"),
            VoiceInfo(id = "voice_net", name = "Network Voice", locale = "en-US", gender = VoiceGender.MALE, requiresNetwork = true)
        )
        val res = VoiceSelector.pick(maleChar, onlyNetworkList, narratorVoiceId = "voice_narrator")

        // Network voice must be skipped, falls back to narrator voice
        assertEquals("voice_narrator", res.voice?.id)
    }
}
