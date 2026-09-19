package io.github.usernamealreadytakensht.trackstuff.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Fixed indigo palette (the launcher icon's colour) in light and dark. Dynamic (wallpaper) colours are
 * not used so that badges, chips and the icon always match.
 */
private val LightColorScheme = lightColorScheme(
    primary = Indigo40, onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Indigo90, onPrimaryContainer = Indigo10,
    secondary = Teal40, onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Teal90, onSecondaryContainer = Teal10,
    tertiary = Amber40, onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = Amber90, onTertiaryContainer = Amber10,
    background = Neutral99, onBackground = Neutral10,
    surface = Neutral99, onSurface = Neutral10,
    surfaceVariant = Neutral90, onSurfaceVariant = Neutral40,
    surfaceContainer = Neutral95, surfaceContainerHigh = Neutral90,
    outline = Neutral60, outlineVariant = Neutral80,
)

private val DarkColorScheme = darkColorScheme(
    primary = Indigo80, onPrimary = Indigo20,
    primaryContainer = Indigo30, onPrimaryContainer = Indigo90,
    secondary = Teal80, onSecondary = Teal10,
    secondaryContainer = Teal30, onSecondaryContainer = Teal90,
    tertiary = Amber80, onTertiary = Amber10,
    tertiaryContainer = Amber30, onTertiaryContainer = Amber90,
    background = Neutral6, onBackground = Neutral90,
    surface = Neutral6, onSurface = Neutral90,
    surfaceVariant = Neutral20, onSurfaceVariant = Neutral80,
    surfaceContainer = Neutral10, surfaceContainerHigh = Neutral20,
    outline = Neutral60, outlineVariant = Neutral40,
)

@Composable
fun TrackStuffTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
