package com.stremli.tv

// Player layout and seeking behaviour adapted from Flixclusive's TV player (feature/tv/player),
// MIT License, Copyright (c) 2023 rhenwinch, https://github.com/flixclusiveorg/Flixclusive
// (see THIRD_PARTY_NOTICES.md). Visuals use Stremli's Material 3 Expressive system (Theme.kt,
// Components.kt); playback, track selection, resume, lifecycle and media session are Stremli's own.

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Left/Right presses are added up and applied this long after the last press (Flixclusive uses 2.5 s). */
private const val SEEK_COMMIT_DELAY_MS = 1_200L
private const val HIDE_CONTROLS_AFTER_MS = 5_000L
private const val MIN_RESUME_MS = 15_000L
/** Within this much of the end an episode counts as watched and its resume point is cleared. */
private const val FINISHED_MARGIN_MS = 90_000L
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private enum class PlayerPanel { None, Quality, AudioSubtitles, Speed, Servers, Episodes }

/**
 * A place an episode can play from. [group] only uses what is known before playing, so servers keep
 * their place in the list; [detail] is what playing it revealed, such as how its subtitles are carried.
 */
data class PlayerServer(val source: String, val label: String, val group: String, val server: ReanimeServer? = null, val detail: String? = null)

private class TrackOption(val label: String, val selected: Boolean, val supporting: String? = null, val apply: () -> Unit)

/** Saved resume position for [key], or 0. */
fun resumePosition(context: android.content.Context, key: String): Long =
    Profiles.prefs(context).getLong("resume:$key", 0L)

/**
 * Full-screen TV player. Takes ownership of [player] and releases it when it leaves composition.
 *
 * Remote: OK plays/pauses, Left/Right seek by the profile's skip step, Up/Down show the controls, Menu opens
 * quality, Back closes a panel, then shows the controls, then leaves. The player pauses when the
 * app goes to the background, publishes a MediaSession, and saves a resume position.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    player: ExoPlayer,
    title: String,
    episodeLabel: String?,
    episodeTitle: String?,
    resumeKey: String?,
    noSubtitlesNote: String,
    describeError: (PlaybackException) -> String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    anime: Anime,
    episode: Episode,
    gotaku: Provider?,
    currentServer: PlayerServer,
    onChooseServer: (PlayerServer) -> Unit,
    onChooseEpisode: (Episode) -> Unit,
    initialNotice: String? = null,
    /** Try the other source if this one fails before playback starts. */
    onStartFailure: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    /** Called once when the episode is watched to the end (the last 90 s, or it ended). */
    onWatched: (() -> Unit)? = null,
    /** Every known skip time for this episode; the ones matching this video are chosen here. */
    skips: List<SkipSegment> = emptyList()
) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val prefs = remember { Profiles.prefs(context) }
    val settings = LocalSettings.current
    val currentDescribeError by rememberUpdatedState(describeError)
    val currentNextEpisode by rememberUpdatedState(onNextEpisode)
    val currentWatched by rememberUpdatedState(onWatched)
    var watchedSent by remember(player) { mutableStateOf(false) }
    fun markWatched() { if (!watchedSent) { watchedSent = true; currentWatched?.invoke() } }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(C.TIME_UNSET) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    var videoHeight by remember(player) { mutableIntStateOf(0) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var panel by remember(player) { mutableStateOf(PlayerPanel.None) }
    var serverOptions by remember(player) { mutableStateOf<List<PlayerServer>>(emptyList()) }
    var serversLoading by remember(player) { mutableStateOf(false) }
    var serverError by remember(player) { mutableStateOf(false) }
    // What each server offers. No entry yet is still checking; null means it did not answer.
    var serverInfo by remember(player) { mutableStateOf<Map<PlayerServer, ServerInfo?>>(emptyMap()) }
    LaunchedEffect(panel, player) {
        if (panel != PlayerPanel.Servers) return@LaunchedEffect
        serversLoading = true
        serverError = false
        val options = mutableListOf<PlayerServer>()
        if (gotaku != null) options.add(PlayerServer("gotaku", "Gotaku", "Subtitled"))
        if (anime.id > 0) {
            try {
                val servers = withContext(Dispatchers.IO) { Reanime.servers(anime.id, anime.mal, episode.number) }
                options.addAll(servers.map { PlayerServer("reanime", "Re:Anime · ${it.name}", it.group, it) })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { serverError = true }
        }
        serverOptions = options.sortedBy { when (it.group) { "Subtitled" -> 0; "Soft subtitles" -> 1; "Hard subtitles" -> 2; else -> 3 } }
        serversLoading = false
        // One at a time, so the video keeps the connection. Closing the panel stops this.
        for (option in serverOptions) {
            if (option.source == currentServer.source && option.server == currentServer.server) continue
            val info = try {
                withContext(Dispatchers.IO) {
                    ServerInfoCache.get("${anime.id}:${episode.number}:${option.source}:${option.server?.name}:${option.server?.language}") {
                        option.server?.let(Reanime::info) ?: GotakuSession.info(requireNotNull(gotaku), anime.id, episode.number)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            serverInfo = serverInfo + (option to info)
        }
    }
    var seekMultiplier by remember(player) { mutableLongStateOf(0L) }
    var seekBase by remember(player) { mutableLongStateOf(0L) }
    var playAfterSeek by remember(player) { mutableStateOf(false) }
    var error by remember(player) { mutableStateOf<String?>(null) }
    var notice by remember(player) { mutableStateOf(initialNotice) }
    LaunchedEffect(player, initialNotice) {
        if (initialNotice != null) { delay(4_000); if (notice == initialNotice) notice = null }
    }
    var skipNotice by remember(player) { mutableStateOf<String?>(null) }
    var lastInteraction by remember(player) { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val consumedKeys = remember { mutableSetOf<Int>() }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val topFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val isSeeking = seekMultiplier != 0L
    // Intros, outros and recaps: the times that fit this video, and the one playing now, if any.
    val segments = remember(skips, duration) { chooseSkips(skips, if (duration == C.TIME_UNSET) 0L else duration) }
    fun skipMode(s: SkipSegment) = when (s.kind) { SkipKind.Intro -> settings.skipIntro; SkipKind.Outro -> settings.skipOutro; SkipKind.Recap -> settings.skipRecap }
    // Derived, so the position ticking does not recompose this whole screen.
    val current by remember(player, segments) {
        derivedStateOf { segments.firstOrNull { position >= it.startMs && position < it.endMs - 1_000L && skipMode(it) != 0 } }
    }
    // Skipped automatically once only, so rewinding to watch it is not undone.
    val autoSkipped = remember(player) { HashSet<SkipKind>() }
    // Only the stream's own times are skipped without asking. Others can be off for this video (they
    // have cut into the story), so those stay a button.
    fun automatic(s: SkipSegment) = skipMode(s) == 2 && s.exact
    val offered = current?.takeIf { !automatic(it) }
    val skipShown = offered != null && !isSeeking
    val skipFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }
    // An outro that runs to the end is the end of the episode: go straight to the next one.
    val endsEpisode = offered?.kind == SkipKind.Outro && duration > 0 && offered.endMs >= duration - 15_000L && onNextEpisode != null

    fun saveResume() {
        val key = resumeKey ?: return
        val pos = player.currentPosition
        val dur = player.duration
        val editor = prefs.edit()
        if (dur != C.TIME_UNSET && dur > 0) editor.putLong("dur:$key", dur)
        if (dur != C.TIME_UNSET && dur > 0 && dur - pos < FINISHED_MARGIN_MS) editor.remove("resume:$key")
        else if (pos > MIN_RESUME_MS) editor.putLong("resume:$key", pos)
        editor.apply()
    }
    fun showControls() { controlsVisible = true; lastInteraction = SystemClock.uptimeMillis() }
    fun skip(s: SkipSegment) {
        if (s.kind == SkipKind.Outro) markWatched()
        if (endsEpisode && s === offered) currentNextEpisode?.invoke() else player.seekTo(s.endMs)
    }
    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        if (player.playWhenReady) player.pause() else player.play()
        showControls()
    }
    fun seekStep(direction: Long) {
        if (seekMultiplier == 0L) {
            // Remember the play state once, when a burst of presses starts.
            seekBase = player.currentPosition
            playAfterSeek = player.playWhenReady
            player.pause()
        }
        seekMultiplier += direction
        showControls()
    }
    fun seekTarget(): Long {
        val dur = player.duration
        var target = (seekBase + seekMultiplier * settings.seekSeconds * 1000L).coerceAtLeast(0L)
        if (dur != C.TIME_UNSET && dur > 0) target = target.coerceAtMost(dur - 1_000L)
        return target
    }

    LaunchedEffect(current) {
        val s = current ?: return@LaunchedEffect
        if (!automatic(s) || isSeeking || !autoSkipped.add(s.kind)) return@LaunchedEffect
        skip(s)
        skipNotice = s.kind.done
    }
    // Its own effect: the skip leaves the part, which cancels the one above.
    LaunchedEffect(skipNotice) {
        if (skipNotice == null) return@LaunchedEffect
        delay(3_000)
        skipNotice = null
    }
    // The button goes when its part ends; focus must not go with it.
    LaunchedEffect(skipShown) {
        if (!skipShown && skipFocused) runCatching { (if (controlsVisible) playFocus else rootFocus).requestFocus() }
    }

    // Autoplay: the next episode starts a moment after this one ends. Back leaves first if wanted.
    LaunchedEffect(playbackState) {
        if (playbackState != Player.STATE_ENDED || !settings.autoplayNext || currentNextEpisode == null) return@LaunchedEffect
        notice = "Next episode in 5 seconds"
        delay(5_000)
        notice = null
        currentNextEpisode?.invoke()
    }

    LaunchedEffect(player) {
        if (settings.speedPercent != 100) player.setPlaybackSpeed(settings.speedPercent / 100f)
        val key = resumeKey ?: return@LaunchedEffect
        val saved = resumePosition(context, key)
        if (saved > MIN_RESUME_MS && settings.resume) {
            player.seekTo(saved)
            if (initialNotice != null) return@LaunchedEffect // says more than where it resumed
            notice = "Resumed from ${formatTime(saved)}"
            delay(4_000)
            notice = null
        }
    }

    DisposableEffect(player) {
        var becameReady = false
        var handedOff = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) { becameReady = true; error = null }
                if (state == Player.STATE_ENDED) {
                    resumeKey?.let { prefs.edit().remove("resume:$it").apply() }
                    markWatched()
                    showControls()
                }
            }
            override fun onPlaybackParametersChanged(value: PlaybackParameters) { speed = value.speed }
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onTrackSelectionParametersChanged(value: TrackSelectionParameters) { parameters = value }
            override fun onVideoSizeChanged(value: VideoSize) { if (value.height > 0) videoHeight = value.height }
            override fun onPlayerError(playerError: PlaybackException) {
                Log.w("TvPlayer", "player error: ${playerError.errorCodeName}: ${playerError.cause?.message}")
                if (!becameReady && !handedOff && onStartFailure != null) { handedOff = true; onStartFailure(); return }
                panel = PlayerPanel.None
                error = currentDescribeError(playerError)
            }
        }
        player.addListener(listener)
        // Voice ("Alexa, next") and Bluetooth NEXT reach the app through the media session, so the
        // session's player offers "next" when there is a next episode.
        val sessionPlayer = object : ForwardingPlayer(player) {
            fun hasNext() = currentNextEpisode != null
            override fun getAvailableCommands(): Player.Commands =
                if (!hasNext()) super.getAvailableCommands()
                else super.getAvailableCommands().buildUpon().add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int): Boolean =
                (hasNext() && (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) || super.isCommandAvailable(command)
            override fun hasNextMediaItem() = hasNext() || super.hasNextMediaItem()
            override fun seekToNext() { currentNextEpisode?.invoke() ?: super.seekToNext() }
            override fun seekToNextMediaItem() { currentNextEpisode?.invoke() ?: super.seekToNextMediaItem() }
        }
        val session = MediaSession.Builder(context, sessionPlayer).setId("stremli-${System.nanoTime()}").build()
        onDispose {
            saveResume()
            player.removeListener(listener)
            session.release()
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        // Fire OS expects a backgrounded app to release its decoders and buffers (onStop) rather
        // than just pause; on a 1 GB stick that also frees the video buffer for the launcher.
        // stop() keeps the episode and position, so coming back re-buffers there, paused.
        var released = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    saveResume()
                    player.pause()
                    if (player.playbackState != Player.STATE_IDLE) { player.stop(); released = true }
                    controlsVisible = true
                }
                Lifecycle.Event.ON_START -> if (released) { released = false; player.prepare() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Fire TV: pause when the HDMI cable or TV is disconnected (it stays paused on reconnect).
        // The sticky first delivery reports the current state and is ignored.
        val hdmi = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                if (isInitialStickyBroadcast) return
                if (intent.getIntExtra(android.media.AudioManager.EXTRA_AUDIO_PLUG_STATE, 1) == 0) { player.pause(); controlsVisible = true }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, hdmi, android.content.IntentFilter(android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(hdmi) }
        }
    }

    // Progress, periodic resume saves, and auto-hiding the controls (they stay up while paused or buffering).
    LaunchedEffect(player) {
        var lastSave = 0L
        while (true) {
            position = player.currentPosition
            duration = player.duration
            val now = SystemClock.uptimeMillis()
            if (!watchedSent && duration != C.TIME_UNSET && duration > 0 && position > duration / 2 && duration - position < FINISHED_MARGIN_MS) markWatched()
            if (player.isPlaying && now - lastSave > 10_000L) {
                saveResume()
                lastSave = now
            }
            if (controlsVisible && panel == PlayerPanel.None && error == null && seekMultiplier == 0L &&
                player.isPlaying && now - lastInteraction > HIDE_CONTROLS_AFTER_MS
            ) controlsVisible = false
            delay(500)
        }
    }

    // Accumulated seeking: pause, show the target time, then jump once the presses stop.
    LaunchedEffect(seekMultiplier) {
        if (seekMultiplier == 0L) return@LaunchedEffect
        delay(SEEK_COMMIT_DELAY_MS)
        val target = seekTarget()
        // Seeking into a part means wanting to see it.
        segments.firstOrNull { target >= it.startMs && target < it.endMs }?.let { autoSkipped.add(it.kind) }
        player.seekTo(target)
        position = target
        seekMultiplier = 0L
        if (playAfterSeek) player.play()
        showControls()
    }

    // isSeeking too: seeking hides everything but the play button, so focus has to move there.
    LaunchedEffect(controlsVisible, panel, error, isSeeking) {
        if (panel != PlayerPanel.None) return@LaunchedEffect // the panel focuses its own selected item
        runCatching {
            when {
                error != null -> retryFocus.requestFocus()
                controlsVisible -> playFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    BackHandler {
        when {
            panel != PlayerPanel.None -> { panel = PlayerPanel.None; showControls() }
            error != null -> onBack()
            isSeeking -> { seekMultiplier = 0L; if (playAfterSeek) player.play() }
            !controlsVisible -> showControls()
            else -> onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent consumedKeys.remove(code)
                lastInteraction = SystemClock.uptimeMillis()
                val handled = when (code) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); showControls(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); showControls(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekStep(1); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekStep(-1); true }
                    // Bluetooth remotes and keyboards: NEXT plays the next episode, PREVIOUS restarts this one.
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { currentNextEpisode?.invoke(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { player.seekTo(0L); showControls(); true }
                    AndroidKeyEvent.KEYCODE_MENU -> {
                        if (error == null) panel = if (panel == PlayerPanel.Quality) PlayerPanel.None else PlayerPanel.Quality
                        true
                    }
                    else -> if (error == null && panel == PlayerPanel.None && !controlsVisible) when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> { if (offered != null && !isSeeking) skip(offered) else togglePlay(); true }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { seekStep(-1); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { seekStep(1); true }
                        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); true }
                        else -> false
                    } else false
                }
                // Swallow the matching key-up too, so it does not click the button that just got focus.
                if (handled) consumedKeys.add(code)
                handled
            }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = player.isPlaying
                isFocusable = false
                isFocusableInTouchMode = false
            }
        }, update = { it.keepScreenOn = isPlaying }, modifier = Modifier.fillMaxSize())

        val noPanel = panel == PlayerPanel.None && error == null
        val barsVisible = controlsVisible && noPanel && !isSeeking
        val (topEnter, topExit) = slideTransition()
        val (bottomEnter, bottomExit) = slideTransition(initialOffsetY = { it }, targetOffsetY = { it })

        AnimatedVisibility(barsVisible, enter = topEnter, exit = topExit, modifier = Modifier.align(Alignment.TopCenter)) {
            TopControls(title, episodeLabel, episodeTitle, topFocus, playFocus, skipFocus.takeIf { skipShown }, onBack, onNextEpisode, onQuality = { panel = PlayerPanel.Quality }, onFocus = ::showControls)
        }

        AnimatedVisibility(isSeeking && noPanel, enter = fadeIn() + scaleIn(initialScale = .9f), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Text(
                formatTime(seekTarget()),
                style = MaterialTheme.typography.displayLargeEmphasized,
                color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(Tokens.radiusSheet)).padding(horizontal = 32.dp, vertical = 12.dp)
            )
        }

        AnimatedVisibility(playbackState == Player.STATE_BUFFERING && !isSeeking && noPanel, enter = scaleIn(), exit = scaleOut(), modifier = Modifier.align(Alignment.Center)) {
            LoadingIndicator(Modifier.size(80.dp), color = c.primary)
        }

        AnimatedVisibility((controlsVisible || isSeeking) && noPanel, enter = bottomEnter, exit = bottomExit, modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomControls(
                isPlaying = isPlaying,
                isSeeking = isSeeking,
                position = if (isSeeking) seekTarget() else position,
                duration = duration,
                speed = speed,
                subtitleLabel = selectedSubtitleLabel(tracks, parameters),
                marks = if (settings.skipMarks) segments else emptyList(),
                skipFocus = skipFocus.takeIf { skipShown },
                chipFocus = chipFocus,
                playFocus = playFocus,
                topFocus = topFocus,
                onPlayPause = ::togglePlay,
                onSeek = ::seekStep,
                onSpeed = { panel = PlayerPanel.Speed },
                onSubtitles = { panel = PlayerPanel.AudioSubtitles },
                onServers = { panel = PlayerPanel.Servers },
                onEpisodes = { panel = PlayerPanel.Episodes },
                onFocus = ::showControls
            )
        }

        // Skip intro, outro or recap. With the controls hidden OK presses it; with them showing it is
        // one press Down from the play button.
        AnimatedVisibility(skipShown && noPanel, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
            val shown = remember { mutableStateOf<SkipSegment?>(null) }
            if (offered != null) shown.value = offered
            FocusSurface(
                onClick = { shown.value?.let(::skip) },
                modifier = Modifier.padding(end = Tokens.screenH, bottom = if (controlsVisible) 150.dp else Tokens.screenV + 12.dp).height(52.dp)
                    .focusRequester(skipFocus)
                    .focusProperties { up = playFocus; left = playFocus; right = FocusRequester.Cancel; down = if (controlsVisible) chipFocus else playFocus },
                shape = RoundedCornerShape(50), focusedShape = RoundedCornerShape(16.dp),
                color = c.primaryContainer, focusedColor = c.primary, contentColor = c.onPrimaryContainer, focusedContentColor = c.onPrimary,
                onFocusChange = { skipFocused = it; if (it) showControls() }
            ) {
                Row(Modifier.padding(start = 20.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                    SymIcon(R.drawable.sym_skip_next, null, Modifier.size(22.dp))
                    Text(if (endsEpisode) "Next episode" else shown.value?.kind?.label.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    if (!controlsVisible) Text("OK", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = .7f))
                }
            }
        }

        (skipNotice ?: notice)?.let {
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = Tokens.screenH).background(c.surfaceContainerHigh, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SymIcon(R.drawable.sym_schedule, null, Modifier.size(18.dp), tint = c.primary)
                Text(it, style = MaterialTheme.typography.labelLarge, color = c.onSurface)
            }
        }

        fun update(block: TrackSelectionParameters.Builder.() -> Unit) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply(block).build()
        }

        AnimatedVisibility(panel == PlayerPanel.Quality, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 }, modifier = Modifier.align(Alignment.CenterEnd)) {
            SidePanel(start = false, icon = R.drawable.sym_high_quality, heading = "Quality", supporting = "Auto adapts to your connection", onClose = { panel = PlayerPanel.None; showControls() }) {
                OptionList(qualityOptions(tracks, parameters, videoHeight, ::update))
            }
        }
        AnimatedVisibility(panel == PlayerPanel.AudioSubtitles, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 }, modifier = Modifier.align(Alignment.CenterEnd)) {
            SidePanel(start = false, icon = R.drawable.sym_subtitles, heading = "Audio & subtitles", supporting = null, onClose = { panel = PlayerPanel.None; showControls() }) {
                AudioSubtitleContent(audioOptions(tracks, ::update), subtitleOptions(tracks, parameters, ::update), noSubtitlesNote, onClose = { panel = PlayerPanel.None; showControls() })
            }
        }
        AnimatedVisibility(panel == PlayerPanel.Speed, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 }, modifier = Modifier.align(Alignment.CenterEnd)) {
            SidePanel(start = false, icon = R.drawable.sym_speed, heading = "Playback speed", supporting = null, onClose = { panel = PlayerPanel.None; showControls() }) {
                OptionList(PLAYBACK_SPEEDS.map { value -> TrackOption("${formatSpeed(value)}x", value == speed) { player.setPlaybackSpeed(value); panel = PlayerPanel.None; showControls() } })
            }
        }
        AnimatedVisibility(panel == PlayerPanel.Servers, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 }, modifier = Modifier.align(Alignment.CenterEnd)) {
            SidePanel(start = false, icon = R.drawable.sym_live_tv, heading = "Servers", supporting = "Episode ${episode.number}", onClose = { panel = PlayerPanel.None; showControls() }) {
                if (serversLoading) Text("Finding servers…", style = MaterialTheme.typography.bodyLarge)
                else ServerOptions(serverOptions, currentServer, ServerInfo(currentServer.detail, videoHeights(tracks)), serverInfo, serverError, onClose = { panel = PlayerPanel.None; showControls() }) { choice ->
                    panel = PlayerPanel.None
                    if (choice.source != currentServer.source || choice.server != currentServer.server) {
                        // Carry on from here: each server keeps its own position, and this one's may be older.
                        val at = player.currentPosition
                        if (at > MIN_RESUME_MS) prefs.edit().putLong("resume:" + watchPositionKey(choice.source, anime.id, episode.number, choice.server?.let { "${it.name}:${it.language}" }), at).apply()
                        onChooseServer(choice)
                    } else showControls()
                }
            }
        }
        AnimatedVisibility(panel == PlayerPanel.Episodes, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 }, modifier = Modifier.align(Alignment.CenterEnd)) {
            SidePanel(start = false, icon = R.drawable.sym_movie, heading = "Episodes", supporting = title, onClose = { panel = PlayerPanel.None; showControls() }) {
                EpisodeOptions(anime.episodes, episode, onClose = { panel = PlayerPanel.None; showControls() }) { chosen ->
                    panel = PlayerPanel.None
                    if (chosen.number != episode.number) onChooseEpisode(chosen) else showControls()
                }
            }
        }

        error?.takeIf { panel == PlayerPanel.None }?.let { message ->
            Box(Modifier.fillMaxSize().background(c.surface.copy(alpha = .94f)), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).background(c.errorContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                        SymIcon(R.drawable.sym_error, null, Modifier.size(40.dp), tint = c.onErrorContainer)
                    }
                    Spacer(Modifier.height(Tokens.gapXl))
                    Text(if (message.contains("unavailable", true)) "Episode unavailable" else "Playback stopped", style = MaterialTheme.typography.labelLargeEmphasized, color = c.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(message, style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Tokens.gapXl))
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                        PillButton("Try again", onRetry, Modifier.focusRequester(retryFocus), icon = R.drawable.sym_refresh, style = ButtonStyle.Filled)
                        PillButton("Servers", { panel = PlayerPanel.Servers }, icon = R.drawable.sym_live_tv)
                        PillButton("Back", onBack, icon = R.drawable.sym_arrow_back)
                    }
                }
            }
        }
    }
}

// ---- Top and bottom bars ----------------------------------------------------------------------

@Composable
private fun TopControls(
    title: String,
    episodeLabel: String?,
    episodeTitle: String?,
    topFocus: FocusRequester,
    playFocus: FocusRequester,
    skipFocus: FocusRequester?,
    onBack: () -> Unit,
    onNextEpisode: (() -> Unit)?,
    onQuality: () -> Unit,
    onFocus: () -> Unit
) {
    val scrim = Color.Black.copy(alpha = .35f)
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .85f), 1f to Color.Transparent)) }
            .padding(start = Tokens.screenH, end = Tokens.screenH, top = Tokens.screenV, bottom = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            RoundIconButton(R.drawable.sym_arrow_back, "Back", onBack, Modifier.focusRequester(topFocus).focusProperties { down = playFocus; left = FocusRequester.Cancel }, color = scrim, contentColor = Color.White, onFocusChange = { if (it) onFocus() })
            if (onNextEpisode != null) RoundIconButton(R.drawable.sym_skip_next, "Next episode", onNextEpisode, Modifier.focusProperties { down = playFocus }, color = scrim, contentColor = Color.White, onFocusChange = { if (it) onFocus() })
            RoundIconButton(R.drawable.sym_high_quality, "Quality", onQuality, Modifier.focusProperties { down = skipFocus ?: playFocus; right = FocusRequester.Cancel }, color = scrim, contentColor = Color.White, onFocusChange = { if (it) onFocus() })
        }
        Text(
            buildAnnotatedString {
                if (episodeLabel != null) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("$episodeLabel\n") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = .85f))) { append(episodeTitle?.takeIf { it.isNotBlank() } ?: title) }
                } else append(title)
            },
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 40.dp).widthIn(max = 640.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomControls(
    isPlaying: Boolean,
    isSeeking: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    subtitleLabel: String?,
    marks: List<SkipSegment>,
    skipFocus: FocusRequester?,
    chipFocus: FocusRequester,
    playFocus: FocusRequester,
    topFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onSubtitles: () -> Unit,
    onServers: () -> Unit,
    onEpisodes: () -> Unit,
    onFocus: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f))) }
            .padding(start = Tokens.screenH, end = Tokens.screenH, top = 64.dp, bottom = Tokens.screenV),
        verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapLg), verticalAlignment = Alignment.CenterVertically) {
            // Play/pause keeps the same D-pad actions with a simple circular focus platter.
            FocusSurface(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp)
                    .focusRequester(playFocus)
                    .focusProperties {
                        up = if (isSeeking) FocusRequester.Cancel else topFocus
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        if (skipFocus != null) down = skipFocus
                    }
                    .onPreviewKeyEvent { event ->
                        // Left/Right on the play button seek instead of moving focus.
                        val code = event.nativeKeyEvent.keyCode
                        if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) onSeek(if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        true
                    },
                shape = CircleShape,
                focusedScale = 1.12f,
                onFocusChange = { if (it) onFocus() }
            ) { t ->
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.lerp(c.primaryContainer, c.primary, t), CircleShape), contentAlignment = Alignment.Center) {
                    SymIcon(if (isPlaying) R.drawable.sym_pause else R.drawable.sym_play_arrow, null, Modifier.size(30.dp),
                        tint = androidx.compose.ui.graphics.lerp(c.onPrimaryContainer, c.onPrimary, t))
                }
            }
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            // A quiet, straight progress bar; skip markers remain below it.
            val markColor = c.tertiary
            Box(Modifier.weight(1f).height(26.dp)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center),
                    color = c.primary,
                    trackColor = Color.White.copy(alpha = .28f),
                    drawStopIndicator = {}
                )
                if (duration > 0 && marks.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height(5.dp).align(Alignment.BottomCenter)) {
                    marks.forEach { m ->
                        val from = size.width * (m.startMs.toFloat() / duration)
                        val to = size.width * (m.endMs.toFloat() / duration)
                        drawRoundRect(markColor, Offset(from, 0f), Size((to - from).coerceAtLeast(size.height), size.height), CornerRadius(size.height / 2))
                    }
                }
            }
            Text(
                "${formatTime(position)} / ${if (duration > 0) formatTime(duration) else "--:--"}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
        Row(Modifier.padding(start = 80.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
            if (!isSeeking) {
                TvChip(
                    "Servers", selected = false, onClick = onServers, icon = R.drawable.sym_live_tv,
                    modifier = Modifier.focusRequester(chipFocus).focusProperties { up = playFocus; down = FocusRequester.Cancel },
                    onFocusChange = { if (it) onFocus() }
                )
                TvChip("Episodes", selected = false, onClick = onEpisodes, icon = R.drawable.sym_movie,
                    modifier = Modifier.focusProperties { up = playFocus; down = FocusRequester.Cancel }, onFocusChange = { if (it) onFocus() })
                TvChip(
                    subtitleLabel ?: "Subtitles", selected = subtitleLabel != null, onClick = onSubtitles, icon = R.drawable.sym_subtitles,
                    modifier = Modifier.focusProperties { up = skipFocus ?: playFocus; down = FocusRequester.Cancel },
                    onFocusChange = { if (it) onFocus() }
                )
                TvChip("${formatSpeed(speed)}x", selected = false, onClick = onSpeed, icon = R.drawable.sym_speed,
                    modifier = Modifier.focusProperties { up = playFocus; down = FocusRequester.Cancel }, onFocusChange = { if (it) onFocus() })
            }
        }
    }
}

// ---- Panels ----------------------------------------------------------------------------------

/** A Material side sheet (surface container low, 28 dp outer corners) over a scrim. */
@Composable
private fun SidePanel(start: Boolean, icon: Int, heading: String, supporting: String?, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = if (start) RoundedCornerShape(topEnd = Tokens.radiusSheet, bottomEnd = Tokens.radiusSheet) else RoundedCornerShape(topStart = Tokens.radiusSheet, bottomStart = Tokens.radiusSheet)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(440.dp).background(c.surfaceContainerLow, shape)
                .padding(start = if (start) Tokens.screenH else 24.dp, end = if (start) 24.dp else Tokens.screenH, top = Tokens.screenV, bottom = Tokens.screenV)
                // Pressing toward the screen edge you came from closes the sheet.
                .onPreviewKeyEvent { closeOn(it.nativeKeyEvent, if (start) AndroidKeyEvent.KEYCODE_DPAD_RIGHT else AndroidKeyEvent.KEYCODE_DPAD_LEFT, onClose) },
            verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(c.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) { SymIcon(icon, null, Modifier.size(22.dp), tint = c.onSecondaryContainer) }
                Column {
                    Text(heading, style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                    if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun OptionList(options: List<TrackOption>, initialFocus: Boolean = true, modifier: Modifier = Modifier) {
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(initialFocus) { if (initialFocus) runCatching { selectedFocus.requestFocus() } }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(options) { index, option -> OptionRow(option, if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier) }
    }
}

@Composable
private fun OptionRow(option: TrackOption, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick = option.apply,
        modifier = modifier.fillMaxWidth().height(if (option.supporting == null) 54.dp else 68.dp),
        shape = RoundedCornerShape(Tokens.radiusCover),
        focusedShape = RoundedCornerShape(Tokens.radiusCover),
        color = if (option.selected) c.secondaryContainer else c.surfaceContainer,
        focusedColor = c.primary,
        contentColor = if (option.selected) c.onSecondaryContainer else c.onSurface,
        focusedContentColor = c.onPrimary,
        focusedScale = 1.02f
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(option.label, style = if (option.selected) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                option.supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (option.selected) SymIcon(R.drawable.sym_check, "Selected", Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ServerOptions(options: List<PlayerServer>, current: PlayerServer, currentInfo: ServerInfo, info: Map<PlayerServer, ServerInfo?>, failed: Boolean, onClose: () -> Unit, choose: (PlayerServer) -> Unit) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val groups = remember(options) { options.groupBy { it.group } }
    val selected = options.indexOfFirst { it.source == current.source && it.server == current.server }.coerceAtLeast(0)
    LaunchedEffect(options) { runCatching { focus.requestFocus() } }
    if (options.isEmpty()) {
        Text(if (failed) "Could not load servers." else "No servers are available.", style = MaterialTheme.typography.bodyLarge)
        PillButton("Close", onClose, Modifier.focusRequester(focus), icon = R.drawable.sym_close)
    } else {
        val items = remember(options) { groups.flatMap { (group, members) -> listOf(group to null) + members.map { group to it } } }
        val selectedRow = items.indexOfFirst { it.second == options[selected] }.coerceAtLeast(0)
        LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = selectedRow), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(items) { _, item ->
                val choice = item.second
                if (choice == null) Text(item.first, Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = c.secondary)
                else {
                    val active = choice.source == current.source && choice.server == current.server
                    val label = choice.server?.name ?: choice.label
                    val offers = when {
                        active -> currentInfo.summary
                        choice in info -> info[choice]?.summary ?: "Not responding"
                        else -> "Checking…"
                    }
                    val about = listOfNotNull("Re:Anime".takeIf { choice.server != null }, offers.ifEmpty { null }).joinToString(" · ").ifEmpty { null }
                    OptionRow(TrackOption(label, active, about) { choose(choice) }, if (choice == options[selected]) Modifier.focusRequester(focus) else Modifier)
                }
            }
            if (failed) item { Text("Some servers could not be loaded.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EpisodeOptions(episodes: List<Episode>, current: Episode, onClose: () -> Unit, choose: (Episode) -> Unit) {
    val focus = remember { FocusRequester() }
    val now = remember(episodes) { System.currentTimeMillis() }
    val aired = remember(episodes) { episodes.filter { it.airsAfter(now) == 0L } }
    val selected = aired.indexOfFirst { it.number == current.number }.coerceAtLeast(0)
    LaunchedEffect(aired) { runCatching { focus.requestFocus() } }
    if (aired.isEmpty()) {
        Text("No episodes are available.", style = MaterialTheme.typography.bodyLarge)
        PillButton("Close", onClose, Modifier.focusRequester(focus), icon = R.drawable.sym_close)
    } else LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = selected), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(aired, key = { _, item -> item.number }) { index, item ->
            OptionRow(TrackOption("Episode ${item.number}", item.number == current.number, item.title.takeIf { it.isNotBlank() }) { choose(item) }, if (index == selected) Modifier.focusRequester(focus) else Modifier)
        }
    }
}

@Composable
private fun AudioSubtitleContent(audio: List<TrackOption>, subtitles: List<TrackOption>, noSubtitlesNote: String, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val closeFocus = remember { FocusRequester() }
    if (audio.isNotEmpty()) {
        Text("Audio", style = MaterialTheme.typography.titleSmall, color = c.secondary)
        OptionList(audio, initialFocus = true, modifier = Modifier.heightIn(max = 260.dp))
    }
    Text("Subtitles", style = MaterialTheme.typography.titleSmall, color = c.secondary)
    if (subtitles.isNotEmpty()) OptionList(subtitles, initialFocus = audio.isEmpty())
    else {
        LaunchedEffect(Unit) { if (audio.isEmpty()) runCatching { closeFocus.requestFocus() } }
        Text(noSubtitlesNote, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        Spacer(Modifier.height(Tokens.gapSm))
        PillButton("Close", onClose, Modifier.focusRequester(closeFocus), icon = R.drawable.sym_close)
    }
}

// ---- Track options ---------------------------------------------------------------------------

@androidx.annotation.OptIn(UnstableApi::class)
private fun qualityOptions(tracks: Tracks, parameters: TrackSelectionParameters, videoHeight: Int, update: (TrackSelectionParameters.Builder.() -> Unit) -> Unit): List<TrackOption> {
    val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    val manual = groups.any { parameters.overrides.containsKey(it.mediaTrackGroup) }
    val auto = TrackOption(if (!manual && videoHeight > 0) "Auto (${videoHeight}p)" else "Auto", !manual) { update { clearOverridesOfType(C.TRACK_TYPE_VIDEO) } }
    val choices = groups.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { i ->
            val format = group.getTrackFormat(i)
            val label = buildString {
                append(if (format.height > 0) "${format.height}p" else "Track ${i + 1}")
                if (format.bitrate > 0) append(String.format(Locale.US, "  ·  %.1f Mbps", format.bitrate / 1_000_000f))
            }
            format.height to TrackOption(label, parameters.overrides[group.mediaTrackGroup]?.trackIndices?.contains(i) == true) {
                update { setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)) }
            }
        }
    }.sortedByDescending { it.first }.map { it.second }
    return listOf(auto) + choices
}

private fun videoHeights(tracks: Tracks): List<Int> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
        .filter { it > 0 }.distinct().sortedDescending()

private fun usableTextTracks(tracks: Tracks): List<Pair<Tracks.Group, Int>> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
        (0 until group.length).filter { i ->
            val format = group.getTrackFormat(i)
            // HLS adds a placeholder CEA-608 track to every TS stream; hide it unless it is described.
            val placeholder = (format.sampleMimeType == MimeTypes.APPLICATION_CEA608 || format.sampleMimeType == MimeTypes.APPLICATION_CEA708) &&
                format.label == null && format.language == null
            group.isTrackSupported(i) && !placeholder
        }.map { group to it }
    }

private fun languageName(code: String?): String? = code?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() }

private fun selectedSubtitleLabel(tracks: Tracks, parameters: TrackSelectionParameters): String? {
    if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return null
    val (group, i) = usableTextTracks(tracks).firstOrNull { (group, i) -> group.isTrackSelected(i) } ?: return null
    val format = group.getTrackFormat(i)
    return format.label ?: languageName(format.language) ?: "Subtitles"
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun subtitleOptions(tracks: Tracks, parameters: TrackSelectionParameters, update: (TrackSelectionParameters.Builder.() -> Unit) -> Unit): List<TrackOption> {
    val usable = usableTextTracks(tracks)
    if (usable.isEmpty()) return emptyList()
    val off = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || usable.none { (group, i) -> group.isTrackSelected(i) }
    return listOf(TrackOption("Off", off) { update { clearOverridesOfType(C.TRACK_TYPE_TEXT); setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) } }) +
        usable.mapIndexed { n, (group, i) ->
            val format = group.getTrackFormat(i)
            TrackOption(format.label ?: languageName(format.language) ?: "Subtitles ${n + 1}", !off && group.isTrackSelected(i)) {
                update { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false); setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)) }
            }
        }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun audioOptions(tracks: Tracks, update: (TrackSelectionParameters.Builder.() -> Unit) -> Unit): List<TrackOption> {
    val usable = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
    }
    if (usable.size < 2) return emptyList()
    return usable.mapIndexed { n, (group, i) ->
        val format = group.getTrackFormat(i)
        TrackOption(format.label ?: languageName(format.language) ?: "Audio ${n + 1}", group.isTrackSelected(i)) {
            update { setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)) }
        }
    }
}

// ---- Helpers ---------------------------------------------------------------------------------

private fun closeOn(event: AndroidKeyEvent, keyCode: Int, onClose: () -> Unit): Boolean {
    if (event.keyCode != keyCode) return false
    if (event.action == AndroidKeyEvent.ACTION_DOWN) onClose()
    return true
}

private fun slideTransition(
    initialOffsetY: (fullHeight: Int) -> Int = { -it },
    targetOffsetY: (fullHeight: Int) -> Int = { -it }
): Pair<EnterTransition, ExitTransition> =
    (fadeIn() + slideInVertically(initialOffsetY = initialOffsetY)) to (slideOutVertically(targetOffsetY = targetOffsetY) + fadeOut())

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) String.format(Locale.US, "%.1f", speed) else speed.toString().trimEnd('0')

private fun formatTime(ms: Long): String {
    if (ms < 0 || ms == C.TIME_UNSET) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
