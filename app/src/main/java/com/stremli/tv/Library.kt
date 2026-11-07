package com.stremli.tv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The six lists AniList and MyAnimeList share. MyAnimeList has no separate "rewatching" status: it
 * is "completed" with is_rewatching set, which [Mal] maps both ways.
 */
enum class ListStatus(val label: String, val anilist: String, val mal: String, val icon: Int) {
    WATCHING("Watching", "CURRENT", "watching", R.drawable.sym_play_arrow),
    PLANNING("Plan to watch", "PLANNING", "plan_to_watch", R.drawable.sym_bookmarks),
    COMPLETED("Completed", "COMPLETED", "completed", R.drawable.sym_check),
    PAUSED("On hold", "PAUSED", "on_hold", R.drawable.sym_pause),
    DROPPED("Dropped", "DROPPED", "dropped", R.drawable.sym_close),
    REWATCHING("Rewatching", "REPEATING", "completed", R.drawable.sym_refresh);

    companion object {
        fun ofAnilist(value: String): ListStatus? = entries.firstOrNull { it.anilist == value }
        fun ofMal(value: String, rewatching: Boolean): ListStatus? =
            if (rewatching && value == "completed") REWATCHING else entries.firstOrNull { it.mal == value && it != REWATCHING }
    }
}

/** The list services a profile can connect. */
enum class Service(val key: String, val label: String) {
    AniList("anilist", "AniList"), Mal("mal", "MyAnimeList")
}

/**
 * One title in a profile's library. [id] is the app's title id (an AniList id, or minus the MAL id
 * for titles only Kuroiru knows). [score] is 0–10 (0 = not scored). Dates are "yyyy-MM-dd" or "".
 */
data class LibraryEntry(
    val id: Int, val anilist: Int?, val mal: Int?, val status: ListStatus, val progress: Int, val score: Float,
    val repeat: Int, val started: String, val completed: String, val updated: Long,
    val title: String, val poster: String, val episodes: Int, val format: String, val year: Int
) {
    /** A light [Anime] for posters and for opening the title (details load from the catalog). */
    fun toAnime() = Anime(id, mal, title, poster, "", year, "", format, episodes, 0, "", "", emptyList())
}

/** A list the viewer made. [anilist] lists are kept in step with AniList's custom lists. */
data class CustomList(val id: Long, val name: String, val anilist: Boolean, val count: Int)

/** What the library grid shows: every title in any of the chosen lists; nothing chosen shows everything. */
data class LibraryView(val statuses: Set<ListStatus> = setOf(ListStatus.WATCHING), val lists: Set<Long> = emptySet()) {
    val all get() = statuses.isEmpty() && lists.isEmpty()
    val size get() = statuses.size + lists.size
    fun toggled(status: ListStatus) = copy(statuses = if (status in statuses) statuses - status else statuses + status)
    fun toggled(list: Long) = copy(lists = if (list in lists) lists - list else lists + list)
}

/** A list entry as a service reports it, in the service's own units ([score] is 0–100 for AniList, 0–10 for MAL). */
class RemoteEntry(
    val remoteId: Int, val anilist: Int?, val mal: Int?, val status: ListStatus, val progress: Int, val score: Int,
    val repeat: Int, val updated: Long, val started: String, val completed: String, val lists: List<String>?,
    val title: String, val poster: String, val episodes: Int, val format: String, val year: Int
)

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/**
 * A profile's library, in its own SQLite file (`library-<profile>.db`), so profiles never share
 * lists. Every change the viewer makes is written here first and then queued for each connected
 * service ([pending]); [TrackerSync] sends the queue and merges what the services report. The
 * queue lives in SQLite, so changes made offline or before a restart are still sent later.
 *
 * Tables: `entries` (the library), `lists` + `list_items` (custom lists), `remote` (the last state
 * each service reported for a title, which is how changes on either side are told apart) and
 * `pending` (titles whose local state still has to reach a service).
 */
class Library private constructor(private val context: Context, val profileId: String) {
    private val db: SQLiteDatabase by lazy { open() }
    private val _changes = MutableStateFlow(0)
    /** Bumped after every change, so screens showing the library reload. */
    val changes: StateFlow<Int> = _changes

    /** Called after a local change, so it is sent soon (set by [TrackerSync]). */
    @Volatile var onLocalChange: (() -> Unit)? = null

    private fun open(): SQLiteDatabase {
        val db = context.openOrCreateDatabase(fileName(profileId), Context.MODE_PRIVATE, null)
        // A small page cache: the library is a few thousand short rows at most.
        db.rawQuery("PRAGMA cache_size = -${(DeviceProfile.of(context).sqlitePageCacheKb / 4).coerceAtLeast(128)}", null).use { it.moveToFirst() }
        db.execSQL("""CREATE TABLE IF NOT EXISTS entries (id INTEGER PRIMARY KEY, anilist INTEGER, mal INTEGER, status TEXT NOT NULL,
            progress INTEGER NOT NULL DEFAULT 0, score REAL NOT NULL DEFAULT 0, repeat INTEGER NOT NULL DEFAULT 0,
            started TEXT NOT NULL DEFAULT '', completed TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL,
            title TEXT NOT NULL DEFAULT '', poster TEXT NOT NULL DEFAULT '', episodes INTEGER NOT NULL DEFAULT 0,
            format TEXT NOT NULL DEFAULT '', year INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS entries_status ON entries(status, updated DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS entries_mal ON entries(mal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS entries_anilist ON entries(anilist)")
        // anilist: 0 = this device only, 1 = an AniList custom list, 2 = still to be created on AniList.
        db.execSQL("CREATE TABLE IF NOT EXISTS lists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, anilist INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS list_items (list INTEGER NOT NULL, id INTEGER NOT NULL, added INTEGER NOT NULL, PRIMARY KEY(list, id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS list_items_id ON list_items(id)")
        // remote_id: AniList's list entry id (MAL needs none); -1 = the service has no such title.
        db.execSQL("""CREATE TABLE IF NOT EXISTS remote (service TEXT NOT NULL, id INTEGER NOT NULL, remote_id INTEGER NOT NULL DEFAULT 0,
            status TEXT, progress INTEGER NOT NULL DEFAULT 0, score INTEGER NOT NULL DEFAULT 0, repeat INTEGER NOT NULL DEFAULT 0,
            lists TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(service, id))""")
        db.execSQL("CREATE TABLE IF NOT EXISTS pending (service TEXT NOT NULL, id INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(service, id))")
        // AniList custom lists to create ("add") or delete ("delete").
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_lists (service TEXT NOT NULL, name TEXT NOT NULL, op TEXT NOT NULL, PRIMARY KEY(service, name))")
        return db
    }

    private fun changed(local: Boolean) {
        _changes.value++
        if (local) onLocalChange?.invoke()
    }

    // ---- Reading ---------------------------------------------------------------------------

    private fun read(c: Cursor): LibraryEntry {
        fun int(i: Int) = c.getInt(i)
        fun nint(i: Int) = if (c.isNull(i)) null else c.getInt(i)
        return LibraryEntry(
            int(0), nint(1), nint(2), runCatching { ListStatus.valueOf(c.getString(3)) }.getOrDefault(ListStatus.PLANNING),
            int(4), c.getFloat(5), int(6), c.getString(7).orEmpty(), c.getString(8).orEmpty(), c.getLong(9),
            c.getString(10).orEmpty(), c.getString(11).orEmpty(), int(12), c.getString(13).orEmpty(), int(14)
        )
    }

    private val columns = "id, anilist, mal, status, progress, score, repeat, started, completed, updated, title, poster, episodes, format, year"

    private fun entryNow(id: Int): LibraryEntry? =
        db.rawQuery("SELECT $columns FROM entries WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) read(it) else null }

    suspend fun entry(id: Int): LibraryEntry? = withContext(Dispatchers.IO) { entryNow(id) }

    /** A page of [view], most recently changed first. */
    suspend fun page(view: LibraryView, offset: Int, limit: Int): List<LibraryEntry> = withContext(Dispatchers.IO) {
        // Enum names and list ids only, never viewer text.
        val chosen = buildList {
            if (view.statuses.isNotEmpty()) add("status IN (" + view.statuses.joinToString { "'${it.name}'" } + ")")
            if (view.lists.isNotEmpty()) add("id IN (SELECT id FROM list_items WHERE list IN (" + view.lists.joinToString() + "))")
        }
        val where = if (chosen.isEmpty()) "" else "WHERE " + chosen.joinToString(" OR ")
        db.rawQuery("SELECT $columns FROM entries $where ORDER BY updated DESC, id LIMIT $limit OFFSET $offset", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    suspend fun counts(): Map<ListStatus, Int> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT status, COUNT(*) FROM entries GROUP BY status", null).use { c ->
            buildMap { while (c.moveToNext()) runCatching { ListStatus.valueOf(c.getString(0)) }.getOrNull()?.let { put(it, c.getInt(1)) } }
        }
    }

    suspend fun total(): Int = withContext(Dispatchers.IO) { db.rawQuery("SELECT COUNT(*) FROM entries", null).use { it.moveToFirst(); it.getInt(0) } }

    suspend fun lists(): List<CustomList> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT l.id, l.name, l.anilist, (SELECT COUNT(*) FROM list_items i JOIN entries e ON e.id = i.id WHERE i.list = l.id) FROM lists l ORDER BY l.created, l.id", null).use { c ->
            buildList { while (c.moveToNext()) add(CustomList(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3))) }
        }
    }

    /** The custom lists [id] is in. */
    suspend fun listsOf(id: Int): Set<Long> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT list FROM list_items WHERE id = ?", arrayOf(id.toString())).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    /** The titles this profile follows, for the schedule: its whole library except Dropped, by app and AniList id. */
    suspend fun followed(): Set<Int> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id, anilist FROM entries WHERE status != 'DROPPED'", null).use { c ->
            buildSet { while (c.moveToNext()) { add(c.getInt(0)); if (!c.isNull(1)) add(c.getInt(1)) } }
        }
    }

    /** Titles being watched, most recent first (for Continue watching). */
    suspend fun watching(limit: Int): List<LibraryEntry> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $columns FROM entries WHERE status IN ('WATCHING','REWATCHING') ORDER BY updated DESC LIMIT $limit", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    // ---- The viewer's changes ----------------------------------------------------------------

    private fun values(e: LibraryEntry) = ContentValues().apply {
        put("id", e.id); if (e.anilist != null) put("anilist", e.anilist) else putNull("anilist"); if (e.mal != null) put("mal", e.mal) else putNull("mal")
        put("status", e.status.name); put("progress", e.progress); put("score", e.score); put("repeat", e.repeat)
        put("started", e.started); put("completed", e.completed); put("updated", e.updated)
        put("title", e.title); put("poster", e.poster); put("episodes", e.episodes); put("format", e.format); put("year", e.year)
    }

    private fun fresh(anime: Anime, status: ListStatus) = LibraryEntry(
        anime.id, anime.id.takeIf { it > 0 }, anime.mal, status, 0, 0f, 0, "", "", System.currentTimeMillis(),
        anime.title, anime.poster, anime.episodeCount, anime.format, anime.year
    )

    /** Queues [id] for every connected service (the sync skips services that already match). */
    private fun enqueue(id: Int, services: Collection<Service> = connected()) {
        for (s in services) db.execSQL("INSERT OR REPLACE INTO pending (service, id, seq) VALUES (?, ?, (SELECT IFNULL(MAX(seq), 0) + 1 FROM pending))", arrayOf<Any>(s.key, id))
    }

    private fun connected(): List<Service> = Service.entries.filter { Accounts.get(context, profileId, it)?.let { a -> !a.needsLogin } == true }

    /** Applies [change] to [anime]'s entry (a new one starts as [initial]) and queues it. */
    suspend fun edit(anime: Anime, initial: ListStatus = ListStatus.PLANNING, change: (LibraryEntry) -> LibraryEntry): LibraryEntry = withContext(Dispatchers.IO) {
        val before = entryNow(anime.id)
        var after = change(before ?: fresh(anime, initial))
        // Keep what the catalog knows about the title current.
        after = after.copy(
            title = anime.title.ifBlank { after.title }, poster = anime.poster.ifBlank { after.poster },
            episodes = anime.episodeCount.takeIf { it > 0 } ?: after.episodes, format = anime.format.ifBlank { after.format },
            year = anime.year.takeIf { it > 0 } ?: after.year, mal = anime.mal ?: after.mal,
            anilist = after.anilist ?: anime.id.takeIf { it > 0 }, updated = System.currentTimeMillis()
        )
        after = after.withDates(before)
        db.insertWithOnConflict("entries", null, values(after), SQLiteDatabase.CONFLICT_REPLACE)
        enqueue(anime.id)
        changed(true)
        after
    }

    /** Fills start and finish dates the way the services do when a status changes. */
    private fun LibraryEntry.withDates(before: LibraryEntry?): LibraryEntry {
        var e = this
        if (e.started.isBlank() && (e.progress > 0 || e.status == ListStatus.WATCHING || e.status == ListStatus.COMPLETED)) e = e.copy(started = today())
        if (e.status == ListStatus.COMPLETED && before?.status != ListStatus.COMPLETED && e.completed.isBlank()) e = e.copy(completed = today())
        return e
    }

    suspend fun setStatus(anime: Anime, status: ListStatus) = edit(anime, status) { e ->
        when {
            status == ListStatus.COMPLETED -> e.copy(
                status = status, progress = maxOf(e.progress, e.episodes),
                // Finishing a rewatch counts one more time through.
                repeat = if (e.status == ListStatus.REWATCHING) e.repeat + 1 else e.repeat
            )
            status == ListStatus.REWATCHING && e.status != ListStatus.REWATCHING -> e.copy(status = status, progress = 0)
            else -> e.copy(status = status)
        }
    }

    suspend fun setProgress(anime: Anime, progress: Int) = edit(anime, ListStatus.WATCHING) { e ->
        val total = e.episodes.takeIf { it > 0 } ?: Int.MAX_VALUE
        val p = progress.coerceIn(0, total)
        val status = when {
            p > 0 && e.status == ListStatus.PLANNING -> ListStatus.WATCHING
            else -> e.status
        }
        e.copy(progress = p, status = status)
    }

    suspend fun setScore(anime: Anime, score: Float) = edit(anime) { it.copy(score = score.coerceIn(0f, 10f)) }

    /**
     * Records that episode [number] of [anime] was watched to the end: progress moves forward, a
     * planned or paused title becomes Watching, and the last episode completes it. Watching an old
     * episode again, or anything of a completed title, changes nothing.
     */
    suspend fun episodeWatched(anime: Anime, number: Int): LibraryEntry? = withContext(Dispatchers.IO) {
        val before = entryNow(anime.id)
        if (before != null && (before.status == ListStatus.COMPLETED || number <= before.progress)) return@withContext null
        val total = anime.episodeCount.takeIf { it > 0 } ?: before?.episodes ?: 0
        // An airing show's count is only what has aired so far, so it is never completed automatically.
        val finished = anime.status != "RELEASING" && anime.status != "NOT_YET_RELEASED"
        edit(anime, ListStatus.WATCHING) { e ->
            val status = when {
                finished && total in 1..number -> ListStatus.COMPLETED
                e.status == ListStatus.REWATCHING -> ListStatus.REWATCHING
                else -> ListStatus.WATCHING
            }
            e.copy(
                progress = maxOf(e.progress, number), status = status,
                repeat = if (status == ListStatus.COMPLETED && e.status == ListStatus.REWATCHING) e.repeat + 1 else e.repeat
            )
        }
    }

    suspend fun remove(id: Int) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            db.delete("entries", "id = ?", arrayOf(id.toString()))
            db.delete("list_items", "id = ?", arrayOf(id.toString()))
            enqueue(id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed(true)
    }

    /** Makes a list; with AniList connected it becomes an AniList custom list too. Null if the name is taken. */
    suspend fun createList(name: String, onAnilist: Boolean): Long? = withContext(Dispatchers.IO) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return@withContext null
        val id = db.insert("lists", null, ContentValues().apply { put("name", clean); put("anilist", if (onAnilist) 2 else 0); put("created", System.currentTimeMillis()) })
        if (id < 0) return@withContext null
        if (onAnilist) db.execSQL("INSERT OR REPLACE INTO pending_lists (service, name, op) VALUES (?, ?, 'add')", arrayOf<Any>(Service.AniList.key, clean))
        changed(true)
        id
    }

    suspend fun deleteList(list: CustomList) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            val members = db.rawQuery("SELECT id FROM list_items WHERE list = ?", arrayOf(list.id.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
            db.delete("list_items", "list = ?", arrayOf(list.id.toString()))
            db.delete("lists", "id = ?", arrayOf(list.id.toString()))
            if (list.anilist) {
                db.execSQL("INSERT OR REPLACE INTO pending_lists (service, name, op) VALUES (?, ?, 'delete')", arrayOf<Any>(Service.AniList.key, list.name))
                // Deleting it on AniList takes the titles out too; the snapshots catch up on the next pull.
                members.forEach { enqueue(it, listOf(Service.AniList).filter { s -> s in connected() }) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed(true)
    }

    /** Adds [anime] to or removes it from [list]. A title needs a library entry, so it starts as Plan to watch. */
    suspend fun toggleInList(anime: Anime, list: CustomList): Boolean = withContext(Dispatchers.IO) {
        val inList = db.rawQuery("SELECT 1 FROM list_items WHERE list = ? AND id = ?", arrayOf(list.id.toString(), anime.id.toString())).use { it.moveToFirst() }
        if (inList) db.delete("list_items", "list = ? AND id = ?", arrayOf(list.id.toString(), anime.id.toString()))
        else {
            if (entryNow(anime.id) == null) db.insertWithOnConflict("entries", null, values(fresh(anime, ListStatus.PLANNING)), SQLiteDatabase.CONFLICT_IGNORE)
            db.insertWithOnConflict("list_items", null, ContentValues().apply { put("list", list.id); put("id", anime.id); put("added", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        db.execSQL("UPDATE entries SET updated = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), anime.id))
        enqueue(anime.id)
        changed(true)
        !inList
    }

    /** One-time move of the old "My List" (a set of ids in the profile's preferences) into Plan to watch. */
    suspend fun importFavorites(ids: Collection<Int>, lookup: suspend (Int) -> Anime?) {
        for (id in ids) {
            if (entry(id) != null) continue
            val anime = lookup(id) ?: continue
            withContext(Dispatchers.IO) { db.insertWithOnConflict("entries", null, values(fresh(anime, ListStatus.PLANNING)), SQLiteDatabase.CONFLICT_IGNORE); enqueue(id) }
        }
        changed(true)
    }

    // ---- Sync support (used by TrackerSync) ---------------------------------------------------

    /** AniList custom list names of [id] (sorted), as the service would report them. */
    fun anilistListsOf(id: Int): List<String> =
        db.rawQuery("SELECT l.name FROM lists l JOIN list_items i ON i.list = l.id WHERE i.id = ? AND l.anilist != 0 ORDER BY l.name COLLATE NOCASE", arrayOf(id.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun hasAnilistLists(): Boolean = db.rawQuery("SELECT 1 FROM lists WHERE anilist != 0 LIMIT 1", null).use { it.moveToFirst() }

    class Snapshot(val remoteId: Int, val status: String?, val progress: Int, val score: Int, val repeat: Int, val lists: String, val updated: Long)

    fun snapshot(service: Service, id: Int): Snapshot? =
        db.rawQuery("SELECT remote_id, status, progress, score, repeat, lists, updated FROM remote WHERE service = ? AND id = ?", arrayOf(service.key, id.toString())).use { c ->
            if (c.moveToFirst()) Snapshot(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getString(5).orEmpty(), c.getLong(6)) else null
        }

    fun putSnapshot(service: Service, id: Int, remoteId: Int, status: String?, progress: Int, score: Int, repeat: Int, lists: String, updated: Long) {
        db.insertWithOnConflict("remote", null, ContentValues().apply {
            put("service", service.key); put("id", id); put("remote_id", remoteId); put("status", status); put("progress", progress)
            put("score", score); put("repeat", repeat); put("lists", lists); put("updated", updated)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun dropSnapshot(service: Service, id: Int) { db.delete("remote", "service = ? AND id = ?", arrayOf(service.key, id.toString())) }

    /** The service's units for [e]: (status, score, lists) as [RemoteEntry] would carry them. */
    fun remoteForm(service: Service, e: LibraryEntry): Triple<String, Int, String> = when (service) {
        Service.AniList -> Triple(e.status.anilist, Math.round(e.score * 10), anilistListsOf(e.id).joinToString("\n"))
        Service.Mal -> Triple(e.status.name, Math.round(e.score), "")
    }

    /** True when the service's last known state of [e] differs from the local one. */
    fun differs(service: Service, e: LibraryEntry, snap: Snapshot?): Boolean {
        if (snap == null || snap.remoteId < 0 && snap.status == null) return true
        val (status, score, lists) = remoteForm(service, e)
        return snap.status != status || snap.progress != e.progress || snap.score != score || snap.repeat != e.repeat ||
            (service == Service.AniList && hasAnilistLists() && snap.lists != lists)
    }

    class Pending(val id: Int, val seq: Long)

    fun pending(service: Service, limit: Int): List<Pending> =
        db.rawQuery("SELECT id, seq FROM pending WHERE service = ? ORDER BY seq LIMIT $limit", arrayOf(service.key)).use { c -> buildList { while (c.moveToNext()) add(Pending(c.getInt(0), c.getLong(1))) } }

    fun pendingCount(service: Service): Int =
        db.rawQuery("SELECT COUNT(*) FROM pending WHERE service = ?", arrayOf(service.key)).use { it.moveToFirst(); it.getInt(0) }

    /** Removes a queued title once sent, unless it changed again meanwhile (a newer [seq]). */
    fun donePending(service: Service, p: Pending) { db.delete("pending", "service = ? AND id = ? AND seq = ?", arrayOf(service.key, p.id.toString(), p.seq.toString())) }

    fun hasPending(service: Service, id: Int): Boolean =
        db.rawQuery("SELECT 1 FROM pending WHERE service = ? AND id = ?", arrayOf(service.key, id.toString())).use { it.moveToFirst() }

    fun entryForSync(id: Int): LibraryEntry? = entryNow(id)

    fun setAnilistId(id: Int, anilist: Int) { db.execSQL("UPDATE entries SET anilist = ? WHERE id = ?", arrayOf<Any>(anilist, id)) }
    fun setMalId(id: Int, mal: Int) { db.execSQL("UPDATE entries SET mal = ? WHERE id = ?", arrayOf<Any>(mal, id)) }

    fun idForMal(mal: Int): Int? = db.rawQuery("SELECT id FROM entries WHERE mal = ? LIMIT 1", arrayOf(mal.toString())).use { if (it.moveToFirst()) it.getInt(0) else null }
    fun idForAnilist(anilist: Int): Int? = db.rawQuery("SELECT id FROM entries WHERE anilist = ? OR id = ? LIMIT 1", arrayOf(anilist.toString(), anilist.toString())).use { if (it.moveToFirst()) it.getInt(0) else null }

    class PendingList(val name: String, val op: String)
    fun pendingLists(service: Service): List<PendingList> =
        db.rawQuery("SELECT name, op FROM pending_lists WHERE service = ?", arrayOf(service.key)).use { c -> buildList { while (c.moveToNext()) add(PendingList(c.getString(0), c.getString(1))) } }
    fun donePendingList(service: Service, name: String) {
        db.delete("pending_lists", "service = ? AND name = ?", arrayOf(service.key, name))
        db.execSQL("UPDATE lists SET anilist = 1 WHERE name = ? AND anilist = 2", arrayOf<Any>(name))
    }

    /**
     * Merges a complete list from [service] (it is called once per full pull). A title queued for
     * the service keeps the local state (it is sent next). Otherwise a change on the service since
     * the last pull is applied locally and queued for the other services; a title that was synced
     * but is gone from the service was removed there, so it is removed here too. Titles the
     * service has never had are queued for it, which is what joins two existing lists on first
     * sign-in.
     */
    fun merge(service: Service, remote: List<RemoteEntry>, resolveMal: (Int) -> Int?, remoteLists: List<String>?, others: List<Service>) {
        db.beginTransaction()
        try {
            if (service == Service.AniList && remoteLists != null) mergeAnilistLists(remoteLists)
            val seen = HashSet<Int>(remote.size * 2)
            for (r in remote) {
                val id = when (service) {
                    Service.AniList -> r.anilist?.let { idForAnilist(it) ?: it } ?: continue
                    Service.Mal -> r.mal?.let { idForMal(it) ?: resolveMal(it) ?: -it } ?: continue
                }
                if (!seen.add(id)) continue
                val snap = snapshot(service, id)
                val lists = r.lists?.sortedWith(String.CASE_INSENSITIVE_ORDER)?.joinToString("\n") ?: snap?.lists.orEmpty()
                val remoteChanged = snap == null || snap.status != r.status.let { if (service == Service.AniList) it.anilist else it.name } ||
                    snap.progress != r.progress || snap.score != r.score || snap.repeat != r.repeat || (r.lists != null && snap.lists != lists)
                putSnapshot(service, id, r.remoteId, if (service == Service.AniList) r.status.anilist else r.status.name, r.progress, r.score, r.repeat, lists, r.updated)
                val local = entryNow(id)
                if (hasPending(service, id)) {
                    // Queued here: the local state wins unless the service changed it after our last change
                    // (last writer wins, which also settles AniList and MyAnimeList disagreeing).
                    if (local == null || !remoteChanged || r.updated <= local.updated) continue
                    db.delete("pending", "service = ? AND id = ?", arrayOf(service.key, id.toString()))
                }
                if (local == null || (remoteChanged && r.updated >= local.updated - 1_000)) {
                    val base = local ?: LibraryEntry(id, r.anilist ?: id.takeIf { it > 0 }, r.mal, r.status, 0, 0f, 0, "", "", r.updated, "", "", 0, "", 0)
                    val applied = base.copy(
                        anilist = base.anilist ?: r.anilist, mal = base.mal ?: r.mal, status = r.status, progress = r.progress,
                        score = if (service == Service.AniList) r.score / 10f else if (Math.round(base.score) == r.score) base.score else r.score.toFloat(),
                        repeat = r.repeat, started = r.started.ifBlank { base.started }, completed = r.completed.ifBlank { base.completed },
                        updated = maxOf(r.updated, local?.updated ?: 0L),
                        title = base.title.ifBlank { r.title }, poster = base.poster.ifBlank { r.poster },
                        episodes = r.episodes.takeIf { it > 0 } ?: base.episodes, format = base.format.ifBlank { r.format }, year = base.year.takeIf { it > 0 } ?: r.year
                    )
                    db.insertWithOnConflict("entries", null, values(applied), SQLiteDatabase.CONFLICT_REPLACE)
                    if (service == Service.AniList && r.lists != null) setAnilistMembership(id, r.lists)
                    for (o in others) if (differs(o, applied, snapshot(o, id))) enqueue(id, listOf(o))
                } else if (!remoteChanged && differs(service, local, snapshot(service, id))) {
                    // The local entry moved on without being queued (an older build, a failed write): send it.
                    enqueue(id, listOf(service))
                } else if (remoteChanged) {
                    // Changed on both sides; the newer local change wins and is sent.
                    enqueue(id, listOf(service))
                }
            }
            // Synced before, gone now: removed on the service.
            val gone = db.rawQuery("SELECT id FROM remote WHERE service = ? AND remote_id >= 0", arrayOf(service.key)).use { c -> buildList { while (c.moveToNext()) c.getInt(0).takeIf { it !in seen }?.let(::add) } }
            for (id in gone) {
                dropSnapshot(service, id)
                if (hasPending(service, id)) continue
                if (entryNow(id) != null) {
                    db.delete("entries", "id = ?", arrayOf(id.toString()))
                    db.delete("list_items", "id = ?", arrayOf(id.toString()))
                    for (o in others) if (snapshot(o, id) != null) enqueue(id, listOf(o))
                }
            }
            // Never on the service: send.
            db.rawQuery("SELECT id FROM entries WHERE id NOT IN (SELECT id FROM remote WHERE service = ?)", arrayOf(service.key)).use { c ->
                val ids = buildList { while (c.moveToNext()) add(c.getInt(0)) }
                ids.forEach { enqueue(it, listOf(service)) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed(false)
    }

    private fun mergeAnilistLists(names: List<String>) {
        val now = System.currentTimeMillis()
        val pendingDeletes = pendingLists(Service.AniList).filter { it.op == "delete" }.map { it.name.lowercase() }.toSet()
        for (name in names) {
            if (name.lowercase() in pendingDeletes) continue
            db.insertWithOnConflict("lists", null, ContentValues().apply { put("name", name); put("anilist", 1); put("created", now) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("UPDATE lists SET anilist = 1 WHERE name = ? AND anilist = 0", arrayOf<Any>(name))
        }
        val lower = names.map { it.lowercase() }.toSet()
        // An AniList list that is no longer there was deleted on AniList.
        db.rawQuery("SELECT id, name FROM lists WHERE anilist = 1", null).use { c ->
            val gone = buildList { while (c.moveToNext()) if (c.getString(1).lowercase() !in lower) add(c.getLong(0)) }
            gone.forEach { db.delete("list_items", "list = ?", arrayOf(it.toString())); db.delete("lists", "id = ?", arrayOf(it.toString())) }
        }
    }

    private fun setAnilistMembership(id: Int, names: List<String>) {
        val lower = names.map { it.lowercase() }.toSet()
        val now = System.currentTimeMillis()
        db.rawQuery("SELECT id, name FROM lists WHERE anilist != 0", null).use { c ->
            while (c.moveToNext()) {
                val list = c.getLong(0)
                if (c.getString(1).lowercase() in lower) db.insertWithOnConflict("list_items", null, ContentValues().apply { put("list", list); put("id", id); put("added", now) }, SQLiteDatabase.CONFLICT_IGNORE)
                else db.delete("list_items", "list = ? AND id = ?", arrayOf(list.toString(), id.toString()))
            }
        }
    }

    /** Forgets everything synced with [service] (sign-out); the library itself stays. */
    fun forget(service: Service) {
        db.delete("remote", "service = ?", arrayOf(service.key))
        db.delete("pending", "service = ?", arrayOf(service.key))
        db.delete("pending_lists", "service = ?", arrayOf(service.key))
        if (service == Service.AniList) db.execSQL("UPDATE lists SET anilist = 0")
        changed(false)
    }

    companion object {
        private fun fileName(profileId: String) = "library-$profileId.db"
        // One per profile at most (Profiles.MAX), so this stays small.
        private val open = LinkedHashMap<String, Library>()

        fun of(context: Context, profileId: String): Library = synchronized(open) {
            open.getOrPut(profileId) { Library(context.applicationContext, profileId) }
        }

        /** Deletes a removed profile's library. */
        fun delete(context: Context, profileId: String) {
            synchronized(open) { open.remove(profileId)?.let { lib -> runCatching { lib.db.close() } } }
            context.applicationContext.deleteDatabase(fileName(profileId))
        }
    }
}
