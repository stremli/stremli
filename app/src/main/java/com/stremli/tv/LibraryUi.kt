package com.stremli.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---- State ----------------------------------------------------------------------------------------

/** Which card is open over the library. */
sealed class LibrarySheet {
    data class SignIn(val service: Service) : LibrarySheet()
    data class AccountInfo(val service: Service) : LibrarySheet()
    data object NewList : LibrarySheet()
}

/**
 * The Library tab's state, kept while other tabs are shown: the chosen list, its titles a page at
 * a time (40 per page from SQLite), the counts on the list chips and the open card.
 */
class LibraryScreenState(val sync: TrackerSync, private val scope: CoroutineScope) {
    val library get() = sync.library
    var view by mutableStateOf(LibraryView())
        private set
    var items by mutableStateOf<List<LibraryEntry>>(emptyList())
        private set
    var counts by mutableStateOf<Map<ListStatus, Int>>(emptyMap())
        private set
    var lists by mutableStateOf<List<CustomList>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set
    var sheet by mutableStateOf<LibrarySheet?>(null)
    /** The list chips in the page, and the copy pinned at the top while scrolling. */
    val chipsFocus = FocusRequester()
    val pinnedFocus = FocusRequester()
    /** The grid rows on screen by their list key, so Down from the pinned chips can land on one. */
    val rowFocus = HashMap<String, FocusRequester>()
    /** The pinned copy is on screen, so the row in the page only keeps its place. */
    var pinnedShown by mutableStateOf(false)
    /** Services the sign-in relay is set up for (null until known). */
    var available by mutableStateOf<Set<Service>?>(null)
    private var end = false
    private var loading = false
    private var generation = 0

    /** Reloads the shown list, keeping as many titles as were already loaded (after a sync or an edit). */
    suspend fun reload(fresh: Boolean = false) {
        val mine = ++generation
        val keep = if (fresh) PAGE else maxOf(items.size, PAGE)
        val page = library.page(view, 0, keep)
        val c = library.counts(); val l = library.lists(); val t = library.total()
        if (mine != generation) return
        // A chosen list that was deleted (here or on AniList) drops out of the choice.
        val known = view.lists.filterTo(HashSet()) { id -> l.any { it.id == id } }
        if (known.size != view.lists.size) { view = view.copy(lists = known); reload(fresh = true); return }
        items = page; counts = c; lists = l; total = t; end = page.size < keep; loaded = true
    }

    fun show(next: LibraryView) {
        if (next == view) return
        // The old titles stay until the new ones are in, so the page does not jump to the top.
        view = next; end = false
        scope.launch { reload(fresh = true) }
    }

    fun loadMore() {
        if (loading || end) return
        loading = true
        val mine = generation
        scope.launch {
            try {
                val next = library.page(view, items.size, PAGE)
                if (mine != generation) return@launch
                items = (items + next).distinctBy { it.id }; end = next.size < PAGE
            } finally { loading = false }
        }
    }

    companion object { const val PAGE = 40 }
}


// ---- Brand marks ----------------------------------------------------------------------------------

private val AniListNavy = Color(0xFF152232)
private val AniListBlue = Color(0xFF02A9FF)
private val MalBlue = Color(0xFF2E51A2)

/** The service's mark, drawn in its colours (used to attribute sign-in, not as a data credit). */
@Composable
fun ServiceLogo(service: Service, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * .24f)
    when (service) {
        Service.AniList -> Box(modifier.size(size).background(AniListNavy, shape), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("A", color = Color.White, fontFamily = BrandFont, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * .5f).sp, letterSpacing = (-1).sp)
                Text("L", color = AniListBlue, fontFamily = BrandFont, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * .5f).sp)
            }
        }
        Service.Mal -> Box(modifier.size(size).background(MalBlue, shape), contentAlignment = Alignment.Center) {
            Text("MAL", color = Color.White, fontFamily = BrandFont, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * .32f).sp, letterSpacing = (-.5).sp)
        }
    }
}

// ---- Library tab ----------------------------------------------------------------------------------

private fun ago(time: Long): String {
    if (time <= 0L) return "never"
    val minutes = ((System.currentTimeMillis() - time) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** With several lists shown, a card also says which list it is from. */
private fun LibraryEntry.listSubtitle(): String = status.label + if (progress > 0) " · Ep $progress" else ""

private fun LibraryEntry.subtitle(): String = when (status) {
    ListStatus.WATCHING, ListStatus.REWATCHING, ListStatus.PAUSED, ListStatus.DROPPED ->
        (if (progress > 0) "Ep $progress" + (episodes.takeIf { it > 0 }?.let { " of $it" } ?: "") else "Not started") + (if (score > 0) " · ★ ${score.fmt()}" else "")
    ListStatus.COMPLETED -> if (score > 0) "★ ${score.fmt()}" else listOfNotNull(year.takeIf { it > 0 }?.toString(), episodes.takeIf { it > 1 }?.let { "$it eps" }).joinToString(" · ").ifBlank { "Completed" }
    ListStatus.PLANNING -> listOfNotNull(year.takeIf { it > 0 }?.toString(), episodes.takeIf { it > 1 }?.let { "$it eps" }).joinToString(" · ")
}

private fun Float.fmt(): String = if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)

/**
 * The Library tab, as items of Home's list (no nested vertical list): the header, the two
 * account tiles, the list chips and a grid of the chosen list.
 */
fun LazyListScope.libraryItems(
    state: LibraryScreenState, syncState: Map<Service, TrackerSync.State>, accounts: Map<Service, Account?>,
    margin: Modifier, open: (Anime) -> Unit, tileFocus: Map<Service, FocusRequester>, newListFocus: FocusRequester, portraitCards: Boolean
) {
    item(key = "lib-header") {
        val c = MaterialTheme.colorScheme
        val connected = accounts.values.filterNotNull().filter { !it.needsLogin }
        val syncing = syncState.values.any { it.syncing }
        Row(margin, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                val last = connected.maxOfOrNull { syncState[it.service]?.lastSync ?: 0L } ?: 0L
                // With the tiles gone once signed in, a sync problem shows here (details are in the popup).
                val problem = connected.firstNotNullOfOrNull { syncState[it.service]?.error }.takeIf { !syncing }
                Text(
                    listOfNotNull(
                        if (state.total == 1) "1 title" else "${state.total} titles",
                        when {
                            syncing -> "Syncing…"
                            problem != null -> problem
                            connected.isEmpty() -> "Saved on this TV for this profile"
                            last > 0 -> "Synced ${ago(last)}"
                            else -> null
                        },
                        "Hold OK on a title for its lists".takeIf { state.total > 0 && problem == null }
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium, color = if (problem != null) c.error else c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            connected.forEach { a ->
                AccountAvatar(a, Modifier.focusRequester(tileFocus.getValue(a.service))) { state.sheet = LibrarySheet.AccountInfo(a.service) }
            }
            if (connected.isNotEmpty()) PillButton(if (syncing) "Syncing…" else "Sync now", { if (!syncing) state.sync.syncNow() }, icon = R.drawable.sym_refresh)
        }
    }
    // A tile only for services still to sign in to; signed-in accounts are avatars in the header.
    val signedOut = Service.entries.filter { accounts[it]?.needsLogin != false }
    if (signedOut.isNotEmpty()) item(key = "lib-accounts") {
        Row(margin, horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg)) {
            signedOut.forEach { s ->
                AccountTile(s, accounts[s], state.available?.let { s in it }, Modifier.weight(1f).focusRequester(tileFocus.getValue(s))) {
                    state.sheet = LibrarySheet.SignIn(s)
                }
            }
            // A lone tile keeps its half of the row.
            if (signedOut.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    item(key = "lib-chips") {
        // Two rows in one spot would leave focus nowhere to go from the pinned one.
        if (state.pinnedShown) Spacer(Modifier.fillMaxWidth().height(48.dp)) else LibraryChips(state, newListFocus, state.chipsFocus)
    }
    val customList = state.view.lists.singleOrNull()?.let { id -> state.lists.firstOrNull { it.id == id } }
    if (customList != null) item(key = "lib-list-actions") {
        var confirm by remember(customList.id) { mutableStateOf(false) }
        Row(margin, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            Text(
                (if (customList.anilist) "${customList.name} is also on AniList." else "${customList.name} is saved on this TV for this profile.") + " Hold OK on any title to add it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            val scope = rememberCoroutineScope()
            PillButton(if (confirm) "Press again to delete" else "Delete list", {
                if (confirm) scope.launch { state.library.deleteList(customList); state.show(state.view.copy(lists = state.view.lists - customList.id)) } else confirm = true
            }, icon = R.drawable.sym_close, style = ButtonStyle.Outlined)
        }
    }
    val libraryColumns = if (portraitCards) 6 else 4
    val rows = state.items.chunked(libraryColumns)
    if (rows.isEmpty() && state.loaded) item(key = "lib-empty") {
        Box(margin) {
            val status = state.view.statuses.singleOrNull()?.takeIf { state.view.lists.isEmpty() }
            val onlyList = customList?.takeIf { state.view.statuses.isEmpty() }
            EmptyNote(
                status?.icon ?: R.drawable.sym_bookmarks,
                when {
                    onlyList != null -> "${onlyList.name} is empty"
                    status != null -> "Nothing in ${status.label}"
                    state.view.all -> "Your library is empty"
                    else -> "Nothing in these lists"
                },
                if (accounts.values.none { it != null }) "Hold OK on any title to add it, or sign in to AniList or MyAnimeList above to bring your lists over."
                else "Hold OK on any title to add it. Changes sync to your accounts."
            )
        }
    }
    items(rows.size, key = { "lib-row-${rows[it].first().id}" }) { index ->
        if (index >= rows.size - 2) LaunchedEffect(rows.size) { state.loadMore() }
        val rowKey = "lib-row-${rows[index].first().id}"
        val rowFocus = remember { FocusRequester() }
        DisposableEffect(rowKey) {
            state.rowFocus[rowKey] = rowFocus
            onDispose { state.rowFocus.remove(rowKey) }
        }
        Row(margin.padding(vertical = 4.dp).focusRequester(rowFocus).focusGroup(), horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg)) {
            rows[index].forEach { e ->
                PosterCard(e.toAnime(), { open(e.toAnime()) }, Modifier.weight(1f), width = Dp.Unspecified, subtitle = if (state.view.size == 1) e.subtitle() else e.listSubtitle(), lookupBanner = true)
            }
            repeat(libraryColumns - rows[index].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * The list chips. Each one turns on and off, so several lists can show together; All clears the
 * choice. [group] lets focus be sent to the row; [onChange] follows a change of the choice.
 */
@Composable
private fun LibraryChips(state: LibraryScreenState, newListFocus: FocusRequester, group: FocusRequester, onChange: () -> Unit = {}) {
    fun show(next: LibraryView) { state.show(next); onChange() }
    val view = state.view
    LazyRow(
        Modifier.focusRequester(group).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), contentPadding = PaddingValues(horizontal = Tokens.screenH, vertical = 4.dp)
    ) {
        item(key = "all") { TvChip("All", view.all, { show(LibraryView(emptySet(), emptySet())) }, icon = R.drawable.sym_live_tv, trailing = state.total.takeIf { it > 0 }?.toString()) }
        items(ListStatus.entries, key = { "s-" + it.name }) { s ->
            TvChip(s.label, s in view.statuses, { show(view.toggled(s)) }, icon = s.icon, trailing = state.counts[s]?.takeIf { it > 0 }?.toString())
        }
        items(state.lists, key = { "l-" + it.id }) { l ->
            TvChip(l.name, l.id in view.lists, { show(view.toggled(l.id)) }, icon = if (l.anilist) R.drawable.sym_link else R.drawable.sym_bookmarks, trailing = l.count.takeIf { it > 0 }?.toString())
        }
        item(key = "new") { TvChip("New list", false, { state.sheet = LibrarySheet.NewList }, Modifier.focusRequester(newListFocus), icon = R.drawable.sym_add) }
    }
}

/**
 * The list chips pinned to the top of the screen once the page has scrolled past them. A copy
 * outside the scrolling list, because a focused chip inside it would scroll the page. It stays while
 * it holds focus; Up returns to the top of the page, Down goes to the titles under it, and [onChange]
 * follows a change of the choice.
 */
@Composable
fun PinnedLibraryChips(state: LibraryScreenState, newListFocus: FocusRequester, onFocus: (Boolean) -> Unit, onChange: () -> Unit, toTop: () -> Unit, toGrid: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(top = Tokens.screenV, bottom = 8.dp)
            .onFocusChanged { onFocus(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                // Focus search does not cross from this row into the list, so both ways are sent by hand.
                if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) { if (event.key == Key.DirectionUp) toTop() else toGrid() }
                true
            }
    ) { LibraryChips(state, newListFocus, state.pinnedFocus, onChange) }
}

/** A signed-in account: its avatar with the service's mark on it. Pressing it opens the account popup. */
@Composable
private fun AccountAvatar(account: Account, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(56.dp)) {
        FocusSurface(
            onClick, modifier.size(52.dp).semantics { contentDescription = "${account.service.label} account: ${account.name}" },
            shape = CircleShape, color = c.surfaceContainerHigh, focusedColor = c.surfaceContainerHighest, focusedScale = 1.12f
        ) {
            if (account.avatar.isNotBlank()) AsyncImage(account.avatar, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ServiceLogo(account.service, 40.dp) }
        }
        // Outside the circle's clip, so the two accounts can be told apart.
        if (account.avatar.isNotBlank()) ServiceLogo(account.service, 22.dp, Modifier.align(Alignment.BottomEnd))
    }
}

/** A service still to sign in to: its mark and "Sign in", or "Sign in again" when the sign-in has lapsed. */
@Composable
private fun AccountTile(service: Service, account: Account?, available: Boolean?, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, modifier.height(88.dp), shape = RoundedCornerShape(Tokens.radiusCard), focusedShape = RoundedCornerShape(Tokens.radiusCard),
        color = c.surfaceContainerHigh, focusedColor = c.primary, contentColor = c.onSurface, focusedContentColor = c.onPrimary, focusedScale = 1.03f
    ) { t ->
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg)) {
            ServiceLogo(service, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(service.label, style = MaterialTheme.typography.titleMediumEmphasized, color = LocalContentColor.current, maxLines = 1)
                val (line, error) = when {
                    account != null -> "Signed out · sign in again to keep syncing" to true
                    available == false -> "Sign-in not available yet" to false
                    else -> "Sign in with your phone" to false
                }
                Text(line, style = MaterialTheme.typography.bodyMedium, color = if (error && t < .5f) c.error else LocalContentColor.current.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Tag(if (account == null) "Sign in" else "Sign in again", container = if (t > .5f) c.onPrimary.copy(alpha = .12f) else c.secondaryContainer, content = LocalContentColor.current)
        }
    }
}

// ---- Cards over the library -------------------------------------------------------------------------

/** The open card ([LibraryScreenState.sheet]); closing it returns focus to what opened it. */
@Composable
fun LibraryOverlays(state: LibraryScreenState, accounts: Map<Service, Account?>, syncState: Map<Service, TrackerSync.State>, tileFocus: Map<Service, FocusRequester>, newListFocus: FocusRequester) {
    val scope = rememberCoroutineScope()
    val sheet = state.sheet
    fun close(focus: FocusRequester?, after: Long = 80) {
        state.sheet = null
        scope.launch { delay(after); runCatching { focus?.requestFocus() } }
    }
    val signIn = sheet as? LibrarySheet.SignIn
    SignInCard(signIn?.service, state.available, onSignedIn = { state.sync.signedIn(it) }, onClose = { close(signIn?.service?.let { tileFocus[it] }) })
    val info = sheet as? LibrarySheet.AccountInfo
    AccountCard(
        info?.service, info?.service?.let { accounts[it] }, info?.service?.let { syncState[it] },
        onSync = { state.sync.syncNow() },
        onSignOut = { s -> state.sync.signOut(s); close(tileFocus[s], after = 300) },
        onClose = { close(info?.service?.let { tileFocus[it] }) }
    )
    val anilist = accounts[Service.AniList]?.needsLogin == false
    SideSheet(sheet == LibrarySheet.NewList, onClose = { close(newListFocus) }) { first ->
        item {
            ListNameEditor(anilist, first, onCreate = { name, onAnilist ->
                scope.launch {
                    val id = state.library.createList(name, onAnilist)
                    if (id != null) { state.reload(); state.show(LibraryView(emptySet(), setOf(id))) }
                    close(newListFocus)
                }
            }, onCancel = { close(newListFocus) })
        }
    }
}

/** A modal card in the middle of the screen; focus stays inside while it is open, Back closes it. */
@Composable
private fun CenterCard(visible: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible) { onClose() }
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    AnimatedVisibility(visible, enter = fadeIn(fade), exit = fadeOut(fade)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
            AnimatedVisibility(visible, enter = fadeIn(fade) + scaleIn(scale, initialScale = .94f), exit = fadeOut(fade)) {
                Column(
                    Modifier.width(760.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Tokens.radiusSheet + 4.dp))
                        .padding(28.dp).focusProperties { onExit = { if (visible) cancelFocusChange() } }.focusGroup(),
                    content = content
                )
            }
        }
    }
}

/**
 * Signing in: a QR code to scan with a phone (or a site and code to type), which opens the
 * service's own sign-in page there. The TV checks for the result every few seconds while this is
 * open, and only then. The code lasts 10 minutes.
 */
@Composable
private fun SignInCard(service: Service?, available: Set<Service>?, onSignedIn: (Account) -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // Keep the last service while the card animates out.
    var shown by remember { mutableStateOf(service) }
    if (service != null) shown = service
    val s = shown ?: return
    var attempt by remember(service) { mutableIntStateOf(0) }
    var ticket by remember(service, attempt) { mutableStateOf<TrackerLogin.Ticket?>(null) }
    var status by remember(service, attempt) { mutableStateOf("Getting a code…") }
    var failed by remember(service, attempt) { mutableStateOf(false) }
    var done by remember(service) { mutableStateOf<Account?>(null) }
    var minutesLeft by remember(service, attempt) { mutableIntStateOf(10) }
    val action = remember { FocusRequester() }
    LaunchedEffect(service, attempt) {
        if (service == null) return@LaunchedEffect
        runCatching { action.requestFocus() }
        if (available != null && service !in available) { status = "${service.label} sign-in isn't available yet."; failed = true; return@LaunchedEffect }
        val t = try { TrackerLogin.start(service) } catch (e: Exception) {
            status = e.message?.takeIf { it.length < 120 } ?: "Couldn't get a sign-in code. Check your connection."; failed = true; return@LaunchedEffect
        }
        ticket = t
        status = "Waiting for your phone…"
        while (isActive) {
            delay(2_500)
            minutesLeft = (((t.expiresAt - System.currentTimeMillis()) + 59_999) / 60_000).toInt().coerceAtLeast(0)
            val result = try { TrackerLogin.poll(t) } catch (e: java.io.IOException) { continue } catch (e: Exception) {
                status = "Sign-in failed: ${e.message ?: "unknown error"}"; failed = true; break
            }
            when (result) {
                is TrackerLogin.Poll.Waiting -> status = if (result.opened) "Continue on your phone…" else "Waiting for your phone…"
                is TrackerLogin.Poll.Done -> { done = result.account; onSignedIn(result.account); delay(1_800); onClose(); break }
                TrackerLogin.Poll.Cancelled -> { status = "Sign-in was cancelled on the phone."; failed = true; break }
                TrackerLogin.Poll.Expired -> { status = "This code has expired."; failed = true; break }
                is TrackerLogin.Poll.Failed -> { status = result.message; failed = true; break }
            }
        }
    }
    CenterCard(service != null, onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                val t = ticket
                when {
                    done != null -> Box(Modifier.fillMaxSize().background(c.primaryContainer, RoundedCornerShape(Tokens.radiusCard)), contentAlignment = Alignment.Center) {
                        SymIcon(R.drawable.sym_check, null, Modifier.size(96.dp), tint = c.onPrimaryContainer)
                    }
                    t != null && !failed -> QrCode(t.url, Modifier.fillMaxSize())
                    else -> Box(Modifier.fillMaxSize().background(c.surfaceContainerHigh, RoundedCornerShape(Tokens.radiusCard)), contentAlignment = Alignment.Center) {
                        ServiceLogo(s, 96.dp)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                    ServiceLogo(s, 40.dp)
                    Text(if (done != null) "Signed in" else "Sign in to ${s.label}", style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                }
                val d = done
                if (d != null) {
                    Text("Welcome, ${d.name}. Your ${s.label} lists are syncing with this profile.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                } else {
                    Step(1, "Scan the code with your phone's camera.")
                    Step(2, "Sign in to ${s.label} and allow Stremli.")
                    Step(3, "Your lists sync with this profile, both ways.")
                    val t = ticket
                    if (t != null && !failed) Text(
                        "No camera? On any device, go to ${t.site} and enter ${t.shownCode}",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                        SymIcon(if (failed) R.drawable.sym_error else R.drawable.sym_schedule, null, Modifier.size(18.dp), tint = if (failed) c.error else c.primary)
                        Text(
                            status + if (t != null && !failed) "  ·  code expires in $minutesLeft min" else "",
                            style = MaterialTheme.typography.titleSmall, color = if (failed) c.error else c.primary, maxLines = 2
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                    if (failed) PillButton("Get a new code", { attempt++ }, Modifier.focusRequester(action), icon = R.drawable.sym_refresh, style = ButtonStyle.Filled)
                    PillButton(if (done != null) "Done" else "Cancel", onClose, if (!failed) Modifier.focusRequester(action) else Modifier, icon = if (done != null) R.drawable.sym_check else R.drawable.sym_close)
                }
                Text("Stremli is not affiliated with ${s.label}.", style = MaterialTheme.typography.labelSmall, color = c.outline)
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
        Box(Modifier.size(26.dp).background(c.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = MaterialTheme.typography.labelLarge, color = c.onSecondaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
    }
}

/** A connected account: who it is, how syncing is going, Sync now and Sign out. */
@Composable
private fun AccountCard(service: Service?, account: Account?, sync: TrackerSync.State?, onSync: () -> Unit, onSignOut: (Service) -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(service to account) }
    if (service != null) shown = service to account
    val (s, a) = shown
    val first = remember { FocusRequester() }
    var confirm by remember(service) { mutableStateOf(false) }
    LaunchedEffect(service) { if (service != null) { delay(60); runCatching { first.requestFocus() } } }
    CenterCard(service != null, onClose) {
        if (s == null) return@CenterCard
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg)) {
            ServiceLogo(s, 64.dp)
            Column(Modifier.weight(1f)) {
                Text(a?.name ?: s.label, style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                Text("${s.label} · this profile only", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            if (a != null && a.avatar.isNotBlank()) AsyncImage(a.avatar, null, Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(Tokens.gapLg))
        val line = when {
            sync?.syncing == true -> "Syncing now…"
            sync?.error != null -> sync.error
            else -> "Last synced ${ago(sync?.lastSync ?: 0L)}." + (sync?.pending?.takeIf { it > 0 }?.let { " $it changes waiting to send." } ?: " Everything is up to date.")
        }
        Text(line, style = MaterialTheme.typography.bodyLarge, color = if (sync?.error != null) c.error else c.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            when (s) {
                Service.AniList -> "Status, progress, score, rewatches and custom lists sync both ways. Episodes you finish here are added automatically."
                Service.Mal -> "Status, progress, score and rewatches sync both ways. MyAnimeList has no custom lists, so those stay in Stremli and on AniList."
            },
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant
        )
        Spacer(Modifier.height(Tokens.gapXl))
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            PillButton("Sync now", onSync, Modifier.focusRequester(first), icon = R.drawable.sym_refresh, style = ButtonStyle.Filled)
            PillButton(if (confirm) "Press again to sign out" else "Sign out", { if (confirm) onSignOut(s) else confirm = true }, icon = R.drawable.sym_close, style = ButtonStyle.Outlined)
            PillButton("Close", onClose, icon = R.drawable.sym_arrow_back)
        }
    }
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** A side sheet on the right (like Explore's filters). [content] receives the requester for its first control. */
@Composable
fun SideSheet(visible: Boolean, onClose: () -> Unit, width: Dp = 420.dp, focusKey: Any? = null, content: LazyListScope.(FocusRequester) -> Unit) {
    val c = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible) { onClose() }
    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedVisibility(visible, enter = fadeIn(fade), exit = fadeOut(fade)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(visible, enter = slideInHorizontally(slide) { it } + fadeIn(fade), exit = slideOutHorizontally(slide) { it } + fadeOut(fade)) {
            // Focus moves to the first control on opening, and again when the sheet's content changes.
            LaunchedEffect(focusKey) { delay(80); runCatching { first.requestFocus() } }
            LazyColumn(
                Modifier.width(width).fillMaxHeight()
                    .background(c.surfaceContainer, RoundedCornerShape(topStart = Tokens.radiusSheet, bottomStart = Tokens.radiusSheet))
                    // OK still held from opening this (hold OK on a poster) must not press what is focused here.
                    .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount > 0 && it.key in OK_KEYS }
                    .focusProperties { onExit = { if (visible) cancelFocusChange() } }.focusGroup(),
                contentPadding = PaddingValues(start = 24.dp, end = 28.dp, top = 28.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapLg)
            ) { content(first) }
        }
    }
}

// ---- A title's lists ------------------------------------------------------------------------------

/**
 * "My lists" for one title: its status, episodes watched, score and custom lists, with Remove.
 * Every change is saved at once and synced a moment later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleListsSheet(visible: Boolean, anime: Anime, sync: TrackerSync, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val library = sync.library
    val scope = rememberCoroutineScope()
    val version by library.changes.collectAsState()
    var entry by remember(anime.id) { mutableStateOf<LibraryEntry?>(null) }
    var lists by remember { mutableStateOf<List<CustomList>>(emptyList()) }
    var member by remember(anime.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var naming by remember(visible) { mutableStateOf(false) }
    var confirmRemove by remember(visible, anime.id) { mutableStateOf(false) }
    LaunchedEffect(anime.id, version, visible) {
        if (!visible) return@LaunchedEffect
        entry = library.entry(anime.id); lists = library.lists(); member = library.listsOf(anime.id)
    }
    val anilist = sync.account(Service.AniList)?.needsLogin == false
    val connected = sync.connected()
    SideSheet(visible, onClose = { if (naming) naming = false else onClose() }, width = 440.dp, focusKey = naming) { first ->
        if (naming) {
            item {
                ListNameEditor(anilist, first, onCreate = { name, onAnilist ->
                    scope.launch {
                        val id = library.createList(name, onAnilist)
                        val made = library.lists().firstOrNull { it.id == id }
                        if (made != null) library.toggleInList(anime, made)
                        naming = false
                    }
                }, onCancel = { naming = false })
            }
            return@SideSheet
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("My lists", style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                    Text(anime.title, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PillButton("Done", onClose, Modifier.focusRequester(first), icon = R.drawable.sym_check, style = ButtonStyle.Filled)
            }
        }
        item {
            SheetGroup("Status") {
                ListStatus.entries.forEach { s ->
                    TvChip(s.label, entry?.status == s, { scope.launch { library.setStatus(anime, s) } }, icon = s.icon)
                }
            }
        }
        val e = entry
        if (e != null) {
            item {
                val total = (anime.episodeCount.takeIf { it > 0 } ?: e.episodes).takeIf { it > 0 }
                Stepper(
                    "Episodes watched", "${e.progress}" + (total?.let { " of $it" } ?: ""),
                    onStep = { d -> scope.launch { library.setProgress(anime, (e.progress + d).coerceAtLeast(0)) } }
                )
            }
            item {
                Stepper(
                    "Your score", if (e.score > 0) "★ ${e.score.fmt()} / 10" else "Not scored",
                    onStep = { d -> scope.launch { library.setScore(anime, (Math.round(e.score) + d).coerceIn(0, 10).toFloat()) } }
                )
            }
        }
        item {
            SheetGroup("Your lists") {
                lists.forEach { l ->
                    TvChip(l.name, l.id in member, { scope.launch { library.toggleInList(anime, l) } }, icon = if (l.id in member) R.drawable.sym_check else if (l.anilist) R.drawable.sym_link else R.drawable.sym_bookmarks)
                }
                TvChip("New list", false, { naming = true }, icon = R.drawable.sym_add)
            }
        }
        if (e != null) item {
            PillButton(if (confirmRemove) "Press again to remove" else "Remove from library", {
                if (confirmRemove) scope.launch { library.remove(anime.id); confirmRemove = false } else confirmRemove = true
            }, icon = R.drawable.sym_close, style = ButtonStyle.Outlined)
        }
        item {
            Text(
                if (connected.isEmpty()) "Saved for this profile on this TV. Sign in to AniList or MyAnimeList in Library to sync."
                else "Syncs with " + connected.joinToString(" and ") { it.label } + ".",
                style = MaterialTheme.typography.bodySmall, color = c.outline
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetGroup(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) { chips() }
    }
}

/** A value changed with Left and Right (and OK for +1). */
@Composable
private fun Stepper(title: String, value: String, onStep: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
        FocusSurface(
            onClick = { onStep(1) },
            modifier = Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = "$title: $value. Left and right to change." }.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) { Key.DirectionLeft -> { onStep(-1); true }; Key.DirectionRight -> { onStep(1); true }; else -> false }
            },
            shape = RoundedCornerShape(Tokens.radiusControl), focusedShape = RoundedCornerShape(Tokens.radiusCard),
            color = c.surfaceContainerHigh, focusedColor = c.secondaryContainer, contentColor = c.onSurface, focusedContentColor = c.onSecondaryContainer, focusedScale = 1.02f
        ) { t ->
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SymIcon(R.drawable.sym_arrow_back, null, Modifier.size(20.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
                    if (t > .5f) Text("Left / Right", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = .7f))
                }
                SymIcon(R.drawable.sym_chevron_right, null, Modifier.size(20.dp))
            }
        }
    }
}

// ---- Naming a new list ------------------------------------------------------------------------------

private val LIST_SUGGESTIONS = listOf("Favorites", "Rewatch later", "Watch with friends", "Weekend binge", "Hidden gems", "Seasonal picks")

/**
 * A name for a new list: suggestions to pick with one press, or typing on the on-screen keys (a
 * Bluetooth or USB keyboard types straight in). With AniList connected it can be an AniList custom list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListNameEditor(anilistConnected: Boolean, first: FocusRequester, onCreate: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var onAnilist by remember { mutableStateOf(anilistConnected) }
    fun create(text: String) {
        val clean = text.trim().replaceFirstChar { it.titlecase() }
        if (clean.isNotEmpty()) onCreate(clean, onAnilist && anilistConnected)
    }
    Column(
        Modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val typing = native.keyCode == android.view.KeyEvent.KEYCODE_DEL || (native.unicodeChar != 0 && native.unicodeChar.toChar().let { it.isLetterOrDigit() || it == ' ' })
            if (!typing) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                if (native.keyCode == android.view.KeyEvent.KEYCODE_DEL) name = name.dropLast(1)
                else if (name.length < 40) name += native.unicodeChar.toChar()
            }
            true
        },
        verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New list", style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface, modifier = Modifier.weight(1f))
            PillButton("Create", { create(name) }, Modifier.focusRequester(first), icon = R.drawable.sym_check, style = if (name.isBlank()) ButtonStyle.Tonal else ButtonStyle.Filled)
        }
        Box(Modifier.fillMaxWidth().height(56.dp).background(c.surfaceContainerHighest, RoundedCornerShape(Tokens.radiusControl)).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                name.replaceFirstChar { it.titlecase() }.ifEmpty { "List name" }, style = MaterialTheme.typography.titleMedium,
                color = if (name.isEmpty()) c.onSurfaceVariant else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (name.isEmpty()) {
            Text("Quick picks", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                LIST_SUGGESTIONS.forEach { s -> TvChip(s, false, { create(s) }) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            "abcdefghijklmnopqrstuvwxyz1234567890".chunked(9).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { k -> Key(k.toString(), Modifier.weight(1f)) { if (name.length < 40) name += k } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Key("Space", Modifier.weight(2f), R.drawable.sym_space_bar) { if (name.isNotEmpty() && name.length < 40) name += " " }
                Key("Delete", Modifier.weight(1.5f), R.drawable.sym_arrow_back) { name = name.dropLast(1) }
                Key("Clear", Modifier.weight(1.5f), R.drawable.sym_close) { name = "" }
            }
        }
        if (anilistConnected) TvChip("Also on AniList", onAnilist, { onAnilist = !onAnilist }, icon = if (onAnilist) R.drawable.sym_check else R.drawable.sym_link)
        else Text("Saved for this profile. Sign in to AniList to sync custom lists.", style = MaterialTheme.typography.bodySmall, color = c.outline)
        PillButton("Cancel", onCancel, icon = R.drawable.sym_close, style = ButtonStyle.Outlined)
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, icon: Int? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, modifier.height(36.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(Tokens.radiusThumb), focusedShape = RoundedCornerShape(Tokens.radiusControl),
        color = c.surfaceContainerHigh, focusedColor = c.primary, contentColor = c.onSurface, focusedContentColor = c.onPrimary, focusedScale = 1.08f
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) SymIcon(icon, null, Modifier.size(18.dp)) else Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---- QR code ----------------------------------------------------------------------------------------

/** [text] as a QR code: black modules on white, drawn once (it never redraws on its own). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Box(modifier.background(Color.White, RoundedCornerShape(Tokens.radiusCard)).padding(16.dp).semantics { contentDescription = "QR code for $text" }) {
        Canvas(Modifier.fillMaxSize()) {
            val n = matrix.width
            val cell = size.minDimension / n
            for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .6f, cell + .6f))
        }
    }
}
