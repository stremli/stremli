package com.stremli.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.text.Html
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/** A title. Positive ids are AniList ids from media-index; negative ids are Kuroiru-only titles (-MAL id). */
data class Anime(val id: Int, val mal: Int?, val title: String, val poster: String, val banner: String, val year: Int, val status: String, val format: String, val episodeCount: Int, val score: Int, val genres: String, val synopsis: String, val episodes: List<Episode>, val seriesIds: List<Int> = emptyList(), val recommendations: List<Anime> = emptyList())
/** Adult-only titles (AniList's Hentai genre), kept out of featured places. */
val Anime.adult: Boolean get() = genres.contains("Hentai", ignoreCase = true)

/** An episode. [airsAt] is its broadcast time (epoch ms) when it has not aired yet and the time is known, else 0. */
data class Episode(val number: Int, val title: String, val overview: String, val still: String, val date: String, val airsAt: Long = 0L)
data class Provider(val name: String, val url: String, val licensed: Boolean)

/** What Kuroiru knows about a title right now: where to watch it and how far it has aired. */
data class KuroiruInfo(val providers: List<Provider>, val lastEpisode: Int, val totalEpisodes: Int, val status: String, val nextAiring: Long)

// ---- Explore ---------------------------------------------------------------------------------

/** What kind of title Explore lists. Music videos are never included. */
enum class ExploreType(val label: String, val formats: List<String>) {
    All("All", listOf("TV", "TV_SHORT", "ONA", "MOVIE", "OVA", "SPECIAL")),
    Series("Series", listOf("TV", "TV_SHORT")),
    Movies("Movies", listOf("MOVIE")),
    Ona("Web (ONA)", listOf("ONA")),
    Ova("OVA & specials", listOf("OVA", "SPECIAL"))
}

/** Explore's order, for the local catalogue (SQL) and for AniList's season query. */
enum class ExploreSort(val label: String, val sql: String, val anilist: String) {
    Popular("Most popular", "popularity DESC", "POPULARITY_DESC"),
    TopRated("Top rated", "score DESC, popularity DESC", "SCORE_DESC"),
    Newest("Newest", "year DESC, popularity DESC", "START_DATE_DESC"),
    Title("A–Z", "title COLLATE NOCASE ASC", "TITLE_ENGLISH")
}

enum class ExploreStatus(val label: String, val value: String?) {
    Any("Any", null), Airing("Airing", "RELEASING"), Upcoming("Upcoming", "NOT_YET_RELEASED"), Finished("Finished", "FINISHED")
}

/** AniList seasons in calendar order (Winter is January to March). */
val ANIME_SEASONS = listOf("WINTER" to "Winter", "SPRING" to "Spring", "SUMMER" to "Summer", "FALL" to "Fall")

/** Explore's filters. A [season] always comes with a [year]. */
data class ExploreFilter(
    val type: ExploreType = ExploreType.All,
    val genre: String? = null,
    val season: String? = null,
    val year: Int? = null,
    val status: ExploreStatus = ExploreStatus.Any,
    val sort: ExploreSort = ExploreSort.Popular
) {
    /** How many filters differ from the defaults (shown on the Filters button). */
    val active: Int get() = listOf(type != ExploreType.All, genre != null, season != null, year != null && season == null, status != ExploreStatus.Any, sort != ExploreSort.Popular).count { it }
}

class ExplorePage(val items: List<Anime>, val total: Int, val hasMore: Boolean)

/** One scheduled broadcast: [episode] of [anime] at [airingAt] (epoch ms). */
data class ScheduleEntry(val anime: Anime, val episode: Int, val airingAt: Long)

/** An episode release from Kuroiru's feed. */
data class NewEpisode(val anime: Anime, val episode: Int, val published: Long)

/** The parts of an episode that can be skipped. */
enum class SkipKind(val label: String, val done: String) {
    Intro("Skip intro", "Skipped intro"), Outro("Skip outro", "Skipped outro"), Recap("Skip recap", "Skipped recap")
}

/** A skippable part of an episode. [lengthMs] is the length of the video it was measured on (0 if unknown). */
class SkipSegment(val kind: SkipKind, val startMs: Long, val endMs: Long, val lengthMs: Long, val priority: Int = 1) {
    /** Times from the stream being played, rather than from another release of the episode. */
    val exact get() = priority == 0
}

/** How far a video's length may differ from the one a skip time was measured on, and still be trusted. */
private const val SKIP_LENGTH_TOLERANCE_MS = 30_000L

/**
 * Prefer the current stream's times, then media-index and AniSkip. Fallback entries must include a
 * compatible duration, although equal durations alone cannot prove two releases use the same edit,
 * so a fallback that runs into one of the stream's own parts is dropped.
 */
fun chooseSkips(all: List<SkipSegment>, durationMs: Long): List<SkipSegment> {
    if (durationMs <= 0L) return emptyList()
    fun off(s: SkipSegment) = kotlin.math.abs(s.lengthMs - durationMs)
    val chosen = SkipKind.entries.mapNotNull { kind ->
        all.asSequence().filter { it.kind == kind && it.startMs >= 0 && it.endMs <= durationMs + 5_000L && it.endMs - it.startMs >= 3_000L && (it.priority == 0 || (it.lengthMs > 0 && off(it) <= SKIP_LENGTH_TOLERANCE_MS)) }
            .filter { when (kind) { SkipKind.Intro -> it.startMs < durationMs * 0.4; SkipKind.Outro -> it.startMs >= durationMs * 0.5; SkipKind.Recap -> it.startMs < durationMs * 0.5 } }
            .minWithOrNull(compareBy<SkipSegment> { it.priority }.thenBy { off(it) })
    }
    val exact = chosen.filter { it.exact }
    return chosen.filter { s -> s.exact || exact.none { it.startMs < s.endMs && s.startMs < it.endMs } }
        .map { SkipSegment(it.kind, it.startMs, minOf(it.endMs, durationMs), it.lengthMs, it.priority) }
}

private fun skipKind(type: String): SkipKind? = when (type) {
    "op", "mixed-op" -> SkipKind.Intro
    "ed", "mixed-ed" -> SkipKind.Outro
    "recap" -> SkipKind.Recap
    else -> null
}

private const val TAG = "Catalog"
private const val MEDIA_INDEX_CDN = "https://cdn.jsdelivr.net/gh/sanilili/media-index@main/data"
private const val MEDIA_INDEX_RAW = "https://raw.githubusercontent.com/sanilili/media-index/main/data"
private const val KUROIRU = "https://kuroiru.co"
private const val ANILIST = "https://graphql.anilist.co"
private const val ANISKIP = "https://api.aniskip.com/v2"
private const val AIRINGS_QUERY = "query(\$id:Int){Media(id:\$id){status startDate{year month day}airingSchedule(notYetAired:true,perPage:50){nodes{episode airingAt}}}}"
private const val EXPLORE_QUERY = "query(\$page:Int,\$perPage:Int,\$season:MediaSeason,\$year:Int,\$formats:[MediaFormat],\$genre:String,\$status:MediaStatus,\$sort:[MediaSort]){Page(page:\$page,perPage:\$perPage){pageInfo{hasNextPage total}media(type:ANIME,isAdult:false,genre_not_in:[\"Hentai\"],season:\$season,seasonYear:\$year,format_in:\$formats,genre:\$genre,status:\$status,sort:\$sort){id idMal format status episodes isAdult seasonYear averageScore popularity genres bannerImage title{english romaji userPreferred}coverImage{large}}}}"
/** Explore's season query for a profile that shows adult titles. */
private val EXPLORE_QUERY_ADULT = EXPLORE_QUERY.replace("isAdult:false,genre_not_in:[\"Hentai\"],", "")
private const val SCHEDULE_QUERY = "query(\$page:Int,\$from:Int,\$to:Int){Page(page:\$page,perPage:50){pageInfo{hasNextPage}airingSchedules(airingAt_greater:\$from,airingAt_lesser:\$to,sort:TIME){airingAt episode media{id idMal format status episodes isAdult seasonYear averageScore popularity genres bannerImage title{english romaji userPreferred}coverImage{large}}}}}"
private const val KUROIRU_IMAGES = "https://static.kuroiru.co"
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
/** How often to ask media-index whether it has a new build (a 100-byte file; it rebuilds daily). */
private const val CATALOG_SYNC_EVERY = 6 * HOUR
private const val DETAIL_MEMORY_MS = 10 * MINUTE
/** Placeholder names like "Episode 55" (often numbered across seasons), shown as no title. */
private val GENERIC_TITLE = Regex("(?i)episode\\s*\\d+")

/**
 * The catalog. A small starter set ships in the APK (assets/catalog-mini.json.gz); the full
 * media-index catalogue is downloaded and refreshed every 12 hours; title details and episode
 * lists are fetched when a title opens and cached. Kuroiru fills what media-index is missing or
 * behind on: airing shows it does not list yet, episodes newer than its episode lists, and a feed
 * of new releases. Everything is cached in a local SQLite database and served stale when offline.
 */
class Catalog(private val context: Context) {
    /** Banner already in the local catalogue; never starts a network request. */
    suspend fun localBanner(id: Int): String = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT banner FROM titles WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }
    }
    /** The current profile's "Show adult titles" (off unless it was turned on). Browse surfaces follow it; featured ones never show adult titles. */
    @Volatile var showAdult = false

    /** Extra SQL for browse lists (Search, Explore). */
    val adultClause: String get() = if (showAdult) "" else " AND genres NOT LIKE '%Hentai%'"

    /** The media-index build this database came from, when known. */
    val build: String? get() = prefs.getString("catalog_version", null)

    companion object {
        @Volatile private var shared: Catalog? = null
        /** One catalog (one SQLite connection) for the whole app. */
        fun shared(context: Context): Catalog = shared ?: synchronized(this) { shared ?: Catalog(context.applicationContext).also { shared = it } }
    }

    private val prefs = context.getSharedPreferences("stremli", 0)
    private val db: SQLiteDatabase by lazy { open() }

    private fun open(): SQLiteDatabase {
        // The previous build copied a 59 MB read-only catalog to storage; reclaim it.
        context.getDatabasePath("catalog-v1.db").takeIf { it.exists() }?.delete()
        val db = context.openOrCreateDatabase("catalog-v2.db", Context.MODE_PRIVATE, null)
        // SQLite keeps up to 2 MB of pages in RAM by default; less on small devices.
        db.rawQuery("PRAGMA cache_size = -${DeviceProfile.of(context).sqlitePageCacheKb}", null).use { it.moveToFirst() }
        db.execSQL("CREATE TABLE IF NOT EXISTS titles (id INTEGER PRIMARY KEY, mal INTEGER, title TEXT, poster TEXT, banner TEXT, year INTEGER, status TEXT, format TEXT, episodes INTEGER, score INTEGER, popularity INTEGER, genres TEXT, synopsis TEXT, source TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS titles_popularity ON titles(popularity DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS titles_mal ON titles(mal)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY, body TEXT, fetched INTEGER)")
        val count = db.rawQuery("SELECT COUNT(*) FROM titles", null).use { it.moveToFirst(); it.getInt(0) }
        if (count == 0) seed(db)
        return db
    }

    /** Loads the bundled starter catalog (columns listed in the file's "fields"). */
    private fun seed(db: SQLiteDatabase) {
        // The build packs assets/catalog-mini.json.gz uncompressed as catalog-mini.json (aapt unpacks .gz
        // assets); accept either name.
        val text = runCatching { context.assets.open("catalog-mini.json").bufferedReader().use { it.readText() } }
            .getOrElse { GZIPInputStream(context.assets.open("catalog-mini.json.gz")).bufferedReader().use { it.readText() } }
        val rows = JSONObject(text).getJSONArray("rows")
        db.beginTransaction()
        try {
            for (i in 0 until rows.length()) {
                val r = rows.getJSONArray(i)
                db.insertWithOnConflict("titles", null, ContentValues().apply {
                    put("id", r.getInt(0)); if (!r.isNull(1)) put("mal", r.getInt(1))
                    put("title", r.optString(2)); put("poster", r.optString(3)); put("banner", r.optString(4))
                    put("year", r.optInt(5)); put("status", r.optString(6)); put("format", r.optString(7))
                    put("episodes", r.optInt(8)); put("score", r.optInt(9)); put("popularity", r.optInt(10))
                    put("genres", r.optString(11)); put("synopsis", r.optString(12)); put("source", "bundled")
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "seeded ${rows.length()} bundled titles")
    }

    // ---- Queries ------------------------------------------------------------------------------

    /** Titles matching [where], ordered by [order] (an internal constant, never user input). */
    suspend fun titles(where: String = "", args: Array<String> = emptyArray(), limit: Int = 35, offset: Int = 0, order: String = "popularity DESC"): List<Anime> = withContext(Dispatchers.IO) {
        val clause = if (where.isEmpty()) "" else "WHERE $where"
        // id breaks ties so pages never overlap or skip a title.
        db.rawQuery("SELECT * FROM titles $clause ORDER BY $order, id LIMIT $limit OFFSET $offset", args).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }
    }

    /**
     * One page of Explore. Without a season everything comes from the local catalogue (instant,
     * offline). media-index keeps seasons only in each title's own file, so a season is listed
     * with AniList's season query instead, cached for six hours per filter and page.
     */
    suspend fun explore(filter: ExploreFilter, offset: Int, limit: Int): ExplorePage = withContext(Dispatchers.IO) {
        if (filter.season != null) return@withContext exploreSeason(filter, offset / limit + 1, limit)
        val clauses = mutableListOf("format IN (" + filter.type.formats.joinToString(",") { "'$it'" } + ")" + adultClause)
        val args = mutableListOf<String>()
        filter.genre?.let { clauses += "genres LIKE ?"; args += "%$it%" }
        filter.year?.let { clauses += "year = ?"; args += it.toString() }
        filter.status.value?.let { clauses += "status = ?"; args += it }
        when (filter.sort) {
            ExploreSort.TopRated -> clauses += "score > 0 AND popularity >= 5000" // enough viewers for the score to mean something
            ExploreSort.Newest -> { clauses += "year > 0"; if (filter.status.value == null) clauses += "status != 'NOT_YET_RELEASED'" }
            else -> {}
        }
        val where = clauses.joinToString(" AND ")
        val items = db.rawQuery("SELECT * FROM titles WHERE $where ORDER BY ${filter.sort.sql}, id LIMIT $limit OFFSET $offset", args.toTypedArray())
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
        val total = if (offset == 0) db.rawQuery("SELECT COUNT(*) FROM titles WHERE $where", args.toTypedArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } else -1
        ExplorePage(items, total, items.size == limit)
    }

    private fun exploreSeason(filter: ExploreFilter, page: Int, perPage: Int): ExplorePage {
        val vars = JSONObject().put("page", page).put("perPage", perPage).put("season", filter.season)
            .put("formats", JSONArray(filter.type.formats)).put("sort", JSONArray().put(filter.sort.anilist))
        filter.year?.let { vars.put("year", it) }
        filter.genre?.let { vars.put("genre", it) }
        filter.status.value?.let { vars.put("status", it) }
        val adult = showAdult
        val body = cached("anilist:explore:$adult:$vars", 6 * HOUR) {
            val payload = JSONObject().put("query", if (adult) EXPLORE_QUERY_ADULT else EXPLORE_QUERY).put("variables", vars)
            val request = Request.Builder().url(ANILIST)
                .header("User-Agent", "Stremli/1.0 (Android TV)").header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("explore HTTP ${r.code}")
                r.body?.string()
            }
        } ?: throw IOException("Season list unavailable")
        val p = JSONObject(body).optJSONObject("data")?.optJSONObject("Page") ?: throw IOException("Season list unavailable")
        val list = p.optJSONArray("media") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { i ->
            val m = list.optJSONObject(i) ?: return@mapNotNull null
            if (!adult && m.optBoolean("isAdult", false)) return@mapNotNull null
            val id = m.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            row(id) ?: lightRow(m)
        }
        val info = p.optJSONObject("pageInfo")
        // AniList's pageInfo.total is capped (it reports 5000 for any season), so the count is left unknown.
        return ExplorePage(items, -1, info?.optBoolean("hasNextPage", false) == true)
    }

    /**
     * Autocomplete for a partial query, from the local catalogue: titles starting with it first,
     * then titles with a word starting with it, then any title containing it; popular ones first.
     */
    suspend fun suggest(query: String, limit: Int = 6): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        db.rawQuery(
            "SELECT title FROM titles WHERE title LIKE ? ESCAPE '\\' AND format != 'MUSIC'$adultClause " +
                "ORDER BY CASE WHEN title LIKE ? ESCAPE '\\' THEN 0 WHEN title LIKE ? ESCAPE '\\' THEN 1 ELSE 2 END, popularity DESC LIMIT ${limit * 2}",
            arrayOf("%$esc%", "$esc%", "% $esc%")
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            .distinctBy { it.lowercase() }.take(limit)
    }

    /** How many titles match [where]. */
    suspend fun count(where: String = "", args: Array<String> = emptyArray()): Int = withContext(Dispatchers.IO) {
        val clause = if (where.isEmpty()) "" else "WHERE $where"
        db.rawQuery("SELECT COUNT(*) FROM titles $clause", args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun row(id: Int): Anime? = db.rawQuery("SELECT * FROM titles WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) read(c) else null }

    /** The catalog id of the title MyAnimeList calls [mal], if the catalog has it (call off the main thread). */
    fun idForMal(mal: Int): Int? =
        db.rawQuery("SELECT id FROM titles WHERE mal = ? ORDER BY id DESC LIMIT 1", arrayOf(mal.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    /** The MyAnimeList id of catalog title [id] (call off the main thread). Kuroiru-only titles carry it in their id. */
    fun malFor(id: Int): Int? = if (id < 0) -id else
        db.rawQuery("SELECT mal FROM titles WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0).takeIf { it > 0 } else null }

    private fun byMal(mal: Int): Anime? =
        db.rawQuery("SELECT * FROM titles WHERE mal = ? ORDER BY id DESC LIMIT 1", arrayOf(mal.toString())).use { c -> if (c.moveToFirst()) read(c) else null }

    private fun read(c: android.database.Cursor): Anime {
        fun str(name: String) = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
        fun int(name: String) = c.getInt(c.getColumnIndexOrThrow(name))
        val malIndex = c.getColumnIndexOrThrow("mal")
        return Anime(int("id"), if (c.isNull(malIndex)) null else c.getInt(malIndex), str("title"), str("poster"), str("banner"), int("year"), str("status"), str("format"), int("episodes"), int("score"), str("genres"), plain(str("synopsis")), emptyList())
    }

    /**
     * Full details for a title, from the local cache or the network. For media-index titles that is
     * the title's detail and episode files; for Kuroiru-only titles, Kuroiru's record with one entry
     * per aired episode. Falls back to whatever is cached (or the bare catalog row) when offline.
     */
    suspend fun detail(id: Int): Anime? = withContext(Dispatchers.IO) {
        // Recently opened titles are kept parsed in RAM (sized per device), so going back and
        // forth, or rebuilding Continue watching, does not re-read and re-parse their JSON.
        DetailMemory.get(context, id, DETAIL_MEMORY_MS)?.let { return@withContext it }
        loadDetail(id)?.also { DetailMemory.put(context, it) }
    }

    private fun loadDetail(id: Int): Anime? {
        val base = row(id) ?: return null
        if (id < 0) {
            val record = runCatching { kuroiruRecord(-id) }.getOrNull() ?: return base
            val synopsis = record.optJSONObject("info")?.text("synopsis").orEmpty()
            val lastEpisode = record.optInt("lastep", record.optInt("episodes", 0))
            val next = record.optString("schedule").toLongOrNull()?.times(1000)?.takeIf { it > System.currentTimeMillis() }
            return base.copy(
                synopsis = synopsis.ifBlank { base.synopsis },
                episodeCount = record.optInt("episodes", base.episodeCount),
                episodes = placeholders(1, lastEpisode) + listOfNotNull(next?.let { Episode(lastEpisode + 1, "", "", "", "", it) })
            )
        }
        val ttl = if (base.status == "FINISHED") 7 * DAY else 6 * HOUR
        val shard = id / 1000
        val detail = cached("mi:detail:$id", ttl) { mediaIndex("anime/$shard/$id/index.json") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val listed = cached("mi:episodes:$id", ttl) { mediaIndex("anime/$shard/$id/episodes.json") }?.let { parseEpisodes(it) }.orEmpty()
        if (detail == null) return base.copy(episodes = listed)
        val status = detail.text("status").ifBlank { base.status }
        // Broadcast times of episodes still to come (airing and announced titles only).
        val airings = if (status == "FINISHED" || status == "CANCELLED") null else runCatching { airings(id) }.getOrNull()
        val episodes = seasonEpisodes(listed, status, airings?.start ?: seasonStart(detail.text("season"), detail.optInt("season_year", 0)), airings?.times.orEmpty())
        val banner = detail.optJSONObject("images")?.text("banner").orEmpty().ifBlank { base.banner }
        val synopsis = detail.text("description").ifBlank { base.synopsis }
        val mal = detail.optJSONObject("ids")?.optInt("mal", 0)?.takeIf { it > 0 } ?: base.mal
        // Keep the catalog row in step so shelves and the home header get the banner and synopsis.
        db.update("titles", ContentValues().apply { put("banner", banner); put("synopsis", synopsis); if (mal != null) put("mal", mal) }, "id = ?", arrayOf(id.toString()))
        val series = detail.optJSONArray("series")?.let { ids ->
            (0 until ids.length()).mapNotNull { i -> ids.optInt(i).takeIf { it > 0 } }
        }.orEmpty()
        return base.copy(mal = mal, banner = banner, synopsis = plain(synopsis), episodes = episodes, seriesIds = series, recommendations = recommendations(detail))
    }

    /** AniList recommendations from a media-index detail record; catalog rows are preferred when present. */
    private fun recommendations(detail: JSONObject): List<Anime> {
        val list = detail.optJSONArray("recommendations") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val r = list.optJSONObject(i) ?: return@mapNotNull null
            val id = r.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            row(id) ?: run {
                val title = r.optJSONObject("title")
                val name = title?.text("english").orEmpty().ifBlank { title?.text("userPreferred").orEmpty() }.ifBlank { title?.text("romaji").orEmpty() }
                val cover = r.optJSONObject("coverImage")
                val poster = cover?.text("extraLarge").orEmpty().ifBlank { cover?.text("large").orEmpty() }
                if (name.isBlank() || poster.isBlank()) return@mapNotNull null
                val genres = r.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(",") { g.optString(it) } }.orEmpty()
                val anime = Anime(id, r.optInt("idMal", 0).takeIf { it > 0 }, name, poster, r.text("bannerImage"), r.optInt("seasonYear", 0),
                    r.text("status"), r.text("format"), r.optInt("episodes", 0), r.optInt("averageScore", 0), genres, "", emptyList())
                // Store it so the title can be opened before the full catalogue has synced.
                db.insertWithOnConflict("titles", null, ContentValues().apply {
                    put("id", id); anime.mal?.let { put("mal", it) }; put("title", name); put("poster", poster); put("banner", anime.banner)
                    put("year", anime.year); put("status", anime.status); put("format", anime.format); put("episodes", anime.episodeCount)
                    put("score", anime.score); put("popularity", r.optInt("popularity", 0)); put("genres", genres); put("synopsis", ""); put("source", "recommendation")
                }, SQLiteDatabase.CONFLICT_IGNORE)
                anime
            }
        }.filter { showAdult || !it.adult }.distinctBy { it.id }.take(20)
    }

    /**
     * Keeps the fetched-metadata cache within this device's budget: drops entries older than 30
     * days, then the oldest until it fits, and compacts the file when that freed a lot. The title
     * catalogue itself is not touched.
     */
    suspend fun prune() = withContext(Dispatchers.IO) {
        runCatching {
            val budget = DeviceProfile.of(context).metadataDiskBytes
            fun total() = db.rawQuery("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM cache", null).use { c -> c.moveToFirst(); c.getLong(0) }
            val before = total()
            db.delete("cache", "fetched < ?", arrayOf((System.currentTimeMillis() - 30 * DAY).toString()))
            var size = total()
            while (size > budget) {
                db.execSQL("DELETE FROM cache WHERE key IN (SELECT key FROM cache ORDER BY fetched ASC LIMIT 25)")
                val next = total()
                if (next == size) break
                size = next
            }
            if (before - size > 2 * 1024 * 1024) db.execSQL("VACUUM")
            Log.i(TAG, "metadata cache ${before / 1024} KB -> ${size / 1024} KB (budget ${budget / 1024} KB)")
        }.onFailure { Log.w(TAG, "cache prune failed: ${it.message}") }
    }

    private class Airings(val start: Long, val times: Map<Int, Long>)

    /** AniList's start date and unaired episode times for a title (cached for an hour). */
    private fun airings(id: Int): Airings? {
        val body = cached("anilist:airings:$id", HOUR) {
            val payload = JSONObject().put("query", AIRINGS_QUERY).put("variables", JSONObject().put("id", id))
            val request = Request.Builder().url(ANILIST)
                .header("User-Agent", "Stremli/1.0 (Android TV)").header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("airings HTTP ${r.code}")
                r.body?.string()
            }
        } ?: return null
        val media = runCatching { JSONObject(body).optJSONObject("data")?.optJSONObject("Media") }.getOrNull() ?: return null
        val start = media.optJSONObject("startDate")?.let { d ->
            val year = d.optInt("year", 0); val month = d.optInt("month", 0); val day = d.optInt("day", 0)
            if (year > 0 && month > 0) java.util.Calendar.getInstance().apply { clear(); set(year, month - 1, if (day > 0) day else 1) }.timeInMillis else 0L
        } ?: 0L
        val nodes = media.optJSONObject("airingSchedule")?.optJSONArray("nodes")
        val times = buildMap {
            if (nodes != null) for (i in 0 until nodes.length()) {
                val n = nodes.optJSONObject(i) ?: continue
                val episode = n.optInt("episode", 0); val at = n.optLong("airingAt", 0L) * 1000
                if (episode > 0 && at > 0) put(episode, at)
            }
        }
        return Airings(start, times)
    }

    /** Related TV installments use their own catalog IDs, episodes and playback providers. */
    suspend fun seriesTitles(anime: Anime): List<Anime> = withContext(Dispatchers.IO) {
        (anime.seriesIds + anime.id).distinct().mapNotNull { id -> if (id == anime.id) anime else row(id) }
            .filter { it.id == anime.id || it.format in setOf("TV", "TV_SHORT") || (it.format == "ONA" && it.episodeCount > 1) }
            .sortedWith(compareBy<Anime> { if (it.year > 0) it.year else Int.MAX_VALUE }.thenBy { it.title })
    }

    private fun parseEpisodes(json: String): List<Episode> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { n ->
            val e = a.optJSONObject(n) ?: return@mapNotNull null
            Episode(e.optDouble("number", 0.0).toInt(), e.text("title").takeUnless { GENERIC_TITLE.matches(it) }.orEmpty(), e.text("overview"), e.text("still"), e.text("air_date"))
        }
    }.getOrDefault(emptyList())

    // ---- media-index sync ---------------------------------------------------------------------

    /**
     * Downloads the full media-index catalogue (about 1.3 MB compressed) and merges it into the local
     * database, at most every 12 hours unless [force]. Returns true when the catalog changed.
     */
    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val last = prefs.getLong("catalog_synced", 0L)
        if (!force && System.currentTimeMillis() - last < CATALOG_SYNC_EVERY) return@withContext false
        try {
            // The catalogue is a few MB to fetch and thousands of rows to write, so nothing happens
            // unless media-index has rebuilt. If the version cannot be read, update anyway.
            val version = runCatching { mediaIndex("sync-status.json")?.let { JSONObject(it).text("last_updated") } }.getOrNull().orEmpty()
            if (!force && version.isNotEmpty() && version == prefs.getString("catalog_version", null)) {
                prefs.edit().putLong("catalog_synced", System.currentTimeMillis()).apply()
                Log.i(TAG, "catalogue is current ($version)")
                return@withContext false
            }
            // Both files are read as streams, a record at a time: building them as JSON trees took
            // about 85 MB of heap at once, too much for 1 GB Fire TV sticks (and on Android 7 the
            // image cache shares that heap).
            val mapping = mediaIndexStream("id-map.json") { reader -> readMalIds(reader) } ?: android.util.SparseIntArray()
            val insert = db.compileStatement("INSERT OR IGNORE INTO titles (id, banner, synopsis) VALUES (?, '', '')")
            val update = db.compileStatement("UPDATE titles SET mal = ?, title = ?, poster = ?, year = ?, status = ?, format = ?, episodes = ?, score = ?, popularity = ?, genres = ?, source = 'media-index' WHERE id = ?")
            val count = mediaIndexStream("index.json") { reader ->
                db.beginTransaction()
                try {
                    var n = 0
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val item = readIndexEntry(reader) ?: continue
                        insert.bindLong(1, item.id.toLong()); insert.executeInsert()
                        update.clearBindings()
                        val mal = mapping.get(item.id, 0)
                        if (mal > 0) update.bindLong(1, mal.toLong()) else update.bindNull(1)
                        update.bindString(2, item.title)
                        update.bindString(3, item.poster)
                        update.bindLong(4, item.year)
                        update.bindString(5, item.status)
                        update.bindString(6, item.format)
                        update.bindLong(7, item.episodes)
                        update.bindLong(8, item.score)
                        update.bindLong(9, item.popularity)
                        update.bindString(10, item.genres)
                        update.bindLong(11, item.id.toLong())
                        update.executeUpdateDelete()
                        n++
                    }
                    reader.endArray()
                    // Kuroiru-only titles that media-index has caught up with are now duplicates.
                    db.execSQL("DELETE FROM titles WHERE id < 0 AND mal IN (SELECT mal FROM titles WHERE id > 0 AND mal IS NOT NULL)")
                    db.setTransactionSuccessful()
                    n
                } finally {
                    db.endTransaction()
                }
            } ?: return@withContext false
            prefs.edit().putLong("catalog_synced", System.currentTimeMillis()).putString("catalog_version", version).apply()
            // Titles and schedules saved from the old build are fetched again when next needed. They
            // stay until then, so they still work offline.
            db.execSQL("UPDATE cache SET fetched = 0 WHERE key LIKE 'mi:%'")
            DetailMemory.clear()
            Log.i(TAG, "synced $count media-index titles ($version)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "media-index sync failed: ${e.message}")
            false
        }
    }

    // ---- Kuroiru ------------------------------------------------------------------------------

    /** Providers plus Kuroiru's airing progress for a MAL id (cached 30 minutes). */
    suspend fun kuroiru(mal: Int): KuroiruInfo = withContext(Dispatchers.IO) {
        val root = kuroiruRecord(mal) ?: throw IOException("Kuroiru returned nothing for $mal")
        val streams = root.optJSONObject("streams")
        val providers = buildList {
            if (streams != null) for (group in listOf("main", "scrap", "licensed")) {
                val a = streams.optJSONArray(group) ?: continue
                for (i in 0 until a.length()) {
                    val source = a.optJSONObject(i) ?: continue
                    val links = source.optJSONArray("links") ?: continue
                    for (j in 0 until links.length()) {
                        val raw = links.optJSONObject(j)?.text("url").orEmpty()
                        if (raw.isNotBlank()) add(Provider(source.text("site"), if (raw.startsWith("/")) "$KUROIRU$raw" else raw, group == "licensed"))
                    }
                }
            }
        }
        KuroiruInfo(providers, root.optInt("lastep", 0), root.optInt("episodes", 0), root.text("status"), root.optLong("schedule", 0L))
    }

    private fun kuroiruRecord(mal: Int): JSONObject? {
        val body = cached("kuroiru:title:$mal", 30 * MINUTE) {
            val request = Request.Builder().url("$KUROIRU/backend/api").post(FormBody.Builder().add("prompt", mal.toString()).build()).build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Kuroiru returned HTTP ${r.code}")
                r.body?.string()
            }
        } ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // Keep Kuroiru-only rows current (title, artwork, synopsis).
        if (byMal(mal)?.id?.let { it < 0 } == true) {
            db.update("titles", ContentValues().apply {
                root.text("title_en").ifBlank { root.text("title") }.takeIf { it.isNotBlank() }?.let { put("title", it) }
                root.text("picture").takeIf { it.isNotBlank() }?.let { put("poster", kuroiruImage(it)) }
                root.optJSONObject("info")?.text("synopsis")?.takeIf { it.isNotBlank() }?.let { put("synopsis", it) }
                root.optInt("episodes", 0).takeIf { it > 0 }?.let { put("episodes", it) }
            }, "id = ?", arrayOf((-mal).toString()))
        }
        return root
    }

    /**
     * Shows currently airing according to Kuroiru (refreshed every 6 hours), most-followed first.
     * Shows media-index does not list yet are added as Kuroiru-only titles, so they can be opened.
     */
    suspend fun airing(limit: Int = 35, maxAge: Long = 6 * HOUR): List<Anime> = withContext(Dispatchers.IO) {
        val body = cached("kuroiru:airing", maxAge) { get("$KUROIRU/data/public/airing.json") }
        val entries = body?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return@withContext titlesNow("status = ? AND format IN ('TV','TV_SHORT','ONA') AND genres NOT LIKE '%Hentai%'", arrayOf("RELEASING"), limit)
        val all = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.sortedByDescending { it.optInt("members", 0) }
        val shelf = mutableListOf<Anime>()
        db.beginTransaction()
        try {
            // Every airing or announced show goes into the catalog (so search finds the ones media-index
            // has not added yet); the shelf shows the most-followed airing TV and web series.
            for (entry in all) {
                val mal = entry.optInt("mal_id", 0).takeIf { it > 0 } ?: continue
                val airingNow = entry.text("status") == "Currently Airing"
                val status = if (airingNow) "RELEASING" else "NOT_YET_RELEASED"
                val known = byMal(mal)
                val anime = if (known != null) {
                    if (airingNow && known.status != "RELEASING") db.update("titles", ContentValues().apply { put("status", "RELEASING") }, "id = ?", arrayOf(known.id.toString()))
                    if (airingNow) known.copy(status = "RELEASING") else known
                } else {
                    insertKuroiruTitle(mal, entry.text("title"), kuroiruImage(entry.text("image_url")), status, formatOf(entry.text("type")), entry.optInt("members", 0), entry.optDouble("score", 0.0), entry.optLong("time", 0L))
                }
                if (airingNow && shelf.size < limit && entry.text("type") in setOf("TV", "ONA") && !entry.optBoolean("kids", false) && !anime.adult) shelf += anime
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        shelf
    }

    /**
     * Every episode broadcast between [from] and [to] (epoch ms), oldest first, from AniList's
     * airing schedule (a few requests per week; cached for an hour). AniList ids are the catalog's
     * ids; titles the catalog does not have yet are added as light rows.
     */
    suspend fun schedule(from: Long, to: Long): List<ScheduleEntry> = withContext(Dispatchers.IO) {
        indexSchedule(from, to) ?: anilistSchedule(from, to)
    }

    /**
     * Fallback skip candidates for an episode, from media-index and AniSkip. The player selects a
     * compatible candidate for its actual duration after considering stream-provided timings.
     */
    suspend fun skipTimes(anime: Anime, episode: Int): List<SkipSegment> = withContext(Dispatchers.IO) {
        val listed = if (anime.id > 0) runCatching { indexSkips(anime.id, episode) }.getOrDefault(emptyList()) else emptyList()
        val mal = anime.mal ?: return@withContext listed
        val live = runCatching { aniSkip(mal, episode) }.getOrDefault(emptyList())
        listed + live
    }

    /** Reads one episode out of `skip/{id/1000}/{id}.json`, skipping past the rest (a long series is over 100 KB). */
    private fun indexSkips(id: Int, episode: Int): List<SkipSegment> {
        val body = cached("mi:skip:$id", 7 * DAY) { mediaIndex("skip/${id / 1000}/$id.json") } ?: return emptyList()
        val found = ArrayList<SkipSegment>()
        JsonReader(StringReader(body)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "episodes") { r.skipValue(); continue }
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != episode.toString()) { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        // ["op", start, end, length, id]: seconds.
                        r.beginArray()
                        val kind = skipKind(r.nextString())
                        val start = (r.nextDouble() * 1000).toLong()
                        val end = (r.nextDouble() * 1000).toLong()
                        val length = (r.nextDouble() * 1000).toLong()
                        while (r.hasNext()) r.skipValue()
                        r.endArray()
                        if (kind != null) found += SkipSegment(kind, start, end, length)
                    }
                    r.endArray()
                }
                r.endObject()
            }
            r.endObject()
        }
        return found
    }

    private fun aniSkip(mal: Int, episode: Int): List<SkipSegment> {
        val types = listOf("op", "ed", "mixed-op", "mixed-ed", "recap").joinToString("&") { "types%5B%5D=$it" }
        val body = cached("aniskip:$mal:$episode", 3 * DAY) { get("$ANISKIP/skip-times/$mal/$episode?$types&episodeLength=0") } ?: return emptyList()
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val interval = item.optJSONObject("interval") ?: return@mapNotNull null
            val kind = skipKind(item.text("skipType")) ?: return@mapNotNull null
            SkipSegment(kind, (interval.optDouble("startTime", 0.0) * 1000).toLong(), (interval.optDouble("endTime", 0.0) * 1000).toLong(), (item.optDouble("episodeLength", 0.0) * 1000).toLong(), 2)
        }
    }

    /** A whole month, from media-index only; null when its file is not published (or cannot be read). */
    suspend fun monthSchedule(from: Long, to: Long): List<ScheduleEntry>? = withContext(Dispatchers.IO) { indexSchedule(from, to) }

    /**
     * Episodes airing in [from, to), from media-index's monthly files: one small CDN request, where
     * AniList would take a request per 50 episodes against the rate limit list sync also needs. Each
     * file runs about a week past its month on both sides, so the files overlap. They carry no adult
     * flag, so only titles the catalog can vouch for are shown (see [showAdult]). Null when a file is missing.
     */
    private fun indexSchedule(from: Long, to: Long): List<ScheduleEntry>? {
        val format = SimpleDateFormat("yyyy-MM", Locale.US)
        val months = listOf(format.format(java.util.Date(from)), format.format(java.util.Date(to - 1))).distinct()
        val titles = HashMap<Int, Anime?>()
        val found = LinkedHashMap<String, ScheduleEntry>()
        for (month in months) {
            val body = cached("mi:schedule:$month", 6 * HOUR) { mediaIndex("schedule/$month.json") } ?: return null
            try {
                JsonReader(StringReader(body)).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = 0; var episode = 0; var at = 0L
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "id" -> id = r.nextInt()
                            "episode" -> episode = r.nextInt()
                            "airingAt" -> at = r.nextLong() * 1000
                            else -> r.skipValue()
                        }
                        r.endObject()
                        if (episode <= 0 || at < from || at >= to) continue
                        if (id !in titles) titles[id] = row(id)
                        val anime = titles[id]?.takeIf { showAdult || !it.adult } ?: continue
                        found["$id:$episode"] = ScheduleEntry(anime, episode, at)
                    }
                    r.endArray()
                }
            } catch (e: Exception) {
                Log.w(TAG, "schedule $month: ${e.message}")
                return null
            }
        }
        return found.values.sortedBy { it.airingAt }
    }

    /** The same from AniList, for weeks media-index has not published yet. */
    private fun anilistSchedule(from: Long, to: Long): List<ScheduleEntry> {
        val body = cached("anilist:schedule:${from / 1000}:${to / 1000}", HOUR) { fetchSchedule(from / 1000, to / 1000) }
            ?: return emptyList()
        val list = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val seen = HashMap<Int, Anime>()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val media = item.optJSONObject("media") ?: return@mapNotNull null
            if (!showAdult && media.optBoolean("isAdult", false)) return@mapNotNull null
            val id = media.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val anime = seen.getOrPut(id) { row(id) ?: lightRow(media) ?: return@mapNotNull null }
            ScheduleEntry(anime, item.optInt("episode", 0), item.optLong("airingAt", 0L) * 1000)
        }.filter { it.episode > 0 && it.airingAt > 0 }
    }

    private fun fetchSchedule(from: Long, to: Long): String {
        val all = JSONArray()
        for (page in 1..12) {
            val payload = JSONObject().put("query", SCHEDULE_QUERY)
                .put("variables", JSONObject().put("page", page).put("from", from).put("to", to))
            val request = Request.Builder().url(ANILIST)
                .header("User-Agent", "Stremli/1.0 (Android TV)").header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val json = Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("schedule HTTP ${r.code}")
                JSONObject(r.body?.string() ?: throw IOException("empty schedule"))
            }
            val p = json.optJSONObject("data")?.optJSONObject("Page") ?: break
            val items = p.optJSONArray("airingSchedules") ?: break
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { all.put(it) }
            if (p.optJSONObject("pageInfo")?.optBoolean("hasNextPage", false) != true) break
        }
        return all.toString()
    }

    /** Adds a title from an AniList media record that the catalog does not have yet. */
    private fun lightRow(media: JSONObject): Anime? {
        val id = media.optInt("id", 0).takeIf { it > 0 } ?: return null
        val title = media.optJSONObject("title")
        val name = title?.text("english").orEmpty().ifBlank { title?.text("userPreferred").orEmpty() }.ifBlank { title?.text("romaji").orEmpty() }
        val poster = media.optJSONObject("coverImage")?.text("large").orEmpty()
        if (name.isBlank()) return null
        val genres = media.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(", ") { g.optString(it) } }.orEmpty()
        val anime = Anime(id, media.optInt("idMal", 0).takeIf { it > 0 }, name, poster, media.text("bannerImage"), media.optInt("seasonYear", 0),
            media.text("status"), media.text("format"), media.optInt("episodes", 0), media.optInt("averageScore", 0), genres, "", emptyList())
        db.insertWithOnConflict("titles", null, ContentValues().apply {
            put("id", id); anime.mal?.let { put("mal", it) }; put("title", name); put("poster", poster); put("banner", anime.banner)
            put("year", anime.year); put("status", anime.status); put("format", anime.format); put("episodes", anime.episodeCount)
            put("score", anime.score); put("popularity", media.optInt("popularity", 0)); put("genres", genres); put("synopsis", ""); put("source", "schedule")
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return anime
    }

    /** The latest episode releases from Kuroiru's feed (refreshed every 15 minutes). */
    suspend fun newEpisodes(limit: Int = 20, maxAge: Long = 15 * MINUTE): List<NewEpisode> = withContext(Dispatchers.IO) {
        val xml = cached("kuroiru:feed", maxAge) { get("$KUROIRU/feeds/kuroiruanime.xml") } ?: return@withContext emptyList()
        val dates = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        val items = mutableListOf<NewEpisode>()
        runCatching {
            val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
            var title = ""; var guid = ""; var date = ""; var image = ""; var tag = ""
            while (parser.next() != XmlPullParser.END_DOCUMENT && items.size < limit) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "item") { title = ""; guid = ""; date = ""; image = "" }
                        if (tag == "enclosure") image = parser.getAttributeValue(null, "url").orEmpty()
                    }
                    XmlPullParser.TEXT -> when (tag) {
                        "title" -> title += parser.text
                        "guid" -> guid += parser.text
                        "pubDate" -> date += parser.text
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            // guid is "<MAL id>/<episode>"
                            val mal = guid.trim().substringBefore('/').toIntOrNull()
                            val episode = guid.trim().substringAfter('/', "").toIntOrNull()
                            if (mal != null && episode != null) {
                                val anime = byMal(mal) ?: insertKuroiruTitle(mal, Html.fromHtml(title.trim(), Html.FROM_HTML_MODE_COMPACT).toString(), image, "RELEASING", "TV", 0, 0.0, 0L)
                                val published = runCatching { dates.parse(date.trim())?.time ?: 0L }.getOrDefault(0L)
                                items += NewEpisode(anime, episode, published)
                            }
                        }
                        tag = ""
                    }
                }
            }
        }.onFailure { Log.w(TAG, "feed parse failed: ${it.message}") }
        items
    }

    private fun insertKuroiruTitle(mal: Int, title: String, poster: String, status: String, format: String, members: Int, score: Double, time: Long): Anime {
        val year = (time / 10_000).toInt().takeIf { it in 1900..2100 } ?: 0
        db.insertWithOnConflict("titles", null, ContentValues().apply {
            put("id", -mal); put("mal", mal); put("title", title); put("poster", poster); put("banner", "")
            put("year", year); put("status", status); put("format", format); put("episodes", 0)
            put("score", (score * 10).toInt()); put("popularity", members); put("genres", ""); put("synopsis", ""); put("source", "kuroiru")
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return row(-mal) ?: Anime(-mal, mal, title, poster, "", year, status, format, 0, (score * 10).toInt(), "", "", emptyList())
    }

    // ---- Networking and cache -------------------------------------------------------------------

    /** A media-index data file from jsDelivr, falling back to GitHub. Returns null when the file does not exist. */
    /** Streams a media-index JSON file into [read] (CDN first, then GitHub); null on 404. */
    private fun <T> mediaIndexStream(path: String, read: (JsonReader) -> T): T? {
        fun open(base: String): T? {
            val request = Request.Builder().url("$base/$path").header("User-Agent", "Stremli/1.0 (Android TV)").build()
            Http.client.newCall(request).execute().use { r ->
                if (r.code == 404) return null
                if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $path")
                val body = r.body ?: throw IOException("empty body for $path")
                return JsonReader(body.charStream()).use(read)
            }
        }
        return try { open(MEDIA_INDEX_CDN) } catch (e: IOException) { open(MEDIA_INDEX_RAW) }
    }

    private fun mediaIndex(path: String): String? = try {
        get("$MEDIA_INDEX_CDN/$path")
    } catch (e: IOException) {
        get("$MEDIA_INDEX_RAW/$path")
    }

    /** GET a URL; null on 404, IOException on other failures. */
    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "Stremli/1.0 (Android TV)").build()
        Http.client.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.string()
        }
    }

    /**
     * Returns the cached body for [key] if younger than [ttl]; otherwise fetches it. A missing file
     * (null) is cached as an empty answer. On network failure the last cached body is returned.
     */
    private fun cached(key: String, ttl: Long, fetch: () -> String?): String? {
        val now = System.currentTimeMillis()
        val stored = db.rawQuery("SELECT body, fetched FROM cache WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }
        if (stored != null && now - stored.second < ttl) return stored.first.ifEmpty { null }
        return try {
            val body = fetch()
            db.insertWithOnConflict("cache", null, ContentValues().apply { put("key", key); put("body", body.orEmpty()); put("fetched", now) }, SQLiteDatabase.CONFLICT_REPLACE)
            body
        } catch (e: Exception) {
            Log.w(TAG, "$key: ${e.message}; using ${if (stored != null) "cached copy" else "nothing"}")
            stored?.first?.ifEmpty { null }
        }
    }

    private fun titlesNow(where: String, args: Array<String>, limit: Int): List<Anime> =
        db.rawQuery("SELECT * FROM titles WHERE $where ORDER BY popularity DESC LIMIT $limit", args).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
}

private class IndexEntry(val id: Int, val title: String, val poster: String, val year: Long, val status: String, val format: String, val episodes: Long, val score: Long, val popularity: Long, val genres: String)

/** One title from media-index's index.json, or null when it has no usable id. */
private fun readIndexEntry(r: JsonReader): IndexEntry? {
    var id = 0; var title = ""; var poster = ""; var year = 0L; var status = ""; var format = ""
    var episodes = 0L; var score = 0L; var popularity = 0L; var genres = ""
    r.beginObject()
    while (r.hasNext()) {
        when (r.nextName()) {
            "id" -> id = r.long().toInt()
            "title" -> title = r.string()
            "poster" -> poster = r.string()
            "year" -> year = r.long()
            "status" -> status = r.string()
            "format" -> format = r.string()
            "episodes" -> episodes = r.long()
            "score" -> score = r.long()
            "popularity" -> popularity = r.long()
            "genres" -> genres = if (r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray()
                val g = buildList { while (r.hasNext()) add(r.string()) }
                r.endArray()
                g.filter { it.isNotBlank() }.joinToString(", ")
            } else r.string()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return if (id > 0) IndexEntry(id, title, poster, year, status, format, episodes, score, popularity, genres) else null
}

/** id-map.json ({"<anilist id>": {"mal": n, ...}}) as AniList id -> MAL id. */
private fun readMalIds(r: JsonReader): android.util.SparseIntArray {
    val map = android.util.SparseIntArray(24_000)
    r.beginObject()
    while (r.hasNext()) {
        val id = r.nextName().toIntOrNull()
        if (id == null || r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "mal") r.long().toInt().takeIf { it > 0 }?.let { map.put(id, it) } else r.skipValue()
        }
        r.endObject()
    }
    r.endObject()
    return map
}

private fun JsonReader.string(): String = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.NULL -> { nextNull(); "" }
    else -> { skipValue(); "" }
}

private fun JsonReader.long(): Long = when (peek()) {
    JsonToken.NUMBER, JsonToken.STRING -> nextString().toDoubleOrNull()?.toLong() ?: 0L
    JsonToken.NULL -> { nextNull(); 0L }
    else -> { skipValue(); 0L }
}

/** Local midnight of a "YYYY-MM-DD" date, or 0. */
fun dayMs(date: String): Long {
    if (date.length < 10) return 0L
    return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date.substring(0, 10))?.time ?: 0L }.getOrDefault(0L)
}

/** First day of an AniList season (WINTER, SPRING, SUMMER, FALL) in [year], or 0. */
private fun seasonStart(season: String, year: Int): Long {
    if (year <= 0) return 0L
    val month = when (season.uppercase()) { "WINTER" -> 0; "SPRING" -> 3; "SUMMER" -> 6; "FALL" -> 9; else -> 0 }
    return java.util.Calendar.getInstance().apply { clear(); set(year, month, 1) }.timeInMillis
}

/**
 * The episodes that belong to this season. media-index sometimes lists a whole show's earlier
 * episodes for a new season (TMDB groups seasons together), so episodes dated well before the
 * season starts are dropped; an unannounced season keeps only dated or scheduled episodes.
 * Unaired episodes get their broadcast time, and scheduled ones missing from the list are added.
 */
private fun seasonEpisodes(listed: List<Episode>, status: String, start: Long, times: Map<Int, Long>): List<Episode> {
    val unreleased = status == "NOT_YET_RELEASED"
    var kept = listed
    if (start > 0) kept = kept.filter { e ->
        val day = dayMs(e.date)
        if (day == 0L) !unreleased || e.number in times else day >= start - 45 * DAY
    } else if (unreleased) kept = kept.filter { it.number in times }
    val numbers = kept.map { it.number }.toSet()
    val now = System.currentTimeMillis()
    val future = times.filter { (n, at) -> at > now && n !in numbers }.map { (n, at) -> Episode(n, "", "", "", "", at) }
    return (kept.map { e -> times[e.number]?.takeIf { it > now }?.let { e.copy(airsAt = it) } ?: e } + future).sortedBy { it.number }
}

/** Episodes [from]..[to] without metadata, for episodes that aired after the episode list was built. */
fun placeholders(from: Int, to: Int): List<Episode> = if (to < from) emptyList() else (from..to).map { Episode(it, "", "", "", "") }

/** Adds placeholder episodes up to [lastAired] when Kuroiru reports more episodes than the list has. */
fun Anime.withAiredEpisodes(lastAired: Int): Anime {
    if (lastAired <= 0) return this
    val have = episodes.mapTo(HashSet()) { it.number }
    val missing = (1..lastAired).filter { it !in have }
    // Kuroiru says these have aired: clear any stale "airs at" time.
    val updated = episodes.map { if (it.number <= lastAired && it.airsAt > 0) it.copy(airsAt = 0L) else it }
    return if (missing.isEmpty() && updated == episodes) this
    else copy(episodes = (updated + missing.map { Episode(it, "", "", "", "") }).sortedBy { it.number }, episodeCount = maxOf(episodeCount, lastAired))
}

private fun plain(html: String): String = if (html.isBlank()) "" else Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

private fun kuroiruImage(path: String): String = when {
    path.isBlank() -> ""
    path.startsWith("http") -> path
    else -> "$KUROIRU_IMAGES$path"
}

private fun formatOf(type: String): String = when (type) {
    "TV" -> "TV"; "ONA" -> "ONA"; "OVA" -> "OVA"; "Movie" -> "MOVIE"; "Special" -> "SPECIAL"; else -> type.uppercase()
}

fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
