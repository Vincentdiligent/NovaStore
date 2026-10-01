package com.novastore.app.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.novastore.app.core.model.AccentPalette
import com.novastore.app.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = EmeraldLight,
    onPrimary = OnEmerald,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = OnEmeraldContainerLight,
    secondary = Amber,
    onSecondary = OnAmber,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = OnAmberContainerLight,
    tertiary = Color(0xFF7D67C7),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4DDFF),
    onTertiaryContainer = Color(0xFF2A1D66),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Paper,
    onBackground = OnPaper,
    surface = Paper,
    onSurface = OnPaper,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = OnPaperVariant,
    surfaceContainerLowest = PaperContainerLowest,
    surfaceContainerLow = PaperContainerLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
    inverseSurface = OnPaper,
    inverseOnSurface = Paper,
    inversePrimary = EmeraldDark,
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = EmeraldDark,
    onPrimary = OnEmerald,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = OnEmeraldContainerDark,
    secondary = Amber,
    onSecondary = OnAmber,
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = OnAmberContainerDark,
    tertiary = Violet,
    onTertiary = Color(0xFF2F2158),
    tertiaryContainer = Color(0xFF3A2D6B),
    onTertiaryContainer = Color(0xFFE2D8FF),
    error = Color(0xFFFF7A70),
    onError = Color(0xFF300604),
    errorContainer = Color(0xFF5C1512),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Night0,
    onBackground = OnNight,
    surface = Night0,
    onSurface = OnNight,
    surfaceVariant = NightVariant,
    onSurfaceVariant = OnNightVariant,
    surfaceContainerLowest = Color(0xFF0A0C0F),
    surfaceContainerLow = Color(0xFF121519),
    surfaceContainer = Night1,
    surfaceContainerHigh = Night2,
    surfaceContainerHighest = Night3,
    surfaceDim = Color(0xFF0B0D10),
    surfaceBright = Color(0xFF2C3138),
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    inverseSurface = OnNight,
    inverseOnSurface = Night0,
    inversePrimary = EmeraldLight,
    scrim = Color(0xFF000000),
)

/** AMOLED variant of [DarkColors] — pure black, battery friendly. */
private fun amoledScheme(accent: NovaAccent) = DarkColors.copy(
    background = Amoled0,
    onBackground = OnNight,
    surface = Amoled0,
    onSurface = OnNight,
    surfaceVariant = AmoledVariant,
    onSurfaceVariant = OnNightVariant,
    surfaceContainerLowest = Amoled0,
    surfaceContainerLow = Amoled1,
    surfaceContainer = Amoled1,
    surfaceContainerHigh = Amoled2,
    surfaceContainerHighest = Amoled3,
    surfaceDim = Amoled0,
    outline = AmoledOutline,
    outlineVariant = AmoledOutlineVariant,
    primary = accent.primaryDark,
    onPrimary = OnEmerald,
    primaryContainer = accent.containerDark,
    onPrimaryContainer = accent.onContainerDark,
)

/** Accent colors exposed to gradients via [LocalNovaAccent]. */
data class NovaAccentColors(val start: Color, val end: Color)

/** The active accent pair; NovaTheme overrides it per user preference. */
val LocalNovaAccent = staticCompositionLocalOf {
    NovaAccentColors(AccentPalettes.EMERALD.start, AccentPalettes.EMERALD.end)
}

/**
 * Nova Store theme — premium Material 3 with selectable theme mode
 * (system / light / dark / AMOLED), selectable accent palette and optional
 * dynamic color (Android 12+).
 */
@Composable
fun NovaTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentPalette = AccentPalette.EMERALD,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val palette = AccentPalettes.of(accent)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        mode == ThemeMode.AMOLED -> amoledScheme(palette)

        darkTheme -> DarkColors.copy(
            primary = palette.primaryDark,
            onPrimary = OnEmerald,
            primaryContainer = palette.containerDark,
            onPrimaryContainer = palette.onContainerDark,
        )

        else -> LightColors.copy(
            primary = palette.primaryLight,
            onPrimary = OnEmerald,
            primaryContainer = palette.containerLight,
            onPrimaryContainer = palette.onContainerLight,
        )
    }

    CompositionLocalProvider(
        LocalNovaAccent provides NovaAccentColors(palette.start, palette.end),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NovaTypography,
            content = content,
        )
    }
}
