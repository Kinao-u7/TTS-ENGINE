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
    fun `narrator and unnamed get distinct voices preferring different locale then different voice name`() {
        val narratorChar = Character(id = Character.NARRATOR_ID, name = "Narrator", voiceGender = VoiceGender.ANY)
        val unnamedChar = Character(id = Character.UNNAMED_ID, name = "Unnamed", voiceGender = VoiceGender.ANY)

        val narratorResult = VoiceSelector.pick(
            character = narratorChar,
            availableVoices = fakeVoices,
            usedVoices = emptySet(),
            bookLocale = "en-US"
        )
        assertNotNull(narratorResult.voice)
        assertEquals("en-US", narratorResult.voice?.locale)

        val unnamedResult = VoiceSelector.pick(
            character = unnamedChar,
            availableVoices = fakeVoices,
            usedVoices = setOf(narratorResult.voice!!.id),
            bookLocale = "en-US",
            narratorVoiceId = narratorResult.voice!!.id
        )
        assertNotNull(unnamedResult.voice)
        // Distinct voices
        assertTrue(unnamedResult.voice?.id != narratorResult.voice?.id)
        // Prefers different locale variant (en-GB or en-AU) over en-US
        assertTrue(unnamedResult.voice?.locale != "en-US")
    }

    @Test
    fun `prefers different voice name when no different locale exists`() {
        val singleLocaleVoices = listOf(
            VoiceInfo(id = "v_us_1", name = "US Voice 1", locale = "en-US", gender = VoiceGender.ANY),
            VoiceInfo(id = "v_us_2", name = "US Voice 2", locale = "en-US", gender = VoiceGender.ANY)
        )
        val char = Character(id = Character.UNNAMED_ID, name = "Unnamed")
        val result = VoiceSelector.pick(
            character = char,
            availableVoices = singleLocaleVoices,
            usedVoices = setOf("v_us_1"),
            bookLocale = "en-US"
        )
        assertEquals("v_us_2", result.voice?.id)
    }

    @Test
    fun `ignores gender when engine does not report it`() {
        val genderlessVoices = listOf(
            VoiceInfo(id = "voice_any_1", name = "Any Voice 1", locale = "en-US", gender = VoiceGender.ANY),
            VoiceInfo(id = "voice_any_2", name = "Any Voice 2", locale = "en-GB", gender = VoiceGender.ANY)
        )
        val femaleChar = Character(
            id = "char_female",
            name = "Female Char",
            voiceGender = VoiceGender.FEMALE
        )
        val result = VoiceSelector.pick(
            character = femaleChar,
            availableVoices = genderlessVoices,
            usedVoices = emptySet(),
            bookLocale = "en-US"
        )
        assertNotNull(result.voice)
        assertEquals("voice_any_1", result.voice?.id)
    }

    @Test
    fun `takes locale from the book not hardcoded en-US`() {
        val frenchVoices = listOf(
            VoiceInfo(id = "voice_fr_1", name = "French 1", locale = "fr-FR", gender = VoiceGender.ANY),
            VoiceInfo(id = "voice_fr_ca", name = "French CA", locale = "fr-CA", gender = VoiceGender.ANY),
            VoiceInfo(id = "voice_us", name = "English US", locale = "en-US", gender = VoiceGender.ANY)
        )
        val narratorChar = Character(id = Character.NARRATOR_ID, name = "Narrator")
        val res = VoiceSelector.pick(
            character = narratorChar,
            availableVoices = frenchVoices,
            usedVoices = emptySet(),
            bookLocale = "fr-FR"
        )
        assertEquals("voice_fr_1", res.voice?.id)
        assertEquals("fr-FR", res.voice?.locale)
    }
}
