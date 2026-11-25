package com.gccera.myai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.gccera.myai.data.settings.ThemeMode

private val Indigo = Color(0xFF3F51B5)
private val LightColors = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF), onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF5B5D72), tertiary = Color(0xFF77536D),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF2A3CA2), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD), tertiary = Color(0xFFE6BAD7),
)

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
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
