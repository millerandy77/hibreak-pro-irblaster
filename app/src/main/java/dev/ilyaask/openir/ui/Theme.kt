package dev.ilyaask.openir.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7CFFB2),
    onPrimary = Color(0xFF003821),
    background = Color(0xFF0B1120),
    surface = Color(0xFF111A2E),
)
private val Light = lightColorScheme(
    primary = Color(0xFF00875A),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun OpenIrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
