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
        page == Page.ABOUT -> page
        state.result == null -> Page.MAIN
        page == Page.DAYS && !state.cloudsEnabled -> Page.MAIN
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
        when {
            p == Page.ABOUT -> AboutScreen(back)
            p == Page.MAIN || state.result == null ->
                MainScreen(state, vm, scrub, showHint = !dragged, onScrub, openFile, showInOsmAnd) { page = it }
            p == Page.START_TIMES -> StartTimesScreen(state, vm::localTime, { vm.update(state.settings.copy(start = it)) }, back)
            else -> DaysScreen(state, { vm.update(state.settings.copy(date = it)) }, { vm.loadClouds(force = true) }, back)
        }
    }
}
