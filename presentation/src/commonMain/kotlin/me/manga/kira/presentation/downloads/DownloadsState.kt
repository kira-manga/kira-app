package me.manga.kira.presentation.downloads

import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.mvi.MviState

/**
 * Downloads screen MVI state.
 *
 * Phase 7.x.downloads.foundation rework (presentation slice). Holds the
 * full downloads inventory (`all`), pre-partitioned bucket projections
 * (`active`, `failed`, `completed`), the currently-selected tab index,
 * and an `isLoading` flag covering the gap between subscription and the
 * first emission from
 * [me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase].
 *
 * The state is **flow-driven**: the VM's `init {}` collector projects
 * each upstream `List<DownloadedChapter>` snapshot into a fresh state
 * with `all` set to the snapshot + the three buckets partitioned via
 * `groupBy`-style logic (see [DownloadsViewModel] for the partition
 * rule). No intent reducer mutates the lists directly — Room writes
 * from elsewhere in the app (e.g., the WorkManager-backed downloader
 * progressing through a chapter) propagate through the upstream flow
 * and naturally re-emit, so the screen is reactive without needing
 * an explicit refresh intent.
 *
 * **Why pre-partition into 3 buckets in the VM** (rather than the
 * `:ui` partitioning at render time): the legacy
 * `DownloadsScreenRoute.kt:48-60` partitions in the route file
 * (between VM and screen). Lifting the partition into VM state
 * keeps the `:ui` composable a near-stateless projection over
 * `DownloadsState` — easier to test, no `remember(allDownloads) {
 * allDownloads.filter { ... } }` complexity in the composable.
 * Same posture as [me.manga.kira.presentation.complaint.ComplaintState]
 * pre-computing `filtered` in the VM.
 *
 * **`selectedTab` default = 2**: matches the legacy
 * `DownloadsScreen.kt:105` which defaults to `mutableStateOf(2)`
 * (Completed tab). Preserved exactly to maintain user-visible behaviour
 * on first open.
 *
 * Contract §6 SRP: one rule — "what the Downloads screen renders right
 * now". No business logic, no derivation beyond the 3-bucket partition
 * (which is a state-projection rule, not a domain rule).
 *
 * **Audit-trail postscript** (Phase 9.x.downloads.staleKdocSweep.cascade.peers,
 * Task #451, 2026-05-28): two stale line-anchored citations above point into
 * the §352-retired legacy `composeApp/.../features/download/ui/screens/
 * DownloadsScreen.kt`:
 *  - Lines 27-32 (the "Why pre-partition into 3 buckets in the VM" paragraph)
 *    cite "the legacy `DownloadsScreenRoute.kt:48-60` partitions in the route
 *    file (between VM and screen)" — the cited route file was retired in
 *    Phase 9.x.downloads.legacyui.retire (§352); verified by a filesystem
 *    check returning zero hits for that path.
 *  - Lines 36-39 (the "`selectedTab` default = 2" paragraph) cite "matches
 *    the legacy `DownloadsScreen.kt:105` which defaults to `mutableStateOf(2)`
 *    (Completed tab)" — same retired file, same §352 retire event.
 * The design rationales stand on their own merits — lifting the partition
 * into VM state remains the cleaner SRP split regardless of which legacy
 * precedent originally justified it; the `selectedTab = 2` default remains
 * a deliberate choice to preserve the user-visible "open on Completed tab"
 * behaviour regardless of which legacy file documented that posture. Original
 * §253-era prose preserved verbatim per the audit-trail-preservation
 * convention — the line-anchored citations are historical record of the
 * design lineage; the state continues to project correctly through the
 * legacy retire.
 */
data class DownloadsState(
    val isLoading: Boolean = true,
    val all: List<DownloadedChapter> = emptyList(),
    val active: List<DownloadedChapter> = emptyList(),
    val failed: List<DownloadedChapter> = emptyList(),
    val completed: List<DownloadedChapter> = emptyList(),
    val selectedTab: Int = 2,
) : MviState
