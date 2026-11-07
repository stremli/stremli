package com.stremli.tv

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * A catalog list loaded a page at a time as a row scrolls (Shows, Movies, Popular series). Only
 * the visible posters are composed, so a long list costs little memory beyond its small rows.
 */
private class PagedTitles(private val catalog: Catalog, private val scope: kotlinx.coroutines.CoroutineScope, private var where: String, private var args: Array<String>, private var order: String = "popularity DESC") {
    var items by mutableStateOf<List<Anime>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    /** True while a page is being read (the first page shows a loading indicator). */
    var loading by mutableStateOf(false)
        private set
    private var end = false
    private var generation = 0

    /** Starts over from the first page (after a catalog sync). */
    suspend fun reload() {
        val mine = ++generation
        loading = true
        try {
            val first = catalog.titles(where, args, PAGE, order = order)
            val count = catalog.count(where, args)
            if (mine != generation) return
            items = first; total = count; end = first.size < PAGE
        } finally { if (mine == generation) loading = false }
    }

    /** Switches to another filter and loads its first page (nothing happens if it is unchanged). */
    suspend fun filter(where: String, args: Array<String>, order: String) {
        if (where == this.where && args.contentEquals(this.args) && order == this.order && (items.isNotEmpty() || end)) return
        this.where = where; this.args = args; this.order = order
        items = emptyList(); total = 0
        reload()
    }

    fun loadMore() {
        if (loading || end) return
        loading = true
        val mine = generation
        scope.launch {
            try {
                val next = runCatching { catalog.titles(where, args, PAGE, offset = items.size, order = order) }.getOrDefault(emptyList())
                if (mine != generation) return@launch
                items = (items + next).distinctBy { it.id }
                end = next.size < PAGE
            } finally { if (mine == generation) loading = false }
        }
    }

    companion object { const val PAGE = 40 }
}

/**
 * Explore's results for the current [ExploreFilter], a page at a time. Local filters answer from
 * SQLite instantly; a season goes to AniList (cached), so its errors are shown, not swallowed.
 */
private class ExploreList(private val catalog: Catalog, private val scope: kotlinx.coroutines.CoroutineScope) {
    var items by mutableStateOf<List<Anime>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var filter: ExploreFilter? = null
    private var end = false
    private var generation = 0

    /** Shows [next]; nothing happens if it is already shown. */
    suspend fun apply(next: ExploreFilter, force: Boolean = false) {
        if (!force && next == filter && (items.isNotEmpty() || end)) return
        filter = next
        val mine = ++generation
        items = emptyList(); total = 0; end = false; failed = false; loading = true
        try {
            val page = catalog.explore(next, 0, PAGE)
            if (mine != generation) return
            items = page.items.take(cap(next)); end = !page.hasMore || items.size >= cap(next)
            total = if (page.total >= 0) page.total else if (end) items.size else 0
        } catch (e: Exception) {
            if (mine == generation) { failed = true; end = true }
        } finally { if (mine == generation) loading = false }
    }

    suspend fun reload() { filter?.let { apply(it, force = true) } }

    fun loadMore() {
        val f = filter ?: return
        if (loading || end) return
        loading = true
        val mine = generation
        scope.launch {
            try {
                val page = catalog.explore(f, items.size, PAGE)
                if (mine != generation) return@launch
                items = (items + page.items).distinctBy { it.id }.take(cap(f))
                end = !page.hasMore || page.items.isEmpty() || items.size >= cap(f)
                if (end && f.season != null) total = items.size
            } catch (_: Exception) {
                if (mine == generation) end = true
            } finally { if (mine == generation) loading = false }
        }
    }

    companion object {
        const val PAGE = 40
        /** A season lists its top 100 (40 a page); the local catalogue pages on without a cap. */
        const val SEASON_CAP = 100
        fun cap(f: ExploreFilter) = if (f.season != null) SEASON_CAP else Int.MAX_VALUE
    }
}

private data class GotakuRequest(val anime: Anime, val episode: Episode, val provider: Provider, val allowFallback: Boolean = true, val fellBack: Boolean = false, val version: String = "sub")
private data class ReanimeRequest(val anime: Anime, val episode: Episode, val server: ReanimeServer? = null, val allowFallback: Boolean = true, val version: String = "sub", val serverName: String? = null)

/** The server picked in the player for a title. Never saved: the next session starts from the profile's preference. */
private class ServerPick(val source: String, val version: String, val server: String?)

/** A title the viewer has started: the episode to continue (or the next one) and how far in. */
private data class ContinueItem(val anime: Anime, val episode: Episode, val progress: Float?, val upNext: Boolean)

private sealed class GotakuStart {
    data object Loading : GotakuStart()
    data class Ready(val session: GotakuSession) : GotakuStart()
    data class Failed(val message: String) : GotakuStart()
}

private sealed class ReanimeStart {
    data object Loading : ReanimeStart()
    data class Ready(val server: ReanimeServer, val session: ReanimeSession) : ReanimeStart()
    data class Failed(val message: String) : ReanimeStart()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coil3.SingletonImageLoader.setSafe { buildImageLoader(it) }
        setContent { StremliApp() }
        // The branded window background covers app start; once Compose draws the screen it is only
        // overdraw, so remove it after the first frame.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        trimCaches(this, level)
    }

    // On a D-pad device Compose spends the remote's Back press clearing focus before any BackHandler
    // runs, so every Back needed two presses. Route it straight to the back dispatcher instead.
    // (An open on-screen keyboard still receives Back first.)
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** The app for one [profile]; [switchProfile] returns to "Who's watching?". */
@Composable
fun Stremli(profile: Profile, switchProfile: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { Catalog.shared(context) }
    val settings = remember { UserSettings.of(context, profile.id) }
    // The catalog is shared, so it follows the profile in use. Parsed details hold filtered recommendations.
    remember(settings.showAdult) { catalog.showAdult = settings.showAdult; DetailMemory.clear(); settings.showAdult }
    val scope = rememberCoroutineScope()
    val shows = remember { PagedTitles(catalog, scope, "format IN ('TV','TV_SHORT') AND genres NOT LIKE '%Hentai%'", emptyArray()) }
    // The Explore tab: every series and film, filtered by type, season, year, genre and status.
    val explore = remember { ExploreList(catalog, scope) }
    // Announced titles, most anticipated first.
    val upcoming = remember { PagedTitles(catalog, scope, "status = 'NOT_YET_RELEASED' AND genres NOT LIKE '%Hentai%'", emptyArray()) }
    val popular = shows.items
    var airing by remember { mutableStateOf<List<Anime>>(emptyList()) }
    // This profile's own data: My List, watch history and resume points, searches.
    val preferences = remember { Profiles.prefs(context) }
    // The profile's library (status lists, custom lists) and its AniList / MyAnimeList sync.
    val sync = remember { TrackerSync.of(context, profile.id) }
    val library = sync.library
    val libraryState = remember { LibraryScreenState(sync, scope) }
    val libraryVersion by library.changes.collectAsState()
    var detailEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var listsOpen by remember { mutableStateOf(false) }
    val listsButton = remember { FocusRequester() }
    // Hold OK (or press Menu) on any poster: its lists, in a sheet over whatever page it is on.
    var menuAnime by remember { mutableStateOf<Anime?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuReturn by remember { mutableStateOf<FocusRequester?>(null) }
    val posterMenu = remember { { anime: Anime, poster: FocusRequester -> menuAnime = anime; menuReturn = poster; menuOpen = true } }
    var continueItems by remember { mutableStateOf<List<ContinueItem>>(emptyList()) }
    var newEpisodes by remember { mutableStateOf<List<NewEpisode>>(emptyList()) }
    var results by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var providerMessage by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("home") }
    // Where Back from a details page returns to (home or search).
    var detailFrom by remember { mutableStateOf("home") }
    var gotakuRequest by remember { mutableStateOf<GotakuRequest?>(null) }
    var reanimeRequest by remember { mutableStateOf<ReanimeRequest?>(null) }
    var seriesTitles by remember { mutableStateOf<List<Anime>>(emptyList()) }
    // Search opens on the viewer's last search (kept in the saved history, so it lasts across
    // restarts and days); results only change when the viewer submits, not while typing.
    val pastSearches = remember { loadSearchHistory(preferences) }
    var query by remember { mutableStateOf(pastSearches.firstOrNull().orEmpty()) }
    var submitted by remember { mutableStateOf(pastSearches.firstOrNull().orEmpty()) }
    var resultsFor by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf(pastSearches) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    // True from opening a title until its details (episodes, synopsis) have arrived.
    var detailLoading by remember { mutableStateOf(false) }

    // Only a few titles get a server picked in one sitting; the oldest pick goes first.
    val serverPicks = remember {
        object : LinkedHashMap<Int, ServerPick>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ServerPick>?) = size > 8
        }
    }

    fun playEpisode(anime: Anime, episode: Episode, chosenSource: String? = null, server: ReanimeServer? = null, allowFallback: Boolean = true, fellBack: Boolean = false) {
        // A server picked for this title this session comes before the profile's preference.
        val pick = serverPicks[anime.id]
        val version = pick?.version ?: settings.preferredVersion
        // Gotaku currently exposes subtitled streams only. Try a dub on Re:Anime first.
        val source = chosenSource ?: pick?.source ?: if (version == "dub" && anime.id > 0) "reanime" else settings.preferredSource
        if (source == "reanime" && anime.id > 0) {
            reanimeRequest = ReanimeRequest(anime, episode, server, allowFallback, version, pick?.server)
            page = "reanime"
            return
        }
        val provider = providers.firstOrNull { it.name.equals("Gotaku", true) }
        if (provider == null) {
            if (allowFallback && anime.id > 0) { reanimeRequest = ReanimeRequest(anime, episode, allowFallback = false, version = version); page = "reanime"; return }
            providerMessage = if (providers.isEmpty()) "Streaming sources are still loading. Try again in a moment." else "Gotaku is not available for this title."
            return
        }
        preferences.edit()
            .putInt("last:${anime.id}", episode.number)
            .putLong("lastAt:${anime.id}", System.currentTimeMillis())
            .putString("lastSource:${anime.id}", "gotaku")
            .apply()
        gotakuRequest = GotakuRequest(anime, episode, provider, allowFallback, fellBack, version)
        page = "gotaku"
    }

    /** Plays the server picked in the player, and keeps it for this title's other episodes this session. */
    fun chooseServer(anime: Anime, episode: Episode, choice: PlayerServer) {
        serverPicks[anime.id] = ServerPick(choice.source, choice.server?.language ?: "sub", choice.server?.name)
        playEpisode(anime, episode, choice.source, choice.server, false)
    }

    /** Opens a title's details; with [autoPlay], starts that episode as soon as Gotaku is found. */
    fun openTitle(anime: Anime, autoPlay: Int? = null) {
        if (page == "home" || page == "search") detailFrom = page
        listsOpen = false
        page = "detail"; selected = anime; providers = emptyList(); seriesTitles = emptyList(); gotakuRequest = null; reanimeRequest = null; providerMessage = "Finding streaming sources…"
        detailLoading = true
        scope.launch {
            try {
                val full = try { catalog.detail(anime.id) ?: anime } finally { if (selected?.id == anime.id) detailLoading = false }
                if (selected?.id != anime.id) return@launch
                selected = full
                val related = catalog.seriesTitles(full)
                if (selected?.id != anime.id) return@launch
                seriesTitles = related
                val mal = full.mal
                if (mal == null) {
                    providerMessage = "Gotaku isn’t available for this title yet."
                    val episode = autoPlay?.let { n -> full.episodes.firstOrNull { it.number == n } }
                    if (episode != null && full.id > 0 && page == "detail") playEpisode(full, episode)
                }
                else {
                    try {
                        val info = catalog.kuroiru(mal)
                        val found = info.providers
                        // Kuroiru knows about episodes that aired after media-index built its list.
                        val current = full.withAiredEpisodes(maxOf(info.lastEpisode, autoPlay ?: 0))
                        if (selected?.id == anime.id) {
                            selected = current
                            providers = found.sortedBy { if (it.name.equals("Gotaku", true)) 0 else if (it.licensed) 1 else 2 }
                            val gotaku = found.firstOrNull { it.name.equals("Gotaku", true) }
                            // Load Gotaku's title and episode list now so pressing Play needs fewer requests.
                            gotaku?.let { launch(Dispatchers.IO) { GotakuTitles.prefetch(it, current.id) } }
                            providerMessage = if (found.isEmpty()) "No streaming sources were returned." else "Pick an episode to play it here, or open a provider's page."
                            val episode = autoPlay?.let { n -> current.episodes.firstOrNull { it.number == n } }
                            if (episode != null && (gotaku != null || current.id > 0) && page == "detail") playEpisode(current, episode)
                        }
                    } catch (e: Exception) {
                        if (selected?.id == anime.id) {
                            providerMessage = "Could not load streaming sources. Check your connection and try again."
                            val episode = autoPlay?.let { n -> full.episodes.firstOrNull { it.number == n } }
                            if (episode != null && full.id > 0 && page == "detail") playEpisode(full, episode)
                        }
                    }
                }
            } catch (e: Exception) { providerMessage = "Could not load title details." }
        }
    }

    // When Home last refreshed; the load below counts as the first time.
    var homeUpdated by remember { mutableLongStateOf(0L) }
    /**
     * Called each time Home is opened: refreshes it in the background unless it updated recently.
     * The shelves are replaced in place, so focus and scroll position stay where they are.
     */
    fun refreshHome() {
        Updates.checkIfDue(context)
        val age = settings.homeRefreshMinutes * 60_000L
        val now = System.currentTimeMillis()
        if (age <= 0L || loading || now - homeUpdated < age) return
        homeUpdated = now
        android.util.Log.i("Stremli", "refreshing Home")
        scope.launch {
            launch { runCatching { catalog.newEpisodes(maxAge = age) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { newEpisodes = it } }
            launch { runCatching { catalog.airing(maxAge = age) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { airing = it.distinctBy { a -> a.id } } }
            launch { runCatching { loadContinue(catalog, preferences, library) }.getOrNull()?.let { continueItems = it } }
            // Only if media-index has a new build, and at most every 6 hours.
            launch { if (catalog.sync()) runCatching { shows.reload(); explore.reload(); upcoming.reload() } }
        }
    }
    LaunchedEffect(Unit) {
        homeUpdated = System.currentTimeMillis()
        GotakuConfig.load(context.applicationContext)
        launch(Dispatchers.IO) { GotakuConfig.refresh(context.applicationContext) }
        launch { delay(5_000); catalog.prune() }
        launch { Updates.cleanUp(context); delay(15_000); Updates.checkIfDue(context) }
        launch {
            // The old My List becomes Plan to watch, once.
            val old = preferences.getStringSet("favorites", null)?.mapNotNull { it.toIntOrNull() }
            if (!old.isNullOrEmpty()) {
                runCatching { library.importFavorites(old) { catalog.detail(it) } }
                preferences.edit().remove("favorites").apply()
            }
            // Sync after the first screen has settled.
            delay(6_000)
            sync.syncIfStale(10 * 60_000L)
        }
        suspend fun loadShelves() {
            shows.reload()
            explore.reload()
            upcoming.reload()
        }
        try {
            // The bundled starter catalog is enough for the first screen; the rest arrives below.
            loadShelves()
            airing = catalog.titles("status = ? AND format IN ('TV','TV_SHORT','ONA') AND genres NOT LIKE '%Hentai%'", arrayOf("RELEASING"))
        } catch (e: Exception) { error = "Could not open the catalog: ${e.message}" }
        loading = false
        launch {
            runCatching { catalog.airing() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { airing = it.distinctBy { a -> a.id } }
            runCatching { upcoming.reload() }
        }
        launch { newEpisodes = runCatching { catalog.newEpisodes() }.getOrDefault(emptyList()) }
        launch {
            if (catalog.sync()) {
                loadShelves()
                runCatching { catalog.airing() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { airing = it.distinctBy { a -> a.id } }
            }
        }
    }
    // Explore's list stays loaded between pages, so it follows the setting here.
    var adultApplied by remember { mutableStateOf(settings.showAdult) }
    LaunchedEffect(settings.showAdult) {
        if (settings.showAdult != adultApplied) { adultApplied = settings.showAdult; runCatching { explore.reload() } }
    }
    LaunchedEffect(selected?.id, libraryVersion) { detailEntry = selected?.let { library.entry(it.id) } }
    // Coming back to the app after a while pulls changes made elsewhere (phone, website).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) sync.syncIfStale(15 * 60_000L)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // While the app stays on screen, changes made elsewhere still arrive. It stops in the background
    // and skips playback, so it never competes with the video on a small stick.
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                delay(TrackerSync.OUTSIDE_TICK_MS)
                if (page != "gotaku" && page != "reanime") sync.pullIfDue()
            }
        }
    }
    // Rebuild "Continue watching" whenever the home screen is shown (positions change during playback).
    LaunchedEffect(page) {
        if (page != "home") return@LaunchedEffect
        continueItems = try { loadContinue(catalog, preferences, library) } catch (e: Exception) { android.util.Log.w("Stremli", "continue watching: ${e.javaClass.simpleName}: ${e.message}"); emptyList() }
    }
    // Autocomplete follows typing, after a short pause; it is a small local query.
    LaunchedEffect(query, page) {
        if (page != "search") return@LaunchedEffect
        if (query.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(150)
        suggestions = runCatching { catalog.suggest(query) }.getOrDefault(emptyList())
    }
    LaunchedEffect(submitted, page == "search") {
        if (page != "search" && resultsFor == submitted.trim()) return@LaunchedEffect
        if (page != "search") return@LaunchedEffect
        val q = submitted.trim()
        results = if (q.isBlank()) emptyList() else try { catalog.titles("title LIKE ?" + catalog.adultClause, arrayOf("%$q%"), 80) } catch (_: Exception) { emptyList() }
        resultsFor = q
    }
    fun submitSearch(text: String) {
        val q = text.trim()
        query = q
        submitted = q
        if (q.isNotBlank()) { history = rememberSearch(preferences, history, q) }
    }
    BackHandler(enabled = page != "home") {
        page = when (page) { "gotaku", "reanime" -> "detail"; "detail" -> detailFrom; else -> "home" }
    }
    @Composable
    fun PageContent(shown: String) {
        when (shown) {
                "launch" -> LaunchScreen(error)
                "home" -> Home(
                    shows, airing, explore, upcoming, libraryState, libraryVersion, continueItems, newEpisodes, loading, error,
                    { page = "search" }, { openTitle(it) },
                    { openTitle(it.anime, autoPlay = it.episode.number) },
                    { openTitle(it.anime, autoPlay = it.episode) },
                    enrich = { catalog.detail(it.id) },
                    loadSchedule = { from, to -> catalog.schedule(from, to) },
                    loadMonth = { from, to -> catalog.monthSchedule(from, to) },
                    playScheduled = { openTitle(it.anime, autoPlay = it.episode) },
                    profile = profile, switchProfile = switchProfile, listsMenuOpen = menuOpen, openSettings = { page = "settings" },
                    onHomeOpened = ::refreshHome
                )
                "settings" -> SettingsPage(
                    profile, settings, sync, catalog, onSwitchProfile = switchProfile,
                    onClearSearches = { history = emptyList(); query = ""; submitted = ""; preferences.edit().remove(SEARCH_HISTORY_KEY).apply() },
                    onClearWatchHistory = {
                        val editor = preferences.edit()
                        preferences.all.keys.filter { key -> listOf("last:", "lastAt:", "lastSource:", "lastServer:", "resume:", "dur:").any(key::startsWith) }.forEach(editor::remove)
                        editor.apply()
                        continueItems = emptyList()
                    },
                    onCatalogUpdated = { scope.launch { runCatching { shows.reload(); explore.reload(); upcoming.reload() } } }
                )
                "search" -> Search(
                    query, { query = it }, { change -> query = change(query) }, submitted, resultsFor, ::submitSearch, suggestions, history,
                    onClearHistory = { history = emptyList(); preferences.edit().remove(SEARCH_HISTORY_KEY).apply() },
                    results = results, popular = popular
                ) { openTitle(it) }
                "detail" -> selected?.let { anime -> key(anime.id) {
                    Box(Modifier.fillMaxSize()) {
                        // While My lists is open, the page behind it cannot take focus.
                        Box(Modifier.fillMaxSize().focusProperties { onEnter = { if (listsOpen) cancelFocusChange() } }.focusGroup()) {
                        Detail(anime, seriesTitles, providers, providerMessage, detailEntry?.takeIf { it.id == anime.id }, preferences.getInt("last:${anime.id}", 0), preferences.getString("lastSource:${anime.id}", settings.preferredSource) ?: settings.preferredSource, { listsOpen = true },
                            { openTitle(anime) }, { episode -> playEpisode(anime, episode) }, { season -> openTitle(season) }, { other -> openTitle(other) }, detailLoading, listsButton)
                        }
                        TitleListsSheet(listsOpen, anime, sync, onClose = {
                            listsOpen = false
                            scope.launch { delay(80); runCatching { listsButton.requestFocus() } }
                        })
                    }
                } }
                "gotaku" -> gotakuRequest?.let { request -> GotakuPlayback(request, providers.firstOrNull { it.name.equals("Gotaku", true) }, onBack = { page = "detail" }, onPlayEpisode = { playEpisode(request.anime, it) }, onChooseServer = { choice -> chooseServer(request.anime, request.episode, choice) }, onFallback = { playEpisode(request.anime, request.episode, "reanime", allowFallback = false) }) }
                "reanime" -> reanimeRequest?.let { request -> ReanimePlayback(request, providers.firstOrNull { it.name.equals("Gotaku", true) }, onBack = { page = "detail" }, onPlayEpisode = { playEpisode(request.anime, it) }, onChooseServer = { choice -> chooseServer(request.anime, request.episode, choice) }, onFallback = { playEpisode(request.anime, request.episode, "gotaku", allowFallback = false, fellBack = true) }) }
        }
    }
    StremliTheme {
        // Material motion for page changes: going deeper (home -> title -> player) scales in
        // slightly while fading through; going back reverses it. Specs come from the expressive
        // motion scheme; the transition lasts about 300 ms and only the two pages involved draw.
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        // Home and Search keep their tab, filters and scroll position while a title is open.
        val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val screen = if (loading) "launch" else page
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalPosterMenu provides posterMenu, LocalSettings provides settings) {
            // While a poster's lists are open, the page behind them cannot take focus.
            Box(Modifier.fillMaxSize().focusProperties { onEnter = { if (menuOpen) cancelFocusChange() } }.focusGroup()) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val deeper = pageDepth(targetState) > pageDepth(initialState)
                    (fadeIn(fade) + scaleIn(scale, initialScale = if (deeper) .94f else 1.04f)) togetherWith fadeOut(fadeOutSpec)
                },
                label = "page"
            ) { shown ->
            if (shown == "home" || shown == "search") saved.SaveableStateProvider(shown) { PageContent(shown) } else PageContent(shown)
            }
            }
            }
            UpdateSheet(allowed = screen == "home" && !menuOpen)
            menuAnime?.let { anime ->
                TitleListsSheet(menuOpen, anime, sync, onClose = {
                    menuOpen = false
                    scope.launch {
                        delay(120)
                        // A title moved out of the lists on show has no poster left: go to the list chips.
                        val back = runCatching { menuReturn?.requestFocus() == true }.getOrDefault(false)
                        if (!back && !runCatching { libraryState.chipsFocus.requestFocus() }.getOrDefault(false)) runCatching { libraryState.pinnedFocus.requestFocus() }
                    }
                })
            }
        }
    }
}

/** Launch < home/search < title < player: decides which way a page transition moves. */
private fun pageDepth(page: String) = when (page) { "launch" -> 0; "home", "search" -> 1; "detail", "settings" -> 2; else -> 3 }

/**
 * Continue watching: titles played on this TV (most recent first), then titles the library says are
 * being watched (progress synced from AniList or MyAnimeList, so watching on a phone counts too).
 */
private suspend fun loadContinue(catalog: Catalog, prefs: android.content.SharedPreferences, library: Library): List<ContinueItem> {
    val all = prefs.all
    val started = all.keys.filter { it.startsWith("last:") }.mapNotNull { it.removePrefix("last:").toIntOrNull() }
        .sortedByDescending { all["lastAt:$it"] as? Long ?: 0L }
        .take(8)
    val now = System.currentTimeMillis()
    val local = started.mapNotNull { id ->
        val anime = catalog.detail(id) ?: return@mapNotNull null
        val last = all["last:$id"] as? Int ?: return@mapNotNull null
        val source = (all["lastSource:$id"] as? String).takeIf { it == "reanime" } ?: "gotaku"
        val key = watchPositionKey(source, id, last, all["lastServer:$id"] as? String)
        val position = all["resume:$key"] as? Long ?: 0L
        val duration = all["dur:$key"] as? Long ?: 0L
        val current = anime.airedEpisode(last, now)
        // Progress synced from elsewhere can be past the episode saved here.
        val synced = library.entry(id)?.takeIf { it.status != ListStatus.COMPLETED }?.progress ?: 0
        if (position > 0L && current != null && last > synced) {
            ContinueItem(anime, current, if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else null, upNext = false)
        } else {
            // Finished (or never saved): offer the next episode, if there is one (the library may
            // know of episodes watched elsewhere since).
            val seen = maxOf(last, synced)
            val next = anime.airedEpisode(seen + 1, now) ?: return@mapNotNull null
            ContinueItem(anime, next, null, upNext = true)
        }
    }
    val shown = local.map { it.anime.id }.toSet()
    val elsewhere = library.watching(10).filter { it.id !in shown && it.id !in started }.mapNotNull { e ->
        val anime = catalog.detail(e.id) ?: return@mapNotNull null
        val next = anime.airedEpisode(e.progress + 1, now) ?: return@mapNotNull null
        ContinueItem(anime, next, null, upNext = true)
    }
    return (local + elsewhere).distinctBy { it.anime.id }.take(10)
}

/**
 * Episode [number] if it has aired. Long-running shows often list only upcoming episodes until
 * Kuroiru fills in the aired ones on the title page, so an aired episode missing from the list
 * is stood in for by a bare one (it gets its details when it opens).
 */
private fun Anime.airedEpisode(number: Int, now: Long): Episode? {
    if (number < 1) return null
    episodes.firstOrNull { it.number == number }?.let { return it.takeIf { e -> e.airsAfter(now) == 0L } }
    val firstUnaired = episodes.filter { it.airsAfter(now) > 0 }.minOfOrNull { it.number }
    val aired = when {
        firstUnaired != null -> number < firstUnaired
        episodes.isNotEmpty() -> number <= episodes.maxOf { it.number }
        else -> status == "FINISHED" && number <= episodeCount
    }
    return if (aired) Episode(number, "", "", "", "") else null
}

/** Re:Anime appends this to every subtitle label, as in "English (Track 2 (ENG))". */
private val TRACK_NUMBER = Regex("""\s*\(Track \d+ \(\w+\)\)$""")

internal fun watchPositionKey(source: String, animeId: Int, episode: Int, server: String?): String =
    if (source == "reanime" && !server.isNullOrBlank()) "$source:$animeId:$episode:$server" else "$source:$animeId:$episode"

private fun ago(time: Long): String {
    if (time <= 0L) return "new"
    val minutes = ((System.currentTimeMillis() - time) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

// ---- Home -----------------------------------------------------------------------------------

@Composable
private fun Home(
    shows: PagedTitles, airing: List<Anime>, explore: ExploreList, upcoming: PagedTitles, library: LibraryScreenState, libraryVersion: Int, continueItems: List<ContinueItem>, newEpisodes: List<NewEpisode>,
    loading: Boolean, error: String, search: () -> Unit, open: (Anime) -> Unit, resume: (ContinueItem) -> Unit,
    playNew: (NewEpisode) -> Unit, enrich: suspend (Anime) -> Anime?,
    loadSchedule: suspend (Long, Long) -> List<ScheduleEntry>, loadMonth: suspend (Long, Long) -> List<ScheduleEntry>?, playScheduled: (ScheduleEntry) -> Unit,
    profile: Profile, switchProfile: () -> Unit, listsMenuOpen: Boolean, openSettings: () -> Unit, onHomeOpened: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val settings = LocalSettings.current
    val popular = shows.items
    val scope = rememberCoroutineScope()
    val homeList = rememberLazyListState()
    var tab by rememberSaveable(settings.startTab) { mutableStateOf(settings.startTab) }
    // Home counts as opened when its tab is chosen, on coming back to this page, and on returning to the app.
    val homeOpened by rememberUpdatedState(onHomeOpened)
    val shownTab by rememberUpdatedState(tab)
    LaunchedEffect(tab) { if (tab == "Home") homeOpened() }
    val homeLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(homeLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START && shownTab == "Home") homeOpened()
        }
        homeLifecycle.lifecycle.addObserver(observer)
        onDispose { homeLifecycle.lifecycle.removeObserver(observer) }
    }
    var filter by rememberSaveable(stateSaver = ExploreFilterSaver) { mutableStateOf(ExploreFilter()) }
    var filtersOpen by remember { mutableStateOf(false) }
    val filtersButton = remember { FocusRequester() }
    LaunchedEffect(tab, filter) {
        if (tab != "Explore") return@LaunchedEffect
        // Stepping through seasons or chips quickly sends one query when it settles.
        delay(250)
        explore.apply(filter)
    }
    // Library: reload when it changes (edits, syncs) while shown; pull when it opens, if stale.
    val accountsVersion by Accounts.changes.collectAsState()
    val syncState by library.sync.state.collectAsState()
    val accounts = remember(accountsVersion, syncState) { Service.entries.associateWith { library.sync.account(it) } }
    val tileFocus = remember { Service.entries.associateWith { FocusRequester() } }
    val newListFocus = remember { FocusRequester() }
    LaunchedEffect(tab, libraryVersion) { if (tab == "Library") library.reload() }
    LaunchedEffect(tab) {
        if (tab != "Library") return@LaunchedEffect
        library.sync.syncIfStale(5 * 60_000L)
        // Which services the sign-in relay offers (one small request; it can change without an update).
        TrackerLogin.available().takeIf { it.isNotEmpty() }?.let { library.available = it }
    }
    // Kept for the cards' focus callbacks; the header no longer follows focus.
    var highlighted by remember { mutableStateOf<Anime?>(null) }

    // Hero slider (Home tab): the newest episodes, then airing and popular titles.
    var enriched by remember { mutableStateOf<Map<Int, Anime>>(emptyMap()) }
    val slides = remember(newEpisodes, airing.take(8), popular.take(8), enriched) {
        heroSlides(newEpisodes, airing, popular).filter { s -> enriched[s.anime.id]?.adult != true }
    }
    var slide by rememberSaveable { mutableIntStateOf(0) }
    val current = slides.getOrNull(slide.coerceIn(0, (slides.size - 1).coerceAtLeast(0)))
    var heroFocused by remember { mutableStateOf(false) }
    val heroOnScreen by remember(homeList) { derivedStateOf { homeList.firstVisibleItemIndex <= 1 } }
    // Advance every 8 s while the hero is on screen and not being used; nothing runs otherwise.
    LaunchedEffect(slide, slides.size, heroFocused, heroOnScreen, tab, settings.heroSlides) {
        if (tab != "Home" || slides.size < 2 || heroFocused || !heroOnScreen || !settings.heroSlides) return@LaunchedEffect
        delay(8_000)
        slide = (slide + 1) % slides.size
    }
    // Catalog rows may lack a banner or synopsis until their details are fetched: fetch them for the
    // current slide and the next one (episode lists dropped, to keep this small).
    LaunchedEffect(current?.anime?.id, slides.size) {
        for (s in listOfNotNull(current, slides.getOrNull((slide + 1) % slides.size.coerceAtLeast(1)))) {
            val a = s.anime
            if (a.id in enriched || (a.banner.isNotBlank() && a.synopsis.isNotBlank())) continue
            runCatching { enrich(a) }.getOrNull()?.let { full -> enriched = enriched + (a.id to full.copy(episodes = emptyList(), recommendations = emptyList())) }
        }
    }
    fun shown(s: HeroSlide) = HeroSlide(enriched[s.anime.id] ?: s.anime, s.episode, s.label)
    val firstFocus = remember { FocusRequester() }
    val backdrop = current?.let { enriched[it.anime.id] ?: it.anime } ?: continueItems.firstOrNull()?.anime ?: popular.firstOrNull()
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(c.surface)) {
        Backdrop(backdrop?.banner?.ifBlank { backdrop.poster }, Modifier.align(Alignment.TopEnd), alpha = if (tab == "Home") .6f else .28f)
        val margin = Modifier.padding(horizontal = Tokens.screenH)
        // Schedule: a week from today, loaded when the tab opens; "now" ticks every 30 s so episodes
        // grey out as they air (a slow tick: nothing redraws in between).
        val days = remember { scheduleDays() }
        var day by rememberSaveable { mutableIntStateOf(0) }
        var schedule by remember { mutableStateOf<List<ScheduleEntry>?>(null) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val upcomingFocus = remember { FocusRequester() }
        LaunchedEffect(tab) {
            if (tab != "Schedule") return@LaunchedEffect
            if (schedule == null) schedule = runCatching { loadSchedule(days.first(), days.last() + DAY_MS) }.getOrDefault(emptyList())
            while (true) { now = System.currentTimeMillis(); delay(30_000) }
        }
        // My list narrows the schedule to the profile's library; Month shows a calendar, and choosing
        // a day lists what airs then in a side sheet (a list inside this list would not scroll).
        var scheduleMine by rememberSaveable(settings.scheduleMine) { mutableStateOf(settings.scheduleMine) }
        var scheduleMonthly by rememberSaveable(settings.scheduleMonth) { mutableStateOf(settings.scheduleMonth) }
        var monthShift by rememberSaveable { mutableIntStateOf(0) }
        var monthLoaded by remember { mutableStateOf(false) }
        var monthEntries by remember { mutableStateOf<List<ScheduleEntry>?>(null) }
        var followed by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var pickedDay by remember { mutableStateOf<Long?>(null) }
        var lastPicked by remember { mutableLongStateOf(0L) }
        val pickedFocus = remember { FocusRequester() }
        fun closeDay() {
            pickedDay = null
            scope.launch { delay(120); runCatching { pickedFocus.requestFocus() } }
        }
        LaunchedEffect(tab, libraryVersion) {
            if (tab == "Schedule") followed = runCatching { library.library.followed() }.getOrDefault(emptySet())
        }
        LaunchedEffect(tab, scheduleMonthly, monthShift) {
            if (tab != "Schedule" || !scheduleMonthly) return@LaunchedEffect
            monthLoaded = false
            // Stepping through months quickly loads only the one it stops on.
            delay(200)
            val start = monthStart(monthShift)
            monthEntries = runCatching { loadMonth(start, monthStart(monthShift + 1)) }.getOrNull()
            monthLoaded = true
        }
        val monthWeeks = remember(monthShift) { monthWeeks(monthStart(monthShift)) }
        val byDay = remember(monthEntries) { monthEntries.orEmpty().groupBy { dayStartOf(it.airingAt) } }
        LazyColumn(
            state = homeList,
            // While a Library card is open, the page behind it cannot take focus.
            modifier = Modifier.focusProperties { onEnter = { if ((tab == "Library" && library.sheet != null) || pickedDay != null) cancelFocusChange() } }.focusGroup(),
            contentPadding = PaddingValues(vertical = Tokens.screenV),
            verticalArrangement = Arrangement.spacedBy(Tokens.gapXl)
        ) {
            item { Box(margin) { TopBar(tab, { tab = it; highlighted = null }, search, firstFocus, profile, switchProfile, openSettings) } }
            // Only Home has a hero; the other tabs start right under the top bar.
            if (tab == "Home") item(key = "hero") {
                HeroSlider(
                    slides.map(::shown), slide.coerceIn(0, (slides.size - 1).coerceAtLeast(0)), { slide = it }, loading, error,
                    onFocusChange = { focused ->
                        heroFocused = focused
                        // After the TV focus pivot has had its turn, so this scroll wins.
                        if (focused) scope.launch { delay(40); if (heroFocused && (homeList.firstVisibleItemIndex != 0 || homeList.firstVisibleItemScrollOffset != 0)) homeList.animateScrollToItem(0) }
                    },
                    play = { s -> if (s.episode != null) playNew(NewEpisode(s.anime, s.episode, 0L)) else open(s.anime) },
                    details = { s -> open(s.anime) },
                    modifier = margin
                )
            }
            when (tab) {
                "Home" -> {
                    if (settings.rowContinue && continueItems.isNotEmpty()) item {
                        Column {
                            SectionHeader("Continue watching", margin)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 10.dp)) {
                                items(continueItems, key = { it.anime.id }) { item ->
                                    ContinueCard(
                                        title = item.anime.title,
                                        subtitle = if (item.upNext) "Up next · Episode ${item.episode.number}" else "Episode ${item.episode.number}" + (item.episode.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                        image = item.episode.still.ifBlank { item.anime.banner.ifBlank { item.anime.poster } },
                                        progress = item.progress,
                                        onClick = { resume(item) },
                                        modifier = Modifier.width(205.dp),
                                        onFocus = { highlighted = item.anime }
                                    )
                                }
                            }
                        }
                    }
                    if (settings.rowNew && newEpisodes.isNotEmpty()) item {
                        Column {
                            SectionHeader("New episodes", margin)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 12.dp)) {
                                items(newEpisodes.filter { !it.anime.adult }, key = { "${it.anime.id}:${it.episode}" }) { release ->
                                    PosterCard(release.anime, { playNew(release) }, onFocus = { highlighted = release.anime }, subtitle = "Episode ${release.episode} · ${ago(release.published)}")
                                }
                            }
                        }
                    }
                    if (settings.rowAiring && airing.isNotEmpty()) item { Shelf("Airing now", airing, { highlighted = it }, open) }
                    if (settings.rowPopular && popular.isNotEmpty()) item { Shelf("Popular series", popular, { highlighted = it }, open, shows.total, shows::loadMore) }
                    if (settings.rowUpcoming && upcoming.items.isNotEmpty()) item { Shelf("Upcoming", upcoming.items, { highlighted = it }, open, upcoming.total, upcoming::loadMore) }
                }
                "Explore" -> {
                    item(key = "explore-bar") {
                        ExploreBar(
                            filter, explore.total, explore.loading, Modifier.then(margin),
                            filtersButton = filtersButton,
                            onFilters = { filtersOpen = true },
                            onThisSeason = { filter = filter.thisSeason() },
                            onStepSeason = { filter = filter.stepSeason(it) }
                        )
                    }
                    val browseColumns = if (settings.portraitCards) 6 else 4
                    val rows = explore.items.chunked(browseColumns)
                    when {
                        rows.isEmpty() && explore.loading -> item(key = "explore-loading") { LoadingRow(margin) }
                        rows.isEmpty() && explore.failed -> item(key = "explore-failed") {
                            Box(margin) { EmptyNote(R.drawable.sym_error, "Couldn't load this season", "Check your connection, or pick another season or filter.") }
                        }
                        rows.isEmpty() -> item(key = "explore-empty") {
                            Box(margin) { EmptyNote(R.drawable.sym_tune, "No titles match", "Try fewer filters, or another season or year.") }
                        }
                    }
                    items(rows.size, key = { "explore-row-${rows[it].first().id}" }) { index ->
                        // Composing one of the last rows asks for the next page.
                        if (index >= rows.size - 2) LaunchedEffect(rows.size) { explore.loadMore() }
                        Row(margin.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg)) {
                            rows[index].forEach { anime ->
                                PosterCard(anime, { open(anime) }, Modifier.weight(1f), width = Dp.Unspecified, onFocus = { highlighted = anime })
                            }
                            repeat(browseColumns - rows[index].size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                "Schedule" -> {
                    item(key = "schedule-controls") {
                        Row(margin, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), verticalAlignment = Alignment.CenterVertically) {
                            TvChip("All shows", !scheduleMine, { scheduleMine = false }, icon = R.drawable.sym_live_tv)
                            TvChip("My list", scheduleMine, { scheduleMine = true }, icon = R.drawable.sym_bookmarks)
                            Spacer(Modifier.weight(1f))
                            TvChip("Week", !scheduleMonthly, { scheduleMonthly = false }, icon = R.drawable.sym_schedule)
                            TvChip("Month", scheduleMonthly, { scheduleMonthly = true }, icon = R.drawable.sym_calendar_month)
                        }
                    }
                    if (scheduleMonthly) {
                        item(key = "month-stepper") {
                            Box(margin) {
                                // Published months run from two back to one ahead; one more each way
                                // shows the note below rather than stopping dead.
                                SeasonStepper(monthLabel(monthStart(monthShift)), { monthShift = (monthShift + it).coerceIn(-2, 2) }, "   Left / Right to change month")
                            }
                        }
                        when {
                            !monthLoaded -> item(key = "month-loading") { LoadingRow(margin) }
                            monthEntries == null -> item(key = "month-missing") {
                                Box(margin) { EmptyNote(R.drawable.sym_calendar_month, "This month isn't published yet", "Its schedule appears a few weeks ahead. Check your connection, or try another month.") }
                            }
                            else -> {
                                item(key = "month-weekdays") {
                                    Row(margin, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                                        weekdayLabels().forEach { label ->
                                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                                val today = dayStartOf(now)
                                items(monthWeeks.size, key = { "month-week-${monthWeeks[it].firstNotNullOf { d -> d }}" }) { index ->
                                    Row(margin, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                                        monthWeeks[index].forEach { date ->
                                            if (date == null) Spacer(Modifier.weight(1f))
                                            else {
                                                val listed = byDay[date].orEmpty()
                                                MonthDay(
                                                    date, listed.size, listed.count { it.anime.id in followed }, scheduleMine, date == today, date < today,
                                                    Modifier.weight(1f).then(if (date == lastPicked) Modifier.focusRequester(pickedFocus) else Modifier)
                                                ) { lastPicked = date; pickedDay = date }
                                            }
                                        }
                                    }
                                }
                                if (scheduleMine && byDay.values.none { d -> d.any { it.anime.id in followed } }) item(key = "month-none") {
                                    Box(margin) { EmptyNote(R.drawable.sym_bookmarks, "Nothing from your list this month", "Titles in your Library show here when they have episodes airing.") }
                                }
                            }
                        }
                    } else {
                    val all = schedule
                    val start = days[day]
                    val entries = all.orEmpty().filter { it.airingAt >= start && it.airingAt < start + DAY_MS && (!scheduleMine || it.anime.id in followed) }
                    val firstUpcoming = entries.indexOfFirst { it.airingAt > now }
                    // Index of the first row in this LazyColumn: top bar, controls, days, heading.
                    val firstRowIndex = 4
                    item(key = "schedule-days") {
                        ScheduleDays(days, day, { day = it; highlighted = null }, Modifier.onPreviewKeyEvent { event ->
                            // On today, Down jumps past what has already aired to the next broadcast.
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && day == 0 && firstUpcoming > 0) {
                                scope.launch {
                                    homeList.scrollToItem(firstRowIndex + firstUpcoming - 1)
                                    withFrameNanos { }
                                    runCatching { upcomingFocus.requestFocus() }
                                }
                                true
                            } else false
                        })
                    }
                    item(key = "schedule-heading") {
                        SectionHeader(dayTitle(start, day), margin, supporting = when {
                            all == null -> "Loading…"
                            entries.isEmpty() -> null
                            else -> "${entries.size} episodes"
                        })
                    }
                    if (all == null) item(key = "schedule-loading") { LoadingRow(margin) }
                    if (all != null && entries.isEmpty()) item(key = "schedule-empty") {
                        Box(margin) {
                            if (scheduleMine) EmptyNote(R.drawable.sym_bookmarks, "Nothing from your list today", "Titles in your Library show here on the days they air. Try another day, or All shows.")
                            else EmptyNote(R.drawable.sym_calendar_month, "Nothing scheduled", "No episodes are listed for this day. Check your connection or try another day.")
                        }
                    }
                    items(entries.size, key = { "sched-${entries[it].anime.id}-${entries[it].episode}-${entries[it].airingAt}" }) { index ->
                        val entry = entries[index]
                        ScheduleRow(
                            entry, now, showNowLine = index == firstUpcoming && day == 0 && index > 0,
                            modifier = margin.then(if (index == firstUpcoming) Modifier.focusRequester(upcomingFocus) else Modifier),
                            onFocus = { highlighted = entry.anime }
                        ) { if (entry.airingAt <= now) playScheduled(entry) else open(entry.anime) }
                    }
                    }
                }
                "Library" -> libraryItems(library, syncState, accounts, margin, open, tileFocus, newListFocus, settings.portraitCards)
            }
        }
        // A calendar day: what airs then.
        val dayEntries = byDay[lastPicked].orEmpty().filter { !scheduleMine || it.anime.id in followed }
        SideSheet(tab == "Schedule" && pickedDay != null, onClose = ::closeDay, width = 560.dp, focusKey = pickedDay) { first ->
            item(key = "day-title") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                    Column(Modifier.weight(1f)) {
                        Text(dayName(lastPicked), style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (dayEntries.size == 1) "1 episode" else "${dayEntries.size} episodes", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                    PillButton("Done", ::closeDay, if (dayEntries.isEmpty()) Modifier.focusRequester(first) else Modifier, icon = R.drawable.sym_check, style = ButtonStyle.Filled)
                }
            }
            item(key = "day-filter") {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                    TvChip("All shows", !scheduleMine, { scheduleMine = false }, icon = R.drawable.sym_live_tv)
                    TvChip("My list", scheduleMine, { scheduleMine = true }, icon = R.drawable.sym_bookmarks)
                }
            }
            if (dayEntries.isEmpty()) item(key = "day-empty") {
                Text(if (scheduleMine) "Nothing from your list airs this day." else "No episodes are listed for this day.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            }
            items(dayEntries.size, key = { "day-${dayEntries[it].anime.id}-${dayEntries[it].episode}" }) { index ->
                val entry = dayEntries[index]
                ScheduleRow(entry, now, showNowLine = false, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier, onFocus = {}) {
                    pickedDay = null
                    if (entry.airingAt <= now) playScheduled(entry) else open(entry.anime)
                }
            }
        }
        if (tab == "Library") {
            // The list chips stay on screen once the page scrolls past them.
            val chipsIndex = if (Service.entries.any { accounts[it]?.needsLogin != false }) 3 else 2
            val chipsGone by remember(chipsIndex) { derivedStateOf { homeList.firstVisibleItemIndex > chipsIndex } }
            var chipsHeld by remember { mutableStateOf(false) }
            fun chipsToTop() {
                scope.launch {
                    chipsHeld = false
                    homeList.scrollToItem(0)
                    // Once for the pinned row to go, once for the page's own row to come back.
                    withFrameNanos { }
                    withFrameNanos { }
                    runCatching { library.chipsFocus.requestFocus() }
                }
            }
            val pinned = chipsGone || chipsHeld
            val pinnedBottom = with(androidx.compose.ui.platform.LocalDensity.current) { 84.dp.roundToPx() }
            SideEffect { library.pinnedShown = pinned }
            if (pinned) Box(Modifier.focusProperties { onEnter = { if (library.sheet != null) cancelFocusChange() } }.focusGroup()) {
                PinnedLibraryChips(
                    library, newListFocus, onFocus = { chipsHeld = it },
                    // A new choice starts from the top of its titles, right under the chips.
                    onChange = { scope.launch { homeList.scrollToItem(chipsIndex) } },
                    toTop = ::chipsToTop,
                    toGrid = {
                        // The first row of titles showing under the pinned chips.
                        val row = homeList.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            (item.key as? String)?.startsWith("lib-row") == true && item.offset + item.size / 2 > pinnedBottom
                        }
                        runCatching { library.rowFocus[row?.key]?.requestFocus() }
                    }
                )
            }
            // Up stops at the row above, so Back is the way up from a long list: to the pinned chips,
            // then to the top of the page. (Other sheets over the page take Back first.)
            BackHandler(enabled = (chipsGone || chipsHeld) && library.sheet == null && !listsMenuOpen) {
                if (chipsHeld) chipsToTop() else runCatching { library.pinnedFocus.requestFocus() }
            }
            LibraryOverlays(library, accounts, syncState, tileFocus, newListFocus)
        }
        // Explore's filters open on demand in a side sheet on the right.
        FilterSheet(
            visible = filtersOpen && tab == "Explore", filter = filter, onFilter = { filter = it },
            onClose = { filtersOpen = false; scope.launch { delay(60); runCatching { filtersButton.requestFocus() } } }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopBar(tab: String, onTab: (String) -> Unit, search: () -> Unit, firstFocus: FocusRequester, profile: Profile, switchProfile: () -> Unit, openSettings: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StremliWordmark()
        Spacer(Modifier.weight(1f))
        Row(Modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(50)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // The app opens with focus on the tab it opens on.
            fun first(name: String) = if (tab == name) Modifier.focusRequester(firstFocus) else Modifier
            NavItem(R.drawable.sym_home, "Home", tab == "Home", { onTab("Home") }, first("Home"))
            NavItem(R.drawable.sym_live_tv, "Explore", tab == "Explore", { onTab("Explore") }, first("Explore"))
            NavItem(R.drawable.sym_calendar_month, "Schedule", tab == "Schedule", { onTab("Schedule") }, first("Schedule"))
            NavItem(R.drawable.sym_bookmarks, "Library", tab == "Library", { onTab("Library") }, first("Library"))
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            RoundIconButton(R.drawable.sym_search, "Search", search, showRing = false)
            RoundIconButton(R.drawable.sym_settings, "Settings", openSettings, showRing = false)
            // The current profile; choosing it opens "Who's watching?".
            FocusSurface(
                switchProfile, Modifier.size(48.dp).semantics { contentDescription = "Profile: ${profile.name}. Switch profile" },
                shape = CircleShape, color = Color.Transparent, focusedColor = MaterialTheme.colorScheme.surfaceContainerHighest, focusedScale = 1.12f
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ProfileAvatar(profile, 38.dp) } }
        }
    }
}

/** A lightweight, dotless wordmark using the app's rounded brand font. */
@Composable
private fun StremliWordmark() {
    val style = MaterialTheme.typography.headlineSmallEmphasized.copy(
        fontFamily = BrandFont, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp,
        letterSpacing = (-.7).sp
    )
    Text("stremlı", modifier = Modifier.clearAndSetSemantics { contentDescription = "Stremli" },
        style = style, color = MaterialTheme.colorScheme.primary, maxLines = 1)
}

/** One hero slide: a title, the new episode it announces (if any) and its label. */
private class HeroSlide(val anime: Anime, val episode: Int?, val label: String)

private fun heroSlides(newEpisodes: List<NewEpisode>, airing: List<Anime>, popular: List<Anime>): List<HeroSlide> {
    val fresh = newEpisodes.filter { !it.anime.adult }.distinctBy { it.anime.id }.take(4).map { HeroSlide(it.anime, it.episode, "New episode") }
    val taken = fresh.map { it.anime.id }.toSet()
    val featured = (airing.take(8) + popular.take(8)).filter { !it.adult }.distinctBy { it.id }.filter { it.id !in taken }.take(7 - fresh.size)
        .map { HeroSlide(it, null, if (it.status == "RELEASING") "Airing now" else "Featured") }
    return fresh + featured
}

/**
 * Home's hero: the slide's label, title, facts and synopsis over the key art, with Play and
 * Details. It advances by itself (see Home) and pauses while focused; Left on the first button
 * and Right on the last one change slides.
 */
@Composable
private fun HeroSlider(
    slides: List<HeroSlide>, index: Int, onIndex: (Int) -> Unit, loading: Boolean, error: String,
    onFocusChange: (Boolean) -> Unit, play: (HeroSlide) -> Unit, details: (HeroSlide) -> Unit, modifier: Modifier = Modifier
) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(Tokens.gapMd, Alignment.Bottom)) {
        val slide = slides.getOrNull(index)
        if (slide == null) {
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodyLarge, color = c.error)
            else LoadingRow(Modifier)
            return@Column
        }
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = slide, contentKey = { it.anime.id }, label = "hero",
            transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) }
        ) { s ->
            Column(Modifier.fillMaxWidth(.6f), verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                Text(s.label.uppercase() + (s.episode?.let { "  ·  EPISODE $it" } ?: ""),
                    style = MaterialTheme.typography.labelLargeEmphasized, color = c.onSurfaceVariant, letterSpacing = 1.sp)
                Text(s.anime.title, style = MaterialTheme.typography.displaySmallEmphasized, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaTags(s.anime)
                if (s.anime.synopsis.isNotBlank()) Text(s.anime.synopsis, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        var playFocused by remember { mutableStateOf(false) }
        var infoFocused by remember { mutableStateOf(false) }
        val hasInfo = slide.episode != null
        val playButton = remember { FocusRequester() }
        Row(
            // Coming down from the top bar always lands on Play, whichever button is nearer.
            Modifier.focusProperties { onEnter = { if (!playFocused) runCatching { playButton.requestFocus() } } }.focusGroup()
                .onFocusChanged { onFocusChange(it.hasFocus) }.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || slides.size < 2) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionLeft && playFocused -> { onIndex((index - 1 + slides.size) % slides.size); true }
                    event.key == Key.DirectionRight && (infoFocused || (!hasInfo && playFocused)) -> { onIndex((index + 1) % slides.size); true }
                    else -> false
                }
            },
            horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd), verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton(
                if (slide.episode != null) "Play E${slide.episode}" else "Watch now", { play(slide) },
                Modifier.focusRequester(playButton).onFocusChanged { playFocused = it.isFocused }, icon = R.drawable.sym_play_arrow, style = ButtonStyle.Filled
            )
            if (hasInfo) PillButton("Details", { details(slide) }, Modifier.onFocusChanged { infoFocused = it.isFocused }, icon = R.drawable.sym_chevron_right)
            Spacer(Modifier.width(Tokens.gapLg))
            // Position dots: the current slide is a wider pill.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                slides.indices.forEach { i ->
                    Box(Modifier.size(width = if (i == index) 22.dp else 8.dp, height = 8.dp).background(if (i == index) c.primary else c.outlineVariant, RoundedCornerShape(50)))
                }
            }
        }
    }
}

@Composable
private fun MetaTags(anime: Anime) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
        if (anime.score > 0) Tag("${anime.score}%", icon = R.drawable.sym_star, container = c.primaryContainer, content = c.onPrimaryContainer)
        if (anime.year > 0) Tag(anime.year.toString(), icon = R.drawable.sym_calendar_month)
        if (anime.format.isNotBlank()) Tag(anime.format.replace('_', ' '))
        if (anime.episodeCount > 0) Tag("${anime.episodeCount} episodes")
        anime.genres.substringBefore(',').takeIf { it.isNotBlank() }?.let { Tag(it.trim()) }
    }
}

// ---- Loading ------------------------------------------------------------------------------------

/**
 * The first screen: the wordmark exactly where the window's launch background drew it (so the
 * hand-over is invisible), with the expressive loading indicator below once loading takes long
 * enough to notice.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LaunchScreen(error: String) {
    val c = MaterialTheme.colorScheme
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(350); slow = true }
    Box(Modifier.fillMaxSize().background(c.surface)) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.launch_logo), contentDescription = "Stremli",
            modifier = Modifier.align(Alignment.Center).width(240.dp)
        )
        Column(Modifier.align(Alignment.Center).padding(top = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodyLarge, color = c.error, textAlign = TextAlign.Center)
            else androidx.compose.animation.AnimatedVisibility(slow, enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())) {
                LoadingIndicator(Modifier.size(52.dp), color = c.primary)
            }
        }
    }
}

/** Where Play will appear: a quiet pill with a small loading indicator, not focusable. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayPlaceholder() {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.height(52.dp).background(c.surfaceContainerHigh, RoundedCornerShape(50)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)
    ) {
        LoadingIndicator(Modifier.size(28.dp), color = c.primary)
        Text("Loading", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
    }
}

/** An expressive loading indicator in place of content that is still on its way. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
    }
}

// ---- Schedule -------------------------------------------------------------------------------

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Local midnight of today and the next six days. */
private fun scheduleDays(): List<Long> {
    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return List(7) { i -> (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }.timeInMillis }
}

/** Local midnight of the day [time] falls on. */
private fun dayStartOf(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Local midnight on the first of the month [shift] months from now. */
private fun monthStart(shift: Int): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.MONTH, shift)
}.timeInMillis

/** The month starting at [start] as weeks of seven; each day is its local midnight, null outside the month. */
private fun monthWeeks(start: Long): List<List<Long?>> {
    val cal = Calendar.getInstance().apply { timeInMillis = start }
    val days = ArrayList<Long?>()
    repeat((cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7) { days.add(null) }
    val month = cal.get(Calendar.MONTH)
    while (cal.get(Calendar.MONTH) == month) { days.add(cal.timeInMillis); cal.add(Calendar.DAY_OF_MONTH, 1) }
    while (days.size % 7 != 0) days.add(null)
    return days.chunked(7)
}

/** Short day names in calendar order for this locale. */
private fun weekdayLabels(): List<String> {
    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }
    val format = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
    return List(7) { format.format(cal.time).also { cal.add(Calendar.DAY_OF_MONTH, 1) } }
}

private fun monthLabel(start: Long): String = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(start))

private fun dayName(start: Long): String = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(java.util.Date(start))

/** One day of the month calendar: its date and how many episodes air, with those from the viewer's list. */
@Composable
private fun MonthDay(date: Long, total: Int, mine: Int, onlyMine: Boolean, today: Boolean, past: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val number = remember(date) { Calendar.getInstance().apply { timeInMillis = date }.get(Calendar.DAY_OF_MONTH) }
    val shows = if (onlyMine) mine else total
    FocusSurface(
        onClick, modifier.height(64.dp).semantics { contentDescription = "${dayName(date)}: $total episodes, $mine from your list" },
        shape = RoundedCornerShape(Tokens.radiusThumb), focusedShape = RoundedCornerShape(Tokens.radiusControl),
        color = if (today) c.primaryContainer else c.surfaceContainerHigh.copy(alpha = if (past) .5f else 1f), focusedColor = c.primary,
        contentColor = if (today) c.onPrimaryContainer else if (past) c.onSurfaceVariant else c.onSurface, focusedContentColor = c.onPrimary, focusedScale = 1.06f
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(number.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
                if (!onlyMine && mine > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SymIcon(R.drawable.sym_bookmarks, "From your list", Modifier.size(14.dp))
                    Text(mine.toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            if (shows > 0) Text(if (shows == 1) "1 show" else "$shows shows", style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = .8f), maxLines = 1)
        }
    }
}

private fun dayLabel(start: Long, index: Int): String = when (index) {
    0 -> "Today"
    1 -> "Tomorrow"
    else -> java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault()).format(java.util.Date(start))
}

private fun dayTitle(start: Long, index: Int): String {
    val date = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(java.util.Date(start))
    return when (index) { 0 -> "Today · $date"; 1 -> "Tomorrow · $date"; else -> date }
}

/** "in 45 min", "in 3 h 10 min", or the empty string once it has aired. */
private fun countdown(at: Long, now: Long): String {
    val minutes = ((at - now) / 60_000L).coerceAtLeast(0)
    return when {
        at <= now -> ""
        minutes < 1 -> "now"
        minutes < 60 -> "in $minutes min"
        minutes < 24 * 60 -> "in ${minutes / 60} h" + (minutes % 60).takeIf { it > 0 }?.let { " $it min" }.orEmpty()
        else -> "in ${minutes / (24 * 60)} d" + ((minutes / 60) % 24).takeIf { it > 0 }?.let { " $it h" }.orEmpty()
    }
}

/**
 * When an episode airs, if it has not yet: its broadcast time when known, otherwise the start of
 * its listed air date. 0 once it has aired.
 */
internal fun Episode.airsAfter(now: Long): Long = when {
    airsAt > now -> airsAt
    airsAt == 0L -> dayMs(date).takeIf { it > now } ?: 0L
    else -> 0L
}

/** "Airs in 2 d 4 h · Fri 12 Nov, 14:30" (or "Airs Fri 12 Nov" when only the date is known). */
private fun airsLabel(context: android.content.Context, episode: Episode, at: Long, now: Long): String {
    val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))
    if (episode.airsAt <= 0L) return "Airs $day"
    val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))
    return "Airs ${countdown(at, now)} · $day, $time"
}

@Composable
private fun ScheduleDays(days: List<Long>, selected: Int, onDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 4.dp)) {
        items(days.size) { index ->
            TvChip(dayLabel(days[index], index), selected == index, { onDay(index) }, icon = if (index == 0) R.drawable.sym_calendar_month else null)
        }
    }
}

/**
 * One broadcast: time, poster, title and episode, then how long until it airs. Episodes that have
 * aired are greyed out (and play when chosen); a line marks "now" on today's list.
 */
@Composable
private fun ScheduleRow(entry: ScheduleEntry, now: Long, showNowLine: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val aired = entry.airingAt <= now
    var focused by remember { mutableStateOf(false) }
    val time = remember(entry.airingAt) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(entry.airingAt)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
        if (showNowLine) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
            Text("Now", style = MaterialTheme.typography.labelLargeEmphasized, color = c.primary)
            Box(Modifier.weight(1f).height(2.dp).background(c.primary, RoundedCornerShape(50)))
        }
        FocusSurface(
            onClick, Modifier.fillMaxWidth().height(84.dp), shape = RoundedCornerShape(Tokens.radiusCard),
            focusedShape = RoundedCornerShape(Tokens.radiusSheet), color = Color.Transparent, focusedColor = c.surfaceContainerHigh,
            focusedScale = 1.02f, onFocusChange = { focused = it; if (it) onFocus() }
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp).graphicsLayer { alpha = if (aired && !focused) .42f else 1f },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg)
            ) {
                Text(time, Modifier.width(84.dp), style = MaterialTheme.typography.titleMediumEmphasized, color = if (aired) c.onSurfaceVariant else c.onSurface)
                AsyncImage(entry.anime.poster, contentDescription = null, modifier = Modifier.width(46.dp).fillMaxHeight().clip(RoundedCornerShape(Tokens.radiusThumb)).background(c.surfaceContainerHigh), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.anime.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val total = entry.anime.episodeCount.takeIf { it > 0 }?.let { " of $it" }.orEmpty()
                    Text("Episode ${entry.episode}$total" + entry.anime.format.takeIf { it.isNotBlank() }?.let { " · ${it.replace('_', ' ')}" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1)
                }
                if (aired) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SymIcon(R.drawable.sym_check, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                    Text(if (focused) "Aired · play" else "Aired", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                } else {
                    val soon = entry.airingAt - now < 60 * 60_000L
                    Tag(countdown(entry.airingAt, now), container = if (soon) c.primaryContainer else c.surfaceContainerHighest, content = if (soon) c.onPrimaryContainer else c.onSurface)
                }
            }
        }
    }
}

// ---- Explore ---------------------------------------------------------------------------------


/** AniList genres offered as categories (adult-only genres left out). */
private val GENRES = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Sci-Fi", "Slice of Life", "Mystery",
    "Supernatural", "Psychological", "Thriller", "Horror", "Sports", "Mecha", "Music", "Mahou Shoujo"
)

private val ExploreFilterSaver = androidx.compose.runtime.saveable.listSaver<ExploreFilter, String>(
    save = { listOf(it.type.name, it.genre.orEmpty(), it.season.orEmpty(), it.year?.toString().orEmpty(), it.status.name, it.sort.name) },
    restore = { ExploreFilter(ExploreType.valueOf(it[0]), it[1].ifBlank { null }, it[2].ifBlank { null }, it[3].toIntOrNull(), ExploreStatus.valueOf(it[4]), ExploreSort.valueOf(it[5])) }
)

/** The current AniList season: Winter is January to March, and so on. */
private fun currentSeason(): Pair<String, Int> {
    val cal = Calendar.getInstance()
    return ANIME_SEASONS[cal.get(Calendar.MONTH) / 3].first to cal.get(Calendar.YEAR)
}

private fun seasonLabel(season: String?, year: Int?): String =
    listOfNotNull(ANIME_SEASONS.firstOrNull { it.first == season }?.second, year?.toString()).joinToString(" ")

private fun ExploreFilter.thisSeason(): ExploreFilter {
    val (season, year) = currentSeason()
    return if (this.season == season && this.year == year) copy(season = null, year = null) else copy(season = season, year = year)
}

/** Moves the season by [delta], rolling the year; up to two seasons past the current one. */
private fun ExploreFilter.stepSeason(delta: Int): ExploreFilter {
    val (nowSeason, nowYear) = currentSeason()
    val index = ANIME_SEASONS.indexOfFirst { it.first == season }.coerceAtLeast(0)
    val limit = nowYear * 4 + ANIME_SEASONS.indexOfFirst { it.first == nowSeason } + 2
    val next = ((year ?: nowYear) * 4 + index + delta).coerceIn(1960 * 4, limit)
    return copy(season = ANIME_SEASONS[next % 4].first, year = next / 4)
}

/** A short description of the filters: "Fall 2026 · Series · Action · Most popular". */
private fun ExploreFilter.summary(): String = listOfNotNull(
    if (season != null) seasonLabel(season, year) else year?.toString(),
    type.takeIf { it != ExploreType.All }?.label,
    genre,
    status.takeIf { it != ExploreStatus.Any }?.label,
    sort.label
).joinToString(" · ")

/**
 * Explore's header: the filters in one line, a Filters button (with how many are set), and a
 * "This season" shortcut. With a season chosen, a season bar below steps through seasons with
 * Left and Right, like a seasonal chart.
 */
@Composable
private fun ExploreBar(
    filter: ExploreFilter, total: Int, loading: Boolean, modifier: Modifier, filtersButton: FocusRequester,
    onFilters: () -> Unit, onThisSeason: () -> Unit, onStepSeason: (Int) -> Unit
) {
    val c = MaterialTheme.colorScheme
    val (nowSeason, nowYear) = currentSeason()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            Column(Modifier.weight(1f)) {
                Text("Explore", style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                Text(
                    filter.summary() + if (total > 0 && !loading) "  ·  $total titles" else "",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            TvChip(
                "This season", filter.season == nowSeason && filter.year == nowYear, onThisSeason,
                icon = R.drawable.sym_calendar_month
            )
            PillButton(
                if (filter.active > 0) "Filters · ${filter.active}" else "Filters", onFilters,
                Modifier.focusRequester(filtersButton), icon = R.drawable.sym_tune,
                style = if (filter.active > 0) ButtonStyle.Filled else ButtonStyle.Tonal
            )
        }
        if (filter.season != null) SeasonStepper(seasonLabel(filter.season, filter.year), onStepSeason)
    }
}

/** A full-width bar: Left and Right step to the previous and next season. */
@Composable
private fun SeasonStepper(label: String, onStep: (Int) -> Unit, hint: String = "   Left / Right to change season") {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(52.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onStep(-1); true }
                Key.DirectionRight -> { onStep(1); true }
                else -> false
            }
        },
        shape = RoundedCornerShape(Tokens.radiusControl), focusedShape = RoundedCornerShape(Tokens.radiusCard),
        color = c.surfaceContainerHigh, focusedColor = c.secondaryContainer,
        contentColor = c.onSurface, focusedContentColor = c.onSecondaryContainer, focusedScale = 1.02f
    ) { t ->
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SymIcon(R.drawable.sym_arrow_back, "Previous season", Modifier.size(20.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                SymIcon(R.drawable.sym_calendar_month, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Tokens.gapSm))
                Text(label, style = MaterialTheme.typography.titleMediumEmphasized)
                if (t > .5f) Text(hint, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = .7f))
            }
            SymIcon(R.drawable.sym_chevron_right, "Next season", Modifier.size(20.dp))
        }
    }
}

/**
 * Explore's filters in a side sheet on the right (Material side sheet), opened from the Filters
 * button. Changes apply as they are made; Back or Done closes it, and focus stays inside while open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(visible: Boolean, filter: ExploreFilter, onFilter: (ExploreFilter) -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible) { onClose() }
    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    androidx.compose.animation.AnimatedVisibility(visible, enter = fadeIn(fade), exit = fadeOut(fade)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        androidx.compose.animation.AnimatedVisibility(
            visible,
            enter = androidx.compose.animation.slideInHorizontally(slide) { it } + fadeIn(fade),
            exit = androidx.compose.animation.slideOutHorizontally(slide) { it } + fadeOut(fade)
        ) {
            LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
            val (nowSeason, nowYear) = currentSeason()
            val years = remember { ((nowYear + 1) downTo 1990).toList() }
            LazyColumn(
                Modifier.width(400.dp).fillMaxHeight()
                    .background(c.surfaceContainer, RoundedCornerShape(topStart = Tokens.radiusSheet, bottomStart = Tokens.radiusSheet))
                    // Keep focus inside while open; let it out while the sheet slides away.
                    .focusProperties { onExit = { if (visible) cancelFocusChange() } }.focusGroup(),
                contentPadding = PaddingValues(start = 24.dp, end = 28.dp, top = 28.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapLg)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Filters", style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface, modifier = Modifier.weight(1f))
                        PillButton("Done", onClose, Modifier.focusRequester(first), icon = R.drawable.sym_check, style = ButtonStyle.Filled)
                    }
                }
                item { FilterGroup("Sort by") { ExploreSort.entries.forEach { o -> TvChip(o.label, filter.sort == o, { onFilter(filter.copy(sort = o)) }) } } }
                item { FilterGroup("Type") { ExploreType.entries.forEach { o -> TvChip(o.label, filter.type == o, { onFilter(filter.copy(type = o)) }) } } }
                item {
                    FilterGroup("Season") {
                        TvChip("Any", filter.season == null, { onFilter(filter.copy(season = null)) })
                        ANIME_SEASONS.forEach { (key, label) ->
                            // A season needs a year: use the chosen one, or this year.
                            TvChip(label, filter.season == key, { onFilter(filter.copy(season = key, year = filter.year ?: nowYear)) })
                        }
                        TvChip("This season", filter.season == nowSeason && filter.year == nowYear, { onFilter(filter.copy(season = nowSeason, year = nowYear)) }, icon = R.drawable.sym_calendar_month)
                    }
                }
                item {
                    FilterGroup("Year") {
                        TvChip("Any", filter.year == null, { onFilter(filter.copy(year = null, season = null)) })
                        years.forEach { y -> TvChip(y.toString(), filter.year == y, { onFilter(filter.copy(year = y)) }) }
                    }
                }
                item { FilterGroup("Status") { ExploreStatus.entries.forEach { o -> TvChip(o.label, filter.status == o, { onFilter(filter.copy(status = o)) }) } } }
                item {
                    FilterGroup("Genre") {
                        TvChip("Any", filter.genre == null, { onFilter(filter.copy(genre = null)) })
                        (if (LocalSettings.current.showAdult) GENRES + "Hentai" else GENRES).forEach { g -> TvChip(g, filter.genre == g, { onFilter(filter.copy(genre = g)) }) }
                    }
                }
                item { PillButton("Clear all", { onFilter(ExploreFilter()) }, icon = R.drawable.sym_refresh, style = ButtonStyle.Outlined) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) { chips() }
    }
}

@Composable
private fun Shelf(title: String, items: List<Anime>, highlight: (Anime) -> Unit, open: (Anime) -> Unit, total: Int = items.size, loadMore: (() -> Unit)? = null) {
    val row = rememberLazyListState()
    // Ask for the next page while about ten posters remain, so the row never visibly ends early.
    if (loadMore != null) LaunchedEffect(row, items.size) {
        snapshotFlow { row.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= items.size - 10) loadMore()
        }
    }
    Column {
        SectionHeader(title, Modifier.padding(horizontal = Tokens.screenH), supporting = "${maxOf(total, items.size)} titles")
        LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 12.dp)) {
            items(items, key = { it.id }) { anime -> PosterCard(anime, { open(anime) }, onFocus = { highlight(anime) }) }
        }
    }
}

@Composable
internal fun EmptyNote(icon: Int, title: String, body: String) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(.6f).background(c.surfaceContainerLow, RoundedCornerShape(Tokens.radiusCard)).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).background(c.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) { SymIcon(icon, null, Modifier.size(24.dp), tint = c.onSecondaryContainer) }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}

// ---- Search ---------------------------------------------------------------------------------

private const val SEARCH_HISTORY_KEY = "searchHistory"
private const val SEARCH_HISTORY_SIZE = 8

private fun loadSearchHistory(prefs: android.content.SharedPreferences): List<String> =
    prefs.getString(SEARCH_HISTORY_KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

/** Puts [query] first in the history (no duplicates, at most 8) and saves it. */
private fun rememberSearch(prefs: android.content.SharedPreferences, history: List<String>, query: String): List<String> {
    val next = (listOf(query) + history.filterNot { it.equals(query, ignoreCase = true) }).take(SEARCH_HISTORY_SIZE)
    prefs.edit().putString(SEARCH_HISTORY_KEY, next.joinToString("\n")).apply()
    return next
}

/**
 * Search, laid out like a TV streaming app: the query and an on-screen keyboard on the left, with
 * autocomplete under it while typing (recent searches when the field is empty), and a poster grid on
 * the right. The grid only changes when the viewer submits: the Search key, Enter, or choosing a
 * suggestion or a recent search. Typing alone never reloads it, which keeps the remote responsive.
 */
@Composable
private fun Search(
    query: String, onQuery: (String) -> Unit, editQuery: ((String) -> String) -> Unit,
    submitted: String, resultsFor: String, onSubmit: (String) -> Unit,
    suggestions: List<String>, history: List<String>, onClearHistory: () -> Unit,
    results: List<Anime>, popular: List<Anime>, open: (Anime) -> Unit
) {
    val c = MaterialTheme.colorScheme
    val firstKey = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val typed = query.trim()
    val ran = submitted.trim()
    val noMatch = ran.isNotEmpty() && resultsFor == ran && results.isEmpty()
    val shown = if (ran.isEmpty() || noMatch) popular.take(60) else results
    // After a search the results take focus, once they are in.
    var focusResults by remember { mutableStateOf(false) }
    val resultsGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scope = rememberCoroutineScope()
    suspend fun focusFirstResult() {
        // Let the grid lay out the new results, from the top, before moving focus there.
        resultsGrid.scrollToItem(0)
        withFrameNanos { }
        delay(80)
        runCatching { firstResult.requestFocus() }
    }
    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        keyboard?.hide()
        // The same search again: the results are already there.
        if (q == resultsFor && results.isNotEmpty()) scope.launch { focusFirstResult() } else focusResults = true
        onSubmit(q)
    }
    // Not keyed on focusResults: clearing it here must not cancel the move.
    LaunchedEffect(resultsFor, results) {
        if (focusResults && resultsFor == ran) {
            if (results.isNotEmpty()) focusFirstResult()
            focusResults = false
        }
    }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
    Row(
        Modifier.fillMaxSize().padding(start = Tokens.screenH, end = Tokens.screenH, top = Tokens.screenV)
            // Hardware keyboards: letters, digits and space type, Backspace deletes. Remote
            // buttons produce no characters, so the D-pad is unaffected.
            .onPreviewKeyEvent { event ->
                // Handled here even with the text field focused: a key press reaching the field
                // would make it ask for the system keyboard. Text from the Fire TV phone app or
                // dictation is not a key press; it reaches the field through its input session.
                val native = event.nativeKeyEvent
                val typing = native.keyCode == android.view.KeyEvent.KEYCODE_DEL ||
                    (native.unicodeChar != 0 && native.unicodeChar.toChar().let { it.isLetterOrDigit() || it == ' ' })
                if (!typing) return@onPreviewKeyEvent false
                // The key-up is swallowed too, so no typing key falls through to anything else.
                if (event.type == KeyEventType.KeyDown) {
                    // editQuery applies the change to the latest query, so fast typing never drops keys.
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_DEL) editQuery { it.dropLast(1) }
                    else { val ch = native.unicodeChar.toChar().lowercaseChar(); editQuery { it + ch } }
                }
                true
            },
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(Modifier.width(258.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // One keyboard: the field shows the query and never opens the system keyboard (a real
            // text field brought it up as soon as the text changed, even with showKeyboardOnFocus
            // off). OK runs the search; Bluetooth and USB keyboards type straight in (see above).
            SearchDisplay(query, onClick = { submit(query) })
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SearchKey("Space", { onQuery("$query ") }, Modifier.weight(1f), icon = R.drawable.sym_space_bar)
                SearchKey("Delete", { onQuery(query.dropLast(1)) }, Modifier.weight(1f), icon = R.drawable.sym_arrow_back)
                SearchKey("Clear", { onQuery("") }, Modifier.weight(.8f), icon = R.drawable.sym_close)
                SearchKey("Search", { submit(query) }, Modifier.weight(.7f), icon = R.drawable.sym_search, primary = true)
            }
            "abcdefghijklmnopqrstuvwxyz1234567890".chunked(6).forEachIndexed { row, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    keys.forEachIndexed { column, key ->
                        SearchKey(key.toString(), { onQuery(query + key) }, Modifier.weight(1f).then(if (row == 0 && column == 0) Modifier.focusRequester(firstKey) else Modifier))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val typingNew = typed.isNotEmpty() && !typed.equals(ran, ignoreCase = true)
            when {
                typingNew && suggestions.isNotEmpty() -> {
                    Text("Suggestions", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                    suggestions.take(4).forEach { title -> Suggestion(title, typed, R.drawable.sym_search) { submit(title) } }
                }
                history.isNotEmpty() -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        SearchKey("Clear history", onClearHistory, Modifier.width(110.dp).height(28.dp))
                    }
                    history.take(4).forEach { past -> Suggestion(past, "", R.drawable.sym_schedule) { submit(past) } }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Typing does not reload the grid; say how to see results for what is typed.
            if (typed.isNotEmpty() && !typed.equals(ran, ignoreCase = true)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                SymIcon(R.drawable.sym_search, null, Modifier.size(18.dp), tint = c.tertiary)
                Text("Press Search to see results for “$typed”", style = MaterialTheme.typography.bodyMedium, color = c.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (noMatch) Text("No titles match “$ran”.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                Text(
                    if (ran.isEmpty() || noMatch) "Popular searches" else "Results for “$ran”",
                    style = MaterialTheme.typography.titleLargeEmphasized, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!noMatch && ran.isNotEmpty() && resultsFor == ran) Text("${results.size} titles", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (LocalSettings.current.portraitCards) 5 else 3),
                state = resultsGrid,
                horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapMd),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = Tokens.screenV)
            ) {
                itemsIndexed(shown, key = { _, it -> it.id }) { index, anime ->
                    PosterCard(anime, { open(anime) }, Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(firstResult) else Modifier), width = Dp.Unspecified)
                }
            }
        }
    }
}

/** The query as text in a pill (not an input field, so no system keyboard); OK runs the search. */
@Composable
private fun SearchDisplay(query: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = "Search: $query" },
        shape = RoundedCornerShape(Tokens.radiusControl), focusedShape = RoundedCornerShape(Tokens.radiusControl),
        color = c.surfaceContainerHigh, focusedColor = c.primary, contentColor = c.onSurface, focusedContentColor = c.onPrimary, focusedScale = 1.03f
    ) { t ->
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            SymIcon(R.drawable.sym_search, null, Modifier.size(24.dp), tint = LocalContentColor.current.copy(alpha = .75f))
            Column(Modifier.weight(1f)) {
                Text(
                    query.ifEmpty { "Search titles" }, style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = if (query.isEmpty()) .7f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (t > .5f && query.isNotBlank()) Text("OK to search", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = .7f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SearchKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @androidx.annotation.DrawableRes icon: Int? = null, primary: Boolean = false) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, Modifier.height(34.dp).then(modifier).semantics { contentDescription = label },
        shape = RoundedCornerShape(Tokens.radiusThumb), focusedShape = RoundedCornerShape(Tokens.radiusControl),
        color = if (primary) c.primaryContainer else c.surfaceContainerHigh, focusedColor = c.primary,
        contentColor = if (primary) c.onPrimaryContainer else c.onSurface, focusedContentColor = c.onPrimary, focusedScale = 1.08f
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) SymIcon(icon, null, Modifier.size(18.dp))
            if (icon == null) Text(label, style = if (label.length == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** A suggestion or recent search; the part matching [typed] is bold. Choosing it runs the search. */
@Composable
private fun Suggestion(title: String, typed: String, @androidx.annotation.DrawableRes icon: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val text = remember(title, typed) {
        val at = if (typed.isBlank()) -1 else title.indexOf(typed, ignoreCase = true)
        androidx.compose.ui.text.buildAnnotatedString {
            if (at < 0) append(title)
            else {
                append(title.substring(0, at))
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append(title.substring(at, at + typed.length))
                pop()
                append(title.substring(at + typed.length))
            }
        }
    }
    FocusSurface(
        onClick, Modifier.fillMaxWidth().height(30.dp), shape = RoundedCornerShape(Tokens.radiusThumb),
        color = Color.Transparent, focusedColor = c.surfaceContainerHighest,
        contentColor = c.onSurfaceVariant, focusedContentColor = c.onSurface, focusedScale = 1.03f
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymIcon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Details --------------------------------------------------------------------------------

private fun progressOf(context: android.content.Context, key: String): Float? {
    val prefs = Profiles.prefs(context)
    val position = prefs.getLong("resume:$key", 0L)
    val duration = prefs.getLong("dur:$key", 0L)
    return if (position > 0L && duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else null
}

/** Episodes per range tab on titles with long episode lists. */
private const val EPISODE_RANGE = 50

/**
 * Title page, laid out like a TV streaming app: a full-screen hero over the key art (title, facts,
 * synopsis, actions), then the episodes (cards for short series; range tabs, a number grid and a
 * preview of the highlighted episode for long ones), then "More like this".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Detail(
    anime: Anime, seriesTitles: List<Anime>, providers: List<Provider>, message: String, entry: LibraryEntry?, lastEpisode: Int,
    selectedSource: String,
    openLists: () -> Unit, retry: () -> Unit, playEpisode: (Episode) -> Unit, selectSeason: (Anime) -> Unit, openTitle: (Anime) -> Unit,
    loadingDetail: Boolean = false, listsButton: FocusRequester = remember { FocusRequester() }
) {
    // Episodes the library counts as watched (synced progress), besides those before the last one played here.
    val watchedThrough = if (entry == null || entry.status == ListStatus.REWATCHING) 0 else if (entry.status == ListStatus.COMPLETED) Int.MAX_VALUE else entry.progress
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val primaryFocus = remember { FocusRequester() }
    val episodesFocus = remember { FocusRequester() }
    // Unaired episodes count down; the clock ticks every 30 s, and only while there are some.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasUnaired = anime.episodes.any { it.airsAfter(now) > 0 }
    LaunchedEffect(anime.id, hasUnaired) {
        while (hasUnaired) { delay(30_000); now = System.currentTimeMillis() }
    }
    val aired = anime.episodes.filter { it.airsAfter(now) == 0L }
    val nextUp = anime.episodes.firstOrNull { it.airsAfter(now) > 0 }
    // The library can be ahead of what was played here (watched on a phone, or set by hand): then
    // Play offers the episode after its progress.
    val libraryNext = entry?.takeIf { it.status != ListStatus.COMPLETED && it.progress > 0 && it.progress >= lastEpisode }
        ?.let { e -> aired.firstOrNull { it.number == e.progress + 1 } }
    val continueEpisode = if (libraryNext != null) null else aired.firstOrNull { it.number == lastEpisode }
    val primaryEpisode = libraryNext ?: continueEpisode ?: aired.firstOrNull()
    val many = anime.episodes.size > 30
    val ranges = remember(anime.episodes) { anime.episodes.chunked(EPISODE_RANGE) }
    var range by rememberSaveable(anime.id) { mutableIntStateOf(0) }
    // The episode list arrives asynchronously; open the range holding the viewer's last episode once it does.
    var initializedRange by rememberSaveable(anime.id) { mutableStateOf(false) }
    LaunchedEffect(anime.id, anime.episodes, lastEpisode) {
        if (!initializedRange && anime.episodes.isNotEmpty()) {
            val index = anime.episodes.indexOfFirst { it.number == lastEpisode }
            range = if (index >= 0) index / EPISODE_RANGE else 0
            initializedRange = true
        }
    }
    val currentRange = ranges.getOrNull(range.coerceIn(0, (ranges.size - 1).coerceAtLeast(0))).orEmpty()
    // Where "Episodes" lands: the viewer's episode if it is in view, otherwise the first one.
    val focusEpisode = if (many) currentRange.firstOrNull { it.number == lastEpisode } ?: currentRange.firstOrNull()
    else continueEpisode ?: aired.lastOrNull()?.takeIf { lastEpisode > 0 } ?: anime.episodes.firstOrNull()
    var preview by remember(anime.id) { mutableStateOf<Episode?>(null) }
    LaunchedEffect(anime.id, primaryEpisode != null) { runCatching { primaryFocus.requestFocus() } }

    // The key art dims once the page scrolls past the hero.
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 160 } }
    val art by animateFloatAsState(if (scrolled) .3f else 1f, MaterialTheme.motionScheme.slowEffectsSpec(), label = "key art")
    val margin = Modifier.padding(horizontal = Tokens.screenH)
    val fallbackStill = anime.banner.ifBlank { anime.poster }
    val lastServer = Profiles.prefsFor(context, Profiles.currentId(context)).getString("lastServer:${anime.id}", null)
    fun positionKey(episode: Episode) = watchPositionKey(selectedSource, anime.id, episode.number, lastServer)
    fun progress(episode: Episode) = progressOf(context, positionKey(episode))
    fun showEpisodes() {
        scope.launch {
            list.animateScrollToItem(1)
            delay(60)
            runCatching { episodesFocus.requestFocus() }
        }
    }

    // TV's default focus pivot scrolls each focused row to a third of the screen, which would cut
    // off the hero. Here the page moves in sections instead: the hero and the episodes each snap to
    // the top when focus enters them, and only the rows inside keep the default behaviour.
    val rowScroll = LocalBringIntoViewSpec.current
    fun snapTo(index: Int) = Modifier.onFocusChanged {
        if (it.hasFocus && (list.firstVisibleItemIndex != index || list.firstVisibleItemScrollOffset != 0)) scope.launch { list.animateScrollToItem(index) }
    }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        KeyArt(fallbackStill, Modifier.graphicsLayer { alpha = art })
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = Tokens.screenV + 12.dp), verticalArrangement = Arrangement.spacedBy(Tokens.gapXl)) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(452.dp).then(snapTo(0)).then(margin), contentAlignment = Alignment.BottomStart) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.gapXl), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(
                        anime.poster.ifBlank { fallbackStill }, contentDescription = null,
                        modifier = Modifier.width(180.dp).height(270.dp).clip(RoundedCornerShape(Tokens.radiusCover)).background(c.surfaceContainerHigh),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (anime.format) { "MOVIE" -> "FILM"; "" -> "ANIME"; else -> "SERIES" },
                            style = MaterialTheme.typography.labelLargeEmphasized, color = c.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(anime.title, style = MaterialTheme.typography.displaySmallEmphasized, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        FactsLine(anime)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            anime.synopsis.ifBlank { if (loadingDetail) "" else "Synopsis unavailable." },
                            style = MaterialTheme.typography.bodyLarge, color = c.onSurface.copy(alpha = .86f),
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                        if (anime.genres.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Genres: " + anime.genres.split(',').joinToString(", ") { it.trim() },
                                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd), verticalAlignment = Alignment.CenterVertically) {
                            if (primaryEpisode != null) {
                                val resumeAt = if (continueEpisode != null && LocalSettings.current.resume) resumePosition(context, positionKey(continueEpisode)) else 0L
                                val label = when {
                                    continueEpisode == null -> if (anime.format == "MOVIE" && anime.episodes.size == 1) "Play" else "Play E${primaryEpisode.number}"
                                    resumeAt > 0L -> "Resume E${primaryEpisode.number}"
                                    else -> "Play E${primaryEpisode.number} again"
                                }
                                PillButton(label, { playEpisode(primaryEpisode) }, Modifier.focusRequester(primaryFocus), icon = R.drawable.sym_play_arrow, style = ButtonStyle.Filled)
                            }
                            // While episodes load, hold Play's place so the buttons do not jump.
                            if (primaryEpisode == null && loadingDetail) PlayPlaceholder()
                            if (anime.episodes.size > 1) PillButton("Episodes", { showEpisodes() }, icon = R.drawable.sym_live_tv)
                            PillButton(
                                entry?.status?.label ?: "Add to list", openLists,
                                (if (primaryEpisode == null) Modifier.focusRequester(primaryFocus) else Modifier).focusRequester(listsButton),
                                icon = if (entry != null) R.drawable.sym_bookmark_added else R.drawable.sym_add
                            )
                        }
                        if (nextUp != null && (primaryEpisode == null || continueEpisode == null)) {
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                                SymIcon(R.drawable.sym_schedule, null, Modifier.size(18.dp), tint = c.primary)
                                Text(
                                    "Episode ${nextUp.number} · " + airsLabel(context, nextUp, nextUp.airsAfter(now), now),
                                    style = MaterialTheme.typography.titleSmall, color = c.primary, maxLines = 1
                                )
                            }
                        }
                        if (continueEpisode != null) {
                            val done = progress(continueEpisode)
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                                if (done != null) LinearProgressIndicator(
                                    progress = { done }, modifier = Modifier.width(140.dp).height(4.dp),
                                    color = c.primary, trackColor = c.onSurface.copy(alpha = .2f), gapSize = 0.dp, drawStopIndicator = {}
                                )
                                Text(
                                    "Episode ${continueEpisode.number}" + (continueEpisode.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    }
                }
            }

            item(key = "episodes") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll) {
                Column(snapTo(1), verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                    SectionHeader(
                        if (anime.format == "MOVIE" && anime.episodes.size <= 1) "Watch" else "Episodes", margin,
                        supporting = if (anime.episodes.isEmpty()) null else "${anime.episodes.size} episodes"
                    )
                    if (seriesTitles.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 4.dp)) {
                            items(seriesTitles, key = { it.id }) { season ->
                                TvChip(season.title, selected = season.id == anime.id, onClick = { if (season.id != anime.id) selectSeason(season) }, trailing = season.year.takeIf { it > 0 }?.toString())
                            }
                        }
                    }
                    when {
                        anime.episodes.isEmpty() && loadingDetail -> LoadingRow(margin)
                        anime.episodes.isEmpty() -> Box(margin) {
                            if (anime.status == "NOT_YET_RELEASED") EmptyNote(R.drawable.sym_schedule, "Not aired yet", "Episodes appear here once their broadcast dates are announced.")
                            else EmptyNote(R.drawable.sym_live_tv, "No episode list yet", message.ifBlank { "Episode details are not available for this title." })
                        }
                        !many -> {
                            val start = anime.episodes.indexOf(focusEpisode).coerceAtLeast(0)
                            val row = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
                            LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 10.dp)) {
                                items(anime.episodes, key = { it.number }) { episode ->
                                    val airs = episode.airsAfter(now)
                                    EpisodeCard(
                                        episode, fallbackStill, progress(episode), current = episode.number == lastEpisode,
                                        airs = if (airs > 0) airsLabel(context, episode, airs, now) else null,
                                        modifier = if (episode == focusEpisode) Modifier.focusRequester(episodesFocus) else Modifier
                                    ) { if (airs == 0L) playEpisode(episode) }
                                }
                            }
                        }
                        else -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 4.dp)) {
                                items(ranges.size) { index ->
                                    val r = ranges[index]
                                    TvChip("${r.first().number}–${r.last().number}", range == index, { range = index; preview = null })
                                }
                            }
                            Row(margin.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.gapXl)) {
                                key(range) {
                                    Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        currentRange.chunked(10).forEach { row ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                row.forEach { episode ->
                                                    val airs = episode.airsAfter(now)
                                                    EpisodeNumber(
                                                        episode, progress(episode), current = episode.number == lastEpisode,
                                                        watched = (lastEpisode > 0 && episode.number < lastEpisode) || episode.number <= watchedThrough, unaired = airs > 0,
                                                        modifier = Modifier.weight(1f).then(if (episode == focusEpisode) Modifier.focusRequester(episodesFocus) else Modifier),
                                                        onFocus = { preview = episode }
                                                    ) { if (airs == 0L) playEpisode(episode) }
                                                }
                                                repeat(10 - row.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    }
                                }
                                // Follow the highlighted number once it settles, not on every step.
                                var shown by remember(anime.id, range) { mutableStateOf(focusEpisode) }
                                LaunchedEffect(preview) { preview?.let { delay(200); shown = it } }
                                EpisodePreview(
                                    shown, fallbackStill, shown?.let { progress(it) },
                                    shown?.airsAfter(now)?.takeIf { it > 0 }?.let { airsLabel(context, shown!!, it, now) }, Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                }
            }

            if (anime.recommendations.isNotEmpty()) item(key = "more") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll) {
                Column {
                    SectionHeader("More like this", margin)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 12.dp)) {
                        items(anime.recommendations, key = { it.id }) { other -> PosterCard(other, { openTitle(other) }) }
                    }
                }
                }
            }

            if (anime.id <= 0 && (providers.none { it.name.equals("Gotaku", true) } || message.startsWith("Gotaku is not available"))) item(key = "status") {
                Row(margin, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (!message.contains("loading", true) && !message.contains("Finding", true)) PillButton("Retry", retry, icon = R.drawable.sym_refresh, style = ButtonStyle.Outlined)
                }
            }
        }
        }
    }
}

/** Scrolls only as far as needed to show the focused item (plus a little room), instead of TV's pivot. */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val room = 48f
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset - room
            else -> end - containerSize + room
        }
    }
}

/** Full-height key art on the right, faded into the surface on the left and bottom. */
@Composable
private fun KeyArt(url: String?, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(.8f).fillMaxHeight(.9f)) {
            if (!url.isNullOrBlank()) AsyncImage(backdropRequest(url), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to surface, .32f to surface.copy(alpha = .92f), .7f to surface.copy(alpha = .76f), 1f to surface.copy(alpha = .52f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to surface.copy(alpha = .25f), .25f to Color.Transparent, .62f to Color.Transparent, 1f to surface)))
        }
    }
}

/** One line of facts under the title: score, year, format, episodes and airing status. */
@Composable
private fun FactsLine(anime: Anime) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (anime.score > 0) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SymIcon(R.drawable.sym_star, null, Modifier.size(18.dp), tint = c.primary)
            Text("${anime.score}%", style = MaterialTheme.typography.titleMediumEmphasized, color = c.primary)
        }
        val facts = listOfNotNull(
            anime.year.takeIf { it > 0 }?.toString(),
            anime.format.takeIf { it.isNotBlank() }?.replace('_', ' '),
            (anime.episodes.size.takeIf { it > 0 } ?: anime.episodeCount.takeIf { it > 0 })?.let { if (it == 1) "1 episode" else "$it episodes" }
        )
        facts.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = c.onSurface) }
        when (anime.status) {
            "RELEASING" -> Tag("Airing", container = c.primaryContainer, content = c.onPrimaryContainer)
            "NOT_YET_RELEASED" -> Tag("Coming soon", container = c.tertiaryContainer, content = c.onTertiaryContainer)
            else -> {}
        }
    }
}

/** The highlighted episode beside the number grid: still, number, title, date and overview. */
@Composable
private fun EpisodePreview(episode: Episode?, fallback: String, progress: Float?, airs: String?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCover)).background(c.surfaceContainerHigh)) {
            AsyncImage(episode?.still?.ifBlank { fallback } ?: fallback, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (progress != null) LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                color = c.primary, trackColor = Color.Black.copy(alpha = .45f), gapSize = 0.dp, drawStopIndicator = {}
            )
        }
        if (episode != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), verticalAlignment = Alignment.CenterVertically) {
                Text("Episode ${episode.number}", style = MaterialTheme.typography.labelLargeEmphasized, color = c.primary)
                if (episode.date.isNotBlank()) Text(episode.date, style = MaterialTheme.typography.labelMedium, color = c.outline)
            }
            if (airs != null) Text(airs, style = MaterialTheme.typography.titleSmall, color = c.tertiary, maxLines = 1)
            if (episode.title.isNotBlank()) Text(episode.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                episode.overview.ifBlank { if (episode.title.isBlank() && airs == null) "No episode details yet." else "" },
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A small numbered tile for the episode grid. */
@Composable
private fun EpisodeNumber(episode: Episode, progress: Float?, current: Boolean, watched: Boolean, unaired: Boolean = false, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, modifier.height(42.dp).semantics { contentDescription = "Episode ${episode.number}" },
        shape = RoundedCornerShape(Tokens.radiusThumb), focusedShape = RoundedCornerShape(Tokens.radiusControl),
        // Unaired: a hollow, greyed tile that still takes focus (its time shows in the preview).
        color = when { current -> c.secondaryContainer; unaired -> c.surfaceContainerLow; else -> c.surfaceContainerHigh },
        focusedColor = if (unaired) c.surfaceContainerHighest else c.primary,
        contentColor = when { current -> c.onSecondaryContainer; watched || unaired -> c.outline; else -> c.onSurface },
        focusedContentColor = if (unaired) c.onSurfaceVariant else c.onPrimary,
        border = if (unaired) BorderStroke(1.dp, c.outlineVariant) else null,
        focusedScale = 1.12f, onFocusChange = { if (it) onFocus() }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(episode.number.toString(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (progress != null) LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = c.primary, trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {}
            )
        }
    }
}

/** A 16:9 episode card for short series, with the title and overview below. */
@Composable
private fun EpisodeCard(episode: Episode, fallback: String, progress: Float?, current: Boolean, airs: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val unaired = airs != null
    // Unaired episodes are greyed out (a little less when focused, so the countdown reads).
    Column(Modifier.width(248.dp).graphicsLayer { alpha = if (unaired) (if (focused) .8f else .45f) else 1f }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick, modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = "Episode ${episode.number}" },
            shape = RoundedCornerShape(Tokens.radiusCover), focusedShape = RoundedCornerShape(Tokens.radiusCard),
            color = c.surfaceContainerHigh, focusedScale = 1.06f, onFocusChange = { focused = it }
        ) { t ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(episode.still.ifBlank { fallback }, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.35f to Color.Transparent, 1f to Color.Black.copy(alpha = .92f))))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, end = 48.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(episode.title.ifBlank { "Episode ${episode.number}" }, style = MaterialTheme.typography.titleSmallEmphasized,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (episode.title.isNotBlank()) Text("Episode ${episode.number}", style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = .82f), maxLines = 1)
                }
                if (current) Tag("Watching", Modifier.align(Alignment.TopStart).padding(8.dp), container = c.primaryContainer, content = c.onPrimaryContainer)
                if (unaired) Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(Tokens.radiusBadge)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SymIcon(R.drawable.sym_schedule, null, Modifier.size(14.dp), tint = Color.White)
                    Text(airs!!.substringBefore(" · ").removePrefix("Airs ").let { if (it.startsWith("in ")) it else "Airs $it" }, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
                }
                if (t > 0.01f && !unaired) Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp)
                        .graphicsLayer { scaleX = t; scaleY = t; alpha = t }.background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) { SymIcon(R.drawable.sym_play_arrow, null, Modifier.size(20.dp), tint = Color.Black) }
                if (progress != null) LinearProgressIndicator(
                    progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = c.primary, trackColor = Color.Black.copy(alpha = .45f), gapSize = 0.dp, drawStopIndicator = {}
                )
            }
        }
        if (unaired) Text(airs!!.substringAfter(" · ", airs.removePrefix("Airs ")), style = MaterialTheme.typography.bodySmall, color = c.tertiary, maxLines = 1)
        else if (episode.overview.isNotBlank()) Text(episode.overview, style = MaterialTheme.typography.bodySmall, color = c.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Playback start / errors ------------------------------------------------------------------

private fun gotakuMessage(episode: Int, throwable: Throwable): String {
    val messages = buildList {
        var current: Throwable? = throwable
        while (current != null && size < 8) {
            current.message?.takeIf { it.isNotBlank() }?.let(::add)
            current = current.cause
        }
    }
    val raw = messages.firstOrNull { message ->
        listOf("Episode", "Gotaku", "HTTP", "subtitled", "stream").any { message.contains(it, true) }
    } ?: messages.firstOrNull().orEmpty()
    return when {
        raw.contains("unavailable", true) -> "Episode $episode is unavailable on Gotaku."
        raw.contains("no subtitled", true) -> "Episode $episode has no subtitled stream on Gotaku."
        raw.contains("different anime", true) -> "Gotaku matched a different anime."
        raw.contains("different episode", true) -> "Gotaku returned a different episode."
        raw.contains("HTTP 401") || raw.contains("HTTP 403") -> "Gotaku refused the playback session. Try again."
        raw.contains("Could not reach", true) || raw.contains("Unable to connect", true) -> "Could not reach Gotaku. Check your connection and try again."
        raw.contains("changed", true) || raw.contains("could not be decoded", true) || raw.contains("missing its playback session", true) ->
            "Gotaku changed its playback format. This episode cannot play right now."
        raw.isBlank() -> "Episode $episode could not be played."
        else -> raw
    }
}

@Composable
private fun GotakuPlayback(request: GotakuRequest, gotaku: Provider?, onBack: () -> Unit, onPlayEpisode: (Episode) -> Unit, onChooseServer: (PlayerServer) -> Unit, onFallback: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { releaseImagesForPlayback(appContext) }
    var attempt by remember(request) { mutableStateOf(0) }
    var state by remember(request, attempt) { mutableStateOf<GotakuStart>(GotakuStart.Loading) }
    LaunchedEffect(request, attempt) {
        state = GotakuStart.Loading
        val result = withContext(Dispatchers.IO) {
            try {
                GotakuStart.Ready(GotakuSession.resolve(request.provider, request.anime.id, request.episode.number))
            } catch (e: Exception) {
                android.util.Log.w("GotakuPlayback", "episode ${request.episode.number}: ${e.javaClass.simpleName}: ${e.message}")
                GotakuStart.Failed(gotakuMessage(request.episode.number, e))
            }
        }
        state = result
        if (result is GotakuStart.Failed && request.allowFallback && request.anime.id > 0) onFallback()
    }
    when (val current = state) {
        is GotakuStart.Loading -> PlaybackStatus(request, "Starting episode ${request.episode.number}", "Opening the Gotaku playback session…")
        is GotakuStart.Failed -> PlaybackStatus(
            request,
            when {
                current.message.contains("unavailable", true) || current.message.contains("no subtitled", true) -> "Episode unavailable"
                current.message.contains("changed its playback format", true) -> "Source error"
                else -> "Playback unavailable"
            },
            current.message,
            onRetry = { attempt++ },
            onBack = onBack
        )
        is GotakuStart.Ready -> GotakuPlayer(request, current.session, gotaku, onBack, onRetry = { attempt++ }, onPlayEpisode = onPlayEpisode, onChooseServer = onChooseServer, onFallback = onFallback)
    }
}

@Composable
private fun ReanimePlayback(request: ReanimeRequest, gotaku: Provider?, onBack: () -> Unit, onPlayEpisode: (Episode) -> Unit, onChooseServer: (PlayerServer) -> Unit, onFallback: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val prefs = remember { Profiles.prefsFor(context, Profiles.currentId(context)) }
    val settings = LocalSettings.current
    val statusRequest = remember(request) { GotakuRequest(request.anime, request.episode, Provider("Re:Anime", "", false)) }
    LaunchedEffect(Unit) { releaseImagesForPlayback(context) }
    var attempt by remember(request) { mutableIntStateOf(0) }
    var state by remember(request, attempt) { mutableStateOf<ReanimeStart>(ReanimeStart.Loading) }
    LaunchedEffect(request, attempt) {
        state = ReanimeStart.Loading
        try {
            val ready = withContext(Dispatchers.IO) {
                val choices = request.server?.let { listOf(it) } ?: Reanime.servers(request.anime.id, request.anime.mal, request.episode.number)
                // Servers differ by episode: the picked one if it is here, then the same version.
                val preferred = if (request.server != null) choices else choices.filter { it.language == request.version }.sortedByDescending { it.name == request.serverName }
                val other = if (request.server != null) emptyList() else choices.filter { it.language != request.version }
                var failure: Exception? = null
                var resolved: ReanimeStart.Ready? = null
                for (server in preferred) {
                    try { resolved = ReanimeStart.Ready(server, Reanime.resolve(server)); break }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failure = e }
                }
                // Prefer another source with the requested language before changing language.
                // Gotaku currently has subtitles only; if both are fallbacks, honor source order.
                if (resolved == null && request.server == null && request.allowFallback && gotaku != null &&
                    (request.version == "sub" || settings.preferredSource == "gotaku"))
                    throw failure ?: java.io.IOException("Preferred version unavailable")
                if (resolved == null) for (server in other) {
                    try { resolved = ReanimeStart.Ready(server, Reanime.resolve(server)); break }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failure = e }
                }
                resolved ?: throw failure ?: java.io.IOException("No servers are available for this episode.")
            }
            val server = ready.server
            prefs.edit().putString("lastServer:${request.anime.id}", "${server.name}:${server.language}")
                .putString("lastSource:${request.anime.id}", "reanime").putInt("last:${request.anime.id}", request.episode.number)
                .putLong("lastAt:${request.anime.id}", System.currentTimeMillis()).apply()
            state = ready
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (request.allowFallback && gotaku != null) onFallback()
            else state = ReanimeStart.Failed(e.message ?: "This server could not start playback.")
        }
    }
    when (val current = state) {
        ReanimeStart.Loading -> PlaybackStatus(statusRequest, "Starting episode ${request.episode.number}", "Finding an available server…")
        is ReanimeStart.Failed -> PlaybackStatus(statusRequest, "Playback unavailable", current.message, onRetry = { attempt++ }, onBack = onBack)
        is ReanimeStart.Ready -> ReanimePlayer(request, current.server, current.session, gotaku, onBack = onBack, onRetry = { attempt++ }, onPlayEpisode = onPlayEpisode, onChooseServer = onChooseServer, onFallback = onFallback)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ReanimePlayer(request: ReanimeRequest, server: ReanimeServer, session: ReanimeSession, gotaku: Provider?, onBack: () -> Unit, onRetry: () -> Unit, onPlayEpisode: (Episode) -> Unit, onChooseServer: (PlayerServer) -> Unit, onFallback: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val number = request.episode.number
    val nextEpisode = remember(request) { request.anime.episodes.filter { it.number > number }.minByOrNull { it.number } }
    val exoPlayer = remember(session) {
        buildTvPlayer(context, largeSegments = true).apply {
            // A dub needs only forced subtitles (signs, foreign lines); full ones stay in the panel.
            val dubbed = server.language.equals("dub", true)
            val subtitles = session.subtitles.map { subtitle ->
                val forced = subtitle.label.contains("forced", true)
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                    .setLabel(subtitle.label.replace(TRACK_NUMBER, "")).setLanguage(subtitle.language).setMimeType(subtitle.mimeType)
                    .setSelectionFlags(if (subtitle.isDefault && !dubbed) C.SELECTION_FLAG_DEFAULT else if (forced) C.SELECTION_FLAG_FORCED else 0).build()
            }
            val item = MediaItem.Builder().setUri(session.sourceUrl).setSubtitleConfigurations(subtitles)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(listOf("Episode $number", request.episode.title).filter { it.isNotBlank() }.joinToString(" · ")).setArtist(request.anime.title).build())
                .build()
            trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguage(if (dubbed) "en" else "ja").apply {
                if (dubbed) setPreferredTextLanguages().setSelectUndeterminedTextLanguage(false)
            }.build()
            setMediaSource(DefaultMediaSourceFactory(ReanimeDataSource.Factory(session.playlistKey)).createMediaSource(item))
            prepare()
            playWhenReady = true
        }
    }
    var skips by remember(session) { mutableStateOf(session.skips) }
    LaunchedEffect(session) { skips = session.skips + runCatching { Catalog.shared(context).skipTimes(request.anime, number) }.getOrDefault(emptyList()) }
    TvPlayerScreen(
        player = exoPlayer, skips = skips, title = request.anime.title, episodeLabel = "Episode $number", episodeTitle = request.episode.title,
        resumeKey = watchPositionKey("reanime", request.anime.id, number, "${server.name}:${server.language}"), noSubtitlesNote = if (Reanime.subtitleKind(server, session) == "Hard subtitles") "This episode's subtitles are burned into the video, so there is nothing to switch." else "No selectable subtitle tracks are available for this server.",
        describeError = { it.message ?: "Playback could not continue." }, onRetry = onRetry, onBack = onBack,
        anime = request.anime, episode = request.episode, gotaku = gotaku,
        currentServer = PlayerServer("reanime", "Re:Anime · ${server.name}", server.group, server, Reanime.subtitleKind(server, session)), onChooseServer = onChooseServer, onChooseEpisode = onPlayEpisode,
        initialNotice = if (request.server == null && server.language != request.version) "Preferred version unavailable · Playing ${if (server.language == "dub") "dubbed" else "subtitled"}" else null,
        onStartFailure = if (request.allowFallback && gotaku != null) onFallback else null,
        onNextEpisode = nextEpisode?.let { next -> { onPlayEpisode(next) } },
        onWatched = {
            val appContext = context.applicationContext
            val profileId = Profiles.currentId(appContext)
            if (UserSettings.of(appContext, profileId).autoTrack) TrackerSync.background { TrackerSync.of(appContext, profileId).library.episodeWatched(request.anime, number) }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaybackStatus(request: GotakuRequest, title: String, message: String, onRetry: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val firstAction = remember { FocusRequester() }
    val failed = onRetry != null || onBack != null
    LaunchedEffect(title) { if (failed) runCatching { firstAction.requestFocus() } }
    Box(Modifier.fillMaxSize().background(c.surface)) {
        Backdrop(request.anime.banner.ifBlank { request.anime.poster }, Modifier.align(Alignment.TopEnd), alpha = .35f)
        Column(Modifier.fillMaxSize().padding(Tokens.screenH), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!failed) ContainedLoadingIndicator(Modifier.size(84.dp))
            else Box(Modifier.size(84.dp).background(c.errorContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                SymIcon(R.drawable.sym_error, null, Modifier.size(40.dp), tint = c.onErrorContainer)
            }
            Spacer(Modifier.height(Tokens.gapXl))
            Text("Episode ${request.episode.number}", style = MaterialTheme.typography.labelLargeEmphasized, color = c.primary)
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, color = c.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(request.anime.title, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Tokens.gapMd))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
            if (failed) {
                Spacer(Modifier.height(Tokens.gapXl))
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                    if (onRetry != null) PillButton("Try again", onRetry, Modifier.focusRequester(firstAction), icon = R.drawable.sym_refresh, style = ButtonStyle.Filled)
                    if (onBack != null) PillButton("Back", onBack, if (onRetry == null) Modifier.focusRequester(firstAction) else Modifier, icon = R.drawable.sym_arrow_back)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun GotakuPlayer(request: GotakuRequest, session: GotakuSession, gotaku: Provider?, onBack: () -> Unit, onRetry: () -> Unit, onPlayEpisode: (Episode) -> Unit, onChooseServer: (PlayerServer) -> Unit, onFallback: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val number = request.episode.number
    val nextEpisode = remember(request) { request.anime.episodes.filter { it.number > number }.minByOrNull { it.number } }
    val exoPlayer = remember(session) {
        buildTvPlayer(context).apply {
            val item = MediaItem.Builder()
                .setUri(session.sourceUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(listOf("Episode $number", request.episode.title).filter { it.isNotBlank() }.joinToString(" · ")).setArtist(request.anime.title).build())
                .build()
            val source = HlsMediaSource.Factory(GotakuDataSource.Factory(session))
                .setLoadErrorHandlingPolicy(GotakuLoadErrorPolicy())
                .createMediaSource(item)
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    // Refresh the playback session ahead of expiry, off the loader threads.
    LaunchedEffect(session) {
        while (isActive) {
            delay(15_000)
            withContext(Dispatchers.IO) { runCatching { session.refreshIfNeeded() }.onFailure { android.util.Log.w("GotakuPlayback", "refresh failed: ${it.message}") } }
        }
    }
    // Intro, outro and recap times arrive while the episode starts.
    var skips by remember(session) { mutableStateOf(session.skips) }
    LaunchedEffect(session) {
        val fallback = runCatching { Catalog.shared(context).skipTimes(request.anime, number) }.getOrDefault(emptyList())
        skips = session.skips + fallback
    }
    TvPlayerScreen(
        player = exoPlayer,
        skips = skips,
        title = request.anime.title,
        episodeLabel = "Episode $number",
        episodeTitle = request.episode.title,
        resumeKey = "gotaku:${request.anime.id}:$number",
        noSubtitlesNote = if (session.subtitleType == "hard_sub") "This episode's subtitles are burned into the video, so there is nothing to switch." else "No selectable subtitle tracks are available for this server.",
        describeError = { gotakuMessage(number, it) },
        onRetry = onRetry,
        onBack = onBack,
        anime = request.anime, episode = request.episode, gotaku = gotaku,
        currentServer = PlayerServer("gotaku", "Gotaku", "Subtitled", detail = if (session.subtitleType == "hard_sub") "Hard subtitles" else "Soft subtitles"), onChooseServer = onChooseServer, onChooseEpisode = onPlayEpisode,
        initialNotice = if ((request.allowFallback || request.fellBack) && request.version == "dub") "Dub unavailable · Playing subtitled" else null,
        onStartFailure = if (request.allowFallback && request.anime.id > 0) onFallback else null,
        onNextEpisode = nextEpisode?.let { next -> { onPlayEpisode(next) } },
        // Finishing an episode moves the library (and AniList / MyAnimeList) forward.
        onWatched = {
            val appContext = context.applicationContext
            val profileId = Profiles.currentId(appContext)
            if (UserSettings.of(appContext, profileId).autoTrack) TrackerSync.background { TrackerSync.of(appContext, profileId).library.episodeWatched(request.anime, number) }
        }
    )
}

