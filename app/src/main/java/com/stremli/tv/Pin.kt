package com.stremli.tv

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 4
private const val MAX_TRIES = 5
private const val LOCKOUT_MS = 30_000L

/** Wrong-PIN lockout, shared by every PIN check while the app runs: 5 misses, then 30 s. */
private object PinGuard {
    var misses = 0
    var lockedUntil by mutableLongStateOf(0L)
    fun fail() { misses++; if (misses >= MAX_TRIES) { lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS; misses = 0 } }
    fun pass() { misses = 0 }
}

/**
 * A PIN pad for the remote: four dots and a 3×4 number grid (D-pad and OK), and the remote's or a
 * keyboard's number keys and Backspace. The PIN is checked as soon as the fourth digit is entered.
 * [reset] changes after a wrong or mismatched PIN, which empties the dots.
 */
@Composable
private fun PinPad(
    title: String, message: String, error: String?, reset: Int, profile: Profile?,
    checking: Boolean, onComplete: (String) -> Unit, actions: @Composable RowScope.() -> Unit
) {
    val c = MaterialTheme.colorScheme
    var digits by remember(reset, title) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // A slow tick, only while locked out, for the countdown.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val locked = checking && PinGuard.lockedUntil > now
    LaunchedEffect(PinGuard.lockedUntil) {
        while (SystemClock.elapsedRealtime() < PinGuard.lockedUntil) { now = SystemClock.elapsedRealtime(); delay(1_000) }
        now = SystemClock.elapsedRealtime()
    }
    fun type(d: Char) { if (!locked && digits.length < PIN_LENGTH) digits += d }
    LaunchedEffect(digits) {
        if (digits.length == PIN_LENGTH) { delay(150); onComplete(digits) }
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = Tokens.screenH, vertical = Tokens.screenV)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val digit = when (code) {
                    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> '0' + (code - android.view.KeyEvent.KEYCODE_0)
                    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - android.view.KeyEvent.KEYCODE_NUMPAD_0)
                    else -> null
                }
                when {
                    digit != null -> { if (event.type == KeyEventType.KeyDown) type(digit); true }
                    code == android.view.KeyEvent.KEYCODE_DEL -> { if (event.type == KeyEventType.KeyDown) digits = digits.dropLast(1); true }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
    ) {
        // Two columns fit a 540 dp TV screen: what is asked on the left, the pad on the right.
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (profile != null) { ProfileAvatar(profile, 88.dp); Spacer(Modifier.height(Tokens.gapMd)) }
            Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, color = c.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                when { locked -> "Too many tries. Try again in ${((PinGuard.lockedUntil - now) / 1000).coerceAtLeast(1)} s."; error != null -> error; else -> message },
                style = MaterialTheme.typography.bodyLarge, color = if (locked || error != null) c.error else c.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Tokens.gapLg))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { contentDescription = "${digits.length} of $PIN_LENGTH digits entered" }) {
                repeat(PIN_LENGTH) { i ->
                    val filled = i < digits.length
                    Box(
                        Modifier.size(18.dp)
                            .background(if (filled) c.primary else c.surfaceContainerHighest, CircleShape)
                            .border(if (filled) 0.dp else 1.dp, c.outline, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(Tokens.gapXl))
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd), content = actions)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { d -> PinKey(d.toString(), { type(d) }, if (d == '1') Modifier.focusRequester(first) else Modifier) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.size(width = 76.dp, height = 56.dp))
                PinKey("0", { type('0') })
                PinKey("Delete", { digits = digits.dropLast(1) }, icon = R.drawable.sym_arrow_back)
            }
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @androidx.annotation.DrawableRes icon: Int? = null) {
    val c = MaterialTheme.colorScheme
    FocusSurface(
        onClick, modifier.size(width = 76.dp, height = 56.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(Tokens.radiusControl), focusedShape = RoundedCornerShape(Tokens.radiusCard),
        color = c.surfaceContainerHigh, focusedColor = c.primary,
        contentColor = c.onSurface, focusedContentColor = c.onPrimary, focusedScale = 1.1f
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) SymIcon(icon, null, Modifier.size(22.dp))
            else Text(label, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/**
 * Asks for [profile]'s PIN. "Forgot PIN?" asks for the admin's master PIN (viewers ask the
 * developer for it); when that is right, the profile's PIN is cleared and [onReset] lets the
 * viewer choose a new one.
 */
@Composable
fun UnlockProfile(profile: Profile, purpose: String, onUnlocked: () -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var master by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableIntStateOf(0) }
    BackHandler { if (master) { master = false; error = null; reset++ } else onBack() }
    PinPad(
        title = if (master) "Forgot your PIN?" else "Enter ${profile.name}'s PIN",
        message = if (master) "Ask the developer for the master PIN and enter it here to reset ${profile.name}'s PIN." else purpose,
        error = error, reset = reset, profile = profile, checking = true,
        onComplete = { pin ->
            val ok = if (master) Profiles.checkMaster(pin) else Profiles.checkPin(context, profile, pin)
            if (ok) {
                PinGuard.pass()
                if (master) { Profiles.setPin(context, profile, null); onReset() } else onUnlocked()
            } else {
                PinGuard.fail(); error = "That PIN isn't right."; reset++
            }
        }
    ) {
        if (!master && Profiles.hasMaster()) PillButton("Forgot PIN?", { master = true; error = null; reset++ }, icon = R.drawable.sym_lock, style = ButtonStyle.Tonal)
        PillButton(if (master) "Back" else "Cancel", { if (master) { master = false; error = null; reset++ } else onBack() }, style = ButtonStyle.Outlined)
    }
}

/** Sets [profile]'s PIN: enter and confirm. With [allowNone], "No PIN" leaves it unlocked. */
@Composable
fun SetProfilePin(profile: Profile, allowNone: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // Steps: 2 PIN, 3 confirm PIN.
    var step by remember { mutableIntStateOf(2) }
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableIntStateOf(0) }
    BackHandler { onBack() }
    val (title, message) = when (step) {
        2 -> "Choose a PIN for ${profile.name}" to "4 digits, asked for each time ${profile.name} is opened."
        else -> "Confirm ${profile.name}'s PIN" to "Enter it again."
    }
    PinPad(
        title, message, error, reset, profile, checking = false,
        onComplete = { pin ->
            when {
                step == 2 -> { first = pin; error = null; step = 3 }
                pin != first -> { error = "The PINs don't match. Try again."; step = 2; reset++ }
                else -> { Profiles.setPin(context, profile, pin); onDone() }
            }
        }
    ) {
        if (allowNone) PillButton("No PIN", onDone, style = ButtonStyle.Tonal)
        PillButton("Cancel", onBack, style = ButtonStyle.Outlined)
    }
}
