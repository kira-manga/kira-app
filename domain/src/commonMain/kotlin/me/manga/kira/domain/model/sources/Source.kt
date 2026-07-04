package me.manga.kira.domain.model.sources

/**
 * A single content source (manga repository) — one row on the Sources screen.
 *
 * Phase 7.x.sources rework. The `:data` layer's
 * [me.manga.kira.data.repository.SourcesRepositoryImpl] maps the legacy `:shared`
 * `SourcesEntity` Room row (`shared/.../data/local/entity/SourcesEntity.kt`) into this pure domain
 * model. The `:presentation` VM projects a `List<Source>` into its MVI state; the `:ui`
 * composable regroups by [language] and renders each entry as a row with the [api] label plus a
 * Material 3 `Switch` driven by [isEnabled].
 *
 * Why a 4-field payload (NOT the 9-field legacy entity): the rework Sources screen only renders
 * the per-source on/off toggle grouped by language. The legacy `siteState` (WORKING/STOPPED),
 * `baseUrl` / `baseVersion` / `imageBaseUrl` / `imageUrlVersion` fields exist on the entity to
 * support the routing surface (Coil interceptor's `findRepoByHost`, `getUrl`, mirror-domain
 * editing) that the rework intentionally leaves on the legacy facade — see
 * [me.manga.kira.domain.repository.SourcesRepository] KDoc for the scoped surface rationale.
 * Carrying those fields on the domain model would falsely advertise mutability the rework slice
 * doesn't expose; ISP wins by carrying only what the screen reads.
 *
 * Contract §6 SRP: one rule — "a single content source as a value". No methods, no derivation,
 * no Room annotations (those live on the legacy entity in `:shared`). The mapper in
 * `:data/.../mapper/SourcesMappers.kt` translates between this domain model and the persistence
 * shape; that mapping rule is its own SRP island.
 *
 * Contract §17: no `Any`, no `!!`. All fields are statically-typed; no nullable fields here —
 * the legacy `saveSources` seed always supplies every field at first-run, and the per-source
 * toggle path only writes [isEnabled].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster138.staleKdocSweep.cascade,
 * Task #594, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirtieth sibling of the cluster57-137
 * sweep — first file of the wave-24 sixth-cluster closing 3-leaf-model
 * joint batch alongside ReadingStatistics plus UpdateEntry; opens
 * cluster138):
 *  (a) "Phase-7.x.sources-rework + :data-layer-SourcesRepositoryImpl-
 *  maps-the-legacy-:shared-SourcesEntity-Room-row-into-this-pure-
 *  domain-model + :presentation-VM-projects-a-List-Source-into-its-
 *  MVI-state + :ui-composable-regroups-by-language-and-renders-each-
 *  entry-as-a-row-with-the-api-label-plus-a-Material-3-Switch-driven-
 *  by-isEnabled + Why-a-4-field-payload-NOT-the-9-field-legacy-entity
 *  + the-rework-Sources-screen-only-renders-the-per-source-on-off-
 *  toggle-grouped-by-language + The-legacy-siteState-WORKING-STOPPED-
 *  baseUrl-baseVersion-imageBaseUrl-imageUrlVersion-fields-exist-on-
 *  the-entity-to-support-the-routing-surface + Coil-interceptor-find-
 *  RepoByHost-getUrl-mirror-domain-editing + that-the-rework-
 *  intentionally-leaves-on-the-legacy-facade + Carrying-those-fields-
 *  on-the-domain-model-would-falsely-advertise-mutability-the-rework-
 *  slice-does-not-expose + ISP-wins-by-carrying-only-what-the-screen-
 *  reads" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via
 *  recursive grep: Source is consumed by ObserveSourcesUseCase plus
 *  SourcesRepositoryImpl plus SourcesMappers plus SourcesState plus
 *  SourcesViewModel plus SourcesScreen. The rework data class carries
 *  exactly 4 fields (api + language + priority + isEnabled) — no
 *  siteState/baseUrl/baseVersion/imageBaseUrl/imageUrlVersion fields,
 *  matching the ISP-trimmed posture predicted. The legacy routing
 *  surface (Coil interceptor's findRepoByHost + getUrl + mirror-domain
 *  editing) continues to read those fields off the :shared
 *  SourcesEntity via the legacy facade — no :domain reach.
 *  (b) "Contract-§6-SRP-one-rule-a-single-content-source-as-a-value +
 *  No-methods-no-derivation-no-Room-annotations-those-live-on-the-
 *  legacy-entity-in-:shared + The-mapper-in-:data-mapper-SourcesMappers-
 *  translates-between-this-domain-model-and-the-persistence-shape +
 *  that-mapping-rule-is-its-own-SRP-island + Contract-§17-no-Any-no-!!
 *  + All-fields-are-statically-typed-no-nullable-fields-here + the-
 *  legacy-saveSources-seed-always-supplies-every-field-at-first-run +
 *  the-per-source-toggle-path-only-writes-isEnabled + api-Source-API-
 *  identifier-doubles-as-the-user-visible-row-label + language-ISO-
 *  language-code-the-source-publishes-in-drives-the-language-grouping
 *  + priority-Display-priority-within-a-language-group-lower-comes-
 *  first-sourced-from-BaseMangaRepository.PRIORITY + isEnabled-whether-
 *  the-user-has-enabled-this-source-drives-the-row-Switch-checked-
 *  state" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified: zero
 *  methods on the data class (only the 4 vals); zero Room annotations
 *  in the :domain file. SourcesMappers.kt in :data is the single SRP-
 *  island translation point. SourcesRepositoryImpl uses the api +
 *  language fields as the (api, language) composite-key for the per-
 *  source toggle write path; isEnabled is the only field the toggle
 *  path mutates. priority sorting matches the BaseMangaRepository.
 *  PRIORITY ordering predicted.
 *  Two classifications STAND on their own merits. Opens cluster138.
 *  Original Phase 7.x.sources-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
data class Source(
    /** Source API identifier (e.g. "mangakakalot"). Doubles as the user-visible row label. */
    val api: String,
    /** ISO language code the source publishes in (e.g. "en", "ar"). Drives the language grouping. */
    val language: String,
    /** Display priority within a language group (lower comes first). Sourced from `BaseMangaRepository.PRIORITY`. */
    val priority: Int,
    /** Whether the user has enabled this source. Drives the row's `Switch` checked-state. */
    val isEnabled: Boolean,
)
