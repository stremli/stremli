package com.stremli.tv

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
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "Megaplay"
private const val MAX_PAGE_BYTES = 256 * 1024
private const val MAX_SOURCES_BYTES = 128 * 1024

/**
 * MegaPlay (megaplay.buzz) only offers an embed, but the player is a small script chain over a plain
 * HTTPS API, so the stream can be resolved without a browser:
 *
 * 1. the embed page carries the file id in `data-id`,
 * 2. `stream/getSources` answers with its JSON sealed by AES-256-CBC, holding the master playlist,
 * 3. the CDN wants a short-lived HMAC ticket on top of a `megaplay.buzz` referer.
 *
 * The player then plays the returned HLS manifest directly, exactly like the other sources. Values
 * below are from the shipped player (e1-player.min.js / newclient.min.js, 2026-11-14).
 */
open class MegaplayException(message: String, cause: Throwable? = null, val fatal: Boolean = true) : IOException(message, cause)

/** One subtitle track the episode offers. */
class MegaplaySubtitle(val url: String, val label: String, val language: String, val mimeType: String, val isDefault: Boolean)

/** A resolved episode: the stream, its subtitle tracks and the intro/outro the provider reported. */
class MegaplaySession(
    val sourceUrl: String,
    val language: String,
    val skips: List<SkipSegment>,
    val subtitles: List<MegaplaySubtitle>
) {
    /** True when the episode has no selectable tracks, so the text is part of the picture. */
    val burnedIn: Boolean get() = language == "sub" && subtitles.isEmpty()
}

/** Blocking network and cryptographic work; call from an IO dispatcher. */
object Megaplay {
    private const val SITE = "https://megaplay.buzz"
    const val REFERER = "$SITE/"
    const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

    // From e1-player.min.js: the key is 16 bytes and is zero padded to the 32 AES-256 wants, the IV is 16.
    private const val SOURCE_KEY = "i?LMTAx0Q6,:}50U"
    private const val SOURCE_IV = "W0;27ToaUpl_P%'c"
    private const val TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    private const val TOKEN_TTL_SEC = 90L

    private val pathKey = Regex("/([a-fA-F0-9]{32})/([a-fA-F0-9]{32})/")
    private val tokenPresent = Regex("[?&]token=")
    private val masterPath = Regex("/anime/[a-fA-F0-9]{32}/[a-fA-F0-9]{32}/master\\.m3u8")
    private val safeId = Regex("[A-Za-z0-9_-]{1,32}")

    /** The stream for one episode and language version. */
    fun resolve(animeId: Int, malId: Int?, episode: Int, language: String): MegaplaySession {
        if (language !in setOf("sub", "dub")) throw MegaplayException("Unsupported MegaPlay language")
        if (episode <= 0) throw MegaplayException("Episode $episode is unavailable on MegaPlay")
        val pages = buildList {
            if (animeId > 0) add("$SITE/stream/ani/$animeId/$episode/$language")
            if (malId != null && malId > 0) add("$SITE/stream/mal/$malId/$episode/$language")
        }
        if (pages.isEmpty()) throw MegaplayException("MegaPlay cannot tell which title this is")

        var last: IOException? = null
        for (page in pages) {
            val fileId = try {
                fileId(page)
            } catch (e: MegaplayException) {
                last = e
                continue
            }
            if (fileId != null) return open(page, fileId, language)
        }
        throw last ?: MegaplayException("Episode $episode is unavailable on MegaPlay")
    }

    /** How the episode carries subtitles, and the heights it offers. Opens the stream without playing it. */
    fun info(animeId: Int, malId: Int?, episode: Int, language: String): ServerInfo {
        val session = resolve(animeId, malId, episode, language)
        val subtitles = if (session.burnedIn) "Hard subtitles" else if (session.subtitles.isNotEmpty()) "Soft subtitles" else null
        return ServerInfo(subtitles, playlistHeights(MegaplayDataSource.Factory().createDataSource(), session.sourceUrl))
    }

    private fun open(page: String, fileId: String, language: String): MegaplaySession {
        val payload = sources(fileId, page)
        val file = payload.optString("file")
        if (!validStream(file)) throw MegaplayException("MegaPlay changed its stream format")
        val builder = Uri.parse(file).buildUpon().appendQueryParameter("t", language)
        val subtitles = buildList {
            val tracks = payload.optJSONArray("tracks") ?: return@buildList
            for (i in 0 until minOf(tracks.length(), 256)) {
                val track = tracks.optJSONObject(i) ?: continue
                val kind = track.optString("kind")
                if (kind.isNotEmpty() && kind != "captions" && kind != "subtitles") continue
                val url = track.optString("file")
                if (!validTrack(url)) continue
                val mime = when (url.substringAfterLast('.', "").lowercase()) {
                    "vtt" -> "text/vtt"
                    "ass", "ssa" -> "text/x-ssa"
                    "srt" -> "application/x-subrip"
                    else -> continue
                }
                val label = track.optString("label").take(80).ifBlank { "Track ${i + 1}" }
                add(MegaplaySubtitle(url, label, subtitleLanguage(label), mime, track.optBoolean("default")))
            }
        }
        val skips = buildList {
            for ((field, kind) in listOf("intro" to SkipKind.Intro, "outro" to SkipKind.Outro)) {
                val range = payload.optJSONObject(field) ?: continue
                val start = range.optDouble("start", Double.NaN)
                val end = range.optDouble("end", Double.NaN)
                if (start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= 86_400) {
                    add(SkipSegment(kind, (start * 1000).toLong(), (end * 1000).toLong(), 0, priority = 0))
                }
            }
        }
        return MegaplaySession(builder.build().toString(), language, skips, subtitles)
    }

    /** Reads the file id out of the embed page, or null when the page is MegaPlay's "not found" one. */
    private fun fileId(page: String): String? {
        val html = String(get(page, REFERER, MAX_PAGE_BYTES, ajax = false), StandardCharsets.UTF_8)
        if (!html.contains("megaplay")) throw MegaplayException("MegaPlay returned an unexpected page", fatal = false)
        val id = Regex("data-id=\"([A-Za-z0-9_-]+)\"").find(html)?.groupValues?.get(1)
        return id?.takeIf(safeId::matches)
    }

    private fun sources(fileId: String, referer: String): JSONObject {
        val body = get("$SITE/stream/getSources?id=$fileId&platform=OTHER&st=other", referer, MAX_SOURCES_BYTES, ajax = true)
        val json = try {
            JSONObject(String(body, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw MegaplayException("MegaPlay changed its API response format", e)
        }
        json.optString("message").takeIf { json.optString("error").isNotEmpty() }?.let { throw MegaplayException(it) }
        val sealed = json.optString("enc")
        if (sealed.isEmpty()) {
            val direct = json.optJSONArray("sources")?.optJSONObject(0)?.optString("file")
                ?: throw MegaplayException("MegaPlay returned no stream")
            return JSONObject().apply { put("file", direct) }
        }
        val plain = try {
            JSONObject(String(decrypt(unbase64(sealed)), StandardCharsets.UTF_8))
        } catch (e: MegaplayException) {
            throw e
        } catch (e: Exception) {
            throw MegaplayException("MegaPlay changed its playback session format", e)
        }
        // Tracks and skip times travel in the clear next to the sealed stream.
        return JSONObject().apply {
            put("file", plain.optString("file"))
            json.optJSONArray("tracks")?.let { put("tracks", it) }
            json.optJSONObject("intro")?.let { put("intro", it) }
            json.optJSONObject("outro")?.let { put("outro", it) }
        }
    }

    private fun decrypt(sealed: ByteArray): ByteArray {
        if (sealed.size < 16 || sealed.size % 16 != 0) throw MegaplayException("MegaPlay returned a truncated response")
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(pad(SOURCE_KEY, 32), "AES"), IvParameterSpec(pad(SOURCE_IV, 16)))
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw MegaplayException("MegaPlay changed its playback session format", e)
        }
    }

    private fun pad(value: String, size: Int): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return ByteArray(size).also { bytes.copyInto(it, 0, 0, minOf(size, bytes.size)) }
    }

    private fun unbase64(value: String): ByteArray {
        val normal = value.replace('-', '+').replace('_', '/')
        val remainder = normal.length % 4
        val padded = if (remainder == 0) normal else normal + "=".repeat(4 - remainder)
        return try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw MegaplayException("MegaPlay changed its playback session format", e)
        }
    }

    private fun validStream(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "https" && uri.port in -1..443 && uri.userInfo == null && uri.fragment == null &&
            uri.host?.isNotBlank() == true && masterPath.matches(uri.path.orEmpty())
    }

    private fun validTrack(value: String): Boolean {
        if (value.length > 512) return false
        val uri = Uri.parse(value)
        return uri.scheme == "https" && uri.port in -1..443 && uri.userInfo == null && uri.host?.isNotBlank() == true
    }

    private fun subtitleLanguage(label: String): String = when (label.substringBefore('(').trim().lowercase()) {
        "arabic" -> "ar"; "chinese" -> "zh"; "czech" -> "cs"; "danish" -> "da"
        "dutch" -> "nl"; "english" -> "en"; "finnish" -> "fi"; "french" -> "fr"
        "german" -> "de"; "greek" -> "el"; "hebrew" -> "he"; "croatian" -> "hr"
        "hungarian" -> "hu"; "italian" -> "it"; "japanese" -> "ja"; "korean" -> "ko"
        "norwegian" -> "no"; "polish" -> "pl"; "portuguese" -> "pt"; "romanian" -> "ro"
        "russian" -> "ru"; "spanish" -> "es"; "swedish" -> "sv"; "turkish" -> "tr"
        "ukrainian" -> "uk"; "indonesian" -> "id"; "thai" -> "th"; "vietnamese" -> "vi"
        "hindi" -> "hi"; "malay" -> "ms"; else -> "und"
    }

    private fun get(url: String, referer: String, limit: Int, ajax: Boolean): ByteArray {
        if (Uri.parse(url).scheme != "https") throw MegaplayException("Only HTTPS sources are supported")
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept-Encoding", "identity")
            .apply { if (ajax) header("X-Requested-With", "XMLHttpRequest").header("Accept", "application/json, text/javascript, */*; q=0.01") }
            .build()
        try {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // A 4xx is the provider's answer (no such episode), not something a retry can fix.
                    throw MegaplayException("MegaPlay refused the request (HTTP ${response.code})", fatal = response.code < 500 && response.code != 429)
                }
                return readBounded(response.body?.byteStream() ?: throw MegaplayException("MegaPlay returned an empty response", fatal = false), limit)
            }
        } catch (e: MegaplayException) {
            throw e
        } catch (e: IOException) {
            throw MegaplayException("Could not reach MegaPlay. Check your connection.", e, fatal = false)
        }
    }

    private fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw MegaplayException("MegaPlay response is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** The signed URL for [url]: the CDN ticket is an HMAC over the expiry and the path's key pair. */
    internal fun signed(url: String): String {
        if (tokenPresent.containsMatchIn(url)) return url
        val key = pathKey.find(url)?.let { it.groupValues[1].lowercase() + "/" + it.groupValues[2].lowercase() } ?: return url
        val message = "${System.currentTimeMillis() / 1000 + TOKEN_TTL_SEC}|$key"
        val signature = try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(TOKEN_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
                doFinal(message.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not sign the CDN ticket: ${e.message}")
            return url
        }
        val token = b64url(message.toByteArray(StandardCharsets.UTF_8)) + "." + b64url(signature)
        return Uri.parse(url).buildUpon().appendQueryParameter("token", token).build().toString()
    }

    private fun b64url(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/**
 * Adds the CDN ticket and referer the stream hosts demand. Everything else (ranges, redirects,
 * response codes) is left to [upstream]. Segments served straight from a TikTok CDN carry a fixed
 * junk prefix, which the shipped player removes; that rule is kept here.
 */
@OptIn(UnstableApi::class)
class MegaplayDataSource(private val upstream: HttpDataSource) : DataSource {
    private var opened = false
    private var resourceUri: Uri? = null
    private var skip = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun getUri(): Uri? = resourceUri
    override fun getResponseHeaders(): Map<String, List<String>> = if (opened) upstream.responseHeaders else emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "https") throw MegaplayException("Unsupported video host")
        if (dataSpec.position < 0) throw MegaplayException("Invalid stream read position")
        val target = Uri.parse(Megaplay.signed(dataSpec.uri.toString()))
        skip = if (dataSpec.position == 0L && STRIPPED_HOST.containsMatchIn(dataSpec.uri.host.orEmpty())) STRIP_BYTES else 0
        val length = upstream.open(dataSpec.withUri(target))
        opened = true
        resourceUri = target
        return if (length == C.LENGTH_UNSET.toLong() || skip == 0) length else maxOf(0L, length - skip)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("Source not open")
        var read = upstream.read(buffer, offset, length)
        while (read > 0 && skip > 0) {
            val drop = minOf(skip, read)
            skip -= drop
            read -= drop
            if (read == 0) {
                if (drop == length) return 0
                read = upstream.read(buffer, offset, length)
                continue
            }
            if (drop > 0) System.arraycopy(buffer, offset + drop, buffer, offset, read)
        }
        return read
    }

    override fun close() {
        resourceUri = null
        skip = 0
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    class Factory : DataSource.Factory {
        private val upstream = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(Megaplay.UA)
            .setDefaultRequestProperties(mapOf("Referer" to Megaplay.REFERER, "Accept-Encoding" to "identity"))

        override fun createDataSource(): DataSource = MegaplayDataSource(upstream.createDataSource())
    }

    companion object {
        private val STRIPPED_HOST = Regex("ibyteimg\\.com|tiktokcdn\\.com|ipstatp\\.com|yoot\\.akirax\\.buzz", RegexOption.IGNORE_CASE)
        private const val STRIP_BYTES = 252
    }
}

/** Retries network failures as usual, but gives up at once on errors a retry cannot fix. */
@OptIn(UnstableApi::class)
class MegaplayLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(5) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var cause: Throwable? = loadErrorInfo.exception
        while (cause != null) {
            if (cause is MegaplayException && cause.fatal) return C.TIME_UNSET
            cause = cause.cause
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
