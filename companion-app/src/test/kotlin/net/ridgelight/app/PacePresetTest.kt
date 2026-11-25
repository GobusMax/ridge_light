package net.ridgelight.app

import net.ridgelight.app.ui.PacePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacePresetTest {
    @Test
    fun presetsAreOrderedFromFastToSlow() {
        val factors = PacePreset.entries.map { it.factor }
        assertEquals(factors.sorted(), factors)
        assertEquals(1.0, PacePreset.SIGNS.factor, "the hiking signs are the reference")
        assertTrue(PacePreset.entries.all { it.factor in 0.6..1.6 }, "within the slider")
    }

    @Test
    fun aFactorFindsItsPreset() {
        assertEquals(PacePreset.RELAXED, PacePreset.of(1.2))
        assertEquals(PacePreset.RELAXED, PacePreset.of(1.2000001))
        assertNull(PacePreset.of(1.1), "between presets")
    }

    @Test
    fun hikeTimeScalesWalkingButNotBreaks() {
        // Four hours of walking at the signs' pace, half an hour of breaks.
        assertEquals(4 * 3600 * 1.2 + 1800, PacePreset.RELAXED.hikeSeconds(4 * 3600.0, 1800.0), 1e-9)
        assertEquals(4 * 3600 * 0.75 + 1800, PacePreset.FAST.hikeSeconds(4 * 3600.0, 1800.0), 1e-9)
    }
}
