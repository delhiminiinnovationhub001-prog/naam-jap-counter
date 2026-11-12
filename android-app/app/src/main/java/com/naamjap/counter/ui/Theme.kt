package com.naamjap.counter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Saffron = Color(0xFFE65100)
val SaffronLight = Color(0xFFFFB74D)
val VoiceGreen = Color(0xFF3ECF6E)
val LevelBlue = Color(0xFF2AA7C9)
val Danger = Color(0xFFE5484D)

private val LightColors = lightColorScheme(
    primary = Saffron,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0B2),
    onPrimaryContainer = Color(0xFF3E1A00),
    secondary = Color(0xFF8D5A2B),
    secondaryContainer = Color(0xFFFFEBD6),
    tertiaryContainer = Color(0xFFFFF3C4),
    onTertiaryContainer = Color(0xFF3A2E00),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFF5E9DE),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = SaffronLight,
    onPrimary = Color(0xFF3E1A00),
    primaryContainer = Color(0xFF6B2E00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE6BE95),
    secondaryContainer = Color(0xFF4A3424),
    tertiaryContainer = Color(0xFF4D4100),
    onTertiaryContainer = Color(0xFFFFE97A),
    background = Color(0xFF1A1410),
    surface = Color(0xFF1A1410),
    surfaceVariant = Color(0xFF2A211A),
    error = Danger,
)

@Composable
fun JapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
