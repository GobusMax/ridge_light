package net.ridgelight.core.weather

import net.ridgelight.core.geo.LatLon

/**
 * One forecast hour at one place. Fractions are 0..1; NaN where the forecast has no value.
 *
 * @property temperature °C at the place's elevation.
 * @property precipitation mm in the preceding hour.
 * @property weatherCode WMO code (see [WeatherCodes]), -1 if unknown.
 * @property windSpeed km/h at 10 m; [windGusts] likewise.
 */
class WeatherHour(
    val timeUtc: Long,
    val temperature: Double,
    val precipitationProbability: Double,
    val precipitation: Double,
    val weatherCode: Int,
    val cloudCover: Double,
    val cloudLow: Double,
    val cloudMid: Double,
    val cloudHigh: Double,
    val windSpeed: Double,
    val windGusts: Double,
)

/** The hourly forecast for one place on the route, [distance] metres from the start. */
class ForecastPoint(val distance: Double, val position: LatLon, val elevation: Double, val hours: List<WeatherHour>)

/** Forecasts at a few places along a route; any place and time in between is looked up with [at]. */
class RouteForecast(val points: List<ForecastPoint>) {
    init {
        require(points.isNotEmpty()) { "No forecast points" }
    }

    /**
     * Weather at [distance] metres along the route at [utcMillis]: the nearest forecast place,
     * interpolated between its hours. With [elevation], the temperature is moved from the
     * place's elevation to it by the standard lapse rate (0.65 °C per 100 m). Null outside the
     * forecast period.
     */
    fun at(distance: Double, utcMillis: Long, elevation: Double = Double.NaN): WeatherHour? {
        val p = points.minBy { kotlin.math.abs(it.distance - distance) }
        val hours = p.hours
        if (hours.isEmpty() || utcMillis < hours.first().timeUtc || utcMillis > hours.last().timeUtc) return null
        var j = hours.indexOfLast { it.timeUtc <= utcMillis }
        if (j == hours.size - 1) j-- // exactly the last hour: interpolate within the last interval
        val h = interpolate(hours[maxOf(j, 0)], hours[minOf(j + 1, hours.size - 1)], utcMillis)
        val dz = elevation - p.elevation
        if (!dz.isFinite()) return h
        return h.copy(temperature = h.temperature - LAPSE_RATE * dz)
    }

    /** Expected share of direct sun from clouds at a place and time (see [SkyCover]), or null. */
    fun clearSky(distance: Double, utcMillis: Long): Double? =
        at(distance, utcMillis)?.let { SkyCover.clearFraction(it) }?.takeIf { it.isFinite() }

    private fun interpolate(a: WeatherHour, b: WeatherHour, t: Long): WeatherHour {
        if (a === b || b.timeUtc == a.timeUtc) return a
        val f = (t - a.timeUtc).toDouble() / (b.timeUtc - a.timeUtc)
        fun mix(x: Double, y: Double) = when {
            x.isNaN() -> y
            y.isNaN() -> x
            else -> x + (y - x) * f
        }
        return WeatherHour(
            t,
            mix(a.temperature, b.temperature),
            mix(a.precipitationProbability, b.precipitationProbability),
            mix(a.precipitation, b.precipitation),
            if (f < 0.5) a.weatherCode else b.weatherCode,
            mix(a.cloudCover, b.cloudCover),
            mix(a.cloudLow, b.cloudLow),
            mix(a.cloudMid, b.cloudMid),
            mix(a.cloudHigh, b.cloudHigh),
            mix(a.windSpeed, b.windSpeed),
            mix(a.windGusts, b.windGusts),
        )
    }

    private fun WeatherHour.copy(temperature: Double) = WeatherHour(
        timeUtc, temperature, precipitationProbability, precipitation, weatherCode,
        cloudCover, cloudLow, cloudMid, cloudHigh, windSpeed, windGusts,
    )

    companion object {
        const val LAPSE_RATE = 0.0065
    }
}

/**
 * How much direct sun clouds let through, as the expected share of time the sun is out.
 * Low and mid-level clouds block it; high clouds are often thin enough to see the sun through,
 * so they count half. Layers are assumed to overlap at random. Forecasts give cover per grid
 * cell, so this is a probability, not a promise for a given minute.
 */
object SkyCover {
    fun clearFraction(h: WeatherHour): Double {
        if (h.cloudLow.isNaN() || h.cloudMid.isNaN() || h.cloudHigh.isNaN()) return 1 - h.cloudCover
        return (1 - h.cloudLow) * (1 - h.cloudMid) * (1 - h.cloudHigh / 2)
    }
}

/** WMO weather interpretation codes, as used by Open-Meteo. */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Rain showers"
        82 -> "Violent rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}
