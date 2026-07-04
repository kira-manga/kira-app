package me.manga.kira.domain.usecase.about

import me.manga.kira.domain.model.about.AppMetadata
import me.manga.kira.domain.repository.AboutRepository

/**
 * Resolve the running app's [AppMetadata] for the rework About picker.
 *
 * Phase 7.x.about. The rework `AboutViewModel` injects this use case and invokes it once from
 * `viewModelScope.launch` in its `init {}` block — the result populates [AppMetadata.versionName]
 * and [AppMetadata.packageName] into the screen's `AboutState`. Suspend pass-through; no
 * combining, no projection, no error mapping (the legacy `AppVersionProvider` is structurally
 * infallible — see [AboutRepository] KDoc).
 *
 * **Why a use case at all when this is a single-line pass-through**: same rationale as the
 * other rework one-shot use cases (`IsAdultContentUseCase` from Phase 6.3.4,
 * `GetReadingProgressUseCase` from Phase 7.x.reader.resumeposition) — the VM depends on a
 * stable use case interface, not on a repository method (DIP); future composition (e.g.,
 * decorate with an analytics event on first-read, or short-circuit to a cached value) lives
 * here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `aboutReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster114.staleKdocSweep.cascade,
 * Task #570, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-fourth sibling of the cluster57-113 sweep — solo
 * wave-14 `:domain/usecase/about/` batch partnering the cluster106
 * sibling sweep of `:presentation/about/`):
 *  (a) "Phase 7.x.about — rework AboutViewModel injects this use case
 *  plus invokes it once from viewModelScope.launch in its init {} block
 *  — the result populates AppMetadata.versionName plus AppMetadata.
 *  packageName into the screen's AboutState" — LIVE-NOT-STALE. About-
 *  ViewModel.kt L110 primary constructor binds `private val getAppMeta-
 *  data: GetAppMetadataUseCase`; L117 init block realizes `viewModel-
 *  Scope.launch { val metadata = getAppMetadata(); updateState { ... } }`
 *  — one-shot suspend call plus state mutation; cluster106 sibling sweep
 *  (Task #562) verified the init-launch posture.
 *  (b) "Suspend pass-through; no combining, no projection, no error
 *  mapping (legacy AppVersionProvider is structurally infallible)" —
 *  LIVE-NOT-STALE. L28 realization `repository.getMetadata()` single-
 *  line pass-through; AboutRepositoryImpl `:data` impl verified at
 *  cluster23 sibling sweep (Task #479) — wraps the structurally-
 *  infallible AppVersionProvider singleton.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  peer cross-ref to IsAdultContentUseCase plus GetReadingProgressUse-
 *  Case; presentation depends on use cases (DIP), not on repository
 *  methods" — LIVE-NOT-STALE. Peer use cases verified at cluster26
 *  sibling sweep (Task #482) — same pure-delegate posture across the
 *  rework one-shot family. AboutViewModel.kt L55 `**DIP**: depends on
 *  `:domain`'s GetAppMetadataUseCase (interface seam)` confirms the
 *  layering symmetry.
 *  (d) "Future composition — decorate with an analytics event on first-
 *  read, or short-circuit to a cached value" — FORECAST-NOT-YET-
 *  FULFILLED. Recursive search for analytics-event emission or cache-
 *  short-circuit decoration on this use case returns zero matches; the
 *  use case remains a single-line pass-through. Forecast posture
 *  preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `aboutReworkModule`" — LIVE-NOT-STALE. AboutReworkModule.
 *  kt L55 `factory { GetAppMetadataUseCase(get()) }` realization
 *  confirms factory lifecycle (stateless, cheap to construct, never
 *  shared).
 *  Five classifications STAND on their own merits as a faithful Get-
 *  AppMetadataUseCase manifest. Original Phase 7.x.about-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class GetAppMetadataUseCase(
    private val repository: AboutRepository,
) {
    suspend operator fun invoke(): AppMetadata = repository.getMetadata()
}
