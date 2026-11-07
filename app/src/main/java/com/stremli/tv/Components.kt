package com.stremli.tv

// Shared TV controls: translucent resting surfaces, restrained lift and a clear focus outline.

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.min

// ---- Focus foundation -----------------------------------------------------------------------

/** A rounded shape between [from] and [to] at [t] (0..1), so corners can morph on focus. */
private class MorphShape(private val from: CornerBasedShape, private val to: CornerBasedShape, private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val max = min(size.width, size.height) / 2f
        fun r(a: androidx.compose.foundation.shape.CornerSize, b: androidx.compose.foundation.shape.CornerSize): CornerRadius {
            val start = a.toPx(size, density)
            val end = b.toPx(size, density)
            val v = (start + (end - start) * t).coerceIn(0f, max)
            return CornerRadius(v, v)
        }
        return Outline.Rounded(
            RoundRect(
                left = 0f, top = 0f, right = size.width, bottom = size.height,
                topLeftCornerRadius = r(from.topStart, to.topStart),
                topRightCornerRadius = r(from.topEnd, to.topEnd),
                bottomRightCornerRadius = r(from.bottomEnd, to.bottomEnd),
                bottomLeftCornerRadius = r(from.bottomStart, to.bottomStart)
            )
        )
    }
}

/**
 * A light outline on the component's rounded edge, visible against both artwork and dark surfaces.
 */
private fun Modifier.focusRing(from: CornerBasedShape, to: CornerBasedShape, t: Float): Modifier = drawWithContent {
    drawContent()
    if (t <= 0f) return@drawWithContent
    drawOutline(MorphShape(from, to, t).createOutline(size, layoutDirection, this), color = RingLight.copy(alpha = t), style = Stroke(width = 4.dp.toPx()))
}

private val RingLight = Color(0xFFF2F2F8)

/**
 * The base of every focusable component. [content] receives the focus progress (0..1).
 * Colors and shape follow the clamped progress; scale follows the raw spring, which overshoots.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(Tokens.radiusControl),
    focusedShape: CornerBasedShape = shape,
    color: Color = Color.Transparent,
    focusedColor: Color = color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    focusedContentColor: Color = contentColor,
    focusedScale: Float = 1.06f,
    border: BorderStroke? = null,
    onFocusChange: (Boolean) -> Unit = {},
    showRing: Boolean = true,
    content: @Composable BoxScope.(focus: Float) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val spring by animateFloatAsState(if (focused) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "focus")
    val t = spring.coerceIn(0f, 1f)
    val morph = MorphShape(shape, focusedShape, t)
    Box(
        modifier
            .graphicsLayer {
                val s = 1f + (focusedScale - 1f) * spring
                scaleX = s; scaleY = s
            }
            .onFocusChanged { onFocusChange(it.isFocused) }
            .clip(morph)
            .background(lerp(color, focusedColor, t))
            .then(if (border != null && t < 1f) Modifier.border(BorderStroke(border.width, (border.brush as? SolidColor)?.value?.copy(alpha = 1f - t) ?: Color.Transparent), morph) else Modifier)
            .then(if (showRing) Modifier.focusRing(shape, focusedShape, t) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        propagateMinConstraints = true
    ) {
        CompositionLocalProvider(LocalContentColor provides lerp(contentColor, focusedContentColor, t)) { content(t) }
    }
}

@Composable
fun SymIcon(@DrawableRes icon: Int, description: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(icon), contentDescription = description, modifier = modifier, tint = tint)
}

// ---- Buttons --------------------------------------------------------------------------------

enum class ButtonStyle { Filled, Tonal, Outlined }

/** A translucent rounded control that turns bright when focused. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, style: ButtonStyle = ButtonStyle.Tonal) {
    val c = MaterialTheme.colorScheme
    val (rest, onRest) = when (style) {
        ButtonStyle.Filled -> Color.White.copy(alpha = .20f) to c.onSurface
        ButtonStyle.Tonal -> Color.White.copy(alpha = .10f) to c.onSurface
        ButtonStyle.Outlined -> Color.Transparent to c.onSurface
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
        focusedShape = RoundedCornerShape(10.dp),
        color = rest, focusedColor = c.primary,
        contentColor = onRest, focusedContentColor = c.onPrimary,
        focusedScale = 1.06f,
        border = if (style == ButtonStyle.Outlined) BorderStroke(1.dp, c.outlineVariant) else null
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) SymIcon(icon, null, Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Round icon button with a clear light focus state. */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onFocusChange: (Boolean) -> Unit = {},
    /** Without a ring, focus is shown by the icon growing and wiggling (used in the top bar). */
    showRing: Boolean = true
) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        focusedShape = CircleShape,
        color = color, focusedColor = c.primary,
        contentColor = contentColor, focusedContentColor = c.onPrimary,
        focusedScale = if (showRing) 1.12f else 1.06f,
        onFocusChange = onFocusChange,
        showRing = showRing
    ) {
        Box(contentAlignment = Alignment.Center) {
            SymIcon(icon, description, Modifier.size(size * 0.5f))
        }
    }
}

/**
 * Focus motion for icons: grows on the expressive spatial spring (which overshoots) and gives a
 * short wiggle when focus arrives.
 */
@Composable
fun Modifier.focusBounce(focused: Boolean, grow: Float = 1.35f): Modifier {
    val scale by animateFloatAsState(if (focused) grow else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "icon grow")
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) wiggle.animateTo(0f, keyframes { durationMillis = 460; -12f at 70; 9f at 170; -5f at 270; 2f at 360 })
        else wiggle.snapTo(0f)
    }
    return graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = wiggle.value }
}

/** Filter chip: 8 dp corners at rest that round into a pill on focus (M3E chip morph). */
@Composable
fun TvChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, trailing: String? = null, onFocusChange: (Boolean) -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(Tokens.radiusThumb),
        focusedShape = RoundedCornerShape(Tokens.radiusThumb),
        color = if (selected) c.secondaryContainer else c.surfaceContainerHigh.copy(alpha = .7f),
        focusedColor = c.primary,
        contentColor = if (selected) c.onSecondaryContainer else c.onSurfaceVariant,
        focusedContentColor = c.onPrimary,
        focusedScale = 1.08f,
        border = null,
        onFocusChange = onFocusChange
    ) {
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) SymIcon(R.drawable.sym_check, null, Modifier.size(18.dp)) else if (icon != null) SymIcon(icon, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = .7f))
        }
    }
}

/** Small non-interactive tag, like Mornye's "24-bit" cover badge. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier.background(container, RoundedCornerShape(Tokens.radiusBadge)).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) SymIcon(icon, null, Modifier.size(14.dp), tint = content)
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** The Expressive play button: a 9-sided cookie that makes one turn when [spinning] becomes true. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CookiePlay(size: Dp, container: Color, content: Color, spinning: Boolean, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.sym_play_arrow) {
    val angle = remember { Animatable(0f) }
    // One turn of a third when focus arrives, then still: a looping spin would redraw the whole
    // screen 60 times a second for as long as the item stays focused, which low-end TV GPUs feel.
    val turn = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(spinning) {
        if (spinning) angle.animateTo(angle.targetValue + 120f, turn)
    }
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Read in the draw phase so the turn does not recompose.
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.value }.background(container, cookie))
        SymIcon(icon, null, Modifier.size(size * 0.46f), tint = content)
    }
}

// ---- Navigation -----------------------------------------------------------------------------

/** Compact TV navigation tab, with a bright platter for remote focus. */
@Composable
fun NavItem(@DrawableRes icon: Int, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50),
        color = if (selected) Color.White.copy(alpha = .14f) else Color.Transparent,
        focusedColor = c.primary, contentColor = c.onSurfaceVariant, focusedContentColor = c.onPrimary,
        focusedScale = 1.03f, showRing = true
    ) { t ->
        Row(Modifier.height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymIcon(icon, null, Modifier.size(20.dp), tint = lerp(if (selected) c.onSurface else c.onSurfaceVariant, c.onPrimary, t))
            Text(
                label,
                style = if (selected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
                color = lerp(if (selected) c.onSurface else c.onSurfaceVariant, c.onPrimary, t), maxLines = 1
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Row(modifier.padding(bottom = Tokens.gapSm), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Cards ----------------------------------------------------------------------------------

/**
 * Set by the app: opens a title's lists from any poster, given the poster to return focus to. Holding
 * OK is a shortcut for it and Menu does the same; the title page's list button reaches the same
 * panel, so nothing needs a long press.
 */
val LocalPosterMenu = staticCompositionLocalOf<((Anime, FocusRequester) -> Unit)?> { null }

/** OK clicks on release; holding it (the first key repeat, about half a second) or Menu opens [menu] instead. */
private fun Modifier.posterKeys(onClick: () -> Unit, menu: () -> Unit, pressed: BooleanArray): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    when (native.keyCode) {
        android.view.KeyEvent.KEYCODE_MENU -> { if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) menu(); true }
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (native.repeatCount == 0) { pressed[0] = true; pressed[1] = false }
                    else if (pressed[0] && !pressed[1]) { pressed[1] = true; menu() }
                }
                KeyEventType.KeyUp -> {
                    if (pressed[0] && !pressed[1]) onClick()
                    pressed[0] = false; pressed[1] = false
                }
            }
            true
        }
        else -> false
    }
}

/** Title card: a poster and caption in portrait mode, or a banner with overlaid text in landscape. */
@Composable
fun PosterCard(anime: Anime, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = null, onFocus: () -> Unit = {}, subtitle: String? = null, lookupBanner: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val portrait = LocalSettings.current.portraitCards
    var focused by remember { mutableStateOf(false) }
    val menu = LocalPosterMenu.current
    val self = remember { FocusRequester() }
    val context = LocalContext.current
    val cachedBanner by produceState(initialValue = anime.banner, anime.id, anime.banner, lookupBanner, portrait) {
        if (!portrait && lookupBanner && value.isBlank()) value = runCatching { Catalog.shared(context).localBanner(anime.id) }.getOrDefault("")
    }
    val sub = subtitle ?: listOfNotNull(anime.year.takeIf { it > 0 }?.toString(), anime.episodeCount.takeIf { it > 1 }?.let { "$it eps" }).joinToString(" · ")
    // [0] OK went down on this poster, [1] that press already opened the menu.
    val pressed = remember { BooleanArray(2) }
    Column(modifier.then(when (width) { null -> Modifier.width(if (portrait) 140.dp else 205.dp); Dp.Unspecified -> Modifier.fillMaxWidth(); else -> Modifier.width(width) }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (portrait) 2f / 3f else 1.65f)
                .then(if (menu != null) Modifier.focusRequester(self).posterKeys(onClick, { menu(anime, self) }, pressed) else Modifier),
            shape = RoundedCornerShape(Tokens.radiusCover),
            focusedShape = RoundedCornerShape(Tokens.radiusCover),
            color = c.surfaceContainerHigh,
            focusedScale = 1.04f,
            onFocusChange = { focused = it; if (it) onFocus() }
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(if (portrait) anime.poster.ifBlank { anime.banner } else cachedBanner.ifBlank { anime.poster }, contentDescription = anime.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (!portrait) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .38f to Color.Black.copy(alpha = .18f), 1f to Color.Black.copy(alpha = .92f))))
                if (anime.score > 0 && LocalSettings.current.scoreBadges) Tag("${anime.score}%", Modifier.align(Alignment.TopStart).padding(8.dp), icon = R.drawable.sym_star, container = c.surfaceContainerHighest.copy(alpha = .88f))
                if (!portrait) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.titleSmallEmphasized, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (portrait) {
            Text(anime.title, style = MaterialTheme.typography.titleSmall, color = if (focused) c.onSurface else c.onSurface.copy(alpha = .86f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Landscape resume card: episode artwork, readable caption and the saved progress. */
@Composable
fun ContinueCard(title: String, subtitle: String, image: String, progress: Float?, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier.aspectRatio(1.65f),
        shape = RoundedCornerShape(Tokens.radiusCover),
        focusedShape = RoundedCornerShape(Tokens.radiusCover),
        color = c.surfaceContainerHigh, focusedColor = c.surfaceContainerHigh,
        contentColor = Color.White, focusedContentColor = Color.White,
        focusedScale = 1.04f,
        onFocusChange = { if (it) onFocus() }
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .38f to Color.Black.copy(alpha = .18f), 1f to Color.Black.copy(alpha = .92f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress != null) {
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Color.White, trackColor = Color.White.copy(alpha = .32f), gapSize = 0.dp, drawStopIndicator = {})
                }
            }
        }
    }
}

/** Colored shortcut tile with an icon disc, after Mornye's home shortcuts. */
@Composable
fun ShortcutTile(@DrawableRes icon: Int, label: String, container: Color, content: Color, disc: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(132.dp),
        shape = RoundedCornerShape(Tokens.radiusCard),
        focusedShape = RoundedCornerShape(Tokens.radiusSheet + 4.dp),
        color = container, contentColor = content,
        focusedScale = 1.07f
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(42.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) { SymIcon(icon, null, Modifier.size(22.dp), tint = container) }
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

// ---- Inputs ---------------------------------------------------------------------------------

/** Pill search field (surface container high), outlined in primary when focused. */
@Composable
fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // When the text changes from outside (on-screen keys), put the cursor at the end, so text
    // typed into the field (phone remote, dictation) continues the query instead of landing at 0.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    val shown = if (field.text == value) field else androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    BasicTextField(
        value = shown,
        onValueChange = { field = it; if (it.text != value) onValueChange(it.text) },
        modifier = modifier.height(60.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize()
                    .background(if (focused) c.surfaceContainerHighest else c.surfaceContainerHigh, RoundedCornerShape(Tokens.radiusControl))
                    .border(if (focused) 2.dp else 0.dp, if (focused) c.primary else Color.Transparent, RoundedCornerShape(Tokens.radiusControl))
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)
            ) {
                SymIcon(icon, null, Modifier.size(24.dp), tint = if (focused) c.primary else c.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    inner()
                }
            }
        }
    )
}

// ---- Backdrop -------------------------------------------------------------------------------

/**
 * Image request for full-width key art. On low-memory devices it is decoded at 1280x720 rather than
 * the full 1920 px width (a 1080p ARGB backdrop is about 8 MB); it is faded behind gradients, so the
 * difference does not show.
 */
@Composable
fun backdropRequest(url: String): Any {
    val context = LocalContext.current
    return remember(url) {
        if (!DeviceProfile.of(context).smallBackdrops) url
        else coil3.request.ImageRequest.Builder(context).data(url).size(1280, 720).build()
    }
}

/** Faded key art in the top-right corner, blended into the surface (cheap: one image, gradients only). */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier, alpha: Float = .55f) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth().fillMaxHeight(.72f)) {
        if (!url.isNullOrBlank()) AsyncImage(backdropRequest(url), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = alpha)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to surface, .45f to surface.copy(alpha = .78f), 1f to surface.copy(alpha = .1f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to surface.copy(alpha = .35f), .55f to Color.Transparent, 1f to surface)))
    }
}
