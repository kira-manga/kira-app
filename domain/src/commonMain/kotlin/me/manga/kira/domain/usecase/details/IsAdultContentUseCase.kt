package me.manga.kira.domain.usecase.details

import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.AdultContentClassifier

/**
 * Decide whether a [Manga] is adult content, according to its source's blacklist policy.
 *
 * Contract §6 SRP: owns ONE rule — "ask the [AdultContentClassifier] whether this manga's genres
 * trip the source's blacklist". The classification policy itself (which genre strings count as
 * adult for source X) lives in the `:data` impl behind the classifier interface; this use case
 * is the seam between the future Details ViewModel (which decides whether to render a content
 * warning / blur the cover / gate the chapter list) and the policy abstraction.
 *
 * Why a use case at all when this is a single-line delegate (same rationale as
 * [FetchMangaDetailsUseCase]):
 *  - **Stable presentation-layer dependency**. The future Details VM depends on
 *    `IsAdultContentUseCase`, not on `AdultContentClassifier`. If the policy evolves (e.g. user
 *    setting overrides, age verification, per-manga override), the new dependency lands here
 *    without forcing a VM signature change.
 *  - **Test seam**. Mocking one operator is cheaper than mocking the full classifier interface
 *    in unit tests.
 *  - **Consistent with the rest of the rework**. Every VM-callable verb has its own use case
 *    (Library: `ObserveLibrary` / `ToggleInLibrary` / `BulkRemoveFromLibrary`; Details:
 *    `FetchMangaDetails` and now this).
 *
 * Why this takes a [Manga] rather than `(api: String, genres: List<String>)`:
 *  - The caller (future Details VM) holds a [Manga] in state; threading the api + genres pair
 *    by hand would be redundant. The use case extracts the two fields and passes them to the
 *    classifier — a single argument is easier to mock and easier to evolve (e.g. adding a
 *    language-aware policy later only changes the use case body, not its signature).
 *
 * Why this is **not suspend**: the classifier is a synchronous in-memory check (see
 * [AdultContentClassifier] KDoc). Wrapping in a coroutine would buy nothing and would force
 * Compose callers into `LaunchedEffect` for a value they can compute synchronously from state.
 *
 * Constructor-injected [AdultContentClassifier] per contract §6 DIP — Koin binds it as a
 * factory in `:composeApp/DetailsReworkModule.kt`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster119.staleKdocSweep.cascade,
 * Task #575, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (sixtieth sibling of the cluster57-118 sweep — closes the
 * wave-19 `:domain/usecase/details/` batch alongside FetchMangaDetails-
 * UseCase.kt):
 *  (a) "Contract §6 SRP owns ONE rule — ask the AdultContentClassifier
 *  whether this manga's genres trip the source's blacklist; the
 *  classification policy itself (which genre strings count as adult for
 *  source X) lives in the `:data` impl behind the classifier interface;
 *  this use case is the seam between the future Details ViewModel
 *  (which decides whether to render a content warning / blur the cover /
 *  gate the chapter list) and the policy abstraction" — LIVE-NOT-STALE
 *  plus FULFILLED-PREDICTION. DetailsViewModel.kt L81 primary
 *  constructor binds `private val isAdultContent: IsAdultContentUseCase`
 *  (the "future Details ViewModel" forecast has fulfilled — the VM
 *  exists and consumes the use case at three sites). L113 `val
 *  tentativeAdult = isAdultContent(manga)` on `OnEnter`; L170 `val
 *  tentativeAdult = isAdultContent(tentative)` on `OnEnterByUrl`-shaped
 *  tentative-Manga path; L238 `val refreshedAdult = isAdultContent(
 *  classifierInput)` on the post-fetch refresh after genres enrich.
 *  DetailsState.kt L31 KDoc cite confirms the re-run-classifier
 *  rationale. AdultContentClassifierImpl `:data` impl source-blacklist-
 *  policy realization verified at cluster25 sibling sweep (Task #481) —
 *  the per-source genre-string match-set lives `:data`-internal; this
 *  use case sees only the boolean verdict.
 *  (b) "Why a use case at all when this is a single-line delegate (same
 *  rationale as FetchMangaDetailsUseCase): stable presentation-layer
 *  dependency (the future Details VM depends on IsAdultContentUseCase,
 *  not on AdultContentClassifier; if the policy evolves — user setting
 *  overrides, age verification, per-manga override — the new dependency
 *  lands here without forcing a VM signature change); test seam (mocking
 *  one operator is cheaper than mocking the full classifier interface);
 *  consistent with the rest of the rework (every VM-callable verb has
 *  its own use case — Library: ObserveLibrary / ToggleInLibrary /
 *  BulkRemoveFromLibrary; Details: FetchMangaDetails and now this)" —
 *  LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED. Stable-presentation-
 *  layer-dependency claim verified — DetailsViewModel.kt L81 binds the
 *  use-case type, not the classifier type; the VM remains free of
 *  classifier-shape leakage. Test-seam claim verified — DetailsViewModel-
 *  Test mocking posture remains the documented contract. Cross-rework
 *  one-use-case-per-VM-callable-verb peer cohort: FetchMangaDetailsUse-
 *  Case (wave-19 sibling, this cluster) plus the three Library use cases
 *  (UNSWEPT in the cascade but exist as architectural references). Policy
 *  evolution forecast (user setting overrides, age verification, per-
 *  manga override) — FORECAST-NOT-YET-FULFILLED. Recursive search for
 *  user-setting-override plus age-verification plus per-manga-override
 *  composition returns zero matches; the use case remains the single-
 *  line classifier delegate.
 *  (c) "Why this takes a Manga rather than (api: String, genres: List<
 *  String>): the caller (future Details VM) holds a Manga in state;
 *  threading the api + genres pair by hand would be redundant; the use
 *  case extracts the two fields and passes them to the classifier — a
 *  single argument is easier to mock and easier to evolve (e.g. adding
 *  a language-aware policy later only changes the use case body, not
 *  its signature)" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. L43-44
 *  realization `classifier.isAdultContent(api = manga.api, genres =
 *  manga.genres)` matches the framing character-for-character — the
 *  use-case body extracts the two fields and the signature remains a
 *  single-Manga argument. Three caller-site Manga-in-state cross-refs
 *  (DetailsViewModel.kt L113/L170/L238) confirm the holds-a-Manga-in-
 *  state claim has fulfilled.
 *  (d) "Why this is not suspend — the classifier is a synchronous in-
 *  memory check (see AdultContentClassifier KDoc); wrapping in a
 *  coroutine would buy nothing and would force Compose callers into
 *  LaunchedEffect for a value they can compute synchronously from state"
 *  — LIVE-NOT-STALE. L43 realization `operator fun invoke(manga: Manga):
 *  Boolean` (no `suspend` modifier) matches the framing. Sync-in-memory-
 *  check posture verified at cluster25 (Task #481) AdultContentClassi-
 *  fierImpl — the per-source blacklist map is loaded eagerly; the
 *  isAdultContent call walks an in-memory Set<String> intersection.
 *  Compose-caller-no-LaunchedEffect-needed claim verified — Details-
 *  ViewModel.kt L113/L170/L238 invoke the use case from inside `update-
 *  State { ... }` builders synchronously, not from `viewModelScope.
 *  launch { ... }` coroutine bodies.
 *  (e) "Constructor-injected AdultContentClassifier per contract §6 DIP
 *  — Koin binds it as a factory in `:composeApp/DetailsReworkModule.kt`"
 *  — LIVE-NOT-STALE. DetailsReworkModule.kt L83 `factory { IsAdult-
 *  ContentUseCase(get()) }` realization confirms factory lifecycle
 *  (stateless, cheap to construct, matches the established "use case
 *  rename-to factory" convention); L81 `single<AdultContentClassifier>
 *  { AdultContentClassifierImpl(sourcesRepository = get()) }` confirms
 *  the classifier itself is `single` because the per-source blacklist
 *  map is eagerly populated at first construction (load-once / read-
 *  many).
 *  Five classifications STAND on their own merits as a faithful Is-
 *  AdultContentUseCase manifest. Original Phase 6.3.1-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class IsAdultContentUseCase(
    private val classifier: AdultContentClassifier,
) {
    operator fun invoke(manga: Manga): Boolean =
        classifier.isAdultContent(api = manga.api, genres = manga.genres)
}
