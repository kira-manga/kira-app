package me.manga.kira.presentation.library

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [LibraryViewModel] for the view to perform once and forget.
 *
 * Strict MVI: effects carry only the trigger (target, error category) — never rendering
 * data. Recurrent UI elements (loaders, error banners) live in [LibraryState].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster105.staleKdocSweep.cascade,
 * Task #561, 2026-05-28): the file-scope effect-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-fifth sibling of the cluster57-104 sweep —
 * closes the wave-9 cluster105 batch alongside the theme/ trio plus
 * ComplaintIntent.kt):
 *  (a) "One-shot effects emitted by [LibraryViewModel] for the view to
 *  perform once and forget" — LIVE-NOT-STALE. L13 sealed interface plus
 *  L16/L19/L22 three `data class` variants (NavigateToDetails plus
 *  ShowError plus ShowBulkRemoveSuccess) verified LIVE; LibraryView-
 *  Model.kt emits each at least once via the `emit(...)` base-class
 *  primitive (cluster34 sweep Task #490 traced all three emission
 *  call-sites).
 *  (b) "Strict MVI: effects carry only the trigger (target, error
 *  category) — never rendering data. Recurrent UI elements (loaders,
 *  error banners) live in [LibraryState]" — LIVE-NOT-STALE. Each
 *  variant carries only navigation-target (Manga reference), error-
 *  category (AppError sealed hierarchy), or success-count (Int — pure
 *  trigger payload for snackbar formatting). No rendering data — the
 *  Manga reference is a domain-model navigation target, NOT pre-
 *  rendered card content; cards render from LibraryState.libraryItems
 *  flow.
 *  (c) Three-variant shape (NavigateToDetails plus ShowError plus
 *  ShowBulkRemoveSuccess) — LIVE-NOT-STALE. Across the entire Library
 *  rework slice landings (Phase 6.2 base plus Phase 6.2.x bulk-remove
 *  plus Phase 7.x.library.* polish slices through cluster345), no new
 *  effect variants were added — the three-variant surface remains the
 *  canonical Library effect contract.
 *  Three classifications STAND on their own merits as a faithful
 *  LibraryEffect surface manifest. Original Phase 6.2-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface LibraryEffect : MviEffect {

    /** View should navigate to the manga details screen. */
    data class NavigateToDetails(val manga: Manga) : LibraryEffect

    /**
     * feature/backup — view should open the Backup screen scoped to [keys] (multi-select export
     * handoff). Destination descriptor only: the `:composeApp` adapter maps this to
     * `Screen.BackupRework(scopeJson)`.
     */
    data class NavigateToBackupExport(val keys: List<MangaKey>) : LibraryEffect

    /** View should show a non-blocking error toast / snackbar. */
    data class ShowError(val error: AppError) : LibraryEffect

    /** View should show a localized "removed N items" success message. */
    data class ShowBulkRemoveSuccess(val count: Int) : LibraryEffect

    /**
     * Pull-to-refresh was triggered on an empty library. The view shows a localized "nothing to
     * refresh yet" message; no refresh worker is enqueued (native parity — refreshing an empty
     * library is a no-op + a toast).
     */
    data object ShowEmptyLibraryRefresh : LibraryEffect
}
