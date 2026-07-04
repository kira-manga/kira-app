package me.manga.kira.domain.usecase.updates

import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.repository.UpdatesRepository

/**
 * Toggle a single chapter-update entry's read flag (the name "mark as read" is historical — the
 * underlying SQL is `isRead = NOT isRead`, so this is non-idempotent: invoking it on an
 * already-read entry flips it back to UNREAD).
 *
 * Phase 7.x.updates rework. The rework `UpdatesViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user taps the per-row "Mark read" button or swipes the
 * row. Fire-and-forget: the upstream
 * [me.manga.kira.domain.usecase.updates.ObserveUpdatesUseCase] flow re-emits with the entry's
 * flipped `isRead` once the Room transaction commits. Swipe-to-toggle is intentional (native
 * parity): the `:ui` swipe dispatch has no `!isRead` guard, so a read row swiped right re-marks
 * it unread.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [UpdatesRepository.markAsRead]". The legacy
 * facade method is `NotificationRepository.markAsRead(id: Long)` where the `id` parameter is
 * the **chapter id** (the facade body delegates to
 * `LibraryDeo.markChapterAndNotificationRead(chapterId)`, which runs
 * `UPDATE notifications SET isRead = NOT isRead WHERE chapterId = :chapterId` + the
 * corresponding `saved_chapters` flip — cross-table consistency between the notification's
 * read-flag and the underlying chapter's read-flag). The naming in the legacy facade is
 * misleading but verified at every call site (legacy `UpdatesScreen` passes
 * `notification.chapterId`). The rework `:data` impl forwards `legacy.markAsRead(entry.chapterId)`.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [DeleteUpdateEntryUseCase] — the VM depends on a stable use case interface, not on a
 * repository method (DIP); future composition (e.g., propagate the read-state to a sync server,
 * or emit an analytics event) lives here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `updatesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster16.staleKdocSweep.cascade,
 * Task #472, 2026-05-28): one stale citation into the §310-retired
 * legacy `:shared/.../features/notifications/ui/screens/UpdatesScreen.kt`
 * appears above:
 *  - Lines 21-24 (chapter-id facade-naming verification): "The naming
 *    in the legacy facade is misleading but verified at every call site
 *    (legacy `UpdatesScreen` passes `notification.chapterId`). The
 *    rework `:data` impl forwards `legacy.markAsRead(entry.chapterId)`".
 *    The legacy `:shared/.../features/notifications/ui/screens/
 *    UpdatesScreen.kt` was retired in Phase 9.aa.updates.legacy_retire
 *    (§310 sweep — "delete unreachable legacy UpdatesScreen + UpdateItem
 *    + NotificationsUiState"); verified by filesystem check returning
 *    zero hits. The chapter-id semantics of the legacy
 *    `NotificationRepository.markAsRead(id)` facade are STILL LIVE —
 *    `LibraryDeo.markChapterAndNotificationRead(chapterId)` continues
 *    to drive the cross-table flip — and the rework `:data` impl
 *    continues to forward `legacy.markAsRead(entry.chapterId)`. The
 *    verification-at-call-site framing is historical (the legacy call
 *    site is gone), but the chapter-id-not-notification-id rule remains
 *    correct and load-bearing — without it the rework would pass
 *    `entry.id` (the notification row id) instead of `entry.chapterId`
 *    and the cross-table consistency would silently break.
 * The SRP (single-rule pass-through) + DIP-via-use-case (VM depends on
 * stable use-case interface) + factory-Koin-lifecycle rationales all
 * stand on their own merits past the §310 retire. The rework
 * MarkUpdateAsReadUseCase remains LIVE as the canonical mark-read
 * mutator for the rework UpdatesViewModel, documented inline above and
 * via the §§240 + §289 KDocs. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citation
 * is historical record of the design lineage including the legacy-call-
 * site verification anchor that was subsequently retired.
 */
class MarkUpdateAsReadUseCase(
    private val repository: UpdatesRepository,
) {
    suspend operator fun invoke(entry: UpdateEntry) = repository.markAsRead(entry)
}
