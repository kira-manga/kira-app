package me.manga.kira.navigation.routes

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.manga.kira.di.ComplaintBackendGraph
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackViewModel
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * One explicit dialog opening owns one ordinary lifecycle store, never the shared backend graph.
 * Called on the UI thread, not from recomposition. Clearing cancels only this VM; it neither erases
 * pending records nor asserts synchronous completion of the VM's cancellation-safe cleanup.
 */
internal class ComplaintBackendRequestOpening(
    candidate: Koin,
    entry: SettingsFeedbackEntry,
) {
    private val store = ViewModelStore()
    val viewModel: SettingsFeedbackViewModel

    init {
        val graph = candidate.get<ComplaintBackendGraph>()
        check(candidate.get<ComplaintReportRepository>() === graph.reports) {
            "Complaint candidate report binding differs"
        }
        check(candidate.get<ComplaintListRepository>() === graph.history) {
            "Complaint candidate history binding differs"
        }
        val factory =
            viewModelFactory {
                initializer { candidate.get<SettingsFeedbackViewModel> { parametersOf(entry) } }
            }
        viewModel = ViewModelProvider.create(store, factory)[SettingsFeedbackViewModel::class]
    }

    fun close() = store.clear()
}
