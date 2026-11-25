package net.ridgelight.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyLight

fun lightColor(light: Light) = Color(LightStyle.argb(light))

/** Terrain and clouds combined: the terrain colours, plus muted amber and grey for clouded sun. */
fun skyLightColor(light: SkyLight): Color = when (light) {
    SkyLight.SUN -> lightColor(Light.SUN)
    SkyLight.SUN_AND_CLOUDS -> Color(0xFFD9BC7E)
    SkyLight.CLOUDED -> Color(0xFF9AA3AD)
    SkyLight.SHADE -> lightColor(Light.TERRAIN_SHADE)
    SkyLight.DARK -> lightColor(Light.BELOW_HORIZON)
    SkyLight.UNKNOWN -> lightColor(Light.UNKNOWN).copy(alpha = 0.5f)
}

/** How much of the sun's way the clouds block, as grey of increasing strength; clear is empty. */
fun skyAlpha(sky: Sky): Float = when (sky) {
    Sky.CLEAR -> 0f
    Sky.THIN_CLOUD -> 0.18f
    Sky.PARTLY_CLOUDY -> 0.42f
    Sky.OVERCAST -> 0.68f
    Sky.IN_CLOUD -> 0.95f
}

// Warm paper in light, warm charcoal in dark. Text colours reach at least 4.5:1 on the cards.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F5D8C),
    secondary = Color(0xFFB7791F),
    background = Color(0xFFFBF8F2),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFBF8F2),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEFEADF),
    onSurfaceVariant = Color(0xFF4E4639),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EC),
    surfaceContainer = Color(0xFFF3EEE6),
    surfaceContainerHigh = Color(0xFFEEE9E0),
    surfaceContainerHighest = Color(0xFFE8E2D8),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CC2EA),
    secondary = Color(0xFFF0C070),
    background = Color(0xFF1A1815),
    onBackground = Color(0xFFEAE3D9),
    surface = Color(0xFF1A1815),
    onSurface = Color(0xFFEAE3D9),
    surfaceVariant = Color(0xFF38332B),
    onSurfaceVariant = Color(0xFFD0C6B6),
    surfaceContainerLowest = Color(0xFF141210),
    surfaceContainerLow = Color(0xFF22201C),
    surfaceContainer = Color(0xFF2A2621),
    surfaceContainerHigh = Color(0xFF332F29),
    surfaceContainerHighest = Color(0xFF3D3831),
)

/** Sun between clouds as text: the legend's amber, darkened (light) or lightened (dark) to read on the cards. */
val partlyText: Color
    @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF8A6420) else Color(0xFFE2BE76)

@Composable
fun RidgeLightTheme(content: @Composable () -> Unit) {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val words = remember(resources, configuration) { Words(resources) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme) {
        CompositionLocalProvider(LocalWords provides words, content = content)
    }
}
