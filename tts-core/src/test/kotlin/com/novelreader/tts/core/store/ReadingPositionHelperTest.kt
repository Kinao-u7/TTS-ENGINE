package com.novelreader.tts.core.store

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPositionHelperTest {

    @Test
    fun `empty list returns 0`() {
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(0, 0))
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(5, 0))
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(-1, 0))
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(null, 0))
    }

    @Test
    fun `negative count returns 0`() {
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(3, -5))
    }

    @Test
    fun `index less than zero clamps to 0`() {
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(-1, 10))
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(-100, 10))
    }

    @Test
    fun `null index returns 0`() {
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(null, 10))
    }

    @Test
    fun `index greater than size clamps to size minus one`() {
        assertEquals(9, ReadingPositionHelper.clampSentenceIndex(10, 10))
        assertEquals(9, ReadingPositionHelper.clampSentenceIndex(50, 10))
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(5, 1))
    }

    @Test
    fun `index exactly size minus one stays unchanged`() {
        assertEquals(9, ReadingPositionHelper.clampSentenceIndex(9, 10))
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(0, 1))
        assertEquals(24, ReadingPositionHelper.clampSentenceIndex(24, 25))
    }

    @Test
    fun `valid index within range stays unchanged`() {
        assertEquals(0, ReadingPositionHelper.clampSentenceIndex(0, 10))
        assertEquals(4, ReadingPositionHelper.clampSentenceIndex(4, 10))
        assertEquals(7, ReadingPositionHelper.clampSentenceIndex(7, 10))
    }
}
