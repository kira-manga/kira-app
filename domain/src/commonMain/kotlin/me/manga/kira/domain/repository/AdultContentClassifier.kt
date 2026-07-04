package me.manga.kira.domain.repository

/**
 * Classifies a `(source api, genre list)` pair as "adult content" or not.
 *
 * Contract §6 SRP: owns ONE rule — "given a source identifier and the source-supplied genres of a
 * manga, does any genre overlap the source's adult-content blacklist?". The blacklist itself is
 * a per-source policy (each [me.manga.kira.sources_repositry.BaseMangaRepository] subclass
 * defines its own `blackListGenres: Set<String>`); the rework hides that detail behind this
 * interface so consumers (`IsAdultContentUseCase`, the future Details VM) never reach into the
 * legacy `SourcesRepository` registry directly.
 *
 * Why this is **not suspend** and returns [Boolean] directly:
 *  - The legacy `MangaDerailsViewModel.isPlus18(gens, api)` is a synchronous in-memory check
 *    (`gens.any { it in sourcesRepository.getRepoByName(api).blackListGenres }`). Every source
 *    repository's `blackListGenres` is a `val` initialized at construction time — no I/O, no
 *    network, no DB. Marking this `suspend` would be a contract that the impl doesn't need and
 *    callers would have to honour by hoisting the call into a coroutine.
 *  - If a future source-rating policy adds remote lookup (e.g. a parental-rating service), the
 *    contract evolves into a separate `suspend` method on a sibling interface
 *    (`RemoteContentRatingService`) — the synchronous policy keeps living here for the in-memory
 *    case.
 *
 * Why a [String] api rather than a typed `Source` value object:
 *  - The legacy registry keys sources by string api name (per
 *    `SourcesRepository.getRepoByName(name: String)`), and existing user libraries / nav args
 *    carry the same string. Introducing a typed wrapper here would force a parallel mapping that
 *    serves no purpose for the rework's minimum-viable scope; we can lift the type later without
 *    breaking observable behaviour.
 *
 * Unknown sources:
 *  - When the api string doesn't match any registered source, the impl returns `false` (treat as
 *    "not adult"). This matches the legacy's effective behaviour because
 *    `SourcesRepository.getRepoByName` falls back to `EmptyMangaRepository` whose
 *    `blackListGenres = emptySet()`, and `gens.any { it in emptySet() }` is always `false`. The
 *    rework impl reaches the same conclusion via the new nullable `getOrRepoByName(...)?` API
 *    introduced in §42.2 — no behavioural delta.
 *
 * DIP (contract §6): consumers depend on this interface, never on the `:data` impl that holds
 * the legacy `SourcesRepository` reference. Koin binds the impl at the composition root
 * (`detailsReworkModule` — wired in this slice's `:composeApp` step).
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade.followup,
 * Task #447, 2026-05-28): the "Why this is not suspend" rationale (lines 14-15) cites
 * `MangaDerailsViewModel.isPlus18(gens, api)` in present tense as the synchronous-in-memory
 * precedent that justified the non-suspend contract. That VM was retired in
 * Phase 9.x.mangadetails.retire (§430, Slice 5 of the Phase 7.x.details.parity campaign);
 * verified by Glob search for `MangaDerailsViewModel.kt` returning zero hits. The
 * design rationale stands on its own merits — the synchronous in-memory characterisation
 * is a fact about the `:data` impl (`AdultContentClassifierImpl` reaches `BaseMangaRepository`
 * `blackListGenres: Set<String>` `val` fields, no I/O) and the not-suspend contract is the
 * right shape for that policy regardless of which consumer ports it. Original §253-era
 * prose preserved verbatim per §253 — the legacy citation is historical record of the
 * design lineage.
 */
interface AdultContentClassifier {

    /**
     * Returns `true` when any of [genres] overlaps the blacklist that source [api] declares as
     * adult content. Pure in-memory lookup — no I/O, safe to call from a Compose `remember {…}`
     * block or any synchronous context.
     *
     * Case-sensitivity matches the legacy: the lookup compares `genres` against the source's
     * `blackListGenres` set verbatim. Sources that want case-insensitive matching pre-normalise
     * their blacklist (e.g. lower-cased strings) — same constraint the rework `Manga.genres`
     * KDoc declares ("lower-cased + trimmed").
     */
    fun isAdultContent(api: String, genres: List<String>): Boolean
}
