package net.ridgelight.core.weather

import net.ridgelight.core.geo.LatLon

/**
 * Cloud cover by height above sea level at one place and hour, from the forecast's pressure
 * levels: [heights] in metres (ascending), [cover] 0..1 at each.
 */
class CloudProfile(val heights: DoubleArray, val cover: DoubleArray) {
    init {
        require(heights.size == cover.size && heights.isNotEmpty())
    }

    /** Cover at [height], linear between levels; the lowest level's below it, none above the top. */
    fun coverAt(height: Double): Double {
        if (height <= heights.first()) return cover.first()
        if (height > heights.last()) return 0.0
        val k = heights.indexOfFirst { it >= height }
        val f = (height - heights[k - 1]) / (heights[k] - heights[k - 1])
        return cover[k - 1] + (cover[k] - cover[k - 1]) * f
    }
}

/**
 * One forecast hour at one place: [clouds] by height, and the total [cloudCover] (0..1, NaN if
 * missing) for when there is no profile.
 */
class WeatherHour(val timeUtc: Long, val cloudCover: Double, val clouds: CloudProfile?)

/** The hourly forecast for one place on the route, [distance] metres from the start. */
class ForecastPoint(val distance: Double, val position: LatLon, val hours: List<WeatherHour>)

/** Forecasts at a few places along a route; any place and time in between is looked up with [at]. */
class RouteForecast(val points: List<ForecastPoint>) {
    init {
        require(points.isNotEmpty()) { "No forecast points" }
    }

    /**
     * Clouds at [distance] metres along the route at [utcMillis]: the nearest forecast place,
     * interpolated between its hours. Null outside the forecast period.
     */
    fun at(distance: Double, utcMillis: Long): WeatherHour? {
        val p = points.minBy { kotlin.math.abs(it.distance - distance) }
        val hours = p.hours
        if (hours.isEmpty() || utcMillis < hours.first().timeUtc || utcMillis > hours.last().timeUtc) return null
        var j = hours.indexOfLast { it.timeUtc <= utcMillis }
        if (j == hours.size - 1) j-- // exactly the last hour: interpolate within the last interval
        return interpolate(hours[maxOf(j, 0)], hours[minOf(j + 1, hours.size - 1)], utcMillis)
    }

    /** First and last forecast hour (UTC), over all places. */
    val period: LongRange get() = points.minOf { it.hours.first().timeUtc }..points.maxOf { it.hours.last().timeUtc }

    private fun interpolate(a: WeatherHour, b: WeatherHour, t: Long): WeatherHour {
        if (a === b || b.timeUtc == a.timeUtc) return a
        val f = (t - a.timeUtc).toDouble() / (b.timeUtc - a.timeUtc)
        val cover = when {
            a.cloudCover.isNaN() -> b.cloudCover
            b.cloudCover.isNaN() -> a.cloudCover
            else -> a.cloudCover + (b.cloudCover - a.cloudCover) * f
        }
        return WeatherHour(t, cover, mixClouds(a.clouds, b.clouds, f))
    }

    private fun mixClouds(a: CloudProfile?, b: CloudProfile?, f: Double): CloudProfile? {
        if (a == null || b == null || a.heights.size != b.heights.size) return if (f < 0.5) a ?: b else b ?: a
        return CloudProfile(
            DoubleArray(a.heights.size) { a.heights[it] + (b.heights[it] - a.heights[it]) * f },
            DoubleArray(a.cover.size) { a.cover[it] + (b.cover[it] - a.cover[it]) * f },
        )
    }
}
