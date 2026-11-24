package net.ridgelight.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
