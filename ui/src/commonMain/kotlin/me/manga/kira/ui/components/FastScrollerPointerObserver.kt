package me.manga.kira.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

// Compose 1.11.1 may deduplicate moves when pointer and thumb travel equally.
// Observe from the stationary viewport so dispatch continues without consuming events.
internal fun Modifier.observeFastScrollerPointerMoves(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent()
            }
        }
    }
