package com.bringyour.network.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bringyour.network.R
import com.bringyour.network.ui.theme.Black
import com.bringyour.network.ui.theme.Red
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A row that reveals a delete button when swiped left, mimicking iOS list
 * behavior: the swipe only reveals the button, and the user must tap it to
 * delete. Swiping back (or the button tap) closes the row.
 */
@Composable
fun SwipeToRevealRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val revealWidth = 72.dp
    val revealPx = with(LocalDensity.current) { revealWidth.toPx() }

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val close: () -> Unit = {
        scope.launch { offsetX.animateTo(0f) }
    }

    Box(
        modifier = modifier.fillMaxWidth()
    ) {

        // delete button behind the content, revealed on swipe
        Box(
            modifier = Modifier
                .matchParentSize(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(revealWidth)
                    .background(Red)
                    .clickable {
                        onDelete()
                        close()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(id = R.string.remove),
                    tint = Color.White
                )
            }
        }

        // foreground content, draggable horizontally to reveal the button
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(Black)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    onDragStopped = {
                        scope.launch {
                            // settle open past the halfway point, else closed
                            val target = if (offsetX.value < -revealPx / 2f) -revealPx else 0f
                            offsetX.animateTo(target)
                        }
                    }
                )
        ) {
            content()
        }
    }
}
