package com.tvfilebridge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = BackgroundDark,
    primaryContainer = TealPrimaryContainerDark,
    onPrimaryContainer = OnSurfaceDark,
    secondary = AmberSecondaryDark,
    onSecondary = BackgroundDark,
    secondaryContainer = AmberSecondaryContainerDark,
    onSecondaryContainer = OnSurfaceDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMutedDark,
    outline = OutlineDark,
    error = ErrorDark,
)

private val LightColors = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = SurfaceLight,
    primaryContainer = TealPrimaryContainerLight,
    onPrimaryContainer = OnSurfaceLight,
    secondary = AmberSecondaryLight,
    onSecondary = SurfaceLight,
    secondaryContainer = AmberSecondaryContainerLight,
    onSecondaryContainer = OnSurfaceLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceMutedLight,
    outline = OutlineLight,
    error = ErrorLight,
)

@Composable
fun TvFileBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TvFileBridgeTypography,
        content = content,
    )
}
