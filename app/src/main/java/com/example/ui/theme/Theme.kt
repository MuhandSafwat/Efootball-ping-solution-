package com.example.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = GamePrimaryYellow,
    secondary = GameSecondaryGreen,
    tertiary = GameBlue,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color(0xFF0A0D16),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF1F5F9),
    onSecondary = Color.White,
    onTertiary = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = GamePrimaryYellow,
    secondary = GameSecondaryGreen,
    tertiary = GameBlue,
    background = Color(0xFFF1F5F9),
    surface = Color.White,
    onPrimary = Color(0xFF0A0D16),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF1E293B)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme for gaming consistency by default
    dynamicColor: Boolean = false, // Disable dynamic colors to keep game branding consistent
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
