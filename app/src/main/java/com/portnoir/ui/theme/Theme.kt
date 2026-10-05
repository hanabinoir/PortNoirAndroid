package com.portnoir.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StatusOk = Color(0xFF3DDC97)
val StatusBusy = Color(0xFFF2B84B)
val StatusOffline = Color(0xFF5B6070)

private val Noir = darkColorScheme(
    primary = Color(0xFFC8577A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2535),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondaryContainer = Color(0xFF3A2430),
    onSecondaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFE7E9EE),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFE7E9EE),
    surfaceVariant = Color(0xFF1C1F26),
    onSurfaceVariant = Color(0xFF8D93A1),
    surfaceContainer = Color(0xFF15171C),
    surfaceContainerHigh = Color(0xFF1C1F26),
    outline = Color(0xFF2A2E37),
)

/** Dark only, matching the Mac app. */
@Composable
fun PortNoirTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Noir, content = content)
