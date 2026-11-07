package com.stremli.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Avatar colours, tuned for the dark theme; text on them is dark. */
private val AVATAR_COLORS = listOf(
    Color(0xFFB1C5FF), Color(0xFFFFB4A8), Color(0xFFA6D3A0), Color(0xFFF3C46B),
    Color(0xFFE0B6FF), Color(0xFF8FD3E8), Color(0xFFFFB0CB), Color(0xFFC8C6D0)
)
private val AVATAR_INK = Color(0xFF1A1B21)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun avatarShape(index: Int): Shape = when (index.mod(8)) {
    0 -> MaterialShapes.Cookie9Sided
    1 -> MaterialShapes.Clover4Leaf
    2 -> MaterialShapes.Sunny
    3 -> MaterialShapes.Flower
    4 -> MaterialShapes.SoftBurst
    5 -> MaterialShapes.Ghostish
    6 -> MaterialShapes.Puffy
    else -> MaterialShapes.Heart
}.toShape()

/** A profile's avatar: its initial on an expressive shape in its colour. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(AVATAR_COLORS[profile.color.mod(AVATAR_COLORS.size)], avatarShape(profile.shape)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            profile.name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = BrandFont, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp),
            color = AVATAR_INK, maxLines = 1
        )
    }
}

private sealed class Gate {
    data object Pick : Gate()
    data class Edit(val profile: Profile) : Gate()
    data class App(val profile: Profile) : Gate()
    /** PIN check before opening ([edit] false) or editing a locked profile. */
    data class Unlock(val profile: Profile, val edit: Boolean) : Gate()
    /** Choose a PIN, then continue to [then]. */
    data class SetPin(val profile: Profile, val allowNone: Boolean, val then: Gate) : Gate()
}

/**
 * The app behind a "Who's watching?" gate. With one profile the app opens straight away; with
 * several, the viewer picks one (the last used is focused). Each profile gets its own app state,
 * so switching profiles starts fresh with that profile's list, history and searches.
 */
@Composable
fun StremliApp() {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(Profiles.all(context)) }
    var gate by remember {
        val only = profiles.singleOrNull()
        mutableStateOf<Gate>(
            when {
                only == null -> Gate.Pick
                Profiles.hasPin(context, only) -> Gate.Unlock(only, edit = false)
                else -> Gate.App(only.also { Profiles.select(context, it) })
            }
        )
    }
    // Where Back from the picker goes: the profile that was in use, when switching.
    var switchingFrom by remember { mutableStateOf<Profile?>(null) }
    // Bumped when a PIN is removed in place, so screens re-read the lock state.
    var pinChanges by remember { mutableIntStateOf(0) }
    fun open(profile: Profile) { Profiles.select(context, profile); switchingFrom = null; gate = Gate.App(profile) }

    StremliTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val fast = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
            AnimatedContent(
                targetState = gate,
                contentKey = {
                    when (it) {
                        Gate.Pick -> "pick"; is Gate.Edit -> "edit:" + it.profile.id; is Gate.App -> "app:" + it.profile.id
                        is Gate.Unlock -> "unlock:" + it.profile.id; is Gate.SetPin -> "pin:" + it.profile.id
                    }
                },
                transitionSpec = { (fadeIn(fade) + scaleIn(scale, initialScale = .96f)) togetherWith fadeOut(fast) },
                label = "profiles"
            ) { shown ->
                when (shown) {
                    Gate.Pick -> ProfilePicker(
                        profiles, Profiles.lastUsed(context),
                        locked = { Profiles.hasPin(context, it) },
                        onPick = { if (Profiles.hasPin(context, it)) gate = Gate.Unlock(it, edit = false) else open(it) },
                        onAdd = { gate = Gate.Edit(Profile("", "", profiles.size, profiles.size)) },
                        // A locked profile is unlocked before it can be edited or deleted.
                        onEdit = { gate = if (Profiles.hasPin(context, it)) Gate.Unlock(it, edit = true) else Gate.Edit(it) },
                        onBack = switchingFrom?.let { from -> { open(from) } }
                    )
                    is Gate.Unlock -> UnlockProfile(
                        shown.profile, if (shown.edit) "Enter the PIN to edit this profile." else "This profile is locked.",
                        onUnlocked = { if (shown.edit) gate = Gate.Edit(shown.profile) else open(shown.profile) },
                        onReset = { gate = Gate.SetPin(shown.profile, allowNone = true, then = if (shown.edit) Gate.Edit(shown.profile) else Gate.App(shown.profile)) },
                        onBack = { gate = Gate.Pick }
                    )
                    is Gate.SetPin -> SetProfilePin(
                        shown.profile, shown.allowNone,
                        onDone = { val next = shown.then; if (next is Gate.App) open(next.profile) else gate = next },
                        onBack = { val next = shown.then; gate = if (next is Gate.App) Gate.Pick else next }
                    )
                    is Gate.Edit -> ProfileEditor(
                        shown.profile, canDelete = shown.profile.id.isNotBlank() && profiles.size > 1,
                        hasPin = pinChanges >= 0 && shown.profile.id.isNotBlank() && Profiles.hasPin(context, shown.profile),
                        onSetPin = { draft ->
                            // A new profile is saved first, so its PIN has a profile to belong to.
                            val name = draft.name.trim().ifBlank { "Profile ${profiles.size + if (draft.id.isBlank()) 1 else 0}" }
                            val saved = Profiles.put(context, draft.copy(name = name))
                            profiles = Profiles.all(context)
                            gate = Gate.SetPin(saved, allowNone = false, then = Gate.Edit(saved))
                        },
                        onRemovePin = { Profiles.setPin(context, shown.profile, null); pinChanges++ },
                        onSave = { edited ->
                            val name = edited.name.trim().ifBlank { "Profile ${profiles.size + if (edited.id.isBlank()) 1 else 0}" }
                            Profiles.put(context, edited.copy(name = name))
                            profiles = Profiles.all(context)
                            switchingFrom = switchingFrom?.let { from -> profiles.firstOrNull { it.id == from.id } }
                            gate = Gate.Pick
                        },
                        onDelete = {
                            Profiles.remove(context, shown.profile)
                            profiles = Profiles.all(context)
                            if (switchingFrom?.id == shown.profile.id) switchingFrom = null
                            gate = Gate.Pick
                        },
                        onCancel = { gate = Gate.Pick }
                    )
                    is Gate.App -> key(shown.profile.id) {
                        Stremli(shown.profile, switchProfile = { switchingFrom = shown.profile; profiles = Profiles.all(context); gate = Gate.Pick })
                    }
                }
            }
        }
    }
}

/** "Who's watching?": profile tiles, an Add tile, and Manage profiles to edit or remove them. */
@Composable
private fun ProfilePicker(
    profiles: List<Profile>, last: Profile, locked: (Profile) -> Boolean, onPick: (Profile) -> Unit, onAdd: () -> Unit,
    onEdit: (Profile) -> Unit, onBack: (() -> Unit)?
) {
    val c = MaterialTheme.colorScheme
    var managing by remember { mutableStateOf(false) }
    val lastFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { lastFocus.requestFocus() } }
    BackHandler(enabled = managing || onBack != null) { if (managing) managing = false else onBack?.invoke() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Tokens.screenH, vertical = Tokens.screenV),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(if (managing) "Manage profiles" else "Who's watching?", style = MaterialTheme.typography.displaySmallEmphasized, color = c.onSurface)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapXl), verticalAlignment = Alignment.Top) {
            profiles.forEach { profile ->
                ProfileTile(
                    profile.name, managing, locked = locked(profile),
                    // The remote's Menu key on a profile edits it, without entering Manage first.
                    modifier = (if (profile.id == last.id) Modifier.focusRequester(lastFocus) else Modifier).onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_MENU) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) onEdit(profile)
                        true
                    },
                    onClick = { if (managing) onEdit(profile) else onPick(profile) }
                ) { ProfileAvatar(profile, 112.dp) }
            }
            if (profiles.size < Profiles.MAX) ProfileTile("Add profile", false, locked = false, onClick = onAdd) {
                Box(Modifier.size(112.dp).background(c.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                    SymIcon(R.drawable.sym_add, null, Modifier.size(44.dp), tint = c.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            PillButton(
                if (managing) "Done" else "Manage profiles", { managing = !managing },
                icon = if (managing) R.drawable.sym_check else R.drawable.sym_tune, style = ButtonStyle.Outlined
            )
        }
        Spacer(Modifier.height(Tokens.gapMd))
        Text(
            if (managing) "Choose a profile to edit, lock or delete it." else "Tip: press Menu on a profile to edit it.",
            style = MaterialTheme.typography.bodyMedium, color = c.outline
        )
    }
}

@Composable
private fun ProfileTile(name: String, managing: Boolean, locked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, avatar: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, modifier.width(148.dp).semantics { contentDescription = name },
        shape = RoundedCornerShape(Tokens.radiusSheet), focusedShape = RoundedCornerShape(Tokens.radiusSheet),
        color = Color.Transparent, focusedColor = c.surfaceContainerHigh, focusedScale = 1.08f
    ) { t ->
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                avatar()
                if (managing || locked) Box(
                    Modifier.align(Alignment.BottomEnd).size(36.dp).background(c.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (managing) SymIcon(R.drawable.sym_tune, "Edit", Modifier.size(20.dp), tint = c.onSurface)
                    else SymIcon(R.drawable.sym_lock, "Locked with a PIN", Modifier.size(18.dp), tint = c.onSurface)
                }
            }
            Text(
                name, style = MaterialTheme.typography.titleMedium, color = if (t > .5f) c.onSurface else c.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Add or edit a profile: a name typed on the on-screen keyboard (or a hardware keyboard), a colour and a shape. */
@Composable
private fun ProfileEditor(
    profile: Profile, canDelete: Boolean, hasPin: Boolean, onSetPin: (Profile) -> Unit, onRemovePin: () -> Unit,
    onSave: (Profile) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf(profile) }
    var confirmDelete by remember { mutableStateOf(false) }
    val keepFocus = remember { FocusRequester() }
    LaunchedEffect(confirmDelete) { if (confirmDelete) runCatching { keepFocus.requestFocus() } }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
    BackHandler { if (confirmDelete) confirmDelete = false else onCancel() }
    fun edit(change: (String) -> String) { draft = draft.copy(name = change(draft.name).take(16)) }

    Row(
        Modifier.fillMaxSize().padding(horizontal = Tokens.screenH, vertical = Tokens.screenV)
            // Hardware keyboards type into the name; remote buttons produce no characters.
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val typing = native.keyCode == android.view.KeyEvent.KEYCODE_DEL ||
                    (native.unicodeChar != 0 && native.unicodeChar.toChar().let { it.isLetterOrDigit() || it == ' ' })
                if (!typing) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_DEL) edit { it.dropLast(1) }
                    else { val ch = native.unicodeChar.toChar(); edit { if (it.isEmpty()) ch.uppercase() else it + ch } }
                }
                true
            },
        horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.gapLg)) {
            Text(if (profile.id.isBlank()) "Add profile" else "Edit profile", style = MaterialTheme.typography.headlineMediumEmphasized, color = c.onSurface)
            ProfileAvatar(draft.copy(name = draft.name.ifBlank { "?" }), 160.dp)
            Text(draft.name.ifBlank { "No name yet" }, style = MaterialTheme.typography.titleLarge, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
        ) {
            // The name sits right above the keyboard that types it; OK on it goes to the keys.
            Text("Name", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
            FocusSurface(
                { runCatching { firstKey.requestFocus() } }, Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = "Name: ${draft.name}" },
                shape = RoundedCornerShape(50), focusedShape = RoundedCornerShape(Tokens.radiusCard),
                color = c.surfaceContainerHigh, focusedColor = c.surfaceContainerHighest, focusedScale = 1.02f
            ) {
                Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        draft.name.ifEmpty { "Type a name below" }, style = MaterialTheme.typography.titleMedium,
                        color = if (draft.name.isEmpty()) c.onSurfaceVariant else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                NameKey("Space", { edit { "$it " } }, Modifier.weight(1f), icon = R.drawable.sym_space_bar)
                NameKey("Delete", { edit { it.dropLast(1) } }, Modifier.weight(1f), icon = R.drawable.sym_arrow_back)
                NameKey("Clear", { edit { "" } }, Modifier.weight(1f), icon = R.drawable.sym_close)
            }
            "abcdefghijklmnopqrstuvwxyz0123456789".chunked(9).forEachIndexed { row, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    keys.forEachIndexed { column, key ->
                        NameKey(key.toString(), { edit { if (it.isEmpty()) key.uppercase() else it + key } },
                            Modifier.weight(1f).then(if (row == 0 && column == 0) Modifier.focusRequester(firstKey) else Modifier))
                    }
                }
            }
            Spacer(Modifier.height(Tokens.gapMd))
            Text("Colour", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                AVATAR_COLORS.forEachIndexed { i, colour ->
                    Choice(selected = draft.color == i, onClick = { draft = draft.copy(color = i) }, label = "Colour ${i + 1}") {
                        Box(Modifier.size(30.dp).background(colour, CircleShape))
                    }
                }
            }
            Text("Shape", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                repeat(8) { i ->
                    Choice(selected = draft.shape == i, onClick = { draft = draft.copy(shape = i) }, label = "Shape ${i + 1}") {
                        Box(Modifier.size(30.dp).background(AVATAR_COLORS[draft.color.mod(AVATAR_COLORS.size)], avatarShape(i)))
                    }
                }
            }
            Text("PIN", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (hasPin) "Change PIN" else "Set PIN", { onSetPin(draft) }, icon = R.drawable.sym_lock, style = ButtonStyle.Tonal)
                if (hasPin) PillButton("Remove PIN", onRemovePin, style = ButtonStyle.Outlined)
                Text(if (hasPin) "Locked" else "Not locked", style = MaterialTheme.typography.bodyMedium, color = c.outline)
            }
            Spacer(Modifier.height(Tokens.gapSm))
            if (confirmDelete) {
                Text(
                    "Delete ${draft.name.ifBlank { "this profile" }}? Its list, history and searches are removed from this device.",
                    style = MaterialTheme.typography.bodyLarge, color = c.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                    PillButton("Keep profile", { confirmDelete = false }, Modifier.focusRequester(keepFocus), style = ButtonStyle.Tonal)
                    PillButton("Delete", onDelete, icon = R.drawable.sym_close, style = ButtonStyle.Outlined)
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                PillButton("Save", { onSave(draft) }, icon = R.drawable.sym_check, style = ButtonStyle.Filled)
                PillButton("Cancel", onCancel, style = ButtonStyle.Tonal)
                if (canDelete) PillButton("Delete profile", { confirmDelete = true }, icon = R.drawable.sym_close, style = ButtonStyle.Outlined)
            }
        }
    }
}

@Composable
private fun NameKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @androidx.annotation.DrawableRes icon: Int? = null) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, Modifier.height(40.dp).then(modifier).semantics { contentDescription = label },
        shape = RoundedCornerShape(Tokens.radiusThumb), focusedShape = RoundedCornerShape(Tokens.radiusControl),
        color = c.surfaceContainerHigh, focusedColor = c.primary,
        contentColor = c.onSurface, focusedContentColor = c.onPrimary, focusedScale = 1.08f
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) SymIcon(icon, null, Modifier.size(20.dp))
            else Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

/** A swatch choice: ringed when selected, highlighted when focused. */
@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, Modifier.size(48.dp).semantics { contentDescription = label },
        shape = CircleShape, color = Color.Transparent, focusedColor = c.surfaceContainerHighest, focusedScale = 1.12f,
        border = if (selected) BorderStroke(2.dp, c.primary) else null
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
