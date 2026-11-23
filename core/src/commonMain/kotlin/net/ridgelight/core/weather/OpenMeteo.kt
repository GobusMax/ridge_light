package net.ridgelight.core.weather

import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.route.SampledRoute
import kotlin.math.ceil

class WeatherException(message: String) : Exception(message)

/**
 * Hourly forecasts from Open-Meteo (https://open-meteo.com): free, no key, up to 16 days ahead
 * and about three months back. Requests carry the coordinates of a few places on the route.
 * The platform fetches [url]; this object only builds the request and reads the answer.
 */
object OpenMeteo {
    const val ATTRIBUTION = "Weather data by Open-Meteo.com (CC BY 4.0)"
    const val FORECAST_DAYS = 16
    const val PAST_DAYS = 92

    private const val HOURLY = "temperature_2m,precipitation_probability,precipitation,weather_code," +
        "cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,wind_speed_10m,wind_gusts_10m"

    /** A place to forecast for: [distance] metres along the route. */
    class Place(val distance: Double, val position: LatLon, val elevation: Double)

    /**
     * Places about every [spacing] metres along the route, start and end included, at most
     * [maxPoints]. Their elevations are sent along so that temperatures fit the route rather
     * than the forecast model's smoothed terrain.
     */
    fun places(route: SampledRoute, spacing: Double = 2_000.0, maxPoints: Int = 12): List<Place> {
        val nodes = route.nodes
        val length = nodes.last().distance
        val count = (ceil(length / spacing).toInt() + 1).coerceIn(2, maxPoints)
        return List(count) { k ->
            val d = length * k / (count - 1)
            val node = nodes.minBy { kotlin.math.abs(it.distance - d) }
            val elevation = if (node.elevation.isFinite()) node.elevation else node.ground
            Place(node.distance, node.position, elevation)
        }
    }

    /** Hourly forecast for [places] over UTC days [startDate]..[endDate] (ISO dates, e.g. 2026-11-22). */
    fun url(places: List<Place>, startDate: String, endDate: String): String = buildString {
        append("https://api.open-meteo.com/v1/forecast")
        append("?latitude=").append(places.joinToString(",") { fmt(it.position.lat) })
        append("&longitude=").append(places.joinToString(",") { fmt(it.position.lon) })
        append("&elevation=").append(places.joinToString(",") { if (it.elevation.isFinite()) it.elevation.toInt().toString() else "nan" })
        append("&hourly=").append(HOURLY)
        append("&timezone=GMT&timeformat=unixtime&wind_speed_unit=kmh")
        append("&start_date=").append(startDate).append("&end_date=").append(endDate)
    }

    /** Reads Open-Meteo's answer for the [places] the request was built from. */
    fun parse(json: String, places: List<Place>): RouteForecast {
        val root = try {
            Json.parse(json)
        } catch (e: Json.SyntaxError) {
            throw WeatherException("Unreadable weather data (${e.message})")
        }
        (root as? Map<*, *>)?.let { if (it["error"] == true) throw WeatherException(it["reason"] as? String ?: "Weather request failed") }
        val results = when (root) {
            is List<*> -> root
            is Map<*, *> -> listOf(root)
            else -> throw WeatherException("Unexpected weather data")
        }
        if (results.size != places.size) throw WeatherException("Expected ${places.size} forecasts, got ${results.size}")
        return RouteForecast(places.mapIndexed { i, place -> ForecastPoint(place.distance, place.position, place.elevation, hours(results[i])) })
    }

    private fun hours(result: Any?): List<WeatherHour> {
        val hourly = (result as? Map<*, *>)?.get("hourly") as? Map<*, *> ?: throw WeatherException("No hourly forecast")
        fun series(name: String) = (hourly[name] as? List<*>).orEmpty()
        val time = series("time")
        fun at(name: String, i: Int, scale: Double = 1.0): Double = ((series(name).getOrNull(i) as? Double) ?: Double.NaN) * scale
        return time.indices.mapNotNull { i ->
            val t = time[i] as? Double ?: return@mapNotNull null
            WeatherHour(
                timeUtc = (t * 1000).toLong(),
                temperature = at("temperature_2m", i),
                precipitationProbability = at("precipitation_probability", i, 0.01),
                precipitation = at("precipitation", i),
                weatherCode = (series("weather_code").getOrNull(i) as? Double)?.toInt() ?: -1,
                cloudCover = at("cloud_cover", i, 0.01),
                cloudLow = at("cloud_cover_low", i, 0.01),
                cloudMid = at("cloud_cover_mid", i, 0.01),
                cloudHigh = at("cloud_cover_high", i, 0.01),
                windSpeed = at("wind_speed_10m", i),
                windGusts = at("wind_gusts_10m", i),
            )
        }
    }

    private fun fmt(v: Double): String {
        val scaled = kotlin.math.round(v * 10_000).toLong()
        val sign = if (scaled < 0) "-" else ""
        val abs = kotlin.math.abs(scaled)
        return "$sign${abs / 10_000}.${(abs % 10_000).toString().padStart(4, '0')}"
    }
}
