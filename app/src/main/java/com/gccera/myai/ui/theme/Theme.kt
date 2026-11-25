package com.gccera.myai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.gccera.myai.data.settings.ThemeMode

/** Brand colour: rust. */
val Rust = Color(0xFFB7410E)

/*
 * Material 3 schemes generated from the rust seed #B7410E with Google's material-color-utilities
 * ("fidelity" variant, which keeps the seed's chroma: primaryContainer is exactly the brand rust).
 */
private val RustLight = ColorScheme(
    primary = Color(0xFF922E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7410E),
    onPrimaryContainer = Color(0xFFFFE2D9),
    secondary = Color(0xFF8D4D36),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFDAA8D),
    onSecondaryContainer = Color(0xFF783C26),
    tertiary = Color(0xFF004E9E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF0066CA),
    onTertiaryContainer = Color(0xFFDEE8FF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF251915),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF251915),
    surfaceVariant = Color(0xFFFDDBD0),
    onSurfaceVariant = Color(0xFF58423A),
    outline = Color(0xFF8C7168),
    outlineVariant = Color(0xFFE0C0B5),
    inverseSurface = Color(0xFF3B2D29),
    inverseOnSurface = Color(0xFFFFEDE8),
    inversePrimary = Color(0xFFFFB59C),
    surfaceDim = Color(0xFFEDD5CE),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EC),
    surfaceContainer = Color(0xFFFFE9E3),
    surfaceContainerHigh = Color(0xFFFBE3DC),
    surfaceContainerHighest = Color(0xFFF5DDD6),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF922E00),
)

private val RustDark = ColorScheme(
    primary = Color(0xFFFFB59C),
    onPrimary = Color(0xFF5C1A00),
    primaryContainer = Color(0xFFB7410E),
    onPrimaryContainer = Color(0xFFFFE2D9),
    secondary = Color(0xFFFFB59C),
    onSecondary = Color(0xFF54210D),
    secondaryContainer = Color(0xFF703621),
    onSecondaryContainer = Color(0xFFF2A185),
    tertiary = Color(0xFFAAC7FF),
    onTertiary = Color(0xFF002F64),
    tertiaryContainer = Color(0xFF0066CA),
    onTertiaryContainer = Color(0xFFDEE8FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C110D),
    onBackground = Color(0xFFF5DDD6),
    surface = Color(0xFF1C110D),
    onSurface = Color(0xFFF5DDD6),
    surfaceVariant = Color(0xFF58423A),
    onSurfaceVariant = Color(0xFFE0C0B5),
    outline = Color(0xFFA78A81),
    outlineVariant = Color(0xFF58423A),
    inverseSurface = Color(0xFFF5DDD6),
    inverseOnSurface = Color(0xFF3B2D29),
    inversePrimary = Color(0xFFA93702),
    surfaceDim = Color(0xFF1C110D),
    surfaceBright = Color(0xFF453631),
    surfaceContainerLowest = Color(0xFF160C08),
    surfaceContainerLow = Color(0xFF251915),
    surfaceContainer = Color(0xFF291D18),
    surfaceContainerHigh = Color(0xFF352722),
    surfaceContainerHighest = Color(0xFF40312D),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFFFB59C),
)

/**
 * @param dynamicColor use the wallpaper-based palette on Android 12+ instead of the rust brand theme
 *   (off by default).
 */
@Composable
fun MyAiTheme(themeMode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> RustDark
        else -> RustLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}
