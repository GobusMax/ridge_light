package net.ridgelight.cli

import net.ridgelight.core.dem.ElevationTile
import net.ridgelight.core.dem.Terrarium
import net.ridgelight.core.dem.TileKey
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Loads Terrarium tiles: first from fixture directories (`{z}-{x}-{y}.png`, as shipped with the
 * web prototype), then from the disk cache (`{z}/{x}/{y}.png`), then over HTTP into the cache.
 */
class TileStore(
    private val cacheDir: Path,
    private val fixtureDirs: List<Path> = emptyList(),
    private val urlTemplate: String = Terrarium.DEFAULT_URL,
    private val offline: Boolean = false,
) {
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build() }

    class Loaded(val tiles: List<ElevationTile>, val missing: List<TileKey>, val downloaded: Int)

    fun load(keys: Collection<TileKey>, threads: Int = 12, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Loaded {
        val pool = Executors.newFixedThreadPool(threads)
        val done = AtomicInteger()
        val downloaded = AtomicInteger()
        try {
            val futures = keys.map { key ->
                pool.submit<ElevationTile?> {
                    val bytes = read(key) ?: if (offline) null else fetch(key)?.also { downloaded.incrementAndGet() }
                    progress(done.incrementAndGet(), keys.size)
                    bytes?.let { decode(key, it) }
                }
            }
            val results = keys.zip(futures.map { it.get() })
            return Loaded(results.mapNotNull { it.second }, results.filter { it.second == null }.map { it.first }, downloaded.get())
        } finally {
            pool.shutdown()
        }
    }

    private fun read(key: TileKey): ByteArray? {
        for (dir in fixtureDirs) {
            val f = dir.resolve("${key.z}-${key.x}-${key.y}.png")
            if (f.exists()) return f.readBytes()
        }
        val cached = cachePath(key)
        return if (cached.exists()) cached.readBytes() else null
    }

    private fun cachePath(key: TileKey): Path = cacheDir.resolve("${key.z}/${key.x}/${key.y}.png")

    private fun fetch(key: TileKey): ByteArray? {
        repeat(3) { attempt ->
            try {
                val req = HttpRequest.newBuilder(URI(Terrarium.url(key, urlTemplate)))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "ridge-light-cli")
                    .build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                if (res.statusCode() == 200) {
                    val path = cachePath(key)
                    Files.createDirectories(path.parent)
                    val tmp = Files.createTempFile(path.parent, "tile", ".part")
                    Files.write(tmp, res.body())
                    Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    return res.body()
                }
                if (res.statusCode() == 404) return null
            } catch (e: Exception) {
                if (attempt == 2) System.err.println("Tile $key failed: ${e.message}")
            }
            Thread.sleep(500L * (attempt + 1))
        }
        return null
    }

    companion object {
        fun decode(key: TileKey, png: ByteArray): ElevationTile? {
            val img = ImageIO.read(ByteArrayInputStream(png)) ?: return null
            val w = img.width
            require(w == img.height) { "Tile $key is not square" }
            val argb = img.getRGB(0, 0, w, w, null, 0, w)
            return ElevationTile(key, Terrarium.decodeArgb(argb))
        }

        fun defaultCacheDir(): Path {
            val base = System.getenv("XDG_CACHE_HOME")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".cache")
            return base.resolve("ridgelight/terrarium")
        }
    }
}
