package net.ridgelight.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The few Material icons the app needs (24 dp paths), without the icon library. */
object AppIcons {
    val Back: ImageVector = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val More: ImageVector = icon(
        "More",
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
            "M12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
    )
    val Chevron: ImageVector = icon("Chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = true)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
