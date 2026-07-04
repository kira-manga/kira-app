package me.manga.kira.data.repository

import me.manga.kira.domain.model.about.AppMetadata
import me.manga.kira.domain.repository.AboutRepository
import me.manga.kira.platform.version.AppVersionProvider as LegacyAppVersionProvider

/**
 * [AboutRepository] strangler-fig delegate over the legacy `:shared` [LegacyAppVersionProvider]
 * expect class.
 *
 * Phase 7.x.about. Wraps two property reads (`legacy.versionName` + `legacy.packageName`) into
 * an [AppMetadata] data class. Same posture as [ThemeRepositoryImpl] /
 * [ReadingSessionRepositoryImpl] / [ReadingStatisticsRepositoryImpl] /
 * [SourcesRepositoryImpl] / [HistoryRepositoryImpl] / [UpdatesRepositoryImpl]: the rework
 * `:data` impl reaches into the legacy `:shared` provider for cross-cutting state that hasn't
 * been ported off `:shared` yet. The legacy `PlatformModule.{android,ios,desktop}.kt` already
 * binds [LegacyAppVersionProvider] as a `single` — no new bindings on the legacy side.
 *
 * **SRP (contract §6)**: owns ONE rule — "project the legacy [LegacyAppVersionProvider]'s two
 * String properties into the rework [AppMetadata] data class". No platform code, no fallback
 * logic (the legacy expect class substitutes `"unknown"` for any read failure — that contract
 * passes through to the rework caller unchanged).
 *
 * **DIP (contract §6)**: depends on the legacy [LegacyAppVersionProvider] type because it's the
 * only vendor for the version + package strings today. The dependency is at the strangler-fig
 * boundary — the rework `:data` layer is allowed to reach into `:shared` for cross-cutting
 * persistence/platform reads that haven't been ported yet. The [AboutRepository] interface in
 * `:domain` is unaffected either way.
 *
 * **Import-alias `as LegacyAppVersionProvider`** — the rework's [AboutRepository] does NOT
 * collide with `AppVersionProvider` (they're different namespaces — `:domain.repository` vs
 * `:shared.core.platform`). The alias is for symmetry with the other strangler-fig impls
 * (`ThemeRepositoryImpl` uses `as LegacySettingsRepository` for the same readability reason) —
 * makes the legacy boundary visible at the call site without needing to grep imports.
 *
 * **`:platform` posture deferral** — Phase 5.3 + 5.z.cleanup relocated `AppVersionProvider`
 * into `:platform` as an interface (with `:platform/.../version/AppVersionProvider.kt`). That
 * `:platform` SPI is NOT yet bound to Koin and is consumed by no caller in the current graph.
 * The rework About slice deliberately reaches into the legacy `:shared` expect class (already
 * bound) rather than rewiring the entire Koin graph for the SPI swap — same posture as every
 * prior rework `:data` impl. A separate `Phase 8.z.platform-rewire` slice retires the legacy
 * binding once the SPI's actuals are confirmed binding-compatible with the existing call
 * sites (the legacy `IntentLauncher` + `AppVersionProvider` + `ToastShower` triumvirate would
 * lift together).
 *
 * **`suspend fun getMetadata()` body is synchronous** — the legacy expect class's two
 * properties (`versionName` + `packageName`) are pure-Kotlin field reads with no IO; the
 * `suspend` declaration on the interface ([AboutRepository.getMetadata]) is
 * forward-compatibility room (see [AboutRepository] KDoc). No `withContext(Dispatchers.IO)`
 * wrap is needed because no blocking call happens.
 *
 * **Lifecycle**: `single` in Koin (declared in `aboutReworkModule`). The upstream legacy
 * provider is `single` (declared by `PlatformModule.*`); a `factory` here would just re-wrap
 * the same provider every resolution — wasteful for a metadata read that the screen does once
 * per visit.
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, the
 * per-host repo registry, OkHttp interceptor, AVIF decoder, HighQualitySkiaImageDecoder, or
 * `:platform` — About is pure metadata projection. No load-bearing risk.
 */
class AboutRepositoryImpl(
    private val legacy: LegacyAppVersionProvider,
) : AboutRepository {

    override suspend fun getMetadata(): AppMetadata = AppMetadata(
        versionName = legacy.versionName,
        packageName = legacy.packageName,
    )
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster154.staleKdocSweep.cascade,
 * Task #610, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-ninth sibling of the cluster57-153
 * sweep — middle file of the wave-26 :data/repository misc cell-of-truth
 * trio 3-leaf batch alongside AdultContentClassifierImpl plus WhatsNew
 * RepositoryImpl; CONTINUES :data/repository misc trio 2/3):
 *  (a) "AboutRepository-strangler-fig-delegate-over-the-legacy-:shared-
 *  LegacyAppVersionProvider-expect-class + Phase-7.x.about + Wraps-two-
 *  property-reads-legacy.versionName-plus-legacy.packageName-into-an-App
 *  Metadata-data-class + Same-posture-as-ThemeRepositoryImpl-Reading
 *  SessionRepositoryImpl-ReadingStatisticsRepositoryImpl-SourcesRepository
 *  Impl-HistoryRepositoryImpl-UpdatesRepositoryImpl + The-rework-:data-
 *  impl-reaches-into-the-legacy-:shared-provider-for-cross-cutting-state-
 *  that-hasn-t-been-ported-off-:shared-yet + The-legacy-PlatformModule.
 *  android-ios-desktop.kt-already-binds-LegacyAppVersionProvider-as-a-
 *  single + SRP-contract-section-6-owns-ONE-rule-project-the-legacy-Legacy
 *  AppVersionProvider-s-two-String-properties-into-the-rework-AppMetadata-
 *  data-class + DIP-contract-section-6-depends-on-the-legacy-LegacyApp
 *  VersionProvider-type-because-it-s-the-only-vendor-for-the-version-plus
 *  -package-strings-today + The-dependency-is-at-the-strangler-fig-boundary
 *  -the-rework-:data-layer-is-allowed-to-reach-into-:shared-for-cross-
 *  cutting-persistence-platform-reads-that-haven-t-been-ported-yet + Import
 *  -alias-as-LegacyAppVersionProvider-the-rework-s-AboutRepository-does-
 *  NOT-collide-with-AppVersionProvider-they-re-different-namespaces +
 *  :platform-posture-deferral-Phase-5.3-plus-5.z.cleanup-relocated-App
 *  VersionProvider-into-:platform-as-an-interface + That-:platform-SPI-is-
 *  NOT-yet-bound-to-Koin-and-is-consumed-by-no-caller-in-the-current-
 *  graph + The-rework-About-slice-deliberately-reaches-into-the-legacy-:
 *  shared-expect-class-already-bound-rather-than-rewiring-the-entire-Koin
 *  -graph-for-the-SPI-swap + A-separate-Phase-8.z.platform-rewire-slice-
 *  retires-the-legacy-binding-once-the-SPI-s-actuals-are-confirmed-binding
 *  -compatible + suspend-fun-getMetadata-body-is-synchronous-the-legacy-
 *  expect-class-s-two-properties-are-pure-Kotlin-field-reads-with-no-IO-
 *  the-suspend-declaration-on-the-interface-is-forward-compatibility-room
 *  + No-withContext-Dispatchers.IO-wrap-is-needed-because-no-blocking-
 *  call-happens + Lifecycle-single-in-Koin-declared-in-aboutReworkModule +
 *  The-upstream-legacy-provider-is-single-a-factory-here-would-just-re-
 *  wrap-the-same-provider-every-resolution + Load-bearing-fixes-preserved
 *  -this-slice-does-NOT-touch-the-Coil-ImageLoader-the-per-host-repo-
 *  registry-OkHttp-interceptor-AVIF-decoder-HighQualitySkiaImageDecoder-
 *  or-:platform" — LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED on the
 *  deferred Phase 8.z.platform-rewire SPI swap. Verified: strangler-fig
 *  delegate over legacy :shared LegacyAppVersionProvider expect class
 *  shipped. suspend getMetadata() projects two String field reads
 *  (legacy.versionName + legacy.packageName) into AppMetadata data class.
 *  The "suspend body is synchronous" rationale honored — no withContext
 *  wrap because legacy expect-class field reads are pure-Kotlin. The
 *  "single Koin lifecycle" stance honored — declared in aboutReworkModule
 *  as a single. The "import-alias as LegacyAppVersionProvider for
 *  strangler-fig boundary visibility" convention honored. The "Phase
 *  8.z.platform-rewire deferral" remains FORECAST-NOT-YET-FULFILLED —
 *  :platform/.../version/AppVersionProvider.kt SPI exists (per Phase 5.3
 *  + 5.z.cleanup) but is NOT bound to Koin and is consumed by no caller;
 *  the rework About slice still reaches into the legacy :shared expect
 *  class. Consumed by GetAppMetadataUseCase (cluster114 sibling X) via
 *  the AboutRepository interface; surfaced as state.metadata on About
 *  ViewModel. CONTINUING FILE of cluster154 — :data/repository misc cell-
 *  of-truth trio 3-leaf batch (2 of 3). One classification. Original
 *  Phase 7.x.about strangler-fig impl prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
