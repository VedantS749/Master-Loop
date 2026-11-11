package app.loop.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.loop.R
import app.loop.model.Category
import app.loop.model.Palette
import app.loop.model.ThemeMode

/*
 * Loop uses the Material 3 theme system only: a ColorScheme (dynamic "Material You" colours on Android 12+,
 * a static brand scheme otherwise), Material typography and Material components. There is no separate palette.
 *
 * The few extra names below are derived from Material roles so category accents and good/caution states follow
 * whichever scheme is active.
 */

/** Seed colours for one preset. Everything else in the Material scheme is derived from these. */
private class Seed(
    val primaryD: Long, val primaryL: Long, val secondaryD: Long, val secondaryL: Long, val tertiaryD: Long, val tertiaryL: Long,
    val errorD: Long, val errorL: Long, val bgD: Long, val bgL: Long,
)

private val seeds = mapOf(
    Palette.VIBRANT to Seed(0xFF8B7CFF, 0xFF5B47FF, 0xFF00F0B5, 0xFF00B386, 0xFFFF7A3D, 0xFFF25C14, 0xFFFF4D6A, 0xFFE0264A, 0xFF0B0A22, 0xFFF6F4FF),
    Palette.OCEAN to Seed(0xFF2EC4FF, 0xFF0277BD, 0xFF00E5CC, 0xFF00897B, 0xFFFFB74D, 0xFFEF6C00, 0xFFFF5D73, 0xFFD32F4A, 0xFF031521, 0xFFEEF8FD),
    Palette.SUNSET to Seed(0xFFFF6B6B, 0xFFE53950, 0xFFFFB02E, 0xFFD98200, 0xFFD16BFF, 0xFF9B3FE0, 0xFFFF4D6A, 0xFFD62850, 0xFF1C0B16, 0xFFFFF4F1),
    Palette.FOREST to Seed(0xFF4CE07E, 0xFF1A9A4A, 0xFFC3EB3F, 0xFF6C9A00, 0xFFFFB347, 0xFFD9822B, 0xFFFF6B6B, 0xFFD33F3F, 0xFF06150D, 0xFFF1F9F3),
    Palette.SOMBER to Seed(0xFF9FA9C6, 0xFF4F5B7A, 0xFF8FB8AA, 0xFF4D7C6F, 0xFFCDA98F, 0xFF8F6A52, 0xFFD98585, 0xFFB04A4A, 0xFF131417, 0xFFF3F3F1),
)

private fun on(c: Color) = if (c.luminance() > 0.42f) Color(0xFF0E1116) else Color.White

private fun darkScheme(sd: Seed): ColorScheme {
    val bg = Color(sd.bgD); val p = Color(sd.primaryD); val s = Color(sd.secondaryD); val t = Color(sd.tertiaryD); val e = Color(sd.errorD)
    val onS = lerp(Color.White, bg, 0.06f)
    return darkColorScheme(
        primary = p, onPrimary = on(p), primaryContainer = lerp(bg, p, 0.38f), onPrimaryContainer = lerp(p, Color.White, 0.85f),
        secondary = s, onSecondary = on(s), secondaryContainer = lerp(bg, s, 0.32f), onSecondaryContainer = lerp(s, Color.White, 0.85f),
        tertiary = t, onTertiary = on(t), tertiaryContainer = lerp(bg, t, 0.36f), onTertiaryContainer = lerp(t, Color.White, 0.85f),
        error = e, onError = on(e), errorContainer = lerp(bg, e, 0.38f), onErrorContainer = lerp(e, Color.White, 0.85f),
        background = bg, onBackground = onS, surface = bg, onSurface = onS,
        surfaceVariant = lerp(bg, Color.White, 0.12f), onSurfaceVariant = lerp(Color.White, bg, 0.32f),
        surfaceContainerLowest = lerp(bg, Color.Black, 0.3f), surfaceContainerLow = lerp(bg, Color.White, 0.035f),
        surfaceContainer = lerp(bg, p, 0.10f), surfaceContainerHigh = lerp(bg, p, 0.17f), surfaceContainerHighest = lerp(bg, p, 0.24f),
        outline = lerp(Color.White, bg, 0.5f), outlineVariant = lerp(bg, p, 0.28f),
    )
}

private fun lightScheme(sd: Seed): ColorScheme {
    val bg = Color(sd.bgL); val p = Color(sd.primaryL); val s = Color(sd.secondaryL); val t = Color(sd.tertiaryL); val e = Color(sd.errorL)
    val onS = lerp(Color(0xFF0E1116), p, 0.10f)
    return lightColorScheme(
        primary = p, onPrimary = on(p), primaryContainer = lerp(Color.White, p, 0.22f), onPrimaryContainer = lerp(p, Color.Black, 0.7f),
        secondary = s, onSecondary = on(s), secondaryContainer = lerp(Color.White, s, 0.22f), onSecondaryContainer = lerp(s, Color.Black, 0.7f),
        tertiary = t, onTertiary = on(t), tertiaryContainer = lerp(Color.White, t, 0.22f), onTertiaryContainer = lerp(t, Color.Black, 0.7f),
        error = e, onError = on(e), errorContainer = lerp(Color.White, e, 0.2f), onErrorContainer = lerp(e, Color.Black, 0.7f),
        background = bg, onBackground = onS, surface = bg, onSurface = onS,
        surfaceVariant = lerp(bg, p, 0.10f), onSurfaceVariant = lerp(onS, bg, 0.38f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = lerp(bg, Color.White, 0.6f),
        surfaceContainer = Color.White, surfaceContainerHigh = lerp(bg, p, 0.09f), surfaceContainerHighest = lerp(bg, p, 0.15f),
        outline = lerp(onS, bg, 0.55f), outlineVariant = lerp(bg, p, 0.20f),
    )
}

// ---- names derived from Material roles ----
val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f
val ColorScheme.recovery: Color get() = secondary
val ColorScheme.sleep: Color get() = primary
val ColorScheme.activity: Color get() = tertiary
val ColorScheme.body: Color get() = lerp(error, primary, 0.38f)
val ColorScheme.mind: Color get() = lerp(primary, tertiary, 0.55f)
val ColorScheme.bad: Color get() = error
/** Positive and caution states have no Material role, so they are fixed semantic hues tuned for each brightness. */
val ColorScheme.good: Color get() = if (isDark) Color(0xFF22F0A8) else Color(0xFF00B86B)
val ColorScheme.warn: Color get() = if (isDark) Color(0xFFFFC21A) else Color(0xFFE08A00)

@Composable
@ReadOnlyComposable
fun Category.color(): Color = with(MaterialTheme.colorScheme) {
    when (this@color) {
        Category.RECOVERY -> recovery
        Category.SLEEP -> sleep
        Category.DAYTIME -> warn
        Category.ACTIVITY -> activity
        Category.BODY -> body
        Category.MIND -> mind
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Sans = FontFamily(
    listOf(300 to FontWeight.Light, 400 to FontWeight.Normal, 500 to FontWeight.Medium, 600 to FontWeight.SemiBold, 700 to FontWeight.Bold).map { (w, fw) ->
        Font(R.font.quicksand, fw, variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private val LoopTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.BLACK -> true
}

/** True when this device can supply Material You colours. */
val dynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Material "black": the dark scheme with true-black surfaces, lightly tinted by the palette so cards still separate. */
private fun ColorScheme.asBlack(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(Color.Black, primary, 0.04f), surfaceContainer = lerp(Color.Black, primary, 0.08f),
    surfaceContainerHigh = lerp(Color.Black, primary, 0.13f), surfaceContainerHighest = lerp(Color.Black, primary, 0.19f),
    surfaceVariant = lerp(Color.Black, primary, 0.14f), outlineVariant = lerp(Color.Black, primary, 0.24f),
)

/** The scheme a preset produces; SYSTEM falls back to Vibrant where Material You isn't available. */
@Composable
fun paletteScheme(palette: Palette, dark: Boolean, black: Boolean = false): ColorScheme {
    val ctx = LocalContext.current
    val base = if (palette == Palette.SYSTEM && dynamicColorSupported) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val seed = seeds.getValue(if (palette == Palette.SYSTEM) Palette.VIBRANT else palette)
        if (dark) darkScheme(seed) else lightScheme(seed)
    }
    return if (dark && black) base.asBlack() else base
}

@Composable
fun LoopTheme(dark: Boolean, palette: Palette, black: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = paletteScheme(palette, dark, black), typography = LoopTypography, content = content)
}
