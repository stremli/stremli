package com.stremli.tv

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "Gotaku"
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
/** The playback heartbeat refreshes the session this long before it expires. */
private const val REFRESH_AHEAD_MS = 90_000L
/** Loaders only wait for a refresh when the session is this close to expiring (the heartbeat normally prevents it). */
private const val BLOCKING_REFRESH_MS = 10_000L
private const val REFRESH_COOLDOWN_MS = 3_000L
private const val TITLE_CACHE_MS = 15 * 60_000L
private const val TITLE_RECHECK_MS = 60_000L

/**
 * A Gotaku failure. [fatal] errors (the provider changed its format, the episode does not exist)
 * are not retried by the player; non-fatal ones (network trouble) are.
 */
open class GotakuException(message: String, cause: Throwable? = null, val fatal: Boolean = true) : IOException(message, cause)

private class PlaylistDecodeException(cause: Throwable) : IOException("Gotaku playlist could not be decoded", cause)

/** Shared HTTP client: one connection pool for API calls, playlists and segments. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Protocol parameters observed in Gotaku's public player on 2026-11-04. They are specific to that
 * player build. The built-in values can be replaced at runtime by a JSON file served from
 * BuildConfig.GOTAKU_CONFIG_URL, so a provider change can
 * be fixed without shipping a new APK.
 */
object GotakuConfig {
    class Params(
        val revision: String,
        val apiRoot: ByteArray,
        val embedRoot: ByteArray,
        val apiBase: String,
        val userAgent: String,
        val playerHosts: List<String>,
        val apiQuery: String,
        val apiInfo: String,
        val manifestInfo: String,
        val playlistInfo: String,
        val obfVersion: Int
    ) {
        val apiKey: ByteArray by lazy { hkdf(apiRoot, ByteArray(0), apiInfo) }
    }

    private const val PREFS_KEY = "gotaku_config"
    private const val DEFAULT_JSON = """{
        "revision": "builtin-2026-11-04",
        "apiRoot": "HmPO7LAa5JM3I5h7BhF+syi1xdLCWugj9xSCK0d1e1I=",
        "embedRoot": "RY5UA8QKczMnBt9AYZPdawQKLql6a7WbUrKUphAG5u8=",
        "apiBase": "https://gotaku.to/api/v1",
        "userAgent": "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
        "playerHosts": ["gotakudl.top"],
        "apiQuery": "k=10",
        "info": {"api": "api-seal|v1", "manifest": "manifest-resp|v1", "playlist": "pl|v1"},
        "obfVersion": 1
    }"""

    @Volatile
    var params: Params = parse(JSONObject(DEFAULT_JSON))
        private set

    /** Applies a previously downloaded configuration, if any. Cheap; call at startup. */
    fun load(context: Context) {
        val stored = context.getSharedPreferences("stremli", 0).getString(PREFS_KEY, null) ?: return
        runCatching { parse(JSONObject(stored)) }
            .onSuccess { params = it }
            .onFailure { Log.w(TAG, "ignoring stored config: ${it.message}") }
    }

    /** Downloads the remote configuration when a URL is configured. Blocking; call off the main thread. */
    fun refresh(context: Context) {
        val url = BuildConfig.GOTAKU_CONFIG_URL
        if (url.isBlank()) return
        try {
            if (!url.startsWith("https://")) throw IOException("config URL must be HTTPS")
            val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
            val text = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                String(readBounded(response.body?.byteStream() ?: throw IOException("empty response"), 64 * 1024), StandardCharsets.UTF_8)
            }
            val parsed = parse(JSONObject(text))
            params = parsed
            context.getSharedPreferences("stremli", 0).edit().putString(PREFS_KEY, text).apply()
            Log.i(TAG, "config ${parsed.revision} loaded")
        } catch (e: Exception) {
            Log.w(TAG, "config refresh failed: ${e.message}")
        }
    }

    private fun parse(json: JSONObject): Params {
        fun key(name: String): ByteArray = Base64.decode(json.getString(name), Base64.DEFAULT).also {
            require(it.size == 32) { "$name must be 32 bytes" }
        }
        val apiBase = json.getString("apiBase").trimEnd('/')
        require(apiBase.startsWith("https://")) { "apiBase must be HTTPS" }
        val hosts = json.getJSONArray("playerHosts").let { a -> (0 until a.length()).map { a.getString(it).lowercase() } }
        require(hosts.isNotEmpty()) { "playerHosts is empty" }
        val info = json.getJSONObject("info")
        return Params(
            revision = json.optString("revision", "unknown"),
            apiRoot = key("apiRoot"),
            embedRoot = key("embedRoot"),
            apiBase = apiBase,
            userAgent = json.getString("userAgent"),
            playerHosts = hosts,
            apiQuery = json.optString("apiQuery", ""),
            apiInfo = info.getString("api"),
            manifestInfo = info.getString("manifest"),
            playlistInfo = info.getString("playlist"),
            obfVersion = json.optInt("obfVersion", 1)
        )
    }
}

private fun unbase64(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun base64url(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

private fun hkdf(key: ByteArray, salt: ByteArray, info: String): ByteArray {
    val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, key)
    return hmac(prk, info.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(1))
}

private fun decryptGcm(key: ByteArray, encrypted: ByteArray): ByteArray {
    if (encrypted.size < 28) throw GotakuException("Gotaku returned a truncated response")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
    return cipher.doFinal(encrypted, 12, encrypted.size - 12)
}

private fun encryptGcm(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return iv + cipher.doFinal(plaintext)
}

private fun ByteArray.startsWithAscii(prefix: String): Boolean {
    val bytes = prefix.toByteArray(StandardCharsets.UTF_8)
    if (size < bytes.size) return false
    for (i in bytes.indices) if (this[i] != bytes[i]) return false
    return true
}

private fun readBounded(stream: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (output.size() + read > limit) throw GotakuException("Gotaku response is too large")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun getBytes(url: String, referer: String): ByteArray {
    if (Uri.parse(url).scheme != "https") throw GotakuException("Only HTTPS sources are supported")
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", GotakuConfig.params.userAgent)
        .header("Referer", referer)
        .build()
    try {
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GotakuException("Gotaku returned HTTP ${response.code}", fatal = response.code < 500 && response.code != 429)
            return readBounded(response.body?.byteStream() ?: throw GotakuException("Gotaku returned an empty response", fatal = false), MAX_RESPONSE_BYTES)
        }
    } catch (e: GotakuException) {
        throw e
    } catch (e: IOException) {
        throw GotakuException("Could not reach Gotaku. Check your connection.", e, fatal = false)
    }
}

private fun apiUrl(path: String): String {
    val config = GotakuConfig.params
    val query = config.apiQuery
    return when {
        query.isBlank() -> "${config.apiBase}/$path"
        path.contains('?') -> "${config.apiBase}/$path&$query"
        else -> "${config.apiBase}/$path?$query"
    }
}

private fun sealedApi(path: String, referer: String): JSONObject {
    val bytes = getBytes(apiUrl(path), referer)
    if (bytes.size < 2 || bytes[0] != 0x0A.toByte()) throw GotakuException("Gotaku changed its API response format")
    val compressed = try {
        decryptGcm(GotakuConfig.params.apiKey, bytes.copyOfRange(1, bytes.size))
    } catch (e: GeneralSecurityException) {
        throw GotakuException("Gotaku changed its API response format", e)
    }
    val json = InflaterInputStream(ByteArrayInputStream(compressed)).use {
        String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)
    }
    return try {
        JSONObject(json)
    } catch (e: Exception) {
        throw GotakuException("Gotaku changed its API response format", e)
    }
}

private fun htmlAttribute(html: String, name: String): String =
    Regex("data-$name=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        ?: run {
            Log.w(TAG, "missing data-$name in ${html.length} bytes")
            throw GotakuException("Gotaku player is missing its playback session")
        }

/** One Gotaku episode from the title's episode list. */
class GotakuEpisodeRef(val id: String, val number: Double, val sub: Boolean)

/** A Gotaku title and its episode list. Cached so an episode can start with fewer requests. */
class GotakuTitle(val titleId: String, val titleUrl: String, val episodes: List<GotakuEpisodeRef>, val loadedAt: Long) {
    fun find(episode: Int): GotakuEpisodeRef? = episodes.firstOrNull { kotlin.math.abs(it.number - episode) < 0.0001 }
}

object GotakuTitles {
    // Bounded: a long browsing session would otherwise keep every title's episode list.
    private val cache = object : LinkedHashMap<String, GotakuTitle>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GotakuTitle>?) = size > 12
    }
    private val locks = ConcurrentHashMap<String, Any>()

    /** Loads the title in the background when a details page opens. Errors surface later, at playback. */
    fun prefetch(provider: Provider, animeId: Int) {
        runCatching { get(provider, animeId) }.onFailure { Log.i(TAG, "prefetch failed: ${it.message}") }
    }

    fun get(provider: Provider, animeId: Int, forceReload: Boolean = false): GotakuTitle {
        val titleUrl = provider.url.substringBefore('?')
        val key = "$titleUrl|$animeId"
        synchronized(locks.getOrPut(key) { Any() }) {
            val cached = synchronized(cache) { cache[key] }
            if (!forceReload && cached != null && System.currentTimeMillis() - cached.loadedAt < TITLE_CACHE_MS) return cached
            return load(titleUrl, animeId).also { synchronized(cache) { cache[key] = it } }
        }
    }

    private fun load(titleUrl: String, animeId: Int): GotakuTitle {
        val titleUri = Uri.parse(titleUrl)
        if (titleUri.scheme != "https" || !titleUri.host.equals("gotaku.to", true)) throw GotakuException("Unsupported Gotaku title link")
        val path = titleUri.pathSegments
        if (path.size != 2 || path[0] != "title" || !path[1].matches(Regex("[A-Za-z0-9_-]+"))) throw GotakuException("Invalid Gotaku title link")
        val titleId = path[1]

        val title = try {
            JSONObject(String(getBytes("${GotakuConfig.params.apiBase}/titles/$titleId", titleUrl), StandardCharsets.UTF_8))
                .getJSONObject("data").getJSONObject("title")
        } catch (e: GotakuException) {
            throw e
        } catch (e: Exception) {
            throw GotakuException("Gotaku could not open this title", e)
        }
        val linkedId = title.optInt("anilist_id", 0)
        // Kuroiru-only titles (negative ids) have no AniList id to compare against.
        if (animeId > 0 && linkedId != 0 && linkedId != animeId) throw GotakuException("Gotaku matched a different anime")

        val entries = sealedApi("titles/$titleId/episodes", titleUrl).optJSONArray("data")
            ?: throw GotakuException("Gotaku returned no episode list")
        val episodes = buildList {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                val number = entry.optDouble("number", Double.NaN)
                if (!id.matches(Regex("[A-Za-z0-9_-]+")) || number.isNaN()) continue
                add(GotakuEpisodeRef(id, number, entry.optBoolean("sub", false)))
            }
        }
        return GotakuTitle(titleId, titleUrl, episodes, System.currentTimeMillis())
    }
}

class GotakuSession private constructor(
    private val manifestBase: String,
    private val embedUrl: String,
    initialStamp: String,
    initial: JSONObject,
    val skips: List<SkipSegment>,
    val subtitleType: String
) {
    @Volatile private var manifest: JSONObject = initial
    @Volatile private var stamp: String = initial.optString("stamp", initialStamp)
    @Volatile private var lastRefreshAt = 0L
    @Volatile private var activeHost: String? = null
    private val refreshLock = Any()
    /** Every stream host seen in this session, so playlists fetched before a refresh stay valid. */
    private val knownHosts = ConcurrentHashMap.newKeySet<String>().apply { addAll(hostsOf(initial)) }

    val sourceUrl: String get() = manifest.getString("source")
    fun referer(): String = embedUrl

    private fun expiresAt(m: JSONObject): Long = m.optLong("expiresAt", 0L) * 1000L

    /** The current manifest. Only blocks when the session is about to expire and no refresh has happened. */
    fun current(): JSONObject {
        val m = manifest
        if (expiresAt(m) - System.currentTimeMillis() > BLOCKING_REFRESH_MS) return m
        return synchronized(refreshLock) {
            if (expiresAt(manifest) - System.currentTimeMillis() > BLOCKING_REFRESH_MS) manifest else refreshLocked()
        }
    }

    /** Called by the playback heartbeat off the loader threads, so loads never wait for a routine refresh. */
    fun refreshIfNeeded() {
        if (expiresAt(manifest) - System.currentTimeMillis() > REFRESH_AHEAD_MS) return
        synchronized(refreshLock) {
            if (expiresAt(manifest) - System.currentTimeMillis() <= REFRESH_AHEAD_MS) refreshLocked()
        }
    }

    /** After a 401/403: refresh unless another loader already replaced the manifest that failed. */
    fun forceRefresh(failed: JSONObject): JSONObject = synchronized(refreshLock) {
        if (manifest !== failed) return manifest
        if (System.currentTimeMillis() - lastRefreshAt < REFRESH_COOLDOWN_MS) return manifest
        refreshLocked()
    }

    private fun refreshLocked(): JSONObject {
        val refreshed = resolveManifest(manifestBase, stamp, embedUrl)
        knownHosts.addAll(hostsOf(refreshed))
        manifest = refreshed
        stamp = refreshed.optString("stamp", stamp)
        lastRefreshAt = System.currentTimeMillis()
        Log.i("GotakuSession", "refreshed; expires in ${refreshed.optLong("expiresAt", 0L) - lastRefreshAt / 1000L}s")
        return refreshed
    }

    /** The host to use now: the manifest's primary host unless it failed and a backup is active. */
    fun hostFor(m: JSONObject): String {
        val hosts = hostsOf(m)
        return activeHost?.takeIf { it in hosts } ?: hosts.first()
    }

    /** Switches to the next backup host not yet tried for this request. Returns false when none is left. */
    fun failover(failed: String, tried: Set<String>): Boolean {
        val hosts = hostsOf(manifest)
        val next = hosts.firstOrNull { it !in tried && it != failed } ?: return false
        Log.w("GotakuSession", "stream host failed; switching to backup ${hosts.indexOf(next)}")
        activeHost = next
        return true
    }

    fun isKnownHost(host: String): Boolean = knownHosts.any { it.equals(host, true) }

    companion object {
        /** Opens a session without playing it, to see what this episode offers. */
        fun info(provider: Provider, animeId: Int, episode: Int): ServerInfo {
            val session = resolve(provider, animeId, episode)
            return ServerInfo(if (session.subtitleType == "hard_sub") "Hard subtitles" else "Soft subtitles", playlistHeights(GotakuDataSource.Factory(session).createDataSource(), session.sourceUrl))
        }

        fun resolve(provider: Provider, animeId: Int, episode: Int): GotakuSession {
            var title = GotakuTitles.get(provider, animeId)
            var match = title.find(episode)
            if (match == null && System.currentTimeMillis() - title.loadedAt > TITLE_RECHECK_MS) {
                // The cached list may predate a newly released episode.
                title = GotakuTitles.get(provider, animeId, forceReload = true)
                match = title.find(episode)
            }
            if (match == null) throw GotakuException("Episode $episode is unavailable on Gotaku")
            if (!match.sub) throw GotakuException("Episode $episode has no subtitled stream")
            val titleUrl = title.titleUrl

            val info = sealedApi("episodes/${match.id}", titleUrl).optJSONObject("data")
                ?: throw GotakuException("Gotaku returned no episode details")
            val actualNumber = info.optDouble("number", Double.NaN)
            if (!actualNumber.isNaN() && kotlin.math.abs(actualNumber - episode) > 0.0001) {
                throw GotakuException("Gotaku returned a different episode")
            }
            val type = when {
                info.optBoolean("hard_sub", false) -> "hard_sub"
                info.optBoolean("soft_sub", false) -> "soft_sub"
                else -> throw GotakuException("Episode $episode has no subtitled stream")
            }
            val embedData = sealedApi("episodes/${match.id}/embed?type=$type", titleUrl).optJSONObject("data")
            val sourceSkips = buildList {
                val skip = embedData?.optJSONObject("skip")
                for ((name, kind) in listOf("intro" to SkipKind.Intro, "outro" to SkipKind.Outro)) {
                    val range = skip?.optJSONArray(name) ?: continue
                    val start = range.optDouble(0, Double.NaN)
                    val end = range.optDouble(1, Double.NaN)
                    if (start.isFinite() && end.isFinite() && start >= 0 && end > start && end < 86_400) {
                        add(SkipSegment(kind, (start * 1000).toLong(), (end * 1000).toLong(), 0, 0))
                    }
                }
            }
            val embed = embedData?.optString("url").orEmpty()
            val embedUri = Uri.parse(embed)
            if (embedUri.scheme != "https" || !isPlayerHost(embedUri.host.orEmpty()) || !embedUri.path.orEmpty().startsWith("/embed/")) {
                throw GotakuException("Gotaku returned an unsupported player link")
            }

            val html = String(getBytes(embed, titleUrl), StandardCharsets.UTF_8)
            val base = htmlAttribute(html, "manifest-base").trimEnd('/')
            val stamp = htmlAttribute(html, "manifest-stamp")
            val baseUri = Uri.parse(base)
            if (baseUri.scheme != "https" || !baseUri.host.equals(embedUri.host, true)) throw GotakuException("Gotaku returned an unsupported manifest endpoint")
            return GotakuSession(base, embed, stamp, resolveManifest(base, stamp, embed), sourceSkips, type)
        }

        private fun isPlayerHost(host: String): Boolean =
            GotakuConfig.params.playerHosts.any { host.equals(it, true) || host.endsWith(".$it", true) }

        private fun hostsOf(m: JSONObject): List<String> {
            val hosts = linkedSetOf<String>()
            Uri.parse(m.optString("source")).host?.takeIf { it.isNotBlank() }?.let { hosts.add(it.lowercase()) }
            val fallbacks: JSONArray? = m.optJSONArray("fallbacks")
            if (fallbacks != null) for (i in 0 until fallbacks.length()) {
                val uri = Uri.parse(fallbacks.optString(i))
                val host = uri.host
                if (uri.scheme == "https" && !host.isNullOrBlank()) hosts.add(host.lowercase())
            }
            return hosts.toList()
        }

        private fun resolveManifest(base: String, stamp: String, referer: String): JSONObject {
            val config = GotakuConfig.params
            val random = SecureRandom()
            val nonce = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val payload = JSONObject().put("nonce", base64url(nonce)).put("stamp", stamp).toString().toByteArray(StandardCharsets.UTF_8)
            val ticket = base64url(encryptGcm(config.embedRoot, iv, payload))
            val response = String(getBytes("$base/$ticket", referer), StandardCharsets.UTF_8).trim()
            val plain = try {
                decryptGcm(hkdf(config.embedRoot, nonce, config.manifestInfo), unbase64(response))
            } catch (e: Exception) {
                throw GotakuException("Gotaku changed its playback session format", e)
            }
            return try {
                validateManifest(JSONObject(String(plain, StandardCharsets.UTF_8)))
            } catch (e: GotakuException) {
                throw e
            } catch (e: Exception) {
                throw GotakuException("Gotaku changed its playback session format", e)
            }
        }

        private fun validateManifest(manifest: JSONObject): JSONObject {
            val sourceUri = Uri.parse(manifest.optString("source"))
            if (sourceUri.scheme != "https" || sourceUri.host.isNullOrBlank()) throw GotakuException("Gotaku returned an invalid stream location")
            if (manifest.optLong("expiresAt", 0L) <= 0L) throw GotakuException("Gotaku returned no stream expiry")
            if (manifest.optString("token").isBlank()) throw GotakuException("Gotaku returned no stream token")
            if (unbase64(manifest.optString("keySeed")).size != 32) throw GotakuException("Gotaku returned an invalid stream key")
            val obf = manifest.optJSONObject("obf") ?: throw GotakuException("Gotaku changed its video format")
            if (obf.optInt("version", -1) != GotakuConfig.params.obfVersion) throw GotakuException("Gotaku changed its video format")
            if (obf.optInt("segmentBytes", -1) !in 0..65536) throw GotakuException("Gotaku changed its video format")
            return manifest
        }
    }
}

/**
 * Wraps Media3's OkHttp data source. HTTP (connection reuse, ranges, redirects, response codes) is
 * left to [upstream]; this layer only rewrites the URL for the current session and host, decrypts
 * `/p/` playlists, and unscrambles the first `obf.segmentBytes` of each `/s/` segment.
 */
@OptIn(UnstableApi::class)
class GotakuDataSource(private val session: GotakuSession, private val upstream: HttpDataSource) : DataSource {
    private var upstreamOpen = false
    private var playlist: ByteArrayInputStream? = null
    private var resourceUri: Uri? = null
    private var position = 0L
    private var mask = ByteArray(0)

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = resourceUri

    override fun getResponseHeaders(): Map<String, List<String>> = if (upstreamOpen) upstream.responseHeaders else emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position < 0L) throw GotakuException("Invalid stream read position")
        val kind = dataSpec.uri.pathSegments.firstOrNull()
        if (kind != "p" && kind != "s") throw GotakuException("Unsupported Gotaku stream resource")
        var manifest = session.current()
        var refreshed = false
        val tried = HashSet<String>()
        while (true) {
            val host = session.hostFor(manifest)
            tried += host
            val target = rewrite(dataSpec.uri, manifest, host)
            try {
                return if (kind == "p") openPlaylist(dataSpec, target, manifest) else openSegment(dataSpec, target, manifest)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                closeQuietly()
                when {
                    (e.responseCode == 401 || e.responseCode == 403) && !refreshed -> {
                        manifest = session.forceRefresh(manifest)
                        refreshed = true
                    }
                    e.responseCode >= 500 && session.failover(host, tried) -> manifest = session.current()
                    else -> throw e
                }
            } catch (e: PlaylistDecodeException) {
                closeQuietly()
                if (refreshed) throw GotakuException("Gotaku playlist could not be decoded", e)
                manifest = session.forceRefresh(manifest)
                refreshed = true
            } catch (e: HttpDataSource.HttpDataSourceException) {
                closeQuietly()
                if (e.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN && session.failover(host, tried)) manifest = session.current()
                else throw e
            }
        }
    }

    private fun openPlaylist(dataSpec: DataSpec, target: Uri, manifest: JSONObject): Long {
        val whole = dataSpec.buildUpon().setUri(target).setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        upstream.open(whole)
        upstreamOpen = true
        val encrypted = try {
            readUpstream()
        } finally {
            closeQuietly()
        }
        val plain = decryptPlaylist(manifest, encrypted)
        if (dataSpec.position > plain.size) throw GotakuException("Playlist read position is invalid")
        val start = dataSpec.position.toInt()
        val available = plain.size - start
        val count = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available.toLong(), dataSpec.length).toInt()
        playlist = ByteArrayInputStream(plain, start, count)
        resourceUri = target
        return count.toLong()
    }

    private fun openSegment(dataSpec: DataSpec, target: Uri, manifest: JSONObject): Long {
        val length = upstream.open(dataSpec.withUri(target))
        upstreamOpen = true
        resourceUri = target
        position = dataSpec.position
        val prefix = manifest.optJSONObject("obf")?.optInt("segmentBytes", 0) ?: 0
        mask = if (position < prefix) segmentMask(manifest, target, prefix) else ByteArray(0)
        return length
    }

    private fun readUpstream(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (output.size() + read > MAX_RESPONSE_BYTES) throw GotakuException("Gotaku playlist is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        playlist?.let { stream ->
            val read = stream.read(buffer, offset, length)
            return if (read < 0) C.RESULT_END_OF_INPUT else read
        }
        if (!upstreamOpen) throw IOException("Source not open")
        val read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) return read
        if (position < mask.size) {
            // Only the first obf.segmentBytes of a segment are scrambled.
            val start = position.toInt()
            val end = minOf(mask.size.toLong(), position + read).toInt()
            for (i in start until end) {
                val index = offset + (i - start)
                buffer[index] = (buffer[index].toInt() xor mask[i].toInt()).toByte()
            }
        }
        position += read
        return read
    }

    override fun close() {
        playlist = null
        resourceUri = null
        mask = ByteArray(0)
        position = 0L
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    private fun closeQuietly() {
        if (!upstreamOpen) return
        upstreamOpen = false
        try {
            upstream.close()
        } catch (_: IOException) {
        }
    }

    private fun decryptPlaylist(manifest: JSONObject, encrypted: ByteArray): ByteArray {
        if (encrypted.startsWithAscii("#EXTM3U")) return encrypted
        val plain = try {
            val seed = unbase64(manifest.getString("keySeed"))
            val key = hkdf(seed, manifest.getString("token").toByteArray(StandardCharsets.UTF_8), GotakuConfig.params.playlistInfo)
            decryptGcm(key, encrypted)
        } catch (e: GotakuException) {
            throw e
        } catch (e: Exception) {
            throw PlaylistDecodeException(e)
        }
        if (!plain.startsWithAscii("#EXTM3U")) throw GotakuException("Gotaku changed its playlist format")
        return plain
    }

    private fun segmentMask(manifest: JSONObject, target: Uri, prefix: Int): ByteArray {
        val parts = target.pathSegments
        if (parts.size < 3 || parts[0] != "s") throw GotakuException("Unsupported Gotaku segment path")
        val name = parts.drop(2).joinToString("/").replace(Regex("\\.[^.]*$"), "")
        if (name.isBlank()) throw GotakuException("Unsupported Gotaku segment name")
        val counter = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, 8) + ByteArray(8)
        val seed = unbase64(manifest.getString("keySeed"))
        return try {
            Cipher.getInstance("AES/CTR/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"), IvParameterSpec(counter))
                doFinal(ByteArray(prefix))
            }
        } catch (e: GeneralSecurityException) {
            throw GotakuException("Unable to prepare the video transform", e)
        }
    }

    private fun rewrite(original: Uri, manifest: JSONObject, host: String): Uri {
        val originalHost = original.host
        if (original.scheme != "https" || originalHost.isNullOrBlank() || !session.isKnownHost(originalHost)) {
            throw GotakuException("Unsupported video host")
        }
        val builder = original.buildUpon().authority(host)
        val parts = original.pathSegments.toMutableList()
        if (parts.firstOrNull() == "p") {
            if (parts.size < 5) throw GotakuException("Unsupported Gotaku playlist path")
            parts[2] = manifest.getLong("expiresAt").toString()
            parts[3] = manifest.getString("token")
            builder.path("/" + parts.joinToString("/"))
        }
        return builder.build()
    }

    class Factory(private val session: GotakuSession) : DataSource.Factory {
        private val upstream = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(GotakuConfig.params.userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to session.referer(), "Accept-Encoding" to "identity"))

        override fun createDataSource(): DataSource = GotakuDataSource(session, upstream.createDataSource())
    }
}

/** Retries network failures as usual, but gives up at once on errors a retry cannot fix. */
@OptIn(UnstableApi::class)
class GotakuLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(5) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var cause: Throwable? = loadErrorInfo.exception
        while (cause != null) {
            if (cause is GotakuException && cause.fatal) return C.TIME_UNSET
            cause = cause.cause
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
