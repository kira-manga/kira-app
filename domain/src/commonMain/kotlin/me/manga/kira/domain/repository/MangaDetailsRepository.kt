package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails

/**
 * Source of [MangaDetails] for a given [Manga].
 *
 * Contract §6 SRP: owns ONE rule — "given a manga identity, hand back the source's current view
 * of its details + chapter list, or a typed failure if the source / network / parse fails". The
 * actual source-routing (which `:shared/sources_repositry/...` implementation handles which API)
 * is a `:data`-layer concern hidden behind this interface.
 *
 * Why this interface is intentionally minimal:
 *  - **No `Flow<MangaDetails>` for "observe"**. Legacy details fetches are stateless network
 *    calls — there is no local cache to observe. If a future slice adds a cache (e.g. for offline
 *    Details rendering), it lands as a separate `observeCachedDetails(...)` method, not as a
 *    behavioral change to this one.
 *  - **No `refresh(...)` variant**. Legacy code calls the fetch fresh on every Details screen
 *    entry; the rework preserves that. A future "pull to refresh" path can either re-invoke
 *    [fetchDetails] or land a dedicated method if the cache distinction matters.
 *  - **Single suspend method**. No streaming, no incremental delivery. The `:data` impl waits for
 *    the source to return everything, then either succeeds with [MangaDetails] or fails with an
 *    [AppResult.Failure] carrying the typed error. This matches every existing source-repository's
 *    one-shot return contract and avoids forcing the Reader pages into the Details payload.
 *
 * DIP (contract §6): consumers (`FetchMangaDetailsUseCase`, the future Details VM) depend on this
 * interface, never on a concrete `:data` impl. Koin binds the impl at the composition root.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster139.staleKdocSweep.cascade,
 * Task #595, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-third sibling of the cluster57-138
 * sweep — first file of the wave-25 first-cluster 5-leaf-repository
 * batch opening :domain/repository/ tier survey; opens cluster139 plus
 * opens wave-25):
 *  (a) "Source-of-MangaDetails-for-a-given-Manga + Contract-§6-SRP-owns-
 *  ONE-rule-given-a-manga-identity-hand-back-the-source-current-view-
 *  of-its-details-plus-chapter-list-or-a-typed-failure + the-actual-
 *  source-routing-which-:shared-sources_repositry-implementation-
 *  handles-which-API-is-a-:data-layer-concern-hidden-behind-this-
 *  interface + No-Flow-MangaDetails-for-observe + Legacy-details-
 *  fetches-are-stateless-network-calls-there-is-no-local-cache-to-
 *  observe + No-refresh-variant + Legacy-code-calls-the-fetch-fresh-on-
 *  every-Details-screen-entry-the-rework-preserves-that + Single-
 *  suspend-method + No-streaming-no-incremental-delivery + the-:data-
 *  impl-waits-for-the-source-to-return-everything-then-either-succeeds-
 *  with-MangaDetails-or-fails-with-an-AppResult.Failure-carrying-the-
 *  typed-error" — LIVE-NOT-STALE plus FULFILLED-PREDICTION plus
 *  FORECAST-NOT-YET-FULFILLED-(future-observeCachedDetails-cache-
 *  method-if-offline-Details-rendering-lands). Verified via recursive
 *  grep: MangaDetailsRepository is consumed by FetchMangaDetailsUse-
 *  Case (the :domain caller predicted) plus MangaDetailsRepositoryImpl
 *  plus MangaDetailsMappers plus DetailsReworkModule. The interface
 *  still declares exactly ONE suspend method (`fetchDetails(manga):
 *  AppResult<MangaDetails>`) — no Flow observe, no refresh, no
 *  observeCachedDetails has landed. The forecast "future cache slice
 *  lands as a separate observeCachedDetails method" remains forecast
 *  — no offline-Details rendering surface exists. The "single suspend
 *  AppResult-wrapped network call" posture holds.
 *  (b) "DIP-contract-§6-consumers-FetchMangaDetailsUseCase-the-future-
 *  Details-VM-depend-on-this-interface-never-on-a-concrete-:data-impl
 *  + Koin-binds-the-impl-at-the-composition-root + AppResult.Failure-
 *  AppError.Network.NoConnectivity-Timeout-Http-on-transport-failure +
 *  AppResult.Failure-AppError.Network.Serialization-on-parse-failure +
 *  AppResult.Failure-AppError.Unexpected-for-unclassifiable-source-
 *  failures-carrying-the-original-Throwable-cause-for-telemetry" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified: FetchManga-
 *  DetailsUseCase.kt sits in :domain/usecase/details/ and depends only
 *  on this interface — no :data import. DetailsReworkModule wires the
 *  :data MangaDetailsRepositoryImpl as the single Koin binding for the
 *  interface. The "future Details VM" is now LIVE (DetailsViewModel.kt
 *  consumes FetchMangaDetailsUseCase per cluster-30 §253 postscript).
 *  AppError taxonomy mapping holds — MangaDetailsRepositoryImpl trans-
 *  lates source exceptions into the predicted Failure variants.
 *  Two classifications STAND on their own merits. Opens wave-25 and
 *  opens cluster139. Original Phase 6.3.x.details-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
interface MangaDetailsRepository {

    /**
     * Fetch the full [MangaDetails] for [manga] from its source.
     *
     * Network call — runs on the I/O dispatcher inside the `:data` impl. Returns:
     *  - `AppResult.Success(details)` when the source responded and the response parsed cleanly.
     *  - `AppResult.Failure(AppError.Network.NoConnectivity / Timeout / Http)` on transport failure.
     *  - `AppResult.Failure(AppError.Network.Serialization)` on parse failure.
     *  - `AppResult.Failure(AppError.Unexpected)` for unclassifiable source failures (carrying the
     *    original [Throwable] cause for telemetry).
     */
    suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails>
}
