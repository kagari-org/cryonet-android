package org.kagari.cryonet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1B4B8F),
        secondary = Color(0xFF3A6EA5),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9CC7FF),
        secondary = Color(0xFF7FD1FF),
    )

@Composable
fun CryonetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
