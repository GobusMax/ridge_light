package net.ridgelight.dem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.ridgelight.core.dem.ElevationTile
import net.ridgelight.core.dem.Terrarium
import net.ridgelight.core.dem.TileKey
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Terrarium elevation tiles on Android: a persistent on-disk cache (kept in app files rather than
 * the system cache, so tiles fetched at home stay available offline in the mountains), parallel
 * downloads for what is missing, and PNG decoding to heights.
 */
class TerrainTileCache(
    context: Context,
    private val urlTemplate: String = Terrarium.DEFAULT_URL,
    private val maxCacheBytes: Long = 500L shl 20,
    private val parallelDownloads: Int = 8,
    val directory: File = File(context.filesDir, "terrain/terrarium"),
) {
    class Result(val tiles: List<ElevationTile>, val missing: List<TileKey>, val downloaded: Int)

    fun interface Progress {
        fun update(done: Int, total: Int)
    }

    private fun file(key: TileKey) = File(directory, "${key.z}/${key.x}/${key.y}.png")

    /** Tiles of [keys] that are not cached yet, i.e. what an offline analysis would lack. */
    fun notCached(keys: Collection<TileKey>): List<TileKey> = keys.filterNot { file(it).isFile }

    /** Loads [keys]: cached tiles from disk, the rest from the network unless [offline]. */
    suspend fun load(keys: Collection<TileKey>, offline: Boolean = false, progress: Progress = Progress { _, _ -> }): Result =
        withContext(Dispatchers.IO) {
            val done = AtomicInteger()
            val downloaded = AtomicInteger()
            val gate = Semaphore(parallelDownloads)
            val results = coroutineScope {
                keys.map { key ->
                    async {
                        val f = file(key)
                        val bytes = if (f.isFile) {
                            f.setLastModified(System.currentTimeMillis())
                            f.readBytes()
                        } else if (!offline) {
                            gate.withPermit { fetch(key) }?.also {
                                store(f, it)
                                downloaded.incrementAndGet()
                            }
                        } else {
                            null
                        }
                        progress.update(done.incrementAndGet(), keys.size)
                        key to bytes?.let { decode(key, it) }
                    }
                }.awaitAll()
            }
            if (downloaded.get() > 0) trim()
            Result(results.mapNotNull { it.second }, results.filter { it.second == null }.map { it.first }, downloaded.get())
        }

    fun sizeBytes(): Long = directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        directory.deleteRecursively()
    }

    private suspend fun fetch(key: TileKey): ByteArray? {
        repeat(3) { attempt ->
            try {
                val conn = URL(Terrarium.url(key, urlTemplate)).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "RidgeLight-Android")
                try {
                    when (conn.responseCode) {
                        200 -> return conn.inputStream.use { it.readBytes() }
                        404 -> return null
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: IOException) {
                // retried below
            }
            delay(500L * (attempt + 1))
        }
        return null
    }

    private fun store(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) tmp.delete()
    }

    /** Drops least recently used tiles beyond [maxCacheBytes]. */
    private fun trim() {
        val files = directory.walkBottomUp().filter { it.isFile && it.name.endsWith(".png") }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= maxCacheBytes) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        fun decode(key: TileKey, png: ByteArray): ElevationTile? {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
                inScaled = false
            }
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return null
            try {
                if (bmp.width != bmp.height) return null
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                return ElevationTile(key, Terrarium.decodeArgb(px))
            } finally {
                bmp.recycle()
            }
        }
    }
}
