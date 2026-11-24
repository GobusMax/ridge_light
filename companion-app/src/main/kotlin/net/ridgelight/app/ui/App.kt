package net.ridgelight.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ridgelight.app.RidgeLightViewModel

@Composable
fun RidgeLightApp(vm: RidgeLightViewModel, openFile: () -> Unit, showInOsmAnd: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Read only where it is drawn, so dragging does not redraw the whole page.
    val scrub = vm.scrub.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var dragged by rememberSaveable { mutableStateOf(false) }
    val onScrub: (Double) -> Unit = remember(vm) {
        { seconds ->
            dragged = true
            vm.scrubTo(seconds)
        }
    }
    val shown = when {
        state.result == null -> Page.MAIN
        !state.skyEnabled && (page == Page.DAYS || page == Page.SKY) -> Page.MAIN
        else -> page
    }
    BackHandler(enabled = shown != Page.MAIN) { page = Page.MAIN }
    val back = { page = Page.MAIN }

    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            if (targetState != Page.MAIN) (slideInHorizontally { it / 5 } + fadeIn()) togetherWith fadeOut()
            else fadeIn() togetherWith (slideOutHorizontally { it / 5 } + fadeOut())
        },
        label = "page",
    ) { p ->
        val result = state.result
        when {
            p == Page.MAIN || result == null ->
                MainScreen(state, vm, scrub, showHint = !dragged, onScrub, openFile, showInOsmAnd) { page = it }
            p == Page.STRETCHES -> StretchesScreen(state, result, scrub, onScrub, vm::localTime, back)
            p == Page.START_TIMES -> StartTimesScreen(state, vm::localTime, { vm.update(state.settings.copy(start = it)) }, back)
            p == Page.DAYS -> DaysScreen(state, { vm.update(state.settings.copy(date = it)) }, vm::checkSky, back)
            else -> SkyScreen(state, result, scrub, onScrub, vm::checkSky, vm::setSkyEnabled, { page = Page.DAYS }, back)
        }
    }
}
