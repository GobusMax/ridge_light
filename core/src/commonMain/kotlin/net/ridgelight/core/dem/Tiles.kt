package net.ridgelight.core.dem

import net.ridgelight.core.geo.DEG
import net.ridgelight.core.geo.EARTH_RADIUS_M
import kotlin.math.PI
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/** XYZ ("slippy map") tile address in Web-Mercator, y counted from the north. */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    fun packed(): Long = pack(z, x, y)

    override fun toString() = "$z/$x/$y"

    companion object {
        fun pack(z: Int, x: Int, y: Int): Long = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()
    }
}

object WebMercator {
    private const val MAX_LAT = 85.05112878

    private val MAX_SIN_LAT = sin(MAX_LAT * DEG)

    /** Normalised x (0 = 180° W, 1 = 180° E) of a longitude. */
    fun x(lon: Double): Double = (lon + 180.0) / 360.0

    /** Normalised y (0 = north edge, 1 = south edge) of a latitude. */
    fun y(lat: Double): Double {
        val phi = lat.coerceIn(-MAX_LAT, MAX_LAT) * DEG
        return (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0
    }

    /** Normalised y from the sine of the latitude; cheaper than [y] when the sine is at hand. */
    fun yOfSinLat(sinLat: Double): Double = 0.5 - atanh(sinLat.coerceIn(-MAX_SIN_LAT, MAX_SIN_LAT)) / (2 * PI)

    /** Fractional tile column of a longitude at zoom [z]. */
    fun tileX(lon: Double, z: Int): Double = x(lon) * (1 shl z)

    /** Fractional tile row of a latitude at zoom [z]. */
    fun tileY(lat: Double, z: Int): Double = y(lat) * (1 shl z)

    /** Ground size in metres of one pixel of a [tileSize]-pixel tile at [lat]. */
    fun pixelSize(lat: Double, z: Int, tileSize: Int = 256): Double =
        2 * PI * EARTH_RADIUS_M * cos(lat * DEG) / ((1 shl z) * tileSize)

    fun wrapX(x: Int, z: Int): Int {
        val n = 1 shl z
        return ((x % n) + n) % n
    }

    fun tileAt(lat: Double, lon: Double, z: Int): TileKey =
        TileKey(z, wrapX(floor(tileX(lon, z)).toInt(), z), floor(tileY(lat, z)).toInt().coerceIn(0, (1 shl z) - 1))
}

/**
 * Mapzen/Tilezen "Terrarium" encoding: height = R*256 + G + B/256 - 32768 metres.
 * See https://github.com/tilezen/joerd/blob/master/docs/formats.md
 */
object Terrarium {
    const val DEFAULT_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    const val ATTRIBUTION = "Terrain Tiles: Mapzen/Tilezen on AWS Open Data (SRTM, GMTED, ETOPO1, NED and others)"

    fun url(key: TileKey, template: String = DEFAULT_URL): String =
        template.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())

    fun decode(r: Int, g: Int, b: Int): Float = (r * 256 + g + b / 256f) - 32768f

    /**
     * Decodes ARGB pixels (as returned by Android's Bitmap.getPixels or Java's BufferedImage.getRGB)
     * to heights. Fully transparent pixels mean "no data" and become NaN.
     */
    fun decodeArgb(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val p = argb[i]
        if ((p ushr 24) == 0) Float.NaN else decode((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }
}
