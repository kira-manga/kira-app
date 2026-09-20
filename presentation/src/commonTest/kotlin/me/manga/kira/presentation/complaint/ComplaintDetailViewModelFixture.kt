package me.manga.kira.presentation.complaint

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistoryPlatform
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerFields
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.usecase.complaint.LoadComplaintDetailUseCase
import kotlin.time.Instant

internal class ComplaintDetailViewModelFixture(
    load: suspend (String) -> AppResult<ComplaintDetail>,
) {
    val reader = DetailReader(load)
    private val store = ViewModelStore()
    private val factory =
        viewModelFactory {
            initializer { ComplaintDetailViewModel(LoadComplaintDetailUseCase(reader)) }
        }
    val model: ComplaintDetailViewModel = ViewModelProvider.create(store, factory)[ComplaintDetailViewModel::class]

    fun close() = store.clear()
}

internal class DetailReader(
    var load: suspend (String) -> AppResult<ComplaintDetail>,
) : ComplaintDetailRepository {
    val requests = mutableListOf<String>()

    override suspend fun loadComplaintDetail(id: String): AppResult<ComplaintDetail> {
        requests += id
        return load(id)
    }
}

/** Deliberately completes after cancellation so both old values and failures must be fenced. */
internal class LateDetailRead(
    private val outcome: () -> AppResult<ComplaintDetail>,
) {
    constructor(detail: ComplaintDetail) : this({ AppResult.Success(detail) })

    val closing = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var cleaned = false
        private set

    suspend fun read(): AppResult<ComplaintDetail> =
        try {
            awaitCancellation()
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                closing.complete(Unit)
                release.await()
                cleaned = true
                outcome()
            }
        }
}

internal fun ownedDetail(id: String = DETAIL_A): ComplaintDetail.Owned =
    ComplaintDetail.Owned(
        ComplaintOwnerRow.Report(
            fields = detailFields(id),
            type = ComplaintHistoryType.Known(ComplaintType.TECHNICAL),
            subject = "Synthetic private subject",
        ),
    )

private fun detailFields(id: String) =
    ComplaintOwnerFields(
        id = id,
        body = "Synthetic private body",
        status = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN),
        createdAt = DETAIL_TIME,
        updatedAt = DETAIL_TIME,
        version = 1,
        actionTag = "synthetic-read-tag-not-authority",
        appVersion = null,
        platform = ComplaintHistoryPlatform.ANDROID,
        osVersion = null,
        manufacturer = null,
        deviceModel = null,
        closureReason = null,
    )

internal const val DETAIL_A = "11111111-1111-4111-8111-111111111111"
internal const val DETAIL_B = "22222222-2222-4222-8222-222222222222"
internal const val DETAIL_C = "33333333-3333-4333-8333-333333333333"
internal val DETAIL_TIME: Instant = Instant.parse("2026-09-18T00:00:00Z")
