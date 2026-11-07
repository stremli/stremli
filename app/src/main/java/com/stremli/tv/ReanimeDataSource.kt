package com.stremli.tv

import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** FlixCloud's HLS wrapper: bounded playlists and streaming image-wrapped transport segments. */
@OptIn(UnstableApi::class)
class ReanimeDataSource(private val playlistKey: ByteArray, private val upstream: HttpDataSource) : DataSource {
    private var opened = false
    private var local: ByteArrayInputStream? = null
    private var uri: Uri? = null
    private var segmentOffset = 0L
    private var masked = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = if (opened) upstream.responseHeaders else emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "https" || dataSpec.position < 0) throw IOException("Invalid video URL")
        val path = dataSpec.uri.path.orEmpty().lowercase()
        uri = dataSpec.uri
        if (path.endsWith(".m3u8")) return openPlaylist(dataSpec)
        if (path.endsWith(".webp") || path.endsWith(".png")) return openImageSegment(dataSpec)
        val size = upstream.open(dataSpec)
        opened = true
        return size
    }

    private fun openPlaylist(spec: DataSpec): Long {
        val whole = spec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        upstream.open(whole)
        opened = true
        val bytes = try { readBounded(512 * 1024) } finally { closeUpstream() }
        val decoded = if (bytes.startsWithPlaylist()) bytes else try {
            val decoded = Base64.decode(bytes, Base64.DEFAULT)
            if (playlistKey.size != 32) throw IOException("Invalid playlist key")
            for (i in decoded.indices) decoded[i] = (decoded[i].toInt() xor playlistKey[i % playlistKey.size].toInt()).toByte()
            if (!decoded.startsWithPlaylist()) throw IOException("Unsupported playlist format")
            decoded
        } catch (e: IllegalArgumentException) { throw IOException("Unsupported playlist format", e) }
        // FlixCloud's master manifests currently report bandwidth in kbit/s even though HLS
        // requires bit/s. Correct only implausibly low variant values so adaptive quality works.
        val text = decoded.toString(Charsets.UTF_8)
        val plain = if (text.contains("#EXT-X-STREAM-INF")) text.lineSequence().joinToString("\n") { line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) line else bandwidth.replace(line) { match ->
                val value = match.groupValues[2].toLongOrNull()
                if (value != null && value in 1..49_999) match.groupValues[1] + (value * 1_000) else match.value
            }
        }.toByteArray(Charsets.UTF_8) else decoded
        if (spec.position > plain.size) throw IOException("Invalid playlist position")
        val start = spec.position.toInt()
        val available = plain.size - start
        val count = if (spec.length == C.LENGTH_UNSET.toLong()) available else minOf(spec.length, available.toLong()).toInt()
        local = ByteArrayInputStream(plain, start, count)
        return count.toLong()
    }

    private fun openImageSegment(spec: DataSpec): Long {
        // Probe the wrapper even for range requests; the range applies to the unwrapped segment.
        upstream.open(spec.buildUpon().setPosition(0).setLength(13).build())
        opened = true
        val prefix = try { readBounded(13) } finally { closeUpstream() }
        val header = when {
            prefix.size >= 12 && prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(82, 73, 70, 70)) && prefix.copyOfRange(8, 12).contentEquals(byteArrayOf(87, 69, 66, 80)) -> 12
            prefix.size >= 8 && prefix.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> 8
            else -> 0
        }
        masked = header > 0 && prefix.size > header && prefix[header] != 0x47.toByte()
        segmentOffset = spec.position
        val size = upstream.open(spec.buildUpon().setPosition(spec.position + header).build())
        opened = true
        return size
    }

    private fun readBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(minOf(limit + 1, 16 * 1024))
        while (true) {
            val read = upstream.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (read == C.RESULT_END_OF_INPUT) break
            if (out.size() + read > limit) throw IOException("Playback response is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        local?.let { return it.read(buffer, offset, length).let { n -> if (n < 0) C.RESULT_END_OF_INPUT else n } }
        if (!opened) throw IOException("Source is closed")
        val count = upstream.read(buffer, offset, length)
        if (count > 0) {
            if (masked) for (i in 0 until count) buffer[offset + i] = (buffer[offset + i].toInt() xor segmentMask[((segmentOffset + i) % segmentMask.size).toInt()].toInt()).toByte()
            segmentOffset += count
        }
        return count
    }

    override fun close() {
        local = null
        uri = null
        segmentOffset = 0
        masked = false
        closeUpstream()
    }

    private fun closeUpstream() {
        if (opened) { opened = false; upstream.close() }
    }

    class Factory(private val playlistKey: ByteArray) : DataSource.Factory {
        private val upstream = OkHttpDataSource.Factory(Http.client)
            .setUserAgent("Mozilla/5.0")
            .setDefaultRequestProperties(mapOf("Referer" to "https://flixcloud.cc/", "Accept-Encoding" to "identity"))
        override fun createDataSource(): DataSource = ReanimeDataSource(playlistKey, upstream.createDataSource())
    }

    companion object {
        private val segmentMask = byteArrayOf(-99, 42, -15, 71, -77, -114, 92, 112, -90, 25, -28, 59, -40, 98, 15, -59)
        private val bandwidth = Regex("((?:^|[:,])(?:AVERAGE-)?BANDWIDTH=)([0-9]+)")
        private fun ByteArray.startsWithPlaylist() = size >= 7 && copyOfRange(0, 7).contentEquals("#EXTM3U".toByteArray(Charsets.US_ASCII))
    }
}
