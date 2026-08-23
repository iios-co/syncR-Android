package com.syncr.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = NightPrimary,
    secondary = NightSecondary,
    tertiary = NightAccent,
    background = NightBackground,
    surface = NightSurface,
    surfaceVariant = NightSurface2,
    onPrimary = NightBackground,
    onSecondary = NightBackground,
    onTertiary = NightBackground,
    onBackground = NightText,
    onSurface = NightText,
    onSurfaceVariant = NightTextMuted,
    outline = NightBorder,
    error = NightDanger,
    errorContainer = NightDanger.copy(alpha = 0.2f),
    onError = NightBackground,
)

private val LightColorScheme = lightColorScheme(
    primary = DayPrimary,
    secondary = DaySecondary,
    tertiary = DayAccent,
    background = DayBackground,
    surface = DaySurface,
    surfaceVariant = DaySurface2,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = DayText,
    onSurface = DayText,
    onSurfaceVariant = DayTextMuted,
    outline = DayBorder,
    error = DayDanger,
    errorContainer = DayDanger.copy(alpha = 0.15f),
    onError = Color.White,
)

@Composable
fun SyncRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
