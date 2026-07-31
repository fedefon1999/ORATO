package com.orato.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DeepTeal = Color(0xFF1B3A4B)
private val SoftSand = Color(0xFFF7F1E8)
private val AccentCoral = Color(0xFFE76F51)
private val AccentGold = Color(0xFFE9C46A)
private val Ink = Color(0xFF14212B)

private val LightColors = lightColorScheme(
    primary = DeepTeal,
    onPrimary = Color.White,
    secondary = AccentCoral,
    onSecondary = Color.White,
    tertiary = AccentGold,
    background = SoftSand,
    onBackground = Ink,
    surface = SoftSand,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8DFD2),
    onSurfaceVariant = Color(0xFF3F4A52),
)

private val DarkColors = darkColorScheme(
    primary = AccentGold,
    onPrimary = Ink,
    secondary = AccentCoral,
    onSecondary = Color.White,
    tertiary = SoftSand,
    background = Color(0xFF0F1A22),
    onBackground = SoftSand,
    surface = Color(0xFF162633),
    onSurface = SoftSand,
    surfaceVariant = Color(0xFF243746),
    onSurfaceVariant = Color(0xFFD5DCE2),
)

@Composable
fun OratoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = OratoTypography,
        content = content,
    )
}
