package com.stremli.tv

// Stremli's TV design system: a dark, artwork-led palette with translucent surfaces and
// high-contrast focus. The design is inspired by BitChord's television interface.

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dark tonal-spot scheme seeded at HCT hue 270, chroma 36 (Material color utilities, 2021 spec).
 * It reproduces Mornye's Material screenshots: primary #B1C5FF, primary container #2F4578,
 * secondary container #404659, tertiary container #593D59.
 */
val StremliColors = darkColorScheme(
    primary = Color(0xFFF5F5F7),
    onPrimary = Color(0xFF111114),
    primaryContainer = Color(0xFF36363D),
    onPrimaryContainer = Color(0xFFF5F5F7),
    inversePrimary = Color(0xFF485D92),
    secondary = Color(0xFFD1D1D6),
    onSecondary = Color(0xFF252529),
    secondaryContainer = Color(0xFF303036),
    onSecondaryContainer = Color(0xFFF2F2F5),
    tertiary = Color(0xFFE0BBDD),
    onTertiary = Color(0xFF412742),
    tertiaryContainer = Color(0xFF593D59),
    onTertiaryContainer = Color(0xFFFED7FA),
    background = Color(0xFF08080B),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF08080B),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFB8B8C0),
    surfaceTint = Color(0xFFF5F5F7),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceContainerHighest = Color(0xFF32323A),
    surfaceContainerHigh = Color(0xFF24242B),
    surfaceContainer = Color(0xFF19191F),
    surfaceContainerLow = Color(0xFF121217),
    surfaceContainerLowest = Color(0xFF08080B),
    surfaceDim = Color(0xFF08080B)
)

/** Shared radii and spacing (Mornye's AppTokens.standard), plus TV safe-area margins. */
object Tokens {
    val radiusBadge = 6.dp
    val radiusThumb = 8.dp
    val radiusCover = 12.dp
    val radiusControl = 16.dp
    val radiusCard = 16.dp
    val radiusSheet = 22.dp
    val gapXs = 4.dp
    val gapSm = 8.dp
    val gapMd = 12.dp
    val gapLg = 16.dp
    val gapXl = 24.dp
    /** Overscan-safe margins for a 16:9 TV (5% horizontal, 5% vertical of 960x540 dp). */
    val screenH = 48.dp
    val screenV = 27.dp
}

private fun flex(weight: Int, rounded: Boolean) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("ROND", if (rounded) 100f else 0f)
    )
)

/** Display, headline and title roles: Google Sans Flex with fully rounded terminals (ROND 100). */
val BrandFont = FontFamily(listOf(400, 500, 600, 700, 800).map { flex(it, rounded = false) })
/** Body and label roles: the same face with cut terminals, crisper for small text. */
val PlainFont = FontFamily(listOf(400, 500, 600, 700).map { flex(it, rounded = false) })

private fun TextStyle.brand(weight: FontWeight? = null) = copy(fontFamily = BrandFont, fontWeight = weight ?: fontWeight)
private fun TextStyle.plain() = copy(fontFamily = PlainFont)

/**
 * M3 type scale on Google Sans Flex. Sizes are the Material baseline; on a 1080p TV (320 dpi,
 * 960 dp wide) body text is raised one step so it stays legible from the couch.
 */
private val StremliTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.brand(),
        displayMedium = t.displayMedium.brand(),
        displaySmall = t.displaySmall.brand(),
        headlineLarge = t.headlineLarge.brand(),
        headlineMedium = t.headlineMedium.brand(),
        headlineSmall = t.headlineSmall.brand(),
        titleLarge = t.titleLarge.brand(),
        titleMedium = t.titleMedium.brand(FontWeight.SemiBold),
        titleSmall = t.titleSmall.brand(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.plain().copy(fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium = t.bodyMedium.plain().copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = t.bodySmall.plain().copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = t.labelLarge.plain().copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.plain().copy(fontSize = 13.sp),
        labelSmall = t.labelSmall.plain().copy(fontSize = 12.sp),
        displayLargeEmphasized = t.displayLargeEmphasized.brand(FontWeight.SemiBold),
        displayMediumEmphasized = t.displayMediumEmphasized.brand(FontWeight.SemiBold),
        displaySmallEmphasized = t.displaySmallEmphasized.brand(FontWeight.SemiBold),
        headlineLargeEmphasized = t.headlineLargeEmphasized.brand(FontWeight.SemiBold),
        headlineMediumEmphasized = t.headlineMediumEmphasized.brand(FontWeight.SemiBold),
        headlineSmallEmphasized = t.headlineSmallEmphasized.brand(FontWeight.SemiBold),
        titleLargeEmphasized = t.titleLargeEmphasized.brand(FontWeight.Bold),
        titleMediumEmphasized = t.titleMediumEmphasized.brand(FontWeight.Bold),
        titleSmallEmphasized = t.titleSmallEmphasized.brand(FontWeight.Bold),
        bodyLargeEmphasized = t.bodyLargeEmphasized.plain().copy(fontSize = 17.sp, lineHeight = 25.sp),
        bodyMediumEmphasized = t.bodyMediumEmphasized.plain().copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmallEmphasized = t.bodySmallEmphasized.plain().copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLargeEmphasized = t.labelLargeEmphasized.plain().copy(fontSize = 15.sp),
        labelMediumEmphasized = t.labelMediumEmphasized.plain().copy(fontSize = 13.sp),
        labelSmallEmphasized = t.labelSmallEmphasized.plain().copy(fontSize = 12.sp)
    )
}

private val StremliShapes = Shapes(
    extraSmall = RoundedCornerShape(Tokens.radiusBadge),
    small = RoundedCornerShape(Tokens.radiusThumb),
    medium = RoundedCornerShape(Tokens.radiusCover),
    large = RoundedCornerShape(Tokens.radiusCard),
    extraLarge = RoundedCornerShape(Tokens.radiusSheet)
)

@Composable
fun StremliTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = StremliColors,
        motionScheme = MotionScheme.expressive(),
        shapes = StremliShapes,
        typography = StremliTypography,
        content = content
    )
}
