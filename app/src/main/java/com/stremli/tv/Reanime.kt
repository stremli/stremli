package com.stremli.tv

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ReanimeSubtitle(val url: String, val label: String, val language: String, val mimeType: String, val isDefault: Boolean)
data class ReanimeServer(val name: String, val language: String, val url: String, val subtitleType: String = "") {
    val group: String get() = when {
        language == "dub" -> "Dubbed"
        subtitleType == "hard" || name.contains("H-SUB", true) -> "Hard subtitles"
        subtitleType == "soft" -> "Soft subtitles"
        else -> "Subtitled"
    }
}
class ReanimeSession(
    val sourceUrl: String,
    playlistKey: ByteArray,
    val skips: List<SkipSegment>,
    val subtitles: List<ReanimeSubtitle>,
    val headers: Map<String, String>,
    val defaultAudioTrack: Int
) {
    private val key = playlistKey.copyOf()
    /** XOR key for base64-encoded playlists; the caller must validate the decoded #EXTM3U prefix. */
    val playlistKey: ByteArray get() = key.copyOf()
}

/** Blocking network and cryptographic work; call from an IO dispatcher. */
object Reanime {
    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
    private const val EMBED_ORIGIN = "https://flixcloud.cc/"
    private val client by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    fun servers(animeId: Int, malId: Int?, episode: Int): List<ReanimeServer> {
        require(animeId > 0 && episode > 0 && (malId == null || malId > 0))
        val url = "https://reanime.to/api/flix/$animeId/$episode" + (malId?.let { "?mal=$it" } ?: "")
        val root = JSONObject(get(url, "reanime.to", 64 * 1024, "https://reanime.to/"))
        if (!root.optBoolean("success")) throw IOException("Reanime did not return servers")
        val servers = root.optJSONArray("servers") ?: throw IOException("Reanime servers missing")
        if (servers.length() > 64) throw IOException("Too many servers")
        val result = ArrayList<ReanimeServer>()
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val type = server.optString("dataType")
            val name = server.optString("serverName")
            val link = server.optString("dataLink")
            if (type !in listOf("sub", "dub") || name.isBlank() || name.length > 80 || !validEmbed(link)) continue
            val subType = when { server.optBoolean("hardsub") -> "hard"; server.optBoolean("softsub") -> "soft"; else -> "" }
            result.add(ReanimeServer(name, type, link, subType))
        }
        return result
    }

    fun resolve(animeId: Int, malId: Int?, episode: Int): ReanimeSession {
        var last: Exception? = null
        for (server in servers(animeId, malId, episode).filter { it.language == "sub" }) {
            try { return resolve(server) } catch (e: Exception) { last = e }
        }
        throw IOException("No usable Reanime sub stream", last)
    }

    /** How a server carries subtitles. A subtitled stream with no tracks has them in the picture. */
    fun subtitleKind(server: ReanimeServer, session: ReanimeSession): String? = when {
        server.group == "Hard subtitles" -> "Hard subtitles"
        session.subtitles.isNotEmpty() -> "Soft subtitles"
        server.language == "sub" -> "Hard subtitles"
        else -> null
    }

    /** The server list says neither, so this opens the server without playing it. */
    fun info(server: ReanimeServer): ServerInfo {
        val session = resolve(server)
        return ServerInfo(subtitleKind(server, session), playlistHeights(ReanimeDataSource.Factory(session.playlistKey).createDataSource(), session.sourceUrl))
    }

    fun resolve(server: ReanimeServer): ReanimeSession {
        if (server.language !in listOf("sub", "dub") || !validEmbed(server.url)) throw IOException("Invalid Reanime server")
        val embed = server.url.toHttpUrlOrNull()!!.newBuilder()
            .setQueryParameter("a", if (server.language == "dub") "1" else "0").build()
        return resolveEmbed(embed.toString())
    }

    private fun validEmbed(link: String): Boolean {
        val embed = link.toHttpUrlOrNull() ?: return false
        return embed.scheme == "https" && embed.host == "flixcloud.cc" && embed.port == 443 &&
            embed.username.isEmpty() && embed.password.isEmpty() &&
            embed.encodedPath.matches(Regex("/e/[A-Za-z0-9_-]{1,128}")) &&
            (embed.queryParameter("v")?.toIntOrNull()?.let { it in 1..99 } == true) && embed.querySize == 1 && embed.fragment == null
    }

    private fun resolveEmbed(url: String): ReanimeSession {
        val html = get(url, "flixcloud.cc", 192 * 1024, "https://reanime.to/")
        val marker = "data: [null,null,{type:\"data\",data:"
        val begin = html.indexOf(marker).let { if (it < 0) throw IOException("Player data missing") else it + marker.length }
        val data = try { JSONTokener(html.substring(begin)).nextValue() as JSONObject }
            catch (e: Exception) { throw IOException("Unsupported player data", e) }
        val seed = data.getString("obfuscation_seed")
        if (!seed.matches(Regex("[0-9a-f]{8,128}"))) throw IOException("Invalid seed")
        var name = seed
        repeat(3) { name = shaHex(name + it) }
        var name2 = name
        repeat(3) { name2 = shaHex(name2 + it) }
        val inner = data.getJSONObject("obfuscated_crypto_data")
            .getJSONObject("cd_${name.substring(24, 32)}")
            .getJSONArray("ad_${name.substring(32, 40)}").getJSONObject(0)
            .getJSONObject("od_${name.substring(40, 48)}")
        val frag1 = decode(inner.getString("kf_${name.substring(8, 16)}"), 32)
        val iv = decode(inner.getString("ivf_${name.substring(16, 24)}"), 16)
        val frag2 = decode(data.getString("${name2.substring(0, 16)}_${name2.substring(16, 24)}"), 32)
        val token = data.getString("${name.substring(48, 64)}_${name.substring(56, 64)}")
        if (!token.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw IOException("Invalid playback token")
        val reply = JSONObject(get("${EMBED_ORIGIN}api/m3u8/$token", "flixcloud.cc", 32 * 1024, url))
        val encrypted = decode(reply.getString(shaHex(token + "vid").substring(0, 10)), null)
        if (encrypted.isEmpty() || encrypted.size > 4096 || encrypted.size % 16 != 0) throw IOException("Invalid encrypted URL")
        val frag3 = decode(reply.getString(shaHex(token + "key").substring(0, 10)), 32)
        val wasm = decode(data.getString("w_payload"), null)
        val decoded = decodeWasm(wasm, frag1, frag2, frag3, seed.substring(0, 8).toLong(16).toInt())
        val derived = pbkdf2(decoded.first, seed.toByteArray(Charsets.UTF_8))
        for (i in derived.indices) derived[i] = (derived[i].toInt() xor seed[i % seed.length].code).toByte()
        val key = MessageDigest.getInstance("SHA-256").digest(derived)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val stream = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        if (!safeMediaUrl(stream)) throw IOException("Invalid stream URL")
        val subtitles = ArrayList<ReanimeSubtitle>()
        data.optJSONArray("subtitles")?.let { a ->
            if (a.length() > 128) throw IOException("Too many subtitles")
            for (i in 0 until a.length()) {
                val sub = a.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url")
                if (!safeMediaUrl(subUrl)) continue
                val mime = when (sub.optString("format").lowercase()) {
                    "vtt" -> "text/vtt"
                    "srt" -> "application/x-subrip"
                    "ass", "ssa" -> "text/x-ssa"
                    else -> continue
                }
                val label = sub.optString("language").take(100)
                subtitles.add(ReanimeSubtitle(subUrl, label, subtitleLanguage(label), mime, sub.optBoolean("default")))
            }
        }
        val skips = ArrayList<SkipSegment>(2)
        fun chapter(field: String, kind: SkipKind) {
            val c = data.optJSONObject(field) ?: return
            val start = c.optDouble("start", Double.NaN)
            val end = c.optDouble("end", Double.NaN)
            if (start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= 86400)
                skips.add(SkipSegment(kind, (start * 1000).toLong(), (end * 1000).toLong(), 0, priority = 0))
        }
        chapter("intro_chapter", SkipKind.Intro)
        chapter("outro_chapter", SkipKind.Outro)
        val defaultAudioTrack = data.optInt("default_audio_track", 0)
        if (defaultAudioTrack !in 0..1) throw IOException("Invalid default audio track")
        return ReanimeSession(stream, decoded.second, skips, subtitles, mapOf("User-Agent" to UA, "Referer" to EMBED_ORIGIN, "Origin" to "https://flixcloud.cc"), defaultAudioTrack)
    }

    private fun subtitleLanguage(label: String): String = when (label.substringBefore('(').trim().lowercase()) {
        "arabic" -> "ar"; "chinese" -> "zh"; "czech" -> "cs"; "danish" -> "da"
        "dutch" -> "nl"; "english" -> "en"; "finnish" -> "fi"; "french" -> "fr"
        "german" -> "de"; "greek" -> "el"; "hebrew" -> "he"; "croatian" -> "hr"
        "hungarian" -> "hu"; "italian" -> "it"; "japanese" -> "ja"; "korean" -> "ko"
        "norwegian" -> "no"; "polish" -> "pl"; "portuguese" -> "pt"; "romanian" -> "ro"
        "slovak" -> "sk"; "spanish" -> "es"; "swedish" -> "sv"; "turkish" -> "tr"
        "ukrainian" -> "uk"; "russian" -> "ru"; "indonesian" -> "id"; "thai" -> "th"
        "vietnamese" -> "vi"; "hindi" -> "hi"; else -> "und"
    }

    private val mediaCdn = Regex("""(?:[a-z0-9-]+\.)*[a-z0-9-]*cdn\.top""")

    private fun safeMediaUrl(value: String): Boolean {
        val u = value.toHttpUrlOrNull() ?: return false
        // The player's CDN names change often (rundowncdn, forestcdn, cobaltcdn...), all under *cdn.top.
        return u.scheme == "https" && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() &&
            (u.host == "flixcloud.cc" || u.host.endsWith(".flixcloud.cc") || mediaCdn.matches(u.host))
    }

    private fun get(url: String, host: String, limit: Int, referer: String): String {
        val u = url.toHttpUrlOrNull() ?: throw IOException("Invalid URL")
        if (u.scheme != "https" || u.host != host || u.port != 443 || u.username.isNotEmpty() || u.password.isNotEmpty()) throw IOException("Unexpected host")
        val req = Request.Builder().url(u).header("User-Agent", UA).header("Referer", referer).build()
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Provider HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty provider response")
            if (body.contentLength() > limit) throw IOException("Provider response too large")
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > limit) throw IOException("Provider response too large")
                    out.write(buf, 0, n)
                }
            }
            out.toString("UTF-8")
        }
    }

    private fun shaHex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun decode(s: String, size: Int?): ByteArray {
        if (s.length > 8192 || !s.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) throw IOException("Invalid base64")
        val b = try { Base64.decode(s, Base64.DEFAULT) } catch (e: IllegalArgumentException) { throw IOException("Invalid base64", e) }
        if (size != null && b.size != size) throw IOException("Invalid key length")
        return b
    }
    private fun pbkdf2(key: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(999) { u = mac.doFinal(u); for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte() }
        return out
    }

    // No WASM is executed. Only the exact observed module and _r instruction grammar are accepted.
    private class Bytes(private val data: ByteArray) {
        var p = 0
        fun byte(): Int { if (p >= data.size) throw IOException("Truncated WASM"); return data[p++].toInt() and 255 }
        fun uint(): Int {
            var v = 0
            for (i in 0..4) { val b = byte(); if (i == 4 && b > 7) throw IOException("Invalid WASM integer"); v = v or ((b and 127) shl (7 * i)); if (b < 128) return v }
            throw IOException("Invalid WASM integer")
        }
        fun take(n: Int): ByteArray { if (n < 0 || n > data.size - p) throw IOException("Invalid WASM length"); return data.copyOfRange(p, p + n).also { p += n } }
        fun expect(hex: String) { for (s in hex.trim().split(' ')) if (byte() != s.toInt(16)) throw IOException("Unsupported WASM structure") }
        fun done() { if (p != data.size) throw IOException("Unexpected WASM data") }
    }
    private fun decodeWasm(wasm: ByteArray, a: ByteArray, b: ByteArray, c: ByteArray, seed: Int): Pair<ByteArray, ByteArray> {
        if (wasm.size !in 200..1024) throw IOException("Invalid WASM size")
        val w = Bytes(wasm)
        w.expect("00 61 73 6d 01 00 00 00")
        fun section(id: Int): Bytes { if (w.byte() != id) throw IOException("Unsupported WASM section"); return Bytes(w.take(w.uint())) }
        fun fixed(id: Int, value: String) { section(id).apply { expect(value); done() } }
        fixed(1, "03 60 01 7f 00 60 05 7f 7f 7f 7f 7f 00 60 00 01 7f")
        fixed(3, "03 00 01 02")
        fixed(5, "01 00 01")
        fixed(6, "01 7f 01 41 00 0b")
        fixed(7, "04 06 6d 65 6d 6f 72 79 02 00 02 5f 73 00 00 02 5f 72 00 01 02 5f 63 00 02")
        val code = section(10)
        code.expect("03")
        Bytes(code.take(code.uint())).apply { expect("00 20 00 24 00 0b"); done() }
        val r = Bytes(code.take(code.uint()))
        r.expect("01 03 7f 41 00 21 05 03 40 02 40 20 05 20 04 4f 0d 00 20 00 20 05 6a 2d 00 00 20 01 20 05 6a 2d 00 00 73 20 02 20 05 6a 2d 00 00 73 21 06")
        val operations = ArrayList<(Int) -> Int>()
        var multiplier: Int
        while (true) {
            r.expect("20")
            when (r.byte()) {
                5 -> { r.expect("41"); multiplier = r.uint(); if (multiplier > 255) throw IOException("Invalid WASM multiplier"); break }
                6 -> {
                    if (operations.size >= 32) throw IOException("Too many WASM operations")
                    r.expect("41"); val n = r.uint(); val op = r.byte()
                    if (op == 0x73) {
                        if (n > 255) throw IOException("Invalid WASM operand")
                        r.expect("21 06"); operations.add { it xor n }
                    } else if (op == 0x6a || op == 0x6b) {
                        if (n > 255) throw IOException("Invalid WASM operand")
                        r.expect("41 ff 01 71 21 06"); operations.add { (if (op == 0x6a) it + n else it - n) and 255 }
                    } else if (op == 0x74 || op == 0x76) {
                        if (n > 8) throw IOException("Invalid WASM shift")
                        if (op == 0x74) r.expect("41 ff 01 71")
                        r.expect("20 06 41"); val other = r.uint()
                        if (other > 8 || n + other != 8) throw IOException("Invalid WASM rotation")
                        if (op == 0x74) r.expect("76 72 21 06") else r.expect("74 41 ff 01 71 72 21 06")
                        operations.add { if (op == 0x74) ((it shl n) and 255) or (it ushr other) else (it ushr n) or ((it shl other) and 255) }
                    } else throw IOException("Unsupported WASM operation")
                }
                else -> throw IOException("Unsupported WASM local")
            }
        }
        r.expect("6c 23 00 6a 41 ff 01 71 21 07 20 06 20 07 73 21 06 20 03 20 05 6a 20 06 3a 00 00 20 05 41 01 6a 21 05 0c 01 0b 0b 0b"); r.done()
        Bytes(code.take(code.uint())).apply { expect("01 01 7f 41 00 21 00 03 40 02 40 20 00 41 20 4f 0d 00 41 90 10 20 00 6a 41 d0 0f 20 00 6a 2d 00 00 41 f0 0f 20 00 6a 2d 00 00 73 3a 00 00 20 00 41 01 6a 21 00 0c 01 0b 0b 41 90 10 0b"); done() }
        code.done()
        val keyData = section(11).run { expect("01 00 41 d0 0f 0b 40"); val bytes = take(64); done(); bytes }
        w.done()
        val material = ByteArray(32) { i ->
            var v = (a[i].toInt() xor b[i].toInt() xor c[i].toInt()) and 255
            for (operation in operations) v = operation(v)
            (v xor ((i * multiplier + seed) and 255)).toByte()
        }
        val playlistKey = ByteArray(32) { i -> (keyData[i].toInt() xor keyData[i + 32].toInt()).toByte() }
        return material to playlistKey
    }
}
