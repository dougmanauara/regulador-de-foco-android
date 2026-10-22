package br.reguladordefoco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionMathTest {

    /** Pessoa simulada: acerta tudo do tamanho [limit] para cima e erra tudo abaixo. */
    private fun run(limit: Int): VisionMath.Staircase {
        val stair = VisionMath.Staircase()
        var guard = 0
        while (!stair.finished) {
            stair.answer(stair.index >= limit)
            check(++guard < 200)
        }
        return stair
    }

    @Test
    fun sizesGoFromHalfMillimeterToCentimeter() {
        assertEquals(0.4, VisionMath.SIZES_MM.first(), 1e-9)
        assertEquals(12.6, VisionMath.SIZES_MM.last(), 0.05)
        assertEquals(1.2589, VisionMath.SIZES_MM[1] / VisionMath.SIZES_MM[0], 1e-3)
    }

    @Test
    fun staircaseFindsTheLimitBelowAndAboveTheStart() {
        for (limit in VisionMath.SIZES_MM.indices) {
            assertEquals("limite $limit", limit, run(limit).smallestPassed)
        }
    }

    @Test
    fun staircaseReportsWhenNotEvenTheLargestIsSeen() {
        assertNull(run(VisionMath.SIZES_MM.size).smallestPassed)
    }

    @Test
    fun oneLuckyGuessDoesNotPassALevel() {
        val stair = VisionMath.Staircase()
        val start = stair.index
        stair.answer(true)
        stair.answer(false)
        assertEquals(start, stair.index)
        stair.answer(false)
        assertEquals(start + 1, stair.index)
    }

    @Test
    fun focusSearchMovesTowardTheChoiceInSmallerSteps() {
        val search = VisionMath.focusSearch(2.0)
        assertEquals(2.0 / 1.8, search.smaller, 1e-9)
        assertEquals(2.0 * 1.8, search.bigger, 1e-9)
        search.choose(VisionMath.Choice.BIGGER)
        search.choose(VisionMath.Choice.SMALLER)
        assertEquals(2.0 * 1.8 / 1.35, search.value, 1e-9)
        assertFalse(search.finished)
        search.choose(VisionMath.Choice.SAME)
        assertTrue(search.finished)
    }

    @Test
    fun searchesStayInsideTheirRange() {
        val focus = VisionMath.focusSearch(10.0)
        repeat(focus.rounds) { focus.choose(VisionMath.Choice.BIGGER) }
        assertEquals(VisionMath.FOCUS_MAX_PX, focus.value, 1e-9)

        val fine = VisionMath.fineSearch()
        repeat(fine.rounds) { fine.choose(VisionMath.Choice.SMALLER) }
        assertEquals(VisionMath.FINE_K_DEFAULT / 1.3 / 1.14, fine.value, 1e-9)
    }

    @Test
    fun onOffKeepsTheCorrectionOnlyWhenItWasChosen() {
        val with = VisionMath.OnOff(3.0)
        with.choose(VisionMath.Choice.BIGGER)
        assertEquals(3.0, with.value, 1e-9)
        assertTrue(with.finished)

        val same = VisionMath.OnOff(3.0)
        same.choose(VisionMath.Choice.SAME)
        assertEquals(0.0, same.value, 1e-9)
    }

    @Test
    fun focusEstimateGrowsWithTheBlur() {
        // Visão normal (~0,5 mm) ainda recebe um palpite mínimo, para o teste ter o que comparar.
        assertEquals(0.05, VisionMath.focusRadiusEstimateMm(0.5), 1e-9)
        assertEquals(0.3, VisionMath.focusRadiusEstimateMm(2.0), 1e-9)
    }

    @Test
    fun logScaleRoundTrips() {
        val min = VisionMath.FOCUS_MIN_PX
        val max = VisionMath.FOCUS_MAX_PX
        assertEquals(min, VisionMath.logScale(0, 100, min, max), 1e-9)
        assertEquals(max, VisionMath.logScale(100, 100, min, max), 1e-9)
        for (level in 0..100) {
            assertEquals(level, VisionMath.logLevel(VisionMath.logScale(level, 100, min, max), 100, min, max))
        }
    }

    @Test
    fun fineLevelGoesFromWeakToStrong() {
        assertEquals(0, VisionMath.fineLevel(VisionMath.FINE_K_MAX))
        assertEquals(100, VisionMath.fineLevel(VisionMath.FINE_K_MIN))
        for (level in 0..100) assertEquals(level, VisionMath.fineLevel(VisionMath.fineK(level)))
    }

    @Test
    fun fontScaleOnlyWhenTheTextIsTooSmall() {
        // Texto comum com maiúscula de 1,6 mm.
        assertEquals(VisionMath.MIN_FONT_SCALE, VisionMath.recommendFontScale(0.5, 1.6), 1e-9)
        assertEquals(1.5, VisionMath.recommendFontScale(1.2, 1.6), 1e-9)
        assertEquals(VisionMath.MAX_FONT_SCALE, VisionMath.recommendFontScale(5.0, 1.6), 1e-9)
    }

    @Test
    fun bodyCapOnATypicalPhone() {
        // 2,75 de densidade a ~500 dpi (S23 Ultra no padrão): maiúscula de ~1,4 mm.
        assertEquals(1.37, VisionMath.bodyCapMm(density = 2.75, dpi = 500.0), 0.01)
    }

    @Test
    fun eyesDifferenceWarning() {
        assertTrue(VisionMath.eyesDifferALot(0.8, 1.6))
        assertFalse(VisionMath.eyesDifferALot(0.8, 1.2))
    }
}
