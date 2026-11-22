package net.ridgelight.core

import net.ridgelight.core.gpx.IsoTime
import net.ridgelight.core.sun.SolarPosition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SolarPositionTest {
    private fun near(expected: Double, actual: Double, tol: Double, what: String) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected ± $tol, got $actual")

    /** Worked example from NREL's Solar Position Algorithm report (Reda & Andreas, 2008), table A4.1. */
    @Test
    fun matchesNrelSpaExample() {
        val t = IsoTime.parse("2003-10-17T12:30:30-07:00")!!
        val sun = SolarPosition.at(t, 39.742476, -105.1786)
        near(194.34024, sun.azimuth, 0.05, "azimuth")
        // SPA's 50.11162° topocentric zenith includes refraction at 820 hPa; ours assumes 1010 hPa.
        near(90 - 50.11162, sun.apparentAltitude, 0.03, "apparent altitude")
    }

    @Test
    fun overheadAtTropicOnJuneSolstice() {
        val day = IsoTime.parse("2024-06-20T00:00:00Z")!!
        val max = (0 until 24 * 60).maxOf { SolarPosition.at(day + it * 60_000L, 23.44, 0.0).altitude }
        near(90.0, max, 0.1, "max altitude")
    }

    @Test
    fun equinoxSunriseIsDueEast() {
        val day = IsoTime.parse("2025-03-20T00:00:00Z")!!
        val sunrise = (0 until 24 * 60).map { day + it * 60_000L }.first { SolarPosition.at(it, 0.0, 0.0).altitude > 0 }
        near(90.0, SolarPosition.at(sunrise, 0.0, 0.0).azimuth, 0.5, "azimuth at sunrise")
        // Sunrise at 0°/0° on the equinox is around 06:00 UTC, shifted by the equation of time (~7 min).
        near(6 * 60.0 + 7, (sunrise - day) / 60_000.0, 4.0, "sunrise minute")
    }

    @Test
    fun refractionIsAboutHalfADegreeAtTheHorizon() {
        near(0.48, SolarPosition.refraction(0.0), 0.03, "refraction at 0°")
        near(0.016, SolarPosition.refraction(45.0), 0.003, "refraction at 45°")
    }
}
