package me.manga.kira.navigation.routes

import androidx.compose.runtime.RememberObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.presentation.complaint.ComplaintDetailIntent
import me.manga.kira.presentation.complaint.ComplaintDetailState
import me.manga.kira.presentation.complaint.ComplaintDetailViewModel
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import org.koin.core.Koin

/** One modal owner at a time, including an action whose UI is hidden while its canceled work drains. */
internal sealed interface ComplaintBackendActionSlot {
    class Action(val opening: ComplaintBackendActionOpening) : ComplaintBackendActionSlot

    class Recovery(val opening: ComplaintBackendRequestOpening) : ComplaintBackendActionSlot
}

/** UI-thread lifetime owner only. It neither runs mutations nor owns/closes the candidate graph. */
@Suppress("TooManyFunctions") // Small allocation, identity and RememberObserver guards form one lifetime.
internal class ComplaintBackendActionHostOwner(
    private val candidate: Koin,
    private val detail: ComplaintDetailViewModel,
) : RememberObserver {
    private val slot = MutableStateFlow<ComplaintBackendActionSlot?>(null)
    val state = slot.asStateFlow()
    private var retired = false
    private var changing = false

    val canSelect: Boolean get() = !retired && !changing && slot.value == null

    fun canOpen(action: ComplaintBackendAction, target: ComplaintDetail.Owned): Boolean =
        canSelect && detail.state.value.actionTarget() === target && action.accepts(target)

    fun open(action: ComplaintBackendAction, target: ComplaintDetail.Owned) {
        if (!canOpen(action, target)) return
        changing = true
        try {
            val opening = ComplaintBackendActionOpening(candidate, action, target)
            if (retired) {
                opening.close()
            } else {
                slot.value = ComplaintBackendActionSlot.Action(opening)
                // Retire the read selection, not the captured action. Another action requires a new explicit read.
                detail.submit(ComplaintDetailIntent.Close)
            }
        } finally {
            changing = false
        }
    }

    /** Only the existing VM's drained effect may call this, never dismissal or its early CLOSED state. */
    fun actionFinished(opening: ComplaintBackendActionOpening, openRecovery: Boolean) {
        val current = slot.value as? ComplaintBackendActionSlot.Action ?: return
        if (retired || changing || current.opening !== opening) return
        changing = true
        try {
            opening.close()
            detail.submit(ComplaintDetailIntent.Close)
            slot.value = null
            if (openRecovery && !retired) createRecoveryOpening()
        } finally {
            changing = false
        }
    }

    private fun createRecoveryOpening() {
        checkComplaintBackendActionBindings(candidate)
        val opening = ComplaintBackendRequestOpening(candidate, SettingsFeedbackEntry.General)
        if (retired) opening.close() else slot.value = ComplaintBackendActionSlot.Recovery(opening)
    }

    fun recoveryFinished(opening: ComplaintBackendRequestOpening) {
        val current = slot.value as? ComplaintBackendActionSlot.Recovery ?: return
        if (retired || changing || current.opening !== opening) return
        changing = true
        try {
            opening.close()
            detail.submit(ComplaintDetailIntent.Close)
            slot.value = null
        } finally {
            changing = false
        }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    fun close() {
        if (retired) return
        retired = true
        val current = slot.value
        slot.value = null
        when (current) {
            is ComplaintBackendActionSlot.Action -> current.opening.close()
            is ComplaintBackendActionSlot.Recovery -> current.opening.close()
            null -> Unit
        }
    }
}

/** Fresh current read shape only. Neither recognition nor matching IDs authorizes a write. */
internal fun ComplaintDetailState.actionTarget(): ComplaintDetail.Owned? {
    if (isLoading || error != null) return null
    val current = detail as? ComplaintDetail.Owned ?: return null
    return current.takeIf { it.item.isContractRecognized && it.item.id == selectedId }
}
