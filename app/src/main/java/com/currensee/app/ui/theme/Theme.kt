package com.currensee.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CurrenSeeColors = lightColorScheme(
    primary = ActionBlue,
    onPrimary = PanelWhite,
    background = PanelWhite,
    onBackground = Ink,
    surface = PanelWhite,
    onSurface = Ink,
    surfaceVariant = SoftGray,
    onSurfaceVariant = SupportingText,
    outline = OutlineGray,
    inverseSurface = Ink,
    inverseOnSurface = PanelWhite
)

@Composable
fun CurrenSeeTheme(content: @Composable () -> Unit) {
    // Palet tetap agar kontras tidak berubah mengikuti wallpaper atau mode sistem.
    MaterialTheme(
        colorScheme = CurrenSeeColors,
        typography = Typography,
        content = content
    )
}
