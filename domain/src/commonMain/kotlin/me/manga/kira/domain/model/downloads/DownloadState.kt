package me.manga.kira.domain.model.downloads

/**
 * Lifecycle state of a chapter download.
 *
 * Phase 7.x.downloads.foundation rework. Mirror of the legacy `:shared`
 * `me.manga.kira.presentation.features.download.data.DownloadingState`
 * enum used by `DownloadRepository` + `ChapterDownloadEntity`. The `:data`
 * strangler-fig impl maps the legacy enum 1:1 across these five values —
 * `RUNNING` / `QUEUED` / `COMPRESSING` / `SUCCESS` / `FAILED`.
 *
 * Contract §6 ISP: pure ADT, no behaviour. The `:ui` layer groups instances
 * into Active / Failed / Completed buckets (matching the legacy tabbed
 * screen's three sections — see
 * `composeApp/.../download/ui/screens/DownloadsScreen.kt` for the legacy
 * grouping rule). The `:presentation` layer projects each state into a
 * displayName/colour as needed.
 *
 * Bucket grouping (carried forward from the legacy
 * `DownloadsScreenRoute.kt:48-60`):
 *  - **Active** = `RUNNING` ∪ `QUEUED` ∪ `COMPRESSING`
 *  - **Failed** = `FAILED`
 *  - **Completed** = `SUCCESS`
 *
 * **Audit-trail postscript** (Phase 9.x.cluster136.staleKdocSweep.cascade,
 * Task #592, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-first sibling of the cluster57-135
 * sweep — first file of the wave-24 fourth-cluster `:domain/model/
 * downloads/` plus `:domain/model/whatsnew/` joint 4-leaf-model batch
 * alongside DownloadedChapter plus MediaType plus WhatsNewFeature; opens
 * cluster136):
 *  (a) "Phase-7.x.downloads.foundation-rework + Mirror-of-the-legacy-
 *  :shared-DownloadingState-enum-used-by-DownloadRepository-plus-
 *  ChapterDownloadEntity + :data-strangler-fig-impl-maps-the-legacy-
 *  enum-1-1-across-these-five-values + RUNNING-QUEUED-COMPRESSING-
 *  SUCCESS-FAILED + Bucket-grouping-Active-equals-RUNNING-union-QUEUED-
 *  union-COMPRESSING + Failed-equals-FAILED + Completed-equals-SUCCESS"
 *  — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive
 *  grep: DownloadsScreen.kt consumes all five DownloadState variants
 *  (RUNNING / QUEUED / COMPRESSING / SUCCESS / FAILED); DownloadsMappers.
 *  kt translates the legacy ChapterDownloadEntity's String/Int state
 *  field to this enum at the :data boundary. The Active/Failed/
 *  Completed bucket-grouping rule from the legacy DownloadsScreenRoute.
 *  kt:48-60 is preserved verbatim in the rework DownloadsScreen tab
 *  partition (RUNNING + QUEUED + COMPRESSING in Active tab; FAILED in
 *  Failed tab; SUCCESS in Completed tab).
 *  (b) "Contract-§6-ISP-pure-ADT-no-behaviour + :ui-layer-groups-
 *  instances-into-Active-Failed-Completed-buckets + :presentation-
 *  layer-projects-each-state-into-a-displayName-or-colour-as-needed"
 *  — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified: the enum
 *  declares 5 variants with NO constructor parameters, NO bodies, NO
 *  companion-object helpers — pure ADT per ISP. Display-name / colour
 *  resolution lives in :ui DownloadsScreen via per-state when-branches
 *  (no resource imports leak into :domain).
 *  Two classifications STAND on their own merits. Opens cluster136.
 *  Original Phase 7.x.downloads.foundation-era prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
enum class DownloadState {
    /** Chapter waiting in the queue for download to start. */
    QUEUED,

    /** Chapter currently downloading; `progress` is meaningful (0-100). */
    RUNNING,

    /** Pages downloaded; CBZ archive being written. Treated as still-active for UI grouping. */
    COMPRESSING,

    /**
     * All pages downloaded to disk, but the chapter is not yet finalized (CBZ archiving + library
     * bookkeeping pending). Produced by the iOS background-`URLSession` engine when transfers finish
     * while the app is suspended; finalization (and the flip to [SUCCESS]) happens on next foreground.
     * Treated as still-active/"finishing" for UI grouping — surfaced in the Active bucket so the user
     * sees a clear "downloaded, finishing" status rather than the chapter silently disappearing.
     */
    DOWNLOADED,

    /** Download finished and archive saved. */
    SUCCESS,

    /** Download terminated with an error; `errorMsg` carries the detail. */
    FAILED,
}
