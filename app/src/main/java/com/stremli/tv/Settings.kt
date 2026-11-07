package com.stremli.tv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * One profile's settings, kept in its own preferences and deleted with it. Each value is Compose
 * state, so a page reading one updates when it changes. The defaults keep the app as it was before
 * settings existed.
 */
class UserSettings private constructor(context: Context, profileId: String) {
    private val prefs = Profiles.prefsFor(context, profileId)

    private inner class Flag(private val key: String, default: Boolean) : ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(prefs.getBoolean("set:$key", default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { state.value = value; prefs.edit().putBoolean("set:$key", value).apply() }
    }

    private inner class Number(private val key: String, default: Int) : ReadWriteProperty<Any?, Int> {
        private val state = mutableIntStateOf(prefs.getInt("set:$key", default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.intValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { state.intValue = value; prefs.edit().putInt("set:$key", value).apply() }
    }

    private inner class Word(private val key: String, default: String) : ReadWriteProperty<Any?, String> {
        private val state = mutableStateOf(prefs.getString("set:$key", default) ?: default)
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { state.value = value; prefs.edit().putString("set:$key", value).apply() }
    }

    // Playback
    /** Per-profile default; migrate the source selected in earlier versions. */
    var preferredSource by Word("preferredSource", prefs.getString("playbackSource", "gotaku") ?: "gotaku")
    var preferredVersion by Word("preferredVersion", "sub")
    var autoplayNext by Flag("autoplayNext", false)
    /** Percent: 100 is normal speed. */
    var speedPercent by Number("speedPercent", 100)
    var seekSeconds by Number("seekSeconds", 10)
    var resume by Flag("resume", true)
    /** 0 off, 1 show a button, 2 skip automatically. */
    var skipIntro by Number("skipIntro", 1)
    var skipOutro by Number("skipOutro", 1)
    var skipRecap by Number("skipRecap", 1)
    var skipMarks by Flag("skipMarks", true)
    // Lists and sync
    var autoTrack by Flag("autoTrack", true)
    /** How often to check AniList and MyAnimeList for outside changes while the app is open; 0 is never. */
    var syncMinutes by Number("syncMinutes", 30)
    // Home
    var startTab by Word("startTab", "Home")
    /** Home refreshes on opening when its last update is at least this old; 0 is never. */
    var homeRefreshMinutes by Number("homeRefreshMinutes", 5)
    var heroSlides by Flag("heroSlides", true)
    var rowContinue by Flag("rowContinue", true)
    var rowNew by Flag("rowNew", true)
    var rowAiring by Flag("rowAiring", true)
    var rowPopular by Flag("rowPopular", true)
    var rowUpcoming by Flag("rowUpcoming", true)
    var scoreBadges by Flag("scoreBadges", true)
    /** Portrait posters or landscape banners for title cards; Continue watching stays landscape. */
    var portraitCards by Flag("portraitCards", false)
    // Schedule
    var scheduleMonth by Flag("scheduleMonth", false)
    var scheduleMine by Flag("scheduleMine", false)
    // Content
    var showAdult by Flag("showAdult", false)

    companion object {
        // One per profile at most (Profiles.MAX).
        private val open = LinkedHashMap<String, UserSettings>()

        fun of(context: Context, profileId: String): UserSettings = synchronized(open) {
            open.getOrPut(profileId) { UserSettings(context.applicationContext, profileId) }
        }

        fun forget(profileId: String) { synchronized(open) { open.remove(profileId) } }
    }
}

/** The current profile's settings. */
val LocalSettings = staticCompositionLocalOf<UserSettings> { error("No settings provided") }

private val SKIP_MODES = listOf(1 to "Show button", 2 to "Skip automatically", 0 to "Off")
/** Recaps only ever come from fallback times, which are never skipped without asking. */
private val RECAP_MODES = listOf(1 to "Show button", 0 to "Off")
private const val SKIP_AUTO_NOTE = "Automatic needs exact times; otherwise the button shows"

private enum class SettingsSection(val label: String, val icon: Int) {
    Playback("Playback", R.drawable.sym_play_arrow),
    Lists("Lists and sync", R.drawable.sym_bookmarks),
    Home("Home", R.drawable.sym_home),
    Schedule("Schedule", R.drawable.sym_calendar_month),
    Content("Content", R.drawable.sym_lock),
    Profile("This profile", R.drawable.sym_tune),
    Tv("This TV", R.drawable.sym_live_tv)
}

/**
 * Settings for the current profile, then this TV's housekeeping: sections on the left, and on the
 * right the options of the section focus lands on. Right or OK moves into the options; Left or Back
 * returns to the section. A choice steps with Left and Right, and Left past its first value leaves.
 * Focus only crosses between the panes by those keys, so it cannot land on another section by accident.
 */
@Composable
fun SettingsPage(
    profile: Profile, settings: UserSettings, sync: TrackerSync, catalog: Catalog,
    onSwitchProfile: () -> Unit, onClearSearches: () -> Unit, onClearWatchHistory: () -> Unit, onCatalogUpdated: () -> Unit
) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(SettingsSection.Playback) }
    val menuFocus = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    val optionsFocus = remember { FocusRequester() }
    val adultRow = remember { FocusRequester() }
    var inOptions by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var returnToAdult by remember { mutableStateOf(false) }
    // Set while focus is sent across on purpose; each pane keeps focus inside otherwise.
    val crossing = remember { BooleanArray(1) }
    fun cross(to: FocusRequester) {
        crossing[0] = true
        runCatching { to.requestFocus() }
        crossing[0] = false
    }
    fun toMenu() = cross(menuFocus.getValue(section))
    fun toOptions() = cross(optionsFocus)
    BackHandler(enabled = inOptions && !askPin) { toMenu() }
    LaunchedEffect(askPin) {
        if (askPin) return@LaunchedEffect
        delay(80)
        runCatching { (if (returnToAdult) adultRow else menuFocus.getValue(section)).requestFocus() }
        returnToAdult = false
    }
    if (askPin) {
        UnlockProfile(
            profile, "Enter the PIN to show adult titles.",
            onUnlocked = { settings.showAdult = true; returnToAdult = true; askPin = false },
            // The master PIN cleared the profile's PIN: nothing is turned on by that alone.
            onReset = { returnToAdult = true; askPin = false },
            onBack = { returnToAdult = true; askPin = false }
        )
        return
    }
    val connected = sync.connected()
    val first = Modifier.focusRequester(optionsFocus)
    Row(Modifier.fillMaxSize().background(c.surface)) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(c.surfaceContainerLow)
                .padding(start = Tokens.screenH, end = Tokens.gapLg, top = Tokens.screenV, bottom = Tokens.screenV)
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) toOptions()
                    true
                }
                .focusProperties { onExit = { if (!crossing[0]) cancelFocusChange() } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.padding(bottom = Tokens.gapLg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                ProfileAvatar(profile, 44.dp)
                Column {
                    Text("Settings", style = MaterialTheme.typography.titleLargeEmphasized, color = c.onSurface, maxLines = 1)
                    Text(profile.name, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SettingsSection.entries.forEach { s ->
                SettingsMenuItem(s, s == section, Modifier.focusRequester(menuFocus.getValue(s)), onFocus = { section = s }, onClick = { toOptions() })
            }
        }
        key(section) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { inOptions = it.hasFocus }
                    // After the rows have had their turn: a choice uses Left until its first value.
                    .onKeyEvent { event ->
                        if (event.key != Key.DirectionLeft) return@onKeyEvent false
                        if (event.type == KeyEventType.KeyDown) toMenu()
                        true
                    }
                    .focusProperties { onExit = { if (!crossing[0]) cancelFocusChange() } }.focusGroup(),
                contentPadding = PaddingValues(start = Tokens.gapXl, end = Tokens.screenH, top = Tokens.screenV, bottom = Tokens.screenV),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)
            ) {
                item(key = "title") {
                    Text(section.label, Modifier.padding(bottom = Tokens.gapSm), style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                }
                when (section) {
                    SettingsSection.Playback -> {
                        item(key = "preferred-source") {
                            SettingChoice("Preferred source", "Used first when it has your preferred version", settings.preferredSource,
                                listOf("gotaku" to "Gotaku", "reanime" to "Re:Anime"), first) { settings.preferredSource = it }
                        }
                        item(key = "preferred-version") {
                            SettingChoice("Preferred version", "Uses another available version if needed", settings.preferredVersion,
                                listOf("sub" to "Subtitled", "dub" to "Dubbed")) { settings.preferredVersion = it }
                        }
                        item(key = "autoplay") {
                            SettingSwitch("Autoplay next episode", "Starts the next episode 5 seconds after one ends", settings.autoplayNext) { settings.autoplayNext = it }
                        }
                        item(key = "speed") {
                            SettingChoice("Playback speed", "Used when an episode starts; the player can still change it", settings.speedPercent, listOf(75 to "0.75×", 100 to "Normal", 125 to "1.25×", 150 to "1.5×", 200 to "2×")) { settings.speedPercent = it }
                        }
                        item(key = "seek") {
                            SettingChoice("Skip step", "How far each Left or Right press jumps in the player", settings.seekSeconds, listOf(5 to "5 s", 10 to "10 s", 15 to "15 s", 30 to "30 s")) { settings.seekSeconds = it }
                        }
                        item(key = "resume") {
                            SettingSwitch("Resume where I left off", "Off starts every episode from the beginning", settings.resume) { settings.resume = it }
                        }
                        item(key = "skip-intro") { SettingChoice("Intros", SKIP_AUTO_NOTE, settings.skipIntro, SKIP_MODES) { settings.skipIntro = it } }
                        item(key = "skip-outro") { SettingChoice("Outros", SKIP_AUTO_NOTE, settings.skipOutro, SKIP_MODES) { settings.skipOutro = it } }
                        item(key = "skip-recap") { SettingChoice("Recaps", "When an episode opens with a recap", settings.skipRecap.coerceAtMost(1), RECAP_MODES) { settings.skipRecap = it } }
                        item(key = "skip-marks") {
                            SettingSwitch("Highlight on the seek bar", "Marks intros, outros and recaps on the player's bar", settings.skipMarks) { settings.skipMarks = it }
                        }
                    }
                    SettingsSection.Lists -> {
                        item(key = "track") {
                            SettingSwitch("Update my lists automatically", "Finishing an episode moves your progress forward", settings.autoTrack, first) { settings.autoTrack = it }
                        }
                        item(key = "sync") {
                            SettingChoice("Check for outside changes", "Picks up edits made on AniList or MyAnimeList while the app is open", settings.syncMinutes, listOf(0 to "Off", 30 to "30 min", 60 to "1 hour", 120 to "2 hours")) { settings.syncMinutes = it }
                        }
                        if (connected.isNotEmpty()) item(key = "sync-now") {
                            SettingAction("Sync now", "Syncs with " + connected.joinToString(" and ") { it.label }, "Sync") { sync.syncNow() }
                        }
                    }
                    SettingsSection.Home -> {
                        item(key = "start") {
                            SettingChoice("Open on", "The tab shown when the app starts", settings.startTab, listOf("Home", "Explore", "Schedule", "Library").map { it to it }, first) { settings.startTab = it }
                        }
                        item(key = "card-style") {
                            SettingChoice("Title cards", "Continue watching stays landscape", settings.portraitCards,
                                listOf(false to "Landscape", true to "Portrait")) { settings.portraitCards = it }
                        }
                        item(key = "refresh") {
                            SettingChoice("Update when opened", "Refreshes new episodes and what's airing if Home hasn't updated lately", settings.homeRefreshMinutes, listOf(0 to "Off", 5 to "After 5 min", 10 to "After 10 min", 30 to "After 30 min")) { settings.homeRefreshMinutes = it }
                        }
                        item(key = "hero") {
                            SettingSwitch("Move banner slides by themselves", "Off keeps the banner still until you move it", settings.heroSlides) { settings.heroSlides = it }
                        }
                        item(key = "r-continue") { SettingSwitch("Continue watching row", null, settings.rowContinue) { settings.rowContinue = it } }
                        item(key = "r-new") { SettingSwitch("New episodes row", null, settings.rowNew) { settings.rowNew = it } }
                        item(key = "r-airing") { SettingSwitch("Airing now row", null, settings.rowAiring) { settings.rowAiring = it } }
                        item(key = "r-popular") { SettingSwitch("Popular series row", null, settings.rowPopular) { settings.rowPopular = it } }
                        item(key = "r-upcoming") { SettingSwitch("Upcoming row", null, settings.rowUpcoming) { settings.rowUpcoming = it } }
                        item(key = "badges") {
                            SettingSwitch("Score on posters", "Shows each title's score on its poster", settings.scoreBadges) { settings.scoreBadges = it }
                        }
                    }
                    SettingsSection.Schedule -> {
                        item(key = "sched-view") {
                            SettingChoice("Open on", "The view Schedule starts with", settings.scheduleMonth, listOf(false to "Week", true to "Month"), first) { settings.scheduleMonth = it }
                        }
                        item(key = "sched-filter") {
                            SettingChoice("Show", "Which shows Schedule starts with", settings.scheduleMine, listOf(false to "All shows", true to "My list")) { settings.scheduleMine = it }
                        }
                    }
                    SettingsSection.Content -> item(key = "adult") {
                        var confirm by remember { mutableStateOf(false) }
                        val pin = Profiles.hasPin(context, profile)
                        SettingSwitch(
                            if (confirm) "Press again to confirm you're 18 or older" else "Show adult titles",
                            when {
                                settings.showAdult -> "On for Search, Explore and Schedule. Home stays clear of them."
                                pin -> "Off. Turning it on asks for this profile's PIN."
                                else -> "Off. Add a PIN to this profile to lock this setting."
                            },
                            settings.showAdult, first.focusRequester(adultRow), onFocusChange = { if (!it) confirm = false }
                        ) { on ->
                            when {
                                !on -> settings.showAdult = false
                                pin -> askPin = true
                                confirm -> { settings.showAdult = true; confirm = false }
                                else -> confirm = true
                            }
                        }
                    }
                    SettingsSection.Profile -> {
                        item(key = "clear-search") { SettingConfirm("Clear search history", "Removes this profile's recent searches", "Clear", first, onClearSearches) }
                        item(key = "clear-watch") { SettingConfirm("Clear watch history", "Empties Continue watching and resume points. Your lists stay.", "Clear", onConfirm = onClearWatchHistory) }
                        item(key = "switch") { SettingAction("Switch profile", "Back to Who's watching?", "Switch", onClick = onSwitchProfile) }
                    }
                    SettingsSection.Tv -> {
                        item(key = "catalogue") {
                            var status by remember { mutableStateOf<String?>(null) }
                            val build = catalog.build?.take(10)
                            SettingAction(
                                "Update the catalogue", status ?: ("Stremli ${BuildConfig.VERSION_NAME}" + (build?.let { " · catalogue from $it" } ?: "")),
                                "Update", first
                            ) {
                                if (status != "Updating…") scope.launch {
                                    status = "Updating…"
                                    val done = catalog.sync(force = true)
                                    if (done) onCatalogUpdated()
                                    status = if (done) "Catalogue updated" else "Couldn't update. Check your connection."
                                }
                            }
                        }
                        item(key = "update") {
                            val status = Updates.status
                            val update = status.update
                            SettingAction(
                                "App updates", updateSummary(status),
                                when {
                                    status is UpdateStatus.Downloading -> "Downloading"
                                    update != null -> "Update"
                                    else -> "Check"
                                }
                            ) { if (update != null) Updates.install(context, update) else Updates.check(context) }
                        }
                        item(key = "update-auto") {
                            var auto by remember { mutableStateOf(Updates.autoCheck(context)) }
                            SettingSwitch("Check for updates automatically", "Looks once a day and tells you when one is ready", auto) {
                                auto = it; Updates.setAutoCheck(context, it)
                            }
                        }
                        item(key = "buffer") {
                            val best = VideoBuffer.recommendedMb(context)
                            val range = VideoBuffer.range(DeviceProfile.of(context).tier)
                            var chosen by remember { mutableIntStateOf(VideoBuffer.chosenMb(context)) }
                            val sizes = remember { VideoBuffer.sizes(context) }
                            Column(verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                                SettingChoice(
                                    "Video buffer", "How much video is kept ready ahead of playback", chosen,
                                    listOf(0 to "Auto ($best MB)") + sizes.map { it to "$it MB" }
                                ) { chosen = it; VideoBuffer.choose(context, it) }
                                val mb = if (chosen == 0) best else chosen
                                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val note = MaterialTheme.typography.bodyMedium
                                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                                    Text("This TV has ${VideoBuffer.memoryLabel(context)} of memory. Best here: $best MB (${range.first} to ${range.last} MB is fine).", style = note, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        "$mb MB holds about ${VideoBuffer.seconds(mb)} s of 1080p. " + when {
                                            mb < range.first -> "Saves memory, but pauses sooner on a weak connection."
                                            mb > range.last -> "Rides out weak connections, but can slow or close the app on this TV."
                                            else -> "A good balance for this TV."
                                        },
                                        style = note, color = color
                                    )
                                    Text("Guide: 1 GB 8 to 16 MB, 2 GB 24 to 48 MB, 3 GB or more 48 to 96 MB.", style = note, color = color)
                                    Text("Changes apply from the next episode.", style = note, color = color)
                                }
                            }
                        }
                        item(key = "images") {
                            var cleared by remember { mutableStateOf(false) }
                            SettingAction("Clear saved images", if (cleared) "Images cleared. They download again as needed." else "Frees storage on this TV", "Clear") {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val loader = coil3.SingletonImageLoader.get(context)
                                        loader.memoryCache?.clear()
                                        loader.diskCache?.clear()
                                    }
                                    cleared = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A section in the menu: landing on it shows its options on the right. */
@Composable
private fun SettingsMenuItem(section: SettingsSection, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(Tokens.radiusControl), focusedShape = RoundedCornerShape(Tokens.radiusCard),
        color = if (selected) c.secondaryContainer else Color.Transparent, focusedColor = c.primary,
        contentColor = if (selected) c.onSecondaryContainer else c.onSurfaceVariant, focusedContentColor = c.onPrimary,
        focusedScale = 1.03f, onFocusChange = { if (it) onFocus() }
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            SymIcon(section.icon, null, Modifier.size(22.dp))
            Text(section.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The shared row: a title, an optional line under it, and [trailing] on the right. */
@Composable
private fun SettingRow(
    title: String, subtitle: String?, modifier: Modifier = Modifier, onFocusChange: (Boolean) -> Unit = {},
    onClick: () -> Unit, trailing: @Composable RowScope.(Float) -> Unit
) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, modifier.fillMaxWidth().height(if (subtitle == null) 54.dp else 68.dp),
        shape = RoundedCornerShape(Tokens.radiusCover), focusedShape = RoundedCornerShape(Tokens.radiusCover),
        color = c.surfaceContainerLow, focusedColor = c.primary, contentColor = c.onSurface, focusedContentColor = c.onPrimary,
        focusedScale = 1.02f, onFocusChange = onFocusChange
    ) { t ->
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing(t)
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {}, onChange: (Boolean) -> Unit
) {
    val c = MaterialTheme.colorScheme
    SettingRow(
        title, subtitle, modifier.semantics { contentDescription = "$title: ${if (checked) "on" else "off"}" }, onFocusChange, { onChange(!checked) }
    ) { t ->
        Box(
            Modifier.size(width = 48.dp, height = 28.dp).background(if (checked) androidx.compose.ui.graphics.lerp(c.primary, c.onPrimary, t) else androidx.compose.ui.graphics.lerp(c.surfaceContainerHighest, c.outlineVariant, t), RoundedCornerShape(50)).padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) { Box(Modifier.size(20.dp).background(if (checked) androidx.compose.ui.graphics.lerp(c.onPrimary, c.primary, t) else c.outline, CircleShape)) }
    }
}

/** One of a few values: OK moves on and wraps; Left and Right step and stop at the ends, where Left leaves for the menu. */
@Composable
private fun <T> SettingChoice(title: String, subtitle: String?, value: T, options: List<Pair<T, String>>, modifier: Modifier = Modifier, onChange: (T) -> Unit) {
    val index = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    SettingRow(
        title, subtitle,
        modifier.semantics { contentDescription = "$title: ${options[index].second}. Left and right to change." }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> if (index > 0) { onChange(options[index - 1].first); true } else false
                Key.DirectionRight -> { if (index < options.size - 1) onChange(options[index + 1].first); true }
                else -> false
            }
        },
        onClick = { onChange(options[(index + 1) % options.size].first) }
    ) { t ->
        if (t > .5f && index > 0) SymIcon(R.drawable.sym_arrow_back, null, Modifier.size(18.dp))
        Text(options[index].second, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
        if (t > .5f && index < options.size - 1) SymIcon(R.drawable.sym_chevron_right, null, Modifier.size(18.dp))
    }
}

@Composable
private fun SettingAction(title: String, subtitle: String?, action: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SettingRow(title, subtitle, modifier, onClick = onClick) { Text(action, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current) }
}

/** An action that deletes something: the first press asks, the second does it. */
@Composable
private fun SettingConfirm(title: String, subtitle: String, action: String, modifier: Modifier = Modifier, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    SettingRow(
        if (armed) "Press again to confirm" else title, if (done) "Done" else subtitle, modifier, onFocusChange = { if (!it) armed = false },
        onClick = { if (armed) { onConfirm(); armed = false; done = true } else { armed = true; done = false } }
    ) { Text(action, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current) }
}
