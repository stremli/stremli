package com.stremli.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "Trackers"
private const val UA = "Stremli/1.0 (Android TV)"
private val JSON_TYPE = "application/json".toMediaType()

/** The service rejected the token: the viewer has to sign in again. */
class AuthExpired(service: Service) : IOException("${service.label} sign-in expired")
/** The service asked us to slow down; nothing is sent to it until [until]. */
class RateLimited(service: Service, val until: Long) : IOException("${service.label} rate limit")

// ---- Tokens at rest ---------------------------------------------------------------------------

/**
 * Encrypts tokens with a key kept in the Android Keystore, so a copy of the app's preference files
 * is useless on its own. If the Keystore is broken (it is on a few boxes), the value is stored
 * encoded but unencrypted rather than not at all.
 */
private object Vault {
    private const val ALIAS = "stremli-trackers"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    fun seal(plain: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        "k1:" + b64(cipher.iv) + ":" + b64(cipher.doFinal(plain.toByteArray()))
    }.getOrElse { Log.w(TAG, "keystore unavailable: ${it.message}"); "p1:" + b64(plain.toByteArray()) }

    fun open(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        return runCatching {
            when {
                sealed.startsWith("k1:") -> {
                    val (iv, ct) = sealed.removePrefix("k1:").split(':').let { unb64(it[0]) to unb64(it[1]) }
                    String(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(ct))
                }
                sealed.startsWith("p1:") -> String(unb64(sealed.removePrefix("p1:")))
                else -> null
            }
        }.getOrNull()
    }
}

private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
private fun unb64(text: String) = Base64.decode(text, Base64.DEFAULT)

// ---- Accounts ---------------------------------------------------------------------------------

/**
 * A profile's signed-in account on a service. [expires] is when the access token stops working
 * (AniList: a year, no refresh; MAL: about a month, then [refresh] renews it). [needsLogin] is set
 * when the service rejected the token; the library stays, and syncing waits for a new sign-in.
 */
data class Account(
    val service: Service, val userId: Int, val name: String, val avatar: String,
    val token: String, val refresh: String, val expires: Long, val clientId: String,
    val needsLogin: Boolean = false
)

/** Accounts live in the profile's own preferences, so every profile signs in separately. */
object Accounts {
    private val listeners = MutableStateFlow(0)
    /** Bumped when any account changes. */
    val changes: StateFlow<Int> = listeners

    private fun key(service: Service) = "account:${service.key}"

    fun get(context: Context, profileId: String, service: Service): Account? {
        val raw = Profiles.prefsFor(context, profileId).getString(key(service), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Account(service, o.optInt("userId"), o.optString("name"), o.optString("avatar"),
                Vault.open(o.optString("token")) ?: return null, Vault.open(o.optString("refresh")).orEmpty(),
                o.optLong("expires"), o.optString("clientId"), o.optBoolean("needsLogin"))
        }.getOrNull()
    }

    fun put(context: Context, profileId: String, a: Account) {
        val o = JSONObject().put("userId", a.userId).put("name", a.name).put("avatar", a.avatar)
            .put("token", Vault.seal(a.token)).put("refresh", if (a.refresh.isEmpty()) "" else Vault.seal(a.refresh))
            .put("expires", a.expires).put("clientId", a.clientId).put("needsLogin", a.needsLogin)
        Profiles.prefsFor(context, profileId).edit().putString(key(a.service), o.toString()).apply()
        listeners.value++
    }

    fun remove(context: Context, profileId: String, service: Service) {
        Profiles.prefsFor(context, profileId).edit().remove(key(service)).apply()
        listeners.value++
    }
}

// ---- Rate limits ------------------------------------------------------------------------------

/**
 * Spaces requests to one service and honours its back-off. AniList allows 90 requests a minute
 * (30 while its API is degraded, as it is now) and locks a client out for a minute after a 429;
 * the limit header sets the spacing. MyAnimeList publishes no number and answers bursts with a
 * temporary 403 ("DoS detected"), so its requests are spaced out generously.
 */
private class RateGate(private val service: Service, @Volatile var intervalMs: Long) {
    private val mutex = Mutex()
    private var next = 0L
    @Volatile var blockedUntil = 0L

    suspend fun <T> run(block: () -> T): T = mutex.withLock {
        val now = System.currentTimeMillis()
        if (blockedUntil > now + 90_000) throw RateLimited(service, blockedUntil)
        val wait = maxOf(next, blockedUntil) - now
        if (wait > 0) delay(wait)
        try { block() } finally { next = System.currentTimeMillis() + intervalMs }
    }

    fun block(ms: Long) { blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + ms) }
}

// ---- JSON streaming helpers ---------------------------------------------------------------------

private fun JsonReader.str(): String = when (peek()) { JsonToken.NULL -> { nextNull(); "" }; JsonToken.STRING, JsonToken.NUMBER -> nextString(); else -> { skipValue(); "" } }
private fun JsonReader.int(): Int = when (peek()) { JsonToken.NUMBER -> runCatching { nextInt() }.getOrElse { nextDouble().toInt() }; JsonToken.STRING -> nextString().toDoubleOrNull()?.toInt() ?: 0; JsonToken.NULL -> { nextNull(); 0 }; else -> { skipValue(); 0 } }
private fun JsonReader.double(): Double = when (peek()) { JsonToken.NUMBER -> nextDouble(); JsonToken.STRING -> nextString().toDoubleOrNull() ?: 0.0; JsonToken.NULL -> { nextNull(); 0.0 }; else -> { skipValue(); 0.0 } }
private fun JsonReader.bool(): Boolean = when (peek()) { JsonToken.BOOLEAN -> nextBoolean(); JsonToken.NULL -> { nextNull(); false }; else -> { skipValue(); false } }
private inline fun JsonReader.obj(field: (String) -> Unit) {
    if (peek() == JsonToken.NULL) { nextNull(); return }
    beginObject(); while (hasNext()) field(nextName()); endObject()
}
private inline fun JsonReader.arr(item: () -> Unit) {
    if (peek() == JsonToken.NULL) { nextNull(); return }
    beginArray(); while (hasNext()) item(); endArray()
}

private fun fuzzyDate(r: JsonReader): String {
    var y = 0; var m = 0; var d = 0
    r.obj { when (it) { "year" -> y = r.int(); "month" -> m = r.int(); "day" -> d = r.int(); else -> r.skipValue() } }
    return if (y > 0) "%04d-%02d-%02d".format(Locale.US, y, m.coerceAtLeast(1), d.coerceAtLeast(1)) else ""
}

private fun fuzzyInput(date: String): JSONObject? {
    val parts = date.split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return null
    return JSONObject().put("year", parts[0]).put("month", parts[1]).put("day", parts[2])
}

// ---- AniList ----------------------------------------------------------------------------------

private object AniList {
    const val URL = "https://graphql.anilist.co"
    val gate = RateGate(Service.AniList, 2_100)

    private const val VIEWER = "query{Viewer{id name avatar{medium} mediaListOptions{animeList{customLists}}}}"
    private const val COLLECTION = "query(\$u:Int,\$c:Int){MediaListCollection(userId:\$u,type:ANIME,chunk:\$c,perChunk:500,forceSingleCompletedList:true){hasNextChunk lists{entries{id mediaId status progress score(format:POINT_100) repeat updatedAt startedAt{year month day} completedAt{year month day} customLists(asArray:true) media{idMal episodes format seasonYear title{userPreferred} coverImage{large}}}}}}"
    private const val SAVE = "mutation(\$m:Int,\$s:MediaListStatus,\$p:Int,\$sc:Int,\$r:Int,\$cl:[String],\$st:FuzzyDateInput,\$co:FuzzyDateInput){SaveMediaListEntry(mediaId:\$m,status:\$s,progress:\$p,scoreRaw:\$sc,repeat:\$r,customLists:\$cl,startedAt:\$st,completedAt:\$co){id updatedAt}}"
    private const val DELETE = "mutation(\$id:Int){DeleteMediaListEntry(id:\$id){deleted}}"
    private const val BY_MAL = "query(\$m:Int){Media(idMal:\$m,type:ANIME){id}}"
    private const val SET_LISTS = "mutation(\$l:[String]){UpdateUser(animeListOptions:{customLists:\$l}){id}}"
    private const val DELETE_LIST = "mutation(\$n:String){DeleteCustomList(customList:\$n,type:ANIME){deleted}}"

    private class Box<T>(val value: T)

    /** One GraphQL request; [read] streams the response body. A 429 waits out AniList's lock-out and retries. */
    suspend fun <T> call(token: String?, query: String, variables: JSONObject, read: (JsonReader) -> T): T = withContext(Dispatchers.IO) {
        for (attempt in 1..3) {
            val box: Box<T>? = gate.run {
                val body = JSONObject().put("query", query).put("variables", variables).toString().toRequestBody(JSON_TYPE)
                val request = Request.Builder().url(URL).post(body).header("User-Agent", UA).header("Accept", "application/json")
                    .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
                Http.client.newCall(request).execute().use { r ->
                    r.header("X-RateLimit-Limit")?.toIntOrNull()?.takeIf { it > 0 }?.let { gate.intervalMs = 60_000L / it + 150 }
                    if ((r.header("X-RateLimit-Remaining")?.toIntOrNull() ?: 99) <= 1) gate.block(20_000)
                    when {
                        r.code == 429 -> { gate.block((r.header("Retry-After")?.toLongOrNull() ?: 60L) * 1000 + 500); null }
                        r.code == 401 -> throw AuthExpired(Service.AniList)
                        r.isSuccessful -> Box(JsonReader(r.body!!.charStream()).use(read))
                        else -> {
                            val text = r.body?.string().orEmpty()
                            if (text.contains("Invalid token", true) || text.contains("Unauthorized", true)) throw AuthExpired(Service.AniList)
                            // "Not Found" (no such title or list entry) is an answer, not a failure.
                            if (r.code == 404) Box(JsonReader("{}".reader()).use(read)) else throw IOException("AniList HTTP ${r.code}: ${text.take(160)}")
                        }
                    }
                }
            }
            if (box != null) return@withContext box.value
        }
        throw RateLimited(Service.AniList, gate.blockedUntil)
    }

    /** Small answers (a few hundred bytes) as a tree: the response's "data", or null when not found. */
    suspend fun small(token: String?, query: String, variables: JSONObject = JSONObject()): JSONObject? {
        val o = call(token, query, variables) { readTree(it) as? JSONObject } ?: return null
        o.optJSONObject("data")?.let { return it }
        val errors = o.optJSONArray("errors")
        if (errors != null && !errors.toString().contains("Not Found", true)) throw IOException("AniList: ${errors.toString().take(160)}")
        return null
    }

    fun readTree(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { o -> r.beginObject(); while (r.hasNext()) { val n = r.nextName(); o.put(n, readTree(r) ?: JSONObject.NULL) }; r.endObject() }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { a -> r.beginArray(); while (r.hasNext()) a.put(readTree(r) ?: JSONObject.NULL); r.endArray() }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString().let { s -> s.toLongOrNull() ?: s.toDouble() }
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    class Viewer(val id: Int, val name: String, val avatar: String, val customLists: List<String>)

    suspend fun viewer(token: String): Viewer {
        val v = small(token, VIEWER)?.optJSONObject("Viewer") ?: throw AuthExpired(Service.AniList)
        val lists = v.optJSONObject("mediaListOptions")?.optJSONObject("animeList")?.optJSONArray("customLists")
        return Viewer(v.optInt("id"), v.optString("name"), v.optJSONObject("avatar")?.optString("medium").orEmpty(),
            lists?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty())
    }

    /** The viewer's whole anime list, streamed (500 entries a request). */
    suspend fun list(token: String, userId: Int): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        val seen = HashSet<Int>()
        var chunk = 1
        while (chunk < 100) {
            var more = false
            call(token, COLLECTION, JSONObject().put("u", userId).put("c", chunk)) { r ->
                r.obj { top ->
                    if (top != "data") { if (top == "errors") throw IOException("AniList: list query failed") else r.skipValue(); return@obj }
                    r.obj { if (it != "MediaListCollection") r.skipValue() else r.obj { f ->
                        when (f) {
                            "hasNextChunk" -> more = r.bool()
                            "lists" -> r.arr { r.obj { g -> if (g != "entries") r.skipValue() else r.arr { readEntry(r)?.let { e -> if (seen.add(e.remoteId)) out.add(e) } } } }
                            else -> r.skipValue()
                        }
                    } }
                }
            }
            if (!more) break
            chunk++
        }
        return out
    }

    private fun readEntry(r: JsonReader): RemoteEntry? {
        var id = 0; var mediaId = 0; var status = ""; var progress = 0; var score = 0.0; var repeat = 0; var updated = 0L
        var started = ""; var completed = ""; val lists = ArrayList<String>()
        var mal = 0; var episodes = 0; var format = ""; var year = 0; var title = ""; var poster = ""
        r.obj { f ->
            when (f) {
                "id" -> id = r.int(); "mediaId" -> mediaId = r.int(); "status" -> status = r.str(); "progress" -> progress = r.int()
                "score" -> score = r.double(); "repeat" -> repeat = r.int(); "updatedAt" -> updated = r.double().toLong() * 1000
                "startedAt" -> started = fuzzyDate(r); "completedAt" -> completed = fuzzyDate(r)
                "customLists" -> r.arr { var name = ""; var on = false; r.obj { k -> when (k) { "name" -> name = r.str(); "enabled" -> on = r.bool(); else -> r.skipValue() } }; if (on && name.isNotBlank()) lists.add(name) }
                "media" -> r.obj { m ->
                    when (m) {
                        "idMal" -> mal = r.int(); "episodes" -> episodes = r.int(); "format" -> format = r.str(); "seasonYear" -> year = r.int()
                        "title" -> r.obj { t -> if (t == "userPreferred") title = r.str() else r.skipValue() }
                        "coverImage" -> r.obj { c -> if (c == "large") poster = r.str() else r.skipValue() }
                        else -> r.skipValue()
                    }
                }
                else -> r.skipValue()
            }
        }
        val s = ListStatus.ofAnilist(status) ?: return null
        if (mediaId <= 0) return null
        return RemoteEntry(id, mediaId, mal.takeIf { it > 0 }, s, progress, Math.round(score).toInt(), repeat, updated, started, completed, lists, title, poster, episodes, format, year)
    }

    class Saved(val id: Int, val updated: Long)

    suspend fun save(token: String, mediaId: Int, e: LibraryEntry, lists: List<String>?): Saved {
        val v = JSONObject().put("m", mediaId).put("s", e.status.anilist).put("p", e.progress).put("sc", Math.round(e.score * 10)).put("r", e.repeat)
        fuzzyInput(e.started)?.let { v.put("st", it) }
        fuzzyInput(e.completed)?.let { v.put("co", it) }
        if (lists != null) v.put("cl", JSONArray(lists))
        val saved = small(token, SAVE, v)?.optJSONObject("SaveMediaListEntry") ?: throw IOException("AniList did not save the entry")
        return Saved(saved.optInt("id"), saved.optLong("updatedAt") * 1000)
    }

    suspend fun delete(token: String, entryId: Int) { small(token, DELETE, JSONObject().put("id", entryId)) }

    suspend fun idForMal(mal: Int): Int? = small(null, BY_MAL, JSONObject().put("m", mal))?.optJSONObject("Media")?.optInt("id")?.takeIf { it > 0 }

    suspend fun setCustomLists(token: String, names: List<String>) { small(token, SET_LISTS, JSONObject().put("l", JSONArray(names))) }
    suspend fun deleteCustomList(token: String, name: String) { small(token, DELETE_LIST, JSONObject().put("n", name)) }
}

// ---- MyAnimeList ------------------------------------------------------------------------------

private object Mal {
    const val API = "https://api.myanimelist.net/v2"
    const val TOKEN = "https://myanimelist.net/v1/oauth2/token"
    val gate = RateGate(Service.Mal, 700)
    private val isoDate = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US) }

    private fun <T> handle(r: Response, read: (Response) -> T): T {
        when (r.code) {
            401 -> throw AuthExpired(Service.Mal)
            // MAL answers too many requests with 403 ("DoS detected") and a temporary block.
            403, 429 -> { gate.block(15 * 60_000L); throw RateLimited(Service.Mal, gate.blockedUntil) }
        }
        return read(r)
    }

    suspend fun <T> request(build: Request.Builder, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        gate.run { Http.client.newCall(build.header("User-Agent", UA).build()).execute().use { handle(it, read) } }
    }

    class Tokens(val access: String, val refresh: String, val expires: Long)

    private fun tokens(r: Response): Tokens {
        val text = r.body?.string().orEmpty()
        if (r.code == 400 || r.code == 401) throw AuthExpired(Service.Mal)
        if (!r.isSuccessful) throw IOException("MyAnimeList sign-in failed (HTTP ${r.code})")
        val o = JSONObject(text)
        return Tokens(o.getString("access_token"), o.optString("refresh_token"), System.currentTimeMillis() + o.optLong("expires_in", 2_592_000L) * 1000)
    }

    suspend fun exchange(clientId: String, code: String, verifier: String, redirectUri: String): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("client_id", clientId).add("grant_type", "authorization_code").add("code", code)
            .add("code_verifier", verifier).add("redirect_uri", redirectUri).build()
        // Token requests do not count toward the API limits, so they skip the gate.
        Http.client.newCall(Request.Builder().url(TOKEN).post(form).header("User-Agent", UA).build()).execute().use { tokens(it) }
    }

    suspend fun refresh(clientId: String, refresh: String): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("client_id", clientId).add("grant_type", "refresh_token").add("refresh_token", refresh).build()
        Http.client.newCall(Request.Builder().url(TOKEN).post(form).header("User-Agent", UA).build()).execute().use { tokens(it) }
    }

    suspend fun me(token: String): Triple<Int, String, String> = request(Request.Builder().url("$API/users/@me?fields=picture").header("Authorization", "Bearer $token")) { r ->
        if (!r.isSuccessful) throw IOException("MyAnimeList HTTP ${r.code}")
        val o = JSONObject(r.body?.string().orEmpty())
        Triple(o.optInt("id"), o.optString("name"), o.optString("picture"))
    }

    /** The viewer's whole anime list, streamed, 1000 entries a page (the API's maximum). */
    suspend fun list(token: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var url: String? = "$API/users/@me/animelist?fields=list_status,num_episodes,media_type,start_season&limit=1000&nsfw=true"
        var pages = 0
        while (url != null && pages++ < 50) {
            var next: String? = null
            request(Request.Builder().url(url).header("Authorization", "Bearer $token")) { r ->
                if (!r.isSuccessful) throw IOException("MyAnimeList HTTP ${r.code}")
                JsonReader(r.body!!.charStream()).use { reader ->
                    reader.obj { top ->
                        when (top) {
                            "data" -> reader.arr { readItem(reader)?.let(out::add) }
                            "paging" -> reader.obj { p -> if (p == "next") next = reader.str().ifBlank { null } else reader.skipValue() }
                            else -> reader.skipValue()
                        }
                    }
                }
            }
            url = next
        }
        return out
    }

    private fun readItem(r: JsonReader): RemoteEntry? {
        var mal = 0; var title = ""; var poster = ""; var episodes = 0; var format = ""; var year = 0
        var status = ""; var score = 0; var watched = 0; var rewatching = false; var updated = 0L; var started = ""; var finished = ""; var repeat = 0
        r.obj { f ->
            when (f) {
                "node" -> r.obj { n ->
                    when (n) {
                        "id" -> mal = r.int(); "title" -> title = r.str(); "num_episodes" -> episodes = r.int()
                        "media_type" -> format = when (val t = r.str()) { "tv" -> "TV"; "movie" -> "MOVIE"; "ova" -> "OVA"; "ona" -> "ONA"; "special" -> "SPECIAL"; "music" -> "MUSIC"; else -> t.uppercase(Locale.US) }
                        "main_picture" -> r.obj { p -> if (p == "large" || (p == "medium" && poster.isEmpty())) poster = r.str() else r.skipValue() }
                        "start_season" -> r.obj { s -> if (s == "year") year = r.int() else r.skipValue() }
                        else -> r.skipValue()
                    }
                }
                "list_status" -> r.obj { s ->
                    when (s) {
                        "status" -> status = r.str(); "score" -> score = r.int(); "num_episodes_watched" -> watched = r.int()
                        "is_rewatching" -> rewatching = r.bool(); "num_times_rewatched" -> repeat = r.int()
                        "updated_at" -> updated = parseTime(r.str()); "start_date" -> started = r.str(); "finish_date" -> finished = r.str()
                        else -> r.skipValue()
                    }
                }
                else -> r.skipValue()
            }
        }
        val s = ListStatus.ofMal(status, rewatching) ?: return null
        if (mal <= 0) return null
        return RemoteEntry(mal, null, mal, s, watched, score, repeat, updated, started.takeIf { it.length == 10 }.orEmpty(), finished.takeIf { it.length == 10 }.orEmpty(), null, title, poster, episodes, format, year)
    }

    fun parseTime(text: String): Long = runCatching { isoDate.get()!!.parse(text)!!.time }.getOrDefault(0L)

    suspend fun save(token: String, mal: Int, e: LibraryEntry): Long {
        val form = FormBody.Builder()
            .add("status", e.status.mal)
            .add("is_rewatching", (e.status == ListStatus.REWATCHING).toString())
            .add("score", Math.round(e.score).coerceIn(0, 10).toString())
            .add("num_watched_episodes", e.progress.toString())
            .add("num_times_rewatched", e.repeat.toString())
            .build()
        return request(Request.Builder().url("$API/anime/$mal/my_list_status").patch(form).header("Authorization", "Bearer $token")) { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 404) return@request -1L // MAL has no such title
            if (!r.isSuccessful) throw IOException("MyAnimeList HTTP ${r.code}: ${text.take(120)}")
            parseTime(runCatching { JSONObject(text).optString("updated_at") }.getOrDefault("")).takeIf { it > 0 } ?: System.currentTimeMillis()
        }
    }

    suspend fun delete(token: String, mal: Int) {
        request(Request.Builder().url("$API/anime/$mal/my_list_status").delete().header("Authorization", "Bearer $token")) { r ->
            // 404 means it was not on the list, which is the goal.
            if (!r.isSuccessful && r.code != 404) throw IOException("MyAnimeList HTTP ${r.code}")
        }
    }
}

// ---- Sign-in by QR code -------------------------------------------------------------------------

/**
 * Signing in from a phone. The TV asks the relay ([BuildConfig.TRACKER_RELAY_URL], `relay/` in this
 * repository) for a short code and shows it as a QR code; the phone signs in on the service's own
 * site and the relay hands the result back. The result is encrypted on the phone to a key pair
 * made here for this one sign-in, so the relay only ever holds ciphertext. MAL uses PKCE: the
 * verifier is made here and the code is exchanged for tokens here, with the public client id.
 */
object TrackerLogin {
    private val base get() = BuildConfig.TRACKER_RELAY_URL.trimEnd('/')
    private val random = SecureRandom()

    class Ticket(
        val service: Service, val code: String, val url: String, val site: String, val expiresAt: Long,
        internal val sid: String, internal val keys: KeyPair, internal val verifier: String?
    ) {
        /** "ABCD-2345": easier to read off the screen. */
        val shownCode get() = code.take(4) + "-" + code.drop(4)
    }

    sealed class Poll {
        data class Waiting(val opened: Boolean) : Poll()
        data class Done(val account: Account) : Poll()
        data object Cancelled : Poll()
        data object Expired : Poll()
        /** The phone finished, but the account could not be set up (the service refused, or the network failed). */
        data class Failed(val message: String) : Poll()
    }

    /** Which services the relay has client ids for. */
    suspend fun available(): Set<Service> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get("$base/api/config"))
            buildSet { if (!o.isNull("anilist")) add(Service.AniList); if (!o.isNull("mal")) add(Service.Mal) }
        }.getOrDefault(emptySet())
    }

    private fun get(url: String): String =
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }

    suspend fun start(service: Service): Ticket = withContext(Dispatchers.IO) {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        // PKCE "plain" (all MAL supports): 64 characters from the unreserved set.
        val verifier = if (service == Service.Mal) Base64.encodeToString(ByteArray(48).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) else null
        val body = JSONObject().put("service", service.key).put("pub", b64(keys.public.encoded)).apply { if (verifier != null) put("challenge", verifier) }
        val request = Request.Builder().url("$base/api/session").post(body.toString().toRequestBody(JSON_TYPE)).header("User-Agent", UA).build()
        val o = Http.client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 503) throw IOException(runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "Sign-in is not available right now.")
            if (!r.isSuccessful) throw IOException("Could not start sign-in (HTTP ${r.code}).")
            JSONObject(text)
        }
        Ticket(service, o.getString("code"), o.getString("url"), o.optString("site"), System.currentTimeMillis() + o.optLong("expiresIn", 600) * 1000, o.getString("sid"), keys, verifier)
    }

    suspend fun poll(ticket: Ticket): Poll = withContext(Dispatchers.IO) {
        val o = JSONObject(get("$base/api/poll?code=${ticket.code}&sid=${java.net.URLEncoder.encode(ticket.sid, "UTF-8")}"))
        when (o.optString("state")) {
            "waiting" -> Poll.Waiting(false)
            "opened" -> Poll.Waiting(true)
            "done" -> {
                val p = o.getJSONObject("payload")
                val result = JSONObject(decrypt(ticket.keys, p.getString("epk"), p.getString("iv"), p.getString("ct")))
                // The session is gone once collected, so a failure here is final: say so, do not keep polling.
                if (result.has("error")) Poll.Cancelled
                else try { Poll.Done(finish(ticket, result)) } catch (e: AuthExpired) {
                    Poll.Failed("${ticket.service.label} didn't accept the sign-in. Get a new code and try again.")
                } catch (e: Exception) {
                    Log.w(TAG, "finish ${ticket.service.key}: ${e.javaClass.simpleName}: ${e.message}")
                    Poll.Failed("Couldn't finish signing in to ${ticket.service.label}. Check your connection, then get a new code.")
                }
            }
            else -> Poll.Expired
        }
    }

    private fun decrypt(keys: KeyPair, epk: String, iv: String, ct: String): String {
        val theirs = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(epk)))
        val shared = KeyAgreement.getInstance("ECDH").apply { init(keys.private); doPhase(theirs, true) }.generateSecret()
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(shared), "AES")
        return String(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(iv))) }.doFinal(unb64(ct)))
    }

    private suspend fun finish(ticket: Ticket, result: JSONObject): Account = when (ticket.service) {
        Service.AniList -> {
            val token = result.getString("access_token")
            val viewer = AniList.viewer(token)
            val lifetime = result.optLong("expires_in", 31_536_000L).takeIf { it > 0 } ?: 31_536_000L
            Account(Service.AniList, viewer.id, viewer.name, viewer.avatar, token, "", System.currentTimeMillis() + lifetime * 1000, "")
        }
        Service.Mal -> {
            val config = JSONObject(withContext(Dispatchers.IO) { get("$base/api/config") }).getJSONObject("mal")
            val clientId = config.getString("clientId")
            val tokens = Mal.exchange(clientId, result.getString("code"), ticket.verifier!!, config.getString("redirectUri"))
            val (id, name, picture) = Mal.me(tokens.access)
            Account(Service.Mal, id, name, picture, tokens.access, tokens.refresh, tokens.expires, clientId)
        }
    }
}

// ---- Sync ---------------------------------------------------------------------------------------

/**
 * Keeps a profile's library and its connected services in step. A pull reads each service's whole
 * list (AniList: one request per 500 titles; MAL: one per 1000) and merges it ([Library.merge]);
 * a push sends queued titles one request each, spaced by the services' limits. Pulls happen when
 * the app opens, when the library is shown, on "Sync now" and on the profile's interval while the app
 * is on screen and not playing; pushes follow local changes after a short pause.
 */
class TrackerSync private constructor(private val context: Context, private val profileId: String) {
    val library = Library.of(context, profileId)

    data class State(val syncing: Boolean = false, val lastSync: Long = 0L, val error: String? = null, val pending: Int = 0)

    private val _state = MutableStateFlow<Map<Service, State>>(emptyMap())
    val state: StateFlow<Map<Service, State>> = _state

    private val mutex = Mutex()
    private var pushJob: Job? = null
    private val prefs get() = Profiles.prefsFor(context, profileId)

    init {
        library.onLocalChange = { schedulePush() }
        _state.value = Service.entries.associateWith { State(lastSync = prefs.getLong("lastSync:${it.key}", 0L)) }
    }

    fun connected(): List<Service> = Service.entries.filter { account(it)?.needsLogin == false }
    fun account(service: Service): Account? = Accounts.get(context, profileId, service)

    private fun update(service: Service, change: (State) -> State) {
        _state.value = _state.value + (service to change(_state.value[service] ?: State()))
    }

    @Volatile private var dirtyAt = 0L
    private val retries = HashMap<Service, Job>()

    /**
     * Sends local changes 2.5 s after the last one, so a burst of edits (stepping through episodes)
     * goes out together. Edits made while a push is running are picked up by that push or the next round.
     */
    fun schedulePush() {
        dirtyAt = System.currentTimeMillis()
        synchronized(this) {
            if (pushJob?.isActive == true) return
            pushJob = scope.launch {
                while (true) {
                    val wait = dirtyAt + 2_500 - System.currentTimeMillis()
                    if (wait > 0) { delay(wait); continue }
                    val started = System.currentTimeMillis()
                    run(pull = false)
                    if (dirtyAt <= started) break
                }
            }
        }
    }

    fun syncNow() { scope.launch { run(pull = true) } }

    /** Pulls when the last full sync is older than [maxAge]; otherwise only sends what is queued. */
    fun syncIfStale(maxAge: Long) {
        scope.launch {
            val oldest = connected().minOfOrNull { _state.value[it]?.lastSync ?: 0L } ?: return@launch
            run(pull = System.currentTimeMillis() - oldest > maxAge)
        }
    }

    /**
     * The check for changes made elsewhere while the app stays open. Called every [OUTSIDE_TICK_MS];
     * it does nothing until the last full sync is as old as the profile's setting (or at all when
     * that is Off), so the screen stays still between checks and any other sync pushes the next back.
     */
    fun pullIfDue() {
        scope.launch {
            val minutes = UserSettings.of(context, profileId).syncMinutes
            if (minutes <= 0) return@launch
            val oldest = connected().minOfOrNull { _state.value[it]?.lastSync ?: 0L } ?: return@launch
            if (System.currentTimeMillis() - oldest < minutes * 60_000L) return@launch
            Log.i(TAG, "checking for changes made elsewhere")
            run(pull = true)
        }
    }

    /** After signing in: merge the two lists right away. */
    fun signedIn(account: Account) {
        Accounts.put(context, profileId, account)
        update(account.service) { it.copy(error = null) }
        syncNow()
    }

    fun signOut(service: Service) {
        scope.launch {
            mutex.withLock {
                Accounts.remove(context, profileId, service)
                withContext(Dispatchers.IO) { library.forget(service) }
                prefs.edit().remove("lastSync:${service.key}").apply()
                update(service) { State() }
            }
        }
    }

    private suspend fun run(pull: Boolean) = mutex.withLock {
        val services = connected()
        if (services.isEmpty()) return@withLock
        if (pull) for (s in services) step(s) { pull(s, services - s) }
        for (s in services) step(s) { push(s) }
        if (pull) for (s in services) if (_state.value[s]?.error == null) {
            val now = System.currentTimeMillis()
            prefs.edit().putLong("lastSync:${s.key}", now).apply()
            update(s) { it.copy(lastSync = now) }
        }
    }

    private suspend fun step(service: Service, work: suspend () -> Unit) {
        update(service) { it.copy(syncing = true) }
        val error = try {
            work(); null
        } catch (e: AuthExpired) {
            account(service)?.let { Accounts.put(context, profileId, it.copy(needsLogin = true)) }
            "Signed out on ${service.label}. Sign in again to keep syncing."
        } catch (e: RateLimited) {
            "${service.label} asked Stremli to slow down. Changes are saved and will sync shortly."
        } catch (e: IOException) {
            Log.w(TAG, "${service.key}: ${e.message}")
            "Couldn't reach ${service.label}. Changes are saved and will sync later."
        } catch (e: Exception) {
            Log.w(TAG, "${service.key}: ${e.javaClass.simpleName}: ${e.message}")
            "${service.label} sync failed. Try Sync now."
        }
        val pending = withContext(Dispatchers.IO) { library.pendingCount(service) }
        update(service) { it.copy(syncing = false, error = error, pending = pending) }
        // A queue held back by a rate limit or a dropped connection is retried later (one retry per service).
        if (error != null && pending > 0 && account(service)?.needsLogin == false) synchronized(retries) {
            if (retries[service]?.isActive != true) retries[service] = scope.launch { delay(3 * 60_000L); run(pull = false) }
        }
    }

    /** A usable token for [service], renewing MAL's when it is close to expiring. */
    private suspend fun token(service: Service): String {
        val a = account(service) ?: throw AuthExpired(service)
        if (a.needsLogin) throw AuthExpired(service)
        if (service == Service.Mal && a.refresh.isNotEmpty() && a.expires - System.currentTimeMillis() < 3 * 24 * 3600_000L) {
            val t = try { Mal.refresh(a.clientId, a.refresh) } catch (e: AuthExpired) { throw e }
            Accounts.put(context, profileId, a.copy(token = t.access, refresh = t.refresh.ifBlank { a.refresh }, expires = t.expires))
            return t.access
        }
        if (service == Service.AniList && a.expires in 1 until System.currentTimeMillis()) throw AuthExpired(service)
        return a.token
    }

    private val catalog by lazy { Catalog.shared(context) }

    private suspend fun pull(service: Service, others: List<Service>) {
        val token = token(service)
        when (service) {
            Service.AniList -> {
                val a = account(service)!!
                val viewer = AniList.viewer(token)
                if (viewer.name != a.name || viewer.avatar != a.avatar) Accounts.put(context, profileId, a.copy(name = viewer.name, avatar = viewer.avatar))
                val entries = AniList.list(token, viewer.id)
                withContext(Dispatchers.IO) { library.merge(service, entries, { catalog.idForMal(it) }, viewer.customLists, others) }
            }
            Service.Mal -> {
                val entries = Mal.list(token)
                withContext(Dispatchers.IO) { library.merge(service, entries, { catalog.idForMal(it) }, null, others) }
            }
        }
    }

    private suspend fun push(service: Service) {
        val token = token(service)
        if (service == Service.AniList) pushAnilistLists(token)
        var rounds = 0
        while (rounds++ < 200) {
            val batch = withContext(Dispatchers.IO) { library.pending(service, 20) }
            if (batch.isEmpty()) break
            for (p in batch) {
                pushOne(service, token, p)
                update(service) { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
            }
        }
    }

    private suspend fun pushAnilistLists(token: String) {
        val ops = withContext(Dispatchers.IO) { library.pendingLists(Service.AniList) }
        if (ops.isEmpty()) return
        val current = AniList.viewer(token).customLists
        val adds = ops.filter { it.op == "add" && current.none { c -> c.equals(it.name, true) } }.map { it.name }
        if (adds.isNotEmpty()) AniList.setCustomLists(token, current + adds)
        for (op in ops.filter { it.op == "delete" }) if (current.any { it.equals(op.name, true) }) AniList.deleteCustomList(token, op.name)
        withContext(Dispatchers.IO) { ops.forEach { library.donePendingList(Service.AniList, it.name) } }
    }

    private suspend fun pushOne(service: Service, token: String, p: Library.Pending) {
        val (entry, snap) = withContext(Dispatchers.IO) { library.entryForSync(p.id) to library.snapshot(service, p.id) }
        if (entry == null) {
            // Removed here: remove it there too, if it was ever there.
            if (snap != null && snap.remoteId > 0) when (service) {
                Service.AniList -> AniList.delete(token, snap.remoteId)
                Service.Mal -> Mal.delete(token, snap.remoteId)
            }
            withContext(Dispatchers.IO) { library.dropSnapshot(service, p.id); library.donePending(service, p) }
            return
        }
        if (!library.differs(service, entry, snap)) { withContext(Dispatchers.IO) { library.donePending(service, p) }; return }
        val (status, score, lists) = withContext(Dispatchers.IO) { library.remoteForm(service, entry) }
        when (service) {
            Service.AniList -> {
                val mediaId = entry.anilist ?: entry.mal?.let { AniList.idForMal(it) }?.also { withContext(Dispatchers.IO) { library.setAnilistId(entry.id, it) } }
                if (mediaId == null) {
                    // AniList does not have this title: remember that, so it is not retried every sync.
                    withContext(Dispatchers.IO) { library.putSnapshot(service, entry.id, -1, status, entry.progress, score, entry.repeat, lists, 0L); library.donePending(service, p) }
                    return
                }
                val sendLists = withContext(Dispatchers.IO) { library.hasAnilistLists() } || snap?.lists?.isNotEmpty() == true
                val saved = AniList.save(token, mediaId, entry, if (sendLists) lists.split('\n').filter { it.isNotBlank() } else null)
                withContext(Dispatchers.IO) { library.putSnapshot(service, entry.id, saved.id, status, entry.progress, score, entry.repeat, if (sendLists) lists else snap?.lists.orEmpty(), saved.updated); library.donePending(service, p) }
            }
            Service.Mal -> {
                val mal = entry.mal ?: catalog.malFor(entry.id)?.also { withContext(Dispatchers.IO) { library.setMalId(entry.id, it) } }
                val updated = if (mal == null) -1L else Mal.save(token, mal, entry)
                withContext(Dispatchers.IO) {
                    library.putSnapshot(service, entry.id, if (updated < 0) -1 else mal!!, status, entry.progress, score, entry.repeat, "", updated.coerceAtLeast(0))
                    library.donePending(service, p)
                }
            }
        }
    }

    companion object {
        const val OUTSIDE_TICK_MS = 5 * 60_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val open = LinkedHashMap<String, TrackerSync>()

        fun of(context: Context, profileId: String): TrackerSync = synchronized(open) {
            open.getOrPut(profileId) { TrackerSync(context.applicationContext, profileId) }
        }

        fun forget(profileId: String) { synchronized(open) { open.remove(profileId) } }

        /** Work that must finish even if the screen that started it closes (e.g. leaving the player). */
        fun background(block: suspend () -> Unit) { scope.launch { runCatching { block() }.onFailure { Log.w(TAG, "background: ${it.message}") } } }
    }
}
