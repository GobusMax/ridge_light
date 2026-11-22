package net.ridgelight.core.dem

import kotlin.math.floor

/** One square elevation tile; heights in metres, row-major from the north-west corner, NaN = no data. */
class ElevationTile(val key: TileKey, val heights: FloatArray) {
    val size: Int = kotlin.math.sqrt(heights.size.toDouble()).toInt()

    init {
        require(size * size == heights.size) { "Tile $key is not square (${heights.size} values)" }
    }
}

/**
 * Terrain heights from in-memory tiles. Loading tiles is the platform's job (HTTP, disk cache,
 * Android bitmaps…); this class only samples what it is given, so it never blocks on I/O.
 *
 * Each lookup uses the finest zoom level whose tiles are present at that location and falls back
 * to coarser ones, which is how a fine ring of tiles around the route and a coarse ring for the
 * distant horizon fit together.
 *
 * Immutable and thread-safe. Lookups are on the hot path of every horizon ray, so tiles sit in a
 * dense grid per zoom level instead of a hash map.
 */
class TerrainModel(tiles: Collection<ElevationTile>) {
    val tileSize: Int
    val tileCount: Int = tiles.size
    private val levels: Array<Level>

    init {
        require(tiles.isNotEmpty()) { "No terrain tiles" }
        tileSize = tiles.first().size
        require(tiles.all { it.size == tileSize }) { "Mixed tile sizes are not supported" }
        levels = tiles.groupBy { it.key.z }.entries
            .sortedByDescending { it.key }
            .map { (z, list) -> Level.of(z, tileSize, list) }
            .toTypedArray()
    }

    /** Kept for callers that used to need a per-thread copy; the model is now thread-safe. */
    fun view(): TerrainModel = this

    val finestZoom: Int get() = levels.first().z

    /** Bilinear terrain height in metres, or NaN where no tile covers the location. */
    fun height(lat: Double, lon: Double): Double = heightMercator(WebMercator.x(lon), WebMercator.y(lat))

    /** Like [height], at normalised Web-Mercator coordinates ([WebMercator.x], [WebMercator.y]). */
    fun heightMercator(x: Double, y: Double): Double {
        for (level in levels) {
            val h = level.bilinear(x, y)
            if (!h.isNaN()) return h
        }
        return Double.NaN
    }

    fun heightAtZoom(lat: Double, lon: Double, z: Int): Double =
        levels.firstOrNull { it.z == z }?.bilinear(WebMercator.x(lon), WebMercator.y(lat)) ?: Double.NaN

    /** The tiles of one zoom level in a grid over their bounding box (which may cross 180°). */
    private class Level(
        val z: Int,
        private val size: Int,
        private val x0: Int,
        private val y0: Int,
        private val width: Int,
        private val height: Int,
        private val grid: Array<FloatArray?>,
    ) {
        private val n = 1 shl z
        private val worldPixels = n.toDouble() * size

        fun tile(tx: Int, ty: Int): FloatArray? {
            val dy = ty - y0
            if (dy < 0 || dy >= height) return null
            val dx = (tx - x0).mod(n)
            if (dx >= width) return null
            return grid[dy * width + dx]
        }

        fun bilinear(x: Double, y: Double): Double {
            // Pixel centres sit at +0.5, hence the shift before flooring.
            val u = x * worldPixels - 0.5
            val v = y * worldPixels - 0.5
            val ix = floor(u).toInt()
            val iy = floor(v).toInt()
            val fx = u - ix
            val fy = v - iy
            val tx = ix.floorDiv(size)
            val ty = iy.floorDiv(size)
            val lx = ix - tx * size
            val ly = iy - ty * size
            val a: Double
            val b: Double
            val c: Double
            val d: Double
            if (lx < size - 1 && ly < size - 1) {
                // All four neighbours in one tile: the common case.
                val t = tile(tx, ty) ?: return Double.NaN
                val o = ly * size + lx
                a = t[o].toDouble()
                b = t[o + 1].toDouble()
                c = t[o + size].toDouble()
                d = t[o + size + 1].toDouble()
            } else {
                a = pixel(ix, iy)
                if (a.isNaN()) return a
                b = pixel(ix + 1, iy)
                c = pixel(ix, iy + 1)
                d = pixel(ix + 1, iy + 1)
            }
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            return top + (bottom - top) * fy
        }

        private fun pixel(gx: Int, gy: Int): Double {
            val tx = gx.floorDiv(size)
            val ty = gy.floorDiv(size)
            val t = tile(tx, ty) ?: return Double.NaN
            return t[(gy - ty * size) * size + (gx - tx * size)].toDouble()
        }

        companion object {
            fun of(z: Int, size: Int, tiles: List<ElevationTile>): Level {
                val n = 1 shl z
                // Smallest x range covering all columns, allowing it to wrap around 180°: it starts
                // right after the widest gap between occupied columns.
                val xs = tiles.map { WebMercator.wrapX(it.key.x, z) }.distinct().sorted()
                var widest = -1
                var gapEnd = xs.first()
                for (i in xs.indices) {
                    val next = if (i + 1 < xs.size) xs[i + 1] else xs.first() + n
                    if (next - xs[i] > widest) {
                        widest = next - xs[i]
                        gapEnd = next.mod(n)
                    }
                }
                val x0 = gapEnd
                val width = n - widest + 1
                val y0 = tiles.minOf { it.key.y }
                val height = tiles.maxOf { it.key.y } - y0 + 1
                val grid = arrayOfNulls<FloatArray>(width * height)
                for (t in tiles) grid[(t.key.y - y0) * width + (WebMercator.wrapX(t.key.x, z) - x0).mod(n)] = t.heights
                return Level(z, size, x0, y0, width, height, grid)
            }
        }
    }
}
