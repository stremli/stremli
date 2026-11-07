package com.stremli.tv

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.Locale

/** What a server offers, found by asking it before anything plays. */
class ServerInfo(val subtitles: String?, val heights: List<Int>) {
    val summary: String get() = listOfNotNull(subtitles?.replace("subtitles", "subs"), heights.take(4).joinToString(" ") { "${it}p" }.ifEmpty { null }).joinToString(" · ")
}

/** Answers for the servers of the last few episodes, so switching server does not ask again. */
object ServerInfoCache {
    private const val MAX_ENTRIES = 32
    private val known = object : LinkedHashMap<String, ServerInfo>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ServerInfo>?) = size > MAX_ENTRIES
    }
    /** Blocks on the network the first time; call from an IO dispatcher. */
    fun get(key: String, load: () -> ServerInfo): ServerInfo {
        synchronized(known) { known[key] }?.let { return it }
        val info = load()
        synchronized(known) { known[key] = info }
        return info
    }
}

private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")

/** The video heights an HLS master playlist offers, tallest first. Empty if it has only one stream. */
@OptIn(UnstableApi::class)
fun playlistHeights(source: DataSource, url: String): List<Int> {
    val text = try {
        source.open(DataSpec(Uri.parse(url)))
        String(DataSourceUtil.readToEnd(source), Charsets.UTF_8)
    } finally { source.close() }
    return RESOLUTION.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.distinct().sortedDescending().toList()
}

/**
 * Builds the ExoPlayer used by every player screen.
 *
 * HLS streams start on a low variant and switch up (here 640x360 -> 1920x1080). ExoPlayer
 * normally keeps the same decoder across the switch when the decoder advertises adaptive
 * playback. Some decoders advertise it but keep their original output buffer size, so only the
 * top-left part of each larger frame is shown, stretched to the screen: the "zoomed in" picture.
 * For those decoders the renderer below forces a decoder reset whenever the resolution changes.
 */
@OptIn(UnstableApi::class)
fun buildTvPlayer(context: Context, largeSegments: Boolean = false): ExoPlayer {
    // ExoPlayer's default buffer allows up to 50 s and about 130 MB of video, held in the Java heap.
    // Cap it per device (12 MB, roughly 19 s of 1080p, on 1 GB sticks), or as set in Settings.
    // Video is never written to disk.
    val profile = DeviceProfile.of(context)
    val chosenMb = VideoBuffer.chosenMb(context)
    // Some sources have much larger segments than Gotaku. Keep their low-memory time limits shorter;
    // one segment can exceed the target before the loader has a chance to stop.
    val compact = largeSegments && profile.lowRam && chosenMb == 0
    // A size picked in Settings gets the time to fill.
    val maxBufferMs = maxOf(profile.videoMaxBufferMs, VideoBuffer.seconds(chosenMb) * 1_000)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(if (compact) 6_000 else 15_000, if (compact) minOf(maxBufferMs, 15_000) else maxBufferMs, 2_000, 4_000)
        .setTargetBufferBytes(VideoBuffer.bytes(context))
        .build()
    val player = ExoPlayer.Builder(context, ResolutionSafeRenderersFactory(context))
        .setLoadControl(loadControl)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
    // Show soft subtitles by default when a stream carries them (English first, then the device language).
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setPreferredTextLanguages(*listOf("en", Locale.getDefault().language).distinct().toTypedArray())
        .setSelectUndeterminedTextLanguage(true)
        .build()
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) player.addAnalyticsListener(EventLogger())
    return player
}

/** Decoders observed to mishandle in-place resolution changes. Add others here if they show the same crop. */
private fun brokenAdaptiveDecoder(name: String): Boolean =
    name.contains("goldfish", ignoreCase = true) || name.contains("ranchu", ignoreCase = true)

@OptIn(UnstableApi::class)
private class ResolutionSafeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val start = out.size
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        val index = (start until out.size).firstOrNull { out[it] is MediaCodecVideoRenderer } ?: return
        out[index] = ResolutionSafeVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        )
    }
}

@OptIn(UnstableApi::class)
private class ResolutionSafeVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format): DecoderReuseEvaluation {
        val evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat)
        val resized = oldFormat.width != newFormat.width || oldFormat.height != newFormat.height
        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO || !resized || !brokenAdaptiveDecoder(codecInfo.name)) return evaluation
        return DecoderReuseEvaluation(
            codecInfo.name,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND or DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED
        )
    }
}
