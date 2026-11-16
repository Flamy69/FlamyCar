package io.github.flamy69.flamycar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Flame = Color(0xFFFF5A1F)
private val Ember = Color(0xFFFFB59A)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB3360B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574C),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
)

private val DarkColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF5F1600),
    primaryContainer = Color(0xFF872300),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0),
    tertiary = Flame,
    background = Color(0xFF1A110F),
    surface = Color(0xFF1A110F),
)

@Composable
fun FlamyCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
