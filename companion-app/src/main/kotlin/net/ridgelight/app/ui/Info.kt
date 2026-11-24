package net.ridgelight.app.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max

/**
 * A small ⓘ that opens (and closes) a popup with [content]: explanations live here instead of
 * on the page.
 */
@Composable
fun InfoButton(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Tapping the button while the popup is open first dismisses it (outside tap); don't reopen it.
    var closedAt by remember { mutableLongStateOf(0L) }
    Box(modifier) {
        IconButton(
            onClick = { if (SystemClock.uptimeMillis() - closedAt > 300) open = !open },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(AppIcons.Info, "Explain", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            InfoPopup(title, {
                open = false
                closedAt = SystemClock.uptimeMillis()
            }, content)
        }
    }
}

/** The popup itself, below (or above) whatever it is placed in, kept on screen. Tapping it closes it. */
@Composable
fun InfoPopup(title: String?, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Popup(popupPositionProvider = NearAnchor, onDismissRequest = dismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 340.dp).clickable(onClick = dismiss),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                content()
            }
        }
    }
}

/** Text in an info popup. */
@Composable
fun InfoText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

private object NearAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below else max(0, anchorBounds.top - popupContentSize.height)
        return IntOffset(x, y)
    }
}
