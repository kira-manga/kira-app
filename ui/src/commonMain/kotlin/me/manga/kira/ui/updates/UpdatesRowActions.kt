package me.manga.kira.ui.updates

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.details_mark_read
import me.manga.kira.ui.generated.resources.details_mark_unread
import me.manga.kira.ui.generated.resources.details_more_options
import org.jetbrains.compose.resources.stringResource

/** Fresh, already entry-bound UI callbacks shared by the row's independent controls. */
internal data class UpdatesRowActions(
    val onChapterClick: () -> Unit,
    val onMangaClick: () -> Unit,
    val onMarkReadClick: () -> Unit,
    val onDownloadClick: () -> Unit,
    val onDeleteClick: () -> Unit,
)

@Composable
internal fun Modifier.updatesEntryActions(
    isRead: Boolean,
    actions: UpdatesRowActions,
): Modifier {
    val readLabel = readActionLabel(isRead)
    val deleteLabel = stringResource(Res.string.delete)
    return semantics {
        customActions =
            listOf(
                CustomAccessibilityAction(readLabel) {
                    actions.onMarkReadClick()
                    true
                },
                CustomAccessibilityAction(deleteLabel) {
                    actions.onDeleteClick()
                    true
                },
            )
    }
}

// Unit Composables intentionally use Compose's PascalCase naming convention.
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun UpdatesRowOverflow(
    isRead: Boolean,
    actions: UpdatesRowActions,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        KiraIconButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = stringResource(Res.string.details_more_options),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            UpdatesRowMenuItems(isRead, actions, onDismiss = { expanded = false })
        }
    }
}

// Unit Composables intentionally use Compose's PascalCase naming convention.
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun UpdatesRowMenuItems(
    isRead: Boolean,
    actions: UpdatesRowActions,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(readActionLabel(isRead)) },
        onClick = {
            onDismiss()
            actions.onMarkReadClick()
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.delete)) },
        onClick = {
            onDismiss()
            actions.onDeleteClick()
        },
    )
}

@Composable
private fun readActionLabel(isRead: Boolean): String =
    stringResource(if (isRead) Res.string.details_mark_unread else Res.string.details_mark_read)

/** One claim spans both directions and any post-release settle callbacks. */
internal class UpdatesSwipeActions(
    private val onToggleRead: () -> Unit,
    private val onRequestDelete: () -> Unit,
) {
    private var sequenceActive = false
    private var armed = false
    private var claimed = false

    fun beginSequence(physicallyNeutral: Boolean) {
        armed = !sequenceActive && physicallyNeutral
        if (armed) claimed = false
        sequenceActive = true
    }

    fun endSequence(released: Boolean) {
        sequenceActive = false
        // A release may qualify on the subsequent fling; coroutine cancellation must not.
        if (!released) armed = false
    }

    fun cancel() {
        sequenceActive = false
        armed = false
    }

    fun confirm(target: SwipeToDismissBoxValue): Boolean {
        if (armed && !claimed && target != SwipeToDismissBoxValue.Settled) {
            claimed = true
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleRead()
                SwipeToDismissBoxValue.EndToStart -> onRequestDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }
        return false
    }
}

@Composable
internal fun rememberUpdatesSwipeActions(
    onToggleRead: () -> Unit,
    onRequestDelete: () -> Unit,
): UpdatesSwipeActions {
    val latestToggle by rememberUpdatedState(onToggleRead)
    val latestDelete by rememberUpdatedState(onRequestDelete)
    return remember { UpdatesSwipeActions({ latestToggle() }, { latestDelete() }) }
}

internal fun Modifier.observeUpdatesSwipeSequence(
    state: SwipeToDismissBoxState,
    actions: UpdatesSwipeActions,
): Modifier =
    pointerInput(state, actions) {
        try {
            awaitEachGesture { observeUpdatesSwipePointers(state, actions) }
        } finally {
            actions.cancel()
        }
    }

private suspend fun AwaitPointerEventScope.observeUpdatesSwipePointers(
    state: SwipeToDismissBoxState,
    actions: UpdatesSwipeActions,
) {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    actions.beginSequence(state.isNeutralForUpdatesSwipe())
    var released = false
    try {
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
        } while (event.changes.any { it.pressed })
        released = true
    } finally {
        actions.endSequence(released)
    }
}

private fun SwipeToDismissBoxState.isNeutralForUpdatesSwipe(): Boolean =
    try {
        requireOffset() == 0f
    } catch (_: IllegalStateException) {
        // requireOffset rejects uninitialized anchors; NaN must never arm a new sequence.
        false
    }
