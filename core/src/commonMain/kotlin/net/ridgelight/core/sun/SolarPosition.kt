package net.ridgelight.core.sun

import net.ridgelight.core.geo.DEG
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun position seen from a place on Earth.
 *
 * @property azimuth degrees clockwise from true north.
 * @property altitude geometric altitude of the sun's centre, degrees, no refraction.
 * @property apparentAltitude altitude including standard atmospheric refraction.
 */
data class SunPosition(val azimuth: Double, val altitude: Double, val apparentAltitude: Double)

/**
 * NOAA solar calculator algorithm (after Meeus, "Astronomical Algorithms"). Good to about
 * 0.01 degrees for years 1800–2100, far better than the terrain data it is compared against.
 */
object SolarPosition {
    private const val MS_PER_DAY = 86_400_000.0
    private const val JD_UNIX_EPOCH = 2_440_587.5

    fun at(utcMillis: Long, lat: Double, lon: Double): SunPosition {
        val jd = utcMillis / MS_PER_DAY + JD_UNIX_EPOCH
        val t = (jd - 2_451_545.0) / 36_525.0

        val meanLon = norm360(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val meanAnom = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val ecc = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val m = meanAnom * DEG
        val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val trueLon = meanLon + center
        val omega = 125.04 - 1934.136 * t
        val appLon = trueLon - 0.00569 - 0.00478 * sin(omega * DEG)
        val meanObliq = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliq = meanObliq + 0.00256 * cos(omega * DEG)
        val decl = asin(sin(obliq * DEG) * sin(appLon * DEG))

        val y = tan(obliq * DEG / 2).let { it * it }
        val l0 = meanLon * DEG
        val eqTimeMin = 4.0 / DEG * (
            y * sin(2 * l0) - 2 * ecc * sin(m) + 4 * ecc * y * sin(m) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) - 1.25 * ecc * ecc * sin(2 * m)
            )

        val minutesOfDay = ((utcMillis % MS_PER_DAY.toLong() + MS_PER_DAY.toLong()) % MS_PER_DAY.toLong()) / 60_000.0
        val trueSolarTime = norm(minutesOfDay + eqTimeMin + 4 * lon, 1440.0)
        val hourAngle = (trueSolarTime / 4.0 - 180.0) * DEG
        val phi = lat * DEG

        val cosZenith = (sin(phi) * sin(decl) + cos(phi) * cos(decl) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        val zenith = acos(cosZenith)
        val altitude = 90.0 - zenith / DEG
        val azimuth = norm360(
            atan2(sin(hourAngle), cos(hourAngle) * sin(phi) - tan(decl) * cos(phi)) / DEG + 180.0,
        )
        return SunPosition(azimuth, altitude, altitude + refraction(altitude))
    }

    /**
     * Standard atmospheric refraction in degrees for a geometric altitude (Saemundsson, 10 °C,
     * 1010 hPa). Clamped below -2 degrees, where the sun is out of sight anyway.
     */
    fun refraction(altitudeDeg: Double): Double {
        if (altitudeDeg < -2.0) return refraction(-2.0)
        if (altitudeDeg > 89.9) return 0.0
        val h = altitudeDeg
        return 1.02 / tan((h + 10.3 / (h + 5.11)) * DEG) / 60.0
    }

    private fun norm360(x: Double) = norm(x, 360.0)
    private fun norm(x: Double, m: Double) = x - floor(x / m) * m
}

/** Sunrise and sunset over flat ground (standard −0.833° altitude of the sun's centre). */
object SunTimes {
    data class Day(val sunriseUtc: Long?, val sunsetUtc: Long?)

    /** Scans the 24 hours from [fromUtc] in [stepSeconds] steps; null means no rise or set in that window. */
    fun day(lat: Double, lon: Double, fromUtc: Long, stepSeconds: Int = 60): Day {
        var rise: Long? = null
        var set: Long? = null
        var prev = SolarPosition.at(fromUtc, lat, lon).altitude > -0.833
        var t = fromUtc
        val end = fromUtc + 86_400_000L
        while (t < end) {
            t += stepSeconds * 1000L
            val up = SolarPosition.at(t, lat, lon).altitude > -0.833
            if (up && !prev && rise == null) rise = t
            if (!up && prev) set = t
            prev = up
        }
        return Day(rise, set)
    }
}
