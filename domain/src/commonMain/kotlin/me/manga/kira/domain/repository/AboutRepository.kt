package me.manga.kira.domain.repository

import me.manga.kira.domain.model.about.AppMetadata

/**
 * Contract for the rework About screen's data source.
 *
 * Phase 7.x.about. Surfaces the running app's [AppMetadata] (version name + package id) as a
 * one-shot suspend read. Backed in `:data` by [me.manga.kira.data.repository.AboutRepositoryImpl],
 * which is a strangler-fig delegate over the legacy `:shared` `AppVersionProvider` expect class.
 * The legacy onboarding About screen + the rework `Screen.AboutRework` route consume the SAME
 * underlying `AppVersionProvider` instance (bound `single` by `PlatformModule.{android,ios,desktop}.kt`)
 * — toggling the version on disk (e.g., via a Play Store update) is visible to both surfaces.
 *
 * **Single-method surface** — ISP §6. The picker has exactly one read concern (the metadata
 * tuple); no preference writes, no list loads, no per-page hydration. Future additions (e.g., a
 * build-flavor field or a "check for updates" remote call) extend the [AppMetadata] data class
 * or grow the interface with a separate method.
 *
 * **`suspend fun getMetadata()` not `Flow<AppMetadata>`** — the version + package id are
 * immutable for the running process. A one-shot `suspend` is the precise shape; a `Flow` would
 * be misleading (single emission, never re-emits). Matches the [IsAdultContentUseCase] pattern
 * from Phase 6.3.4 — also a one-shot suspend that resolves immediately on subscription.
 *
 * **Why no `Result<AppMetadata>` wrapper** — the legacy `AppVersionProvider` is structurally
 * infallible (substitutes `"unknown"` on any platform read failure). Wrapping the return in a
 * `Result` / `AppResult` would just push a `getOrElse { ... }` to every caller for no gain. If
 * a future implementation introduces a fallible call (e.g., a "current build channel" remote
 * lookup), the surface can grow a separate `getBuildInfo(): AppResult<BuildInfo>` method
 * alongside this one.
 *
 * Contract §6 DIP — `:presentation`'s [me.manga.kira.presentation.about.AboutViewModel]
 * depends on this `:domain` interface (via [me.manga.kira.domain.usecase.about.GetAppMetadataUseCase])
 * not on the `:data` impl or the legacy `:shared` expect class. Test substitution + future
 * `:platform` rewire (Phase 8.z) both stay open without touching `:presentation`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster140.staleKdocSweep.cascade,
 * Task #596, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fortieth sibling of the cluster57-139
 * sweep — third file of the wave-25 second-cluster 5-leaf-repository
 * batch alongside PageProgressRepository plus ReadingStatisticsRepository):
 *  (a) "Contract-for-the-rework-About-screen-data-source + Phase-7.x.
 *  about + Surfaces-the-running-app-AppMetadata-version-name-plus-
 *  package-id-as-a-one-shot-suspend-read + Backed-in-:data-by-About-
 *  RepositoryImpl-which-is-a-strangler-fig-delegate-over-the-legacy-
 *  :shared-AppVersionProvider-expect-class + The-legacy-onboarding-
 *  About-screen-plus-the-rework-Screen.AboutRework-route-consume-the-
 *  SAME-underlying-AppVersionProvider-instance + Single-method-surface-
 *  ISP-§6 + The-picker-has-exactly-one-read-concern-the-metadata-tuple-
 *  no-preference-writes-no-list-loads-no-per-page-hydration + suspend-
 *  fun-getMetadata-not-Flow-AppMetadata + The-version-plus-package-id-
 *  are-immutable-for-the-running-process + A-one-shot-suspend-is-the-
 *  precise-shape-a-Flow-would-be-misleading-single-emission-never-re-
 *  emits + Matches-the-IsAdultContentUseCase-pattern-from-Phase-6.3.4-
 *  also-a-one-shot-suspend-that-resolves-immediately-on-subscription" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION plus FORECAST-NOT-YET-
 *  FULFILLED-(post-route-swap-legacy-onboarding-About-screen-retire).
 *  Verified via recursive grep: AboutRepository is consumed by Get-
 *  AppMetadataUseCase (the :domain caller) plus AboutViewModel plus
 *  AboutReworkScreenRoute plus AboutRepositoryImpl. The legacy
 *  onboarding AboutScreenRoute remains LIVE per the live status doc —
 *  both routes consume the same AppVersionProvider single — so the
 *  cross-strangler-fig dual-route posture holds.
 *  (b) "Why-no-Result-AppMetadata-wrapper + The-legacy-AppVersion-
 *  Provider-is-structurally-infallible-substitutes-unknown-on-any-
 *  platform-read-failure + Wrapping-the-return-in-a-Result-or-AppResult-
 *  would-just-push-a-getOrElse-to-every-caller-for-no-gain + If-a-
 *  future-implementation-introduces-a-fallible-call-e.g.-a-current-
 *  build-channel-remote-lookup-the-surface-can-grow-a-separate-
 *  getBuildInfo-AppResult-BuildInfo-method-alongside-this-one + Contract-
 *  §6-DIP + :presentation-AboutViewModel-depends-on-this-:domain-
 *  interface-via-GetAppMetadataUseCase-not-on-the-:data-impl-or-the-
 *  legacy-:shared-expect-class + Test-substitution-plus-future-:platform-
 *  rewire-Phase-8.z-both-stay-open-without-touching-:presentation" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION plus FORECAST-NOT-YET-
 *  FULFILLED-(future-getBuildInfo-AppResult-BuildInfo-method-if-remote-
 *  build-channel-lookup-lands plus Phase-8.z-:platform-rewire). Verified:
 *  AboutViewModel imports only GetAppMetadataUseCase — no :data or
 *  :shared reach. The single-suspend infallible-return posture holds —
 *  no Result/AppResult has crept in. The Phase-8.z :platform AppVersion-
 *  Provider rewire remains forecast.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  about-era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
interface AboutRepository {

    /**
     * Returns the running app's metadata snapshot. Suspend for forward-compatibility with a
     * future async source (e.g., remote build channel lookup); the current implementation
     * resolves synchronously via two property reads on the legacy provider.
     */
    suspend fun getMetadata(): AppMetadata
}
