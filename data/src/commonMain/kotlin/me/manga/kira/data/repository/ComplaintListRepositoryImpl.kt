package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.auth.UserIdProvider
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.presentation.features.complaint.model.Complaint as LegacyComplaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus as LegacyComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType as LegacyComplaintType
import me.manga.kira.presentation.features.complaint.usecase.GetUserComplaintUseCase as LegacyGetUserComplaintUseCase

/** Explicit legacy-only compatibility adapter. Never accepts backend owner rows or identity material. */
class ComplaintListRepositoryImpl(
    private val legacy: LegacyGetUserComplaintUseCase,
    private val userIdProvider: UserIdProvider,
) : ComplaintListRepository {

    override suspend fun loadUserComplaints(): AppResult<ComplaintHistory> = try {
        val userId = userIdProvider.getUserId()
        AppResult.Success(ComplaintHistory.Legacy(PINNED_COMPLAINTS + legacy(userId).map { it.toSummary() }))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AppResult.Failure(AppError.Network.NoConnectivity())
    }

    private fun LegacyComplaint.toSummary(): ComplaintSummary = ComplaintSummary(
        id = id,
        userId = userId,
        type = type.toDomain(),
        subject = subject,
        body = body,
        createdAt = createdAt,
        status = status.toDomain(),
        appVersion = metadata?.get("appVersion")?.toString(),
        reason = metadata?.get("reason")?.toString(),
        replyToId = metadata?.get("replyto")?.toString(),
        osVersion = metadata?.get("osVersion")?.toString(),
        manufacturer = metadata?.get("manufacturer")?.toString(),
    )

    private fun LegacyComplaintStatus.toDomain(): ComplaintStatus = when (this) {
        LegacyComplaintStatus.OPEN -> ComplaintStatus.OPEN
        LegacyComplaintStatus.IN_PROGRESS -> ComplaintStatus.IN_PROGRESS
        LegacyComplaintStatus.RESOLVED -> ComplaintStatus.RESOLVED
        LegacyComplaintStatus.CLOSED -> ComplaintStatus.CLOSED
        LegacyComplaintStatus.PLANNED -> ComplaintStatus.PLANNED
        LegacyComplaintStatus.PINNED -> ComplaintStatus.PINNED
        LegacyComplaintStatus.UNKNOWN -> ComplaintStatus.UNKNOWN
        LegacyComplaintStatus.NOT_PLANNED -> ComplaintStatus.NOT_PLANNED
    }

    private fun LegacyComplaintType.toDomain(): ComplaintType = when (this) {
        LegacyComplaintType.TECHNICAL -> ComplaintType.TECHNICAL
        LegacyComplaintType.LANGUAGES -> ComplaintType.LANGUAGES
        LegacyComplaintType.SITES_ADD -> ComplaintType.SITES_ADD
        LegacyComplaintType.SITE_ERROR -> ComplaintType.SITE_ERROR
        LegacyComplaintType.FEATURES -> ComplaintType.FEATURES
        LegacyComplaintType.CUSTOM -> ComplaintType.CUSTOM
    }
}
