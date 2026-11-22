package net.ridgelight.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.gpx.LightStyle

fun lightColor(light: Light) = Color(LightStyle.argb(light))

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F5D8C),
    secondary = Color(0xFFB7791F),
    background = Color(0xFFFBF8F2),
    surface = Color(0xFFFBF8F2),
    surfaceVariant = Color(0xFFEFEADF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CC2EA),
    secondary = Color(0xFFF0C070),
)

@Composable
fun RidgeLightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}
