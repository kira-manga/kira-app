package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.ui.settings.feedback.SettingsFeedbackDialog
import org.koin.core.Koin

/** Candidate route lifetime only; all draft, retry, setup and recovery behavior stays in the existing VM. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendRequestHost(
    candidate: Koin,
    onOpenUrl: (String) -> Unit,
    content: @Composable (onRequest: (SettingsFeedbackEntry) -> Unit) -> Unit,
) {
    val owner = remember(candidate) { ComplaintBackendRequestHostOwner(candidate) }
    ComplaintBackendRequestHost(owner, onOpenUrl) { content(owner::request) }
}

/** General Settings also supplies the same owner's explicit history-click callback. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendRequestHost(
    owner: ComplaintBackendRequestHostOwner,
    onOpenUrl: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val history = owner.historyOpening
    if (history == null) {
        content()
    } else {
        key(history) {
            ComplaintBackendDetailRoute(
                candidate = history.candidate,
                onBack = { owner.historyClosed(history) },
                onOpenUrl = onOpenUrl,
            )
        }
    }
    owner.requestOpening?.let { current ->
        SettingsFeedbackDialog(
            viewModel = current.viewModel,
            onClosed = { owner.requestClosed(current) },
            onOpenUrl = onOpenUrl,
        )
    }
}

/** UI-thread route lifetime only; exposes the actual callback guards to the existing ownership tests. */
internal class ComplaintBackendRequestHostOwner(private val candidate: Koin) : RememberObserver {
    /** Identity-only display key: the existing detail route owns its ViewModel stores. */
    class History(val candidate: Koin)

    var requestOpening by mutableStateOf<ComplaintBackendRequestOpening?>(null)
        private set
    var historyOpening by mutableStateOf<History?>(null)
        private set
    private var retired = false
    private var changing = false
    private val canOpen: Boolean get() = !retired && !changing && requestOpening == null && historyOpening == null

    fun request(entry: SettingsFeedbackEntry) {
        if (!canOpen) return
        changing = true
        try {
            val opening = ComplaintBackendRequestOpening(candidate, entry)
            if (retired) opening.close() else requestOpening = opening
        } finally {
            changing = false
        }
    }

    fun openHistory() {
        if (canOpen) historyOpening = History(candidate)
    }

    /** Called only by the existing VM's drained Closed effect, never by a dismiss click or UI flag. */
    fun requestClosed(opening: ComplaintBackendRequestOpening) {
        if (retired || changing || requestOpening !== opening) return
        changing = true
        try {
            opening.close()
            requestOpening = null
        } finally {
            changing = false
        }
    }

    fun historyClosed(opening: History) {
        if (!retired && historyOpening === opening) historyOpening = null
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    fun close() {
        if (retired) return
        retired = true
        val current = requestOpening
        requestOpening = null
        historyOpening = null
        current?.close()
    }
}
