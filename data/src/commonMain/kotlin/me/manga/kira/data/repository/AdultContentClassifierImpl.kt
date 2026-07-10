package me.manga.kira.data.repository

import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources.contracts.SourceRegistry

/**
 * Source-backed [AdultContentClassifier] implementation.
 *
 * SRP (contract §6): owns ONE rule — "look up the source's blacklist via the legacy
 * [SourcesRepository] registry and check whether any of the supplied genres appear in it".
 *
 * DIP: depends on [AdultContentClassifier] (`:domain`) and [SourcesRepository] (legacy `:shared` —
 * transitional, removed in a later phase when sources move into `:data`). No Compose, no UI
 * types, no platform-specific APIs.
 *
 * Why this is a separate impl from [MangaDetailsRepositoryImpl] even though they share the
 * legacy [SourcesRepository] dependency:
 *  - Different SRP. Fetching details is a network/parse policy; classifying adult content is an
 *    in-memory policy lookup. Keeping them on separate impls means a future change to one
 *    (e.g. swapping the source registry, adding caching to fetches) doesn't ripple through to
 *    the other.
 *  - Different lifecycle semantics. The fetch impl runs on the I/O dispatcher; the classifier
 *    runs synchronously on the caller's thread. Bundling them would have forced a fake
 *    `withContext` wrap on the classifier or a fake "this method is actually sync" comment on
 *    the fetch — both worse than two small impls.
 *
 * Why `getOrRepoByName` (nullable) instead of `getRepoByName` (legacy non-null with empty
 * fallback): same call site as §42.2 — the rework treats unknown sources as a typed condition,
 * not as a silent empty result. For the adult-content gate the observable behaviour is
 * identical (`false` either way, see [AdultContentClassifier] KDoc), but the call site is
 * uniform across the rework's `:data` impls — both reach into `SourcesRepository` through the
 * same nullable accessor so we never accidentally rely on the legacy empty fallback.
 */
class AdultContentClassifierImpl(
    private val sourcesRepository: SourcesRepository,
    private val sourceRegistry: SourceRegistry,
) : AdultContentClassifier {

    override fun isAdultContent(api: String, genres: List<String>): Boolean {
        if (genres.isEmpty()) return false
        // MangaSource decoupling (2026-07): the config stanza's blacklist is consulted first, so a
        // CONFIG-ONLY source keeps its adult gate (previously the lookup was legacy-repo-only and
        // silently disappeared for an api with no compiled repo). A pilot whose stanza declares no
        // blacklist falls back to its compiled repo's list — parity with the pre-decoupling gate.
        val blacklist =
            sourceRegistry.descriptor(api)?.blacklistGenres?.takeIf { it.isNotEmpty() }
                ?: sourcesRepository.getOrRepoByName(api)?.blackListGenres
                ?: return false
        if (blacklist.isEmpty()) return false
        return genres.any { it in blacklist }
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster154.staleKdocSweep.cascade,
 * Task #610, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-eighth sibling of the cluster57-153
 * sweep — OPENING file of the wave-26 :data/repository misc cell-of-truth
 * trio 3-leaf batch alongside AboutRepositoryImpl plus WhatsNewRepositoryImpl;
 * OPENS :data/repository misc trio 1/3):
 *  (a) "Source-backed-AdultContentClassifier-implementation + SRP-contract
 *  -section-6-owns-ONE-rule-look-up-the-source-s-blacklist-via-the-legacy-
 *  SourcesRepository-registry-and-check-whether-any-of-the-supplied-genres-
 *  appear-in-it + DIP-depends-on-AdultContentClassifier-:domain-and-Sources
 *  Repository-legacy-:shared-transitional-removed-in-a-later-phase-when-
 *  sources-move-into-:data + No-Compose-no-UI-types-no-platform-specific-
 *  APIs + Why-this-is-a-separate-impl-from-MangaDetailsRepositoryImpl-even
 *  -though-they-share-the-legacy-SourcesRepository-dependency-Different-SRP
 *  -Fetching-details-is-a-network-parse-policy-classifying-adult-content-
 *  is-an-in-memory-policy-lookup + Keeping-them-on-separate-impls-means-a-
 *  future-change-to-one-doesn-t-ripple-through-to-the-other + Different-
 *  lifecycle-semantics-The-fetch-impl-runs-on-the-I-O-dispatcher-the-
 *  classifier-runs-synchronously-on-the-caller-s-thread + Why-getOrRepo
 *  ByName-nullable-instead-of-getRepoByName-legacy-non-null-with-empty-
 *  fallback-the-rework-treats-unknown-sources-as-a-typed-condition-not-as-
 *  a-silent-empty-result" — LIVE-NOT-STALE. Verified: source-backed in-
 *  memory blacklist lookup via legacy SourcesRepository.getOrRepoByName.
 *  isAdultContent(api, genres) short-circuits to false on three guards
 *  (genres.isEmpty / blacklist null / blacklist.isEmpty) before the
 *  genres.any { it in blacklist } intersection check. The "separate impl
 *  from MangaDetailsRepositoryImpl despite shared SourcesRepository
 *  dependency" SRP carve-out rationale honored — both impls reach into the
 *  same legacy registry but own different policies (network/parse vs in-
 *  memory lookup) so a future change to one doesn't ripple through to the
 *  other. The "getOrRepoByName nullable accessor" call-site uniformity
 *  stance honored — same pattern as MangaDetailsRepositoryImpl. Consumed
 *  by IsAdultContentUseCase (cluster119 sibling X) via the
 *  AdultContentClassifier interface, surfaced as state.isAdult on
 *  DetailsViewModel and drives the AdultConfirmationDialog gate. OPENING
 *  FILE of cluster154 — opens the wave-26 :data/repository misc cell-of-
 *  truth trio 3-leaf batch (1 of 3: AdultContentClassifierImpl +
 *  AboutRepositoryImpl + WhatsNewRepositoryImpl). One classification.
 *  Original Phase 6.3.4 adult-content gate impl prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
