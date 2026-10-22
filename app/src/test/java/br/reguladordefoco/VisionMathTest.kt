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
        val stair = run(VisionMath.SIZES_MM.size)
        assertNull(stair.smallestPassed)
        assertNull(stair.thresholdMm)
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
    fun goodVisionNeedsNoChange() {
        // Texto comum com maiúscula de 1,6 mm; quem lê 0,5 mm não precisa de nada.
        val s = VisionMath.recommend(thresholdMm = 0.5, bodyCapMm = 1.6)
        assertEquals(VisionMath.MIN_FONT_SCALE, s.fontScale, 1e-9)
        assertEquals(1.0, s.zoom, 1e-9)
    }

    @Test
    fun moderateBlurOnlyGrowsLetters() {
        val s = VisionMath.recommend(thresholdMm = 1.2, bodyCapMm = 1.6)
        assertEquals(1.5, s.fontScale, 1e-9)
        assertEquals(1.0, s.zoom, 1e-9)
    }

    @Test
    fun strongBlurAddsZoomOnTopOfMaxLetters() {
        val s = VisionMath.recommend(thresholdMm = 2.4, bodyCapMm = 1.6)
        assertEquals(VisionMath.MAX_FONT_SCALE, s.fontScale, 1e-9)
        assertEquals(1.5, s.zoom, 1e-9)
    }

    @Test
    fun zoomIsCapped() {
        val s = VisionMath.recommend(thresholdMm = 20.0, bodyCapMm = 1.6)
        assertEquals(VisionMath.MAX_ZOOM, s.zoom, 1e-9)
    }

    @Test
    fun fineTuneAndNightScaleTheResult() {
        val base = VisionMath.recommend(1.0, 1.6)
        val more = VisionMath.recommend(1.0, 1.6, fineTune = 1.2, extra = VisionMath.NIGHT_EXTRA)
        assertEquals(base.fontScale * 1.2 * VisionMath.NIGHT_EXTRA, more.fontScale, 1e-9)
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
