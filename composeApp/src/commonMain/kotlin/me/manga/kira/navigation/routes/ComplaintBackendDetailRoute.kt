package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.manga.kira.ui.complaint.BackendComplaintDetail
import me.manga.kira.ui.complaint.ComplaintScreen
import org.koin.core.Koin

/** Explicit isolated candidate only; no shipping navigation registration, global lookup or write affordance. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendDetailRoute(
    candidate: Koin,
    onBack: () -> Unit,
) {
    // The RememberObserver owner also closes if composition is abandoned before any effect starts.
    val opening = remember(candidate) { ComplaintBackendDetailOpening(candidate) }
    ComplaintScreen(
        viewModel = opening.history,
        onBack = {
            opening.close()
            onBack()
        },
        onBackendRowClick = opening::select,
    )
    BackendComplaintDetail(opening.detail)
}
