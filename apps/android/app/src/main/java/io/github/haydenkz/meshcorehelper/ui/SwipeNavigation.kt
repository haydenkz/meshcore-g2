package io.github.haydenkz.meshcorehelper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Horizontal touch slop leaves vertical scrolling, selection and taps to the children. */
internal fun Modifier.swipeNavigation(onPrevious: (() -> Unit)?, onNext: (() -> Unit)?): Modifier = composed {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val alpha by animateFloatAsState(1f - (abs(drag) / threshold).coerceIn(0f, 1f) * .3f, tween(90), label = "swipe fade")
    graphicsLayer { this.alpha = alpha }
        .pointerInput(threshold) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragCancel = { drag = 0f },
                onDragEnd = {
                    val action = if (drag >= threshold) previous else if (drag <= -threshold) next else null
                    drag = 0f
                    action?.invoke()
                },
            ) { change, amount ->
                val proposed = drag + amount
                if ((proposed > 0 && previous != null) || (proposed < 0 && next != null)) {
                    change.consume()
                    drag = proposed
                }
            }
        }
}
