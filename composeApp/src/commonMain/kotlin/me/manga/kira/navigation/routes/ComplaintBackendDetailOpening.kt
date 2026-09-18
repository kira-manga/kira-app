package me.manga.kira.navigation.routes

import androidx.compose.runtime.RememberObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.manga.kira.di.ComplaintBackendGraph
import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.usecase.complaint.LoadComplaintDetailUseCase
import me.manga.kira.presentation.complaint.ComplaintDetailIntent
import me.manga.kira.presentation.complaint.ComplaintDetailViewModel
import me.manga.kira.presentation.complaint.ComplaintViewModel
import org.koin.core.Koin

/** UI-thread VM owner for remembered, forgotten and abandoned composition; never the shared graph. */
@Suppress("TooGenericExceptionCaught") // Failed construction still owns any already-resolved ViewModels.
internal class ComplaintBackendDetailOpening(
    candidate: Koin,
) : RememberObserver {
    private val store = ViewModelStore()
    private var closed = false
    val history: ComplaintViewModel
    val detail: ComplaintDetailViewModel

    init {
        val graph = candidate.get<ComplaintBackendGraph>()
        check(candidate.get<ComplaintListRepository>() === graph.history) {
            "Complaint candidate history binding differs"
        }
        check(candidate.get<ComplaintDetailRepository>() === graph.details) {
            "Complaint candidate detail binding differs"
        }
        val factory =
            viewModelFactory {
                initializer { candidate.get<ComplaintViewModel>() }
                initializer { ComplaintDetailViewModel(candidate.get<LoadComplaintDetailUseCase>()) }
            }
        val provider = ViewModelProvider.create(store, factory)
        try {
            history = provider[ComplaintViewModel::class]
            detail = provider[ComplaintDetailViewModel::class]
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    fun select(id: String) {
        val state = history.state.value
        if (closed || state.isLoading) return
        if (state.backendItems.none { it.id == id && it.isContractRecognized }) return
        detail.submit(ComplaintDetailIntent.Select(id))
    }

    fun close() {
        if (closed) return
        closed = true
        store.clear()
    }
}
