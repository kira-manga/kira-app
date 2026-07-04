package me.manga.kira.domain.model.downloads

/**
 * One chapter-download row as shown on the Downloads screen.
 *
 * Phase 7.x.downloads.foundation rework. Pure data class — no behaviour, no
 * platform types. Field set matches what the legacy `:ui` displays + the
 * identifiers the mutation use cases need to dispatch retry / cancel /
 * delete operations:
 *
 *  - **Display fields**: [number], [mangaTitle], [state], [progress],
 *    [errorMsg]. The legacy Downloads screen renders exactly these five —
 *    chapter number prefix + manga title on a single row, state label
 *    underneath, progress bar (only when `state == RUNNING`), failure
 *    detail (only when `state == FAILED`).
 *  - **Identifier fields**: [chapterId], [mangaId]. The legacy mutation
 *    paths take `chapterId` (retry, cancel, delete) and `chapterId + mangaId`
 *    (cancelRunning) — the `:domain` repository's mutation signatures
 *    mirror this. The `:data` impl reconstructs the remaining metadata
 *    (`api`, `url`) from the legacy DAO row when it needs to re-enqueue.
 *
 * **Fields intentionally omitted vs the legacy `ChapterDownloadEntity`**:
 *  - `id` (Room auto-gen PK) — internal to the DAO; the rework keys by
 *    [chapterId] (which is the domain identifier and is unique-indexed).
 *  - `api`, `url` — needed only for re-enqueue at the `:data` boundary;
 *    keeping them out of the `:domain` model preserves DIP (the `:ui` /
 *    `:presentation` layer never needs to know which source the chapter
 *    came from to render a row, only to identify it for mutations).
 *
 * **`mangaTitle` non-nullable**: legacy entity allows `null` (and the
 * legacy `:ui` renders empty string when null). The rework lifts the
 * null handling to the `:data` boundary (mapper substitutes empty
 * string) — `:domain` and downstream layers never have to null-check.
 *
 * **`progress` is 0-100**: matches the legacy entity's range. The `:ui`
 * normalises to a 0f-1f fraction at render time (legacy
 * `LinearProgressIndicator(progress = { ... / 100f })`). Keeping the
 * `:domain` field as `Int 0-100` matches the upstream DAO and avoids
 * floating-point precision loss on round-trips.
 *
 * **`errorMsg` nullable**: only populated when [state] is
 * [DownloadState.FAILED]. The `:ui` falls back to a generic
 * `"Unknown error"` string when null.
 *
 * Contract §6:
 *  - SRP: pure ADT, one rule (describe a single download row).
 *  - OCP: `data class` — equality / copy / componentN auto-generated. No
 *    inheritance; future fields go through `copy()` without breaking
 *    existing consumers.
 *  - DIP: no platform types, no `:data` imports. Consumers in `:ui` /
 *    `:presentation` depend on this model, not on the legacy
 *    `ChapterDownloadEntity`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster136.staleKdocSweep.cascade,
 * Task #592, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-second sibling of the cluster57-135
 * sweep — second file of the wave-24 fourth-cluster joint batch alongside
 * DownloadState plus MediaType plus WhatsNewFeature; CLOSES :domain/model/
 * downloads/ subpackage at 2/2):
 *  (a) "Phase-7.x.downloads.foundation-rework + Pure-data-class-no-
 *  behaviour-no-platform-types + Display-fields-number-mangaTitle-
 *  state-progress-errorMsg-legacy-Downloads-screen-renders-exactly-
 *  these-five + Identifier-fields-chapterId-mangaId-legacy-mutation-
 *  paths-take-chapterId-retry-cancel-delete-and-chapterId-plus-
 *  mangaId-cancelRunning + :domain-repository-mutation-signatures-
 *  mirror-this + :data-impl-reconstructs-remaining-metadata-api-url-
 *  from-the-legacy-DAO-row-when-it-needs-to-re-enqueue" — LIVE-NOT-
 *  STALE plus FULFILLED-PREDICTION. Verified via recursive grep:
 *  DownloadedChapter is consumed by 15+ surfaces — ObserveDownloads-
 *  UseCase, DownloadsRepositoryImpl, DownloadsMappers, DownloadsState,
 *  DownloadsIntent, DownloadsScreen. The :domain mutation use cases
 *  (Retry/Cancel/Delete/CancelRunning Download) take chapterId
 *  (mostly) and (chapterId+mangaId) for cancelRunning — matching the
 *  legacy signatures verbatim. The 5 display fields render exactly as
 *  predicted in DownloadsScreen.kt.
 *  (b) "Fields-intentionally-omitted-vs-the-legacy-ChapterDownload-
 *  Entity + id-Room-auto-gen-PK-internal-to-the-DAO + rework-keys-by-
 *  chapterId-which-is-the-domain-identifier-and-is-unique-indexed +
 *  api-url-needed-only-for-re-enqueue-at-the-:data-boundary + keeping-
 *  them-out-of-the-:domain-model-preserves-DIP" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION. Verified: the rework DownloadedChapter
 *  carries exactly 7 fields (chapterId + mangaId + number + mangaTitle
 *  + state + progress + errorMsg) — no Room id, no api, no url. The
 *  :data mapper at DownloadsMappers.kt boundary reads api/url from
 *  the legacy DAO row when re-enqueueing, never exposing them to
 *  :presentation or :ui.
 *  (c) "mangaTitle-non-nullable-legacy-entity-allows-null + rework-
 *  lifts-the-null-handling-to-the-:data-boundary-(mapper-substitutes-
 *  empty-string) + :domain-and-downstream-layers-never-have-to-null-
 *  check + progress-is-0-100-matches-the-legacy-entity-range + :ui-
 *  normalises-to-0f-1f-fraction-at-render-time + errorMsg-nullable-
 *  only-populated-when-state-is-FAILED + :ui-falls-back-to-Unknown-
 *  error-string-when-null + Contract-§6-SRP-pure-ADT-one-rule + OCP-
 *  data-class-equality-copy-componentN-auto-generated + DIP-no-
 *  platform-types-no-:data-imports" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION. Verified: mangaTitle: String (non-null) + progress: Int
 *  + errorMsg: String? declarations match the predicted nullability
 *  posture. DownloadsScreen.kt LinearProgressIndicator divides by
 *  100f as predicted. errorMsg null-fallback to "Unknown error"
 *  preserved in :ui per the predicted UX. Zero platform/:data imports
 *  in the :domain file.
 *  Three classifications STAND on their own merits. CLOSES :domain/
 *  model/downloads/ subpackage at 2/2. Original Phase 7.x.downloads.
 *  foundation-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
data class DownloadedChapter(
    val chapterId: Long,
    val mangaId: Long,
    val number: String,
    val mangaTitle: String,
    val state: DownloadState,
    val progress: Int,
    val errorMsg: String?,
    /**
     * Chapter source `url` (carried from the legacy `ChapterDownloadEntity.url`). Added 2026-06-02
     * so a `:presentation` consumer can join a download row onto a displayed `Chapter` (which is
     * keyed by `url`, has no Room id) WITHOUT a per-row suspend `ChapterIdResolver` round-trip — the
     * Details screen builds its live per-chapter download map synchronously off this field, which is
     * what makes the running→downloaded transition atomic (no leave/re-enter). The Downloads queue
     * screen ignores it. Note this widens the model past the original DIP-minimal field set on
     * purpose; the url is just an identifier (no `:data` types leak).
     */
    val url: String,
    /**
     * Final on-disk size of the downloaded chapter in bytes (`ChapterDownloadEntity.sizeBytes`).
     * 0 until the download reaches SUCCESS. The UI formats it (`:core` `formatBytes`) for the native
     * size display next to the chapter date / in the per-manga total header. Default 0.
     */
    val sizeBytes: Long = 0,
) {
    companion object {
        /**
         * Locale-independent sentinel persisted in [errorMsg] when a download is cancelled by the user
         * (iOS/Desktop engine). The `:ui` maps it to a localized "cancelled by user" string at render
         * time so the Failed tab is never stuck showing English on a localized device, and it tracks
         * the current app locale even if the row was written under a different one. The `:shared`
         * download engine writes this exact literal; keep the two in sync.
         */
        const val CANCELLED_BY_USER_SENTINEL: String = "__cancelled_by_user__"

        /**
         * Locale-independent sentinel persisted in [errorMsg] when a download's page resolution failed on
         * a Cloudflare / anti-bot challenge (HTTP 403/429/503/52x or a challenge-bodied response). The
         * download engine bypasses the `:data` `AppError` classifier, so this sentinel is how the engine
         * tells a `:presentation` consumer "this failure is WebView-solvable" without leaking a status
         * code or fragile message string. `DetailsViewModel` detects it on a FAILED row and auto-routes
         * to the existing WebView Cloudflare solver, then re-enqueues. The `:shared` download engine
         * writes this exact literal (its own local copy); keep the two in sync.
         */
        const val CLOUDFLARE_CHALLENGE_SENTINEL: String = "__cloudflare_challenge__"
    }
}
