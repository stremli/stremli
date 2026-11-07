package com.stremli.tv

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade

/** Memory classes. LOW: 1-1.5 GB (Fire TV Stick Lite, 3rd gen, first 4K). MID: 2 GB (4K and 4K Max sticks). HIGH: more. */
enum class Tier { LOW, MID, HIGH }

/**
 * Cache and buffer sizes for this device, chosen once from its RAM and free storage.
 *
 * Metadata is always cached on disk (SQLite): it costs little RAM, and fetching and parsing it
 * again is slower on a stick's CPU than reading it back. What changes per device is how much of
 * it is kept, how many parsed titles stay in RAM, and the image caches, which are the largest
 * use of memory. Video is never cached; only the playback buffer is sized here.
 */
class DeviceProfile(
    val tier: Tier,
    /** Decoded images kept in RAM. */
    val imageMemoryBytes: Long,
    /** Downloaded images kept on disk. */
    val imageDiskBytes: Long,
    /** Fetched title details, episode lists and Kuroiru answers kept in SQLite. */
    val metadataDiskBytes: Long,
    /** Parsed titles kept in RAM, counted in episodes (a title with 500 episodes weighs 500). */
    val detailMemoryEpisodes: Int,
    /** SQLite page cache, in KiB. */
    val sqlitePageCacheKb: Int,
    val videoBufferBytes: Int,
    val videoMaxBufferMs: Int,
    /** Decode opaque images as 16-bit (half the memory). */
    val rgb565: Boolean,
    /** Decode full-width key art at 1280x720 instead of screen size. */
    val smallBackdrops: Boolean,
    /** Drop decoded images when the player starts, leaving RAM to the video decoder. */
    val clearImagesForPlayback: Boolean
) {
    val lowRam get() = tier == Tier.LOW

    companion object {
        private const val MB = 1L shl 20

        @Volatile private var current: DeviceProfile? = null

        fun of(context: Context): DeviceProfile = current ?: synchronized(this) {
            current ?: measure(context.applicationContext).also {
                current = it
                android.util.Log.i("DeviceProfile", "tier=${it.tier} images=${it.imageMemoryBytes / MB}MB/${it.imageDiskBytes / MB}MB metadata=${it.metadataDiskBytes / MB}MB details=${it.detailMemoryEpisodes} video=${it.videoBufferBytes / MB}MB")
            }
        }

        private fun measure(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val tier = when {
                am.isLowRamDevice || info.totalMem < 1_600 * MB -> Tier.LOW
                info.totalMem < 3_000 * MB -> Tier.MID
                else -> Tier.HIGH
            }
            // Per-app heap class (MB); the image memory cache is a share of it, capped per tier.
            val heap = am.memoryClass * MB
            val imageMemory = when (tier) {
                Tier.LOW -> minOf(heap / 10, 20 * MB)
                Tier.MID -> minOf(heap / 7, 40 * MB)
                Tier.HIGH -> minOf(heap / 5, 64 * MB)
            }
            // Fire TV sticks have 8 GB of storage that is often nearly full: shrink disk caches when
            // space is short, down to a minimal metadata cache when it is critical.
            val free = context.cacheDir.usableSpace
            val storage = when {
                free < 300 * MB -> 0.1
                free < 1_000 * MB -> 0.5
                else -> 1.0
            }
            fun disk(bytes: Long, floor: Long) = maxOf((bytes * storage).toLong(), floor)
            return when (tier) {
                Tier.LOW -> DeviceProfile(
                    tier, imageMemory, disk(48 * MB, 8 * MB), disk(12 * MB, 2 * MB), 2_500, 512,
                    12 shl 20, 30_000, rgb565 = true, smallBackdrops = true, clearImagesForPlayback = true
                )
                Tier.MID -> DeviceProfile(
                    tier, imageMemory, disk(96 * MB, 16 * MB), disk(32 * MB, 4 * MB), 8_000, 1_024,
                    48 shl 20, 40_000, rgb565 = false, smallBackdrops = false, clearImagesForPlayback = false
                )
                Tier.HIGH -> DeviceProfile(
                    tier, imageMemory, disk(160 * MB, 16 * MB), disk(64 * MB, 4 * MB), 20_000, 2_048,
                    64 shl 20, 50_000, rgb565 = false, smallBackdrops = false, clearImagesForPlayback = false
                )
            }
        }
    }
}

/**
 * The video buffer. It depends on the hardware, so it is set for this TV rather than per profile.
 * Auto is [DeviceProfile.videoBufferBytes]; the sizes offered stop where the buffer, which lives in
 * the Java heap, would crowd out the app.
 */
object VideoBuffer {
    private const val KEY = "videoBufferMb"
    private val SIZES = listOf(8, 12, 16, 24, 32, 48, 64, 96)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("stremli-device", 0)

    /** The size picked in Settings, in MB; 0 is Auto. */
    fun chosenMb(context: Context): Int = prefs(context).getInt(KEY, 0)
    fun choose(context: Context, mb: Int) = prefs(context).edit().putInt(KEY, mb).apply()

    fun bytes(context: Context): Int = chosenMb(context).let { if (it > 0) it shl 20 else DeviceProfile.of(context).videoBufferBytes }
    fun recommendedMb(context: Context): Int = DeviceProfile.of(context).videoBufferBytes shr 20

    /** About how long [mb] lasts: 1080p here runs near 5 Mbps, so 1.6 s per MB. */
    fun seconds(mb: Int): Int = mb * 8 / 5

    fun range(tier: Tier): IntRange = when (tier) { Tier.LOW -> 8..16; Tier.MID -> 24..48; Tier.HIGH -> 48..96 }

    fun sizes(context: Context): List<Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tierMax = when (DeviceProfile.of(context).tier) { Tier.LOW -> 32; Tier.MID -> 64; Tier.HIGH -> 96 }
        val limit = minOf(tierMax, am.memoryClass / 3)
        return SIZES.filter { it <= limit || it == recommendedMb(context) }
    }

    /** This TV's memory, rounded up to the nearest half GB as sold ("1 GB", "1.5 GB"). */
    fun memoryLabel(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val total = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        val halves = kotlin.math.ceil(total / (512.0 * 1024 * 1024)).toInt()
        return if (halves % 2 == 0) "${halves / 2} GB" else "${halves / 2}.5 GB"
    }
}

/** Kept for existing callers: true on the LOW tier. */
object DeviceTier {
    fun lowRam(context: Context): Boolean = DeviceProfile.of(context).lowRam
}

/** The app-wide image loader, sized by [DeviceProfile], sharing the app's OkHttp client. */
fun buildImageLoader(context: Context): ImageLoader {
    val profile = DeviceProfile.of(context)
    return ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(profile.imageMemoryBytes).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(profile.imageDiskBytes).build() }
        .allowRgb565(profile.rgb565)
        // Images fade in briefly instead of popping (a 160 ms alpha change, then nothing redraws).
        .crossfade(160)
        .build()
}

/**
 * Gives memory back when the system asks. Fire OS closes background apps early on 1 GB sticks;
 * an app that trims when hidden is more likely to still be there when the viewer comes back.
 */
fun trimCaches(context: Context, level: Int) {
    val images = SingletonImageLoader.get(context).memoryCache
    @Suppress("DEPRECATION")
    when {
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
            images?.clear()
            DetailMemory.clear()
        }
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> images?.let { it.trimToSize(it.maxSize / 2) }
    }
}

/** Frees decoded images before playback on the LOW tier, so the video decoder has room. */
fun releaseImagesForPlayback(context: Context) {
    if (DeviceProfile.of(context).clearImagesForPlayback) SingletonImageLoader.get(context).memoryCache?.clear()
}

/** Parsed title details kept in RAM, weighed by episode count so long series count for more. */
object DetailMemory {
    private class Entry(val anime: Anime, val at: Long)
    @Volatile private var cache: android.util.LruCache<Int, Entry>? = null

    private fun cache(context: Context) = cache ?: synchronized(this) {
        cache ?: object : android.util.LruCache<Int, Entry>(DeviceProfile.of(context).detailMemoryEpisodes) {
            override fun sizeOf(key: Int, value: Entry) = value.anime.episodes.size + 1
        }.also { cache = it }
    }

    fun get(context: Context, id: Int, maxAge: Long): Anime? =
        cache(context).get(id)?.takeIf { System.currentTimeMillis() - it.at < maxAge }?.anime

    fun put(context: Context, anime: Anime) { cache(context).put(anime.id, Entry(anime, System.currentTimeMillis())) }

    fun clear() { cache?.evictAll() }
}
