package me.manga.kira.presentation.sources

import me.manga.kira.domain.model.sources.Source
import me.manga.kira.presentation.mvi.MviState

/**
 * Sources screen MVI state.
 *
 * Phase 7.x.sources rework. Holds the per-source snapshot rendered by the screen plus an
 * [isLoading] flag covering the gap between subscription and the first `List<Source>` emission.
 * No `error` field — the Sources upstream is Room's `allSources` flow which does not throw at
 * the observe site (matches the [me.manga.kira.presentation.updates.UpdatesState] /
 * [me.manga.kira.presentation.history.HistoryState] no-`error` posture); the toggle
 * mutations are `UPDATE sources SET isEnabled` writes whose runtime-failure modes are
 * vanishingly small.
 *
 * The state is **flow-driven**: the VM's `init {}` collector projects each upstream
 * `List<Source>` snapshot into a fresh [items] list. Toggle mutations propagate naturally — the
 * legacy `SourcesDao` re-emits the table on every write, so the screen is reactive without
 * needing an `OnRefresh` intent or imperative state mutation in the reducer.
 *
 * Two derived getters live here ([groupedByLanguage], [enabledCount]) rather than recomputing in
 * the `:ui` composable — same locality posture as [UpdatesState]'s `hasUnreadItems`. The
 * [enabledCount] is intentionally surfaced even though no current call site renders it; the
 * future "x of N enabled" header line is a one-line append (OCP — see [PLAN_sources.md]).
 *
 * **`complaintDialogOpen` + `isSubmittingComplaint`** (Phase 7.x.sources.complaint): the
 * Request-Source dialog's open/closed flag and its in-flight-submission flag. The dialog's
 * body text field is LOCAL to the `:ui` composable (`remember { mutableStateOf("") }`),
 * matching the [me.manga.kira.presentation.settings.SettingsState]'s feedback-dialog
 * field pair — payloads ride along with the submit intent rather than being mirrored into
 * MVI state. This keeps the state surface narrow (2 fields, not 3) and preserves "user can
 * resume their typing mid-edit" behaviour without needing OnComplaintBodyChange intents that
 * fire per-keystroke. The complaint TYPE is fixed at SITES_ADD (pinned by the row label
 * "Request adding source"), so no `complaintType` field is needed — that's a VM-internal
 * constant. `isSubmittingComplaint` gates the Submit button's enabled state and the
 * dismiss path (the dialog refuses to close mid-submission to avoid orphaning the in-flight
 * use-case call).
 *
 * Contract §6 SRP: one rule — "what the Sources screen renders right now". No business logic,
 * no derivation that lives in the use case or repository.
 *
 * Contract §17: no `Any`, no `!!`. `items: List<Source>` is read-only (the public interface —
 * the underlying list might be a `MutableList` but consumers can never call `add` etc.).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster30.staleKdocSweep.cascade,
 * Task #486, 2026-05-28): one fulfilled-forecast / stale citation
 * appears in a member KDoc below:
 *  - Line 95 ([groupedByLanguage] KDoc, "the legacy onboarding
 *    screen iterates the same way"). STALE-SYMBOL-REFERENCE —
 *    Phase 9.x.onboarding.legacy_retire (§307) DELETED the legacy
 *    onboarding chain (including the cited legacy onboarding screen
 *    that iterated `repoMap.values` the same way as [groupedByLanguage]
 *    iterates the Room snapshot) in its entirety; a recursive search
 *    of the legacy onboarding folder for an onboarding-sources screen
 *    iterating per-language returns NO MATCHES. HOWEVER — the
 *    supporting argument that the legacy `saveSources` seed iterates
 *    `repoMap.values` SURVIVES through the LIVE `:shared`
 *    `SourcesRepository` seed path (the seed-iteration that drives
 *    insertion-order in the Room `sources` table is unchanged by the
 *    onboarding retire). NOTE: the original "raw `SELECT FROM sources`
 *    flow with no explicit `ORDER BY` yields insertion-order" rationale
 *    is now stale — `SourcesDao.getAllSources()` is
 *    `SELECT * FROM sources ORDER BY priority`, so rows arrive priority-
 *    ordered and the getter's `sortedBy { it.priority }` is a defensive
 *    re-assertion. The [SourcesState] class remains LIVE as
 *    the canonical Sources-screen state ADT consumed by
 *    [SourcesViewModel] + the rework `:ui` `SourcesScreen`. Original
 *    §253-era prose preserved verbatim per the audit-trail-
 *    preservation convention — the citation is historical record of
 *    the design lineage including the parallel-iteration-pattern
 *    forecast that was subsequently fulfilled (legacy onboarding
 *    retired) across §307.
 */
data class SourcesState(
    val isLoading: Boolean = true,
    val items: List<Source> = emptyList(),
    val complaintDialogOpen: Boolean = false,
    val isSubmittingComplaint: Boolean = false,
) : MviState {

    /** Convenience: true when the snapshot is empty and we're not still loading. */
    val isEmpty: Boolean get() = !isLoading && items.isEmpty()

    /**
     * Sources bucketed by [Source.language], with each bucket sorted by [Source.priority]
     * (lower comes first). The `:ui` LazyColumn renders one section per entry in this map: a
     * language header (with a per-language `Switch`) plus the per-source rows. Same
     * regroup-in-`:presentation` posture as the History / Updates date grouping (§82.3, §83.3) —
     * keeps the flat-domain contract while letting the screen render its preferred section shape.
     *
     * Sort parity-fix: native `RepoSettingsViewModel.kt:35` does
     * `repoList = sourcesRepository.repoTaps.sortedBy { it.PRIORITY }` and then
     * `groupedByLanguage()` (native line 114-115) groups that priority-sorted list, so within each
     * language sources are ordered by `PRIORITY`. The upstream `legacy.allSources` flow is
     * `SourcesDao.getAllSources()` = `SELECT * FROM sources ORDER BY priority`, so the rows already
     * arrive priority-ordered; the `sortedBy { it.priority }` before the `groupBy` is a (harmless)
     * defensive re-assertion of native's within-language priority ordering. `groupBy` is
     * LinkedHashMap-backed, so the language-bucket (key) order remains the first-seen order of the
     * priority-sorted list — matching native, which likewise relies on the priority-sorted list's
     * first-seen language order.
     */
    val groupedByLanguage: Map<String, List<Source>>
        get() = items.sortedBy { it.priority }.groupBy { it.language }

    /**
     * Convenience: count of currently-enabled sources. Computed (no field) so the value stays
     * in sync with [items] without a redundant copy in [SourcesViewModel.handle].
     */
    val enabledCount: Int get() = items.count { it.isEnabled }
}
