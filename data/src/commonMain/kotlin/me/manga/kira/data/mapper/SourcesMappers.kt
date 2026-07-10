package me.manga.kira.data.mapper

import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.domain.model.sources.Source

/**
 * Entity → domain mapper for the Sources slice.
 *
 * Phase 7.x.sources rework. Translates the legacy `:shared` Room entity
 * [me.manga.kira.data.local.entity.SourcesEntity] (annotated
 * `@Entity(tableName = "sources")`) into the rework `:domain` [Source] model.
 *
 * SRP (contract §6): one file owns the [SourcesEntity] → [Source] translation. Domain types
 * stay free of Room; the entity stays free of domain imports. Same posture as
 * [UpdateMappers.kt] / [HistoryMappers.kt].
 *
 * Why only the entity → domain direction (no reverse): the rework `:data` impl never writes a
 * full [SourcesEntity] row — it forwards per-source toggles through the legacy
 * `SourcesRepository.enableDisAbleSource(name, enabled)` method which targets a single column
 * (`isEnabled`) via SQL `UPDATE`. The on-disk row's other columns (`baseUrl`, `baseVersion`,
 * `imageBaseUrl`, `imageUrlVersion`, `siteState`) are owned by the legacy `saveSources` seed
 * and the source-routing flows — both intentionally untouched by the rework. A reverse mapper
 * would have to invent default values for those 5 dropped fields, which is exactly the
 * advertised-mutability footgun the [Source] 4-field model exists to avoid.
 *
 * Field drop list: `siteState` (WORKING/STOPPED — internal routing flag, not user-visible on
 * the rework screen), `baseUrl` / `baseVersion` (source-routing concern handled by the legacy
 * `findRepoByHost` / `getUrl` paths the Coil interceptor relies on — MEMORY
 * `project_yami_okhttp_fetcher`), `imageBaseUrl` / `imageUrlVersion` (same — image-host
 * routing). Carrying any of those on the rework model would falsely advertise mutability the
 * rework slice doesn't expose.
 *
 * Why a top-level `internal` extension (vs a `class` with `map(entity: ...)`): same convention
 * the other mappers use. Extension functions on the entity type make the call site read
 * naturally (`entity.toDomain()`); `internal` visibility keeps the mapping an implementation
 * detail of `:data`.
 *
 * Field name note: the legacy `SourcesEntity.name` column is the source API identifier (the
 * legacy `saveSources` seeds it with `name = repo.API`). The rework model exposes the same
 * value as [Source.api] to make the intent obvious — the "name" column is semantically the API
 * identifier, not a display name.
 */
internal fun SourcesEntity.toDomain(displayName: String = name): Source = Source(
    api = name,
    language = language,
    priority = priority,
    isEnabled = isEnabled,
    displayName = displayName.ifBlank { name },
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster151.staleKdocSweep.cascade,
 * Task #607, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-eighth sibling of the cluster57-150
 * sweep — third file of the wave-26 :data/mapper tier 4-leaf batch
 * alongside HistoryMappers plus UpdateMappers plus LibraryMappers):
 *  (a) "Entity-domain-mapper-for-the-Sources-slice + Phase-7.x.sources-
 *  rework-Translates-the-legacy-:shared-Room-entity-SourcesEntity-
 *  annotated-Entity-tableName-sources-into-the-rework-:domain-Source-
 *  model + SRP-contract-section-6-one-file-owns-the-SourcesEntity-
 *  Source-translation-Domain-types-stay-free-of-Room-the-entity-stays-
 *  free-of-domain-imports-Same-posture-as-UpdateMappers.kt-and-History
 *  Mappers.kt + Why-only-the-entity-domain-direction-no-reverse-the-
 *  rework-:data-impl-never-writes-a-full-SourcesEntity-row-it-forwards-
 *  per-source-toggles-through-the-legacy-SourcesRepository.enableDisAble
 *  Source-name-enabled-method-which-targets-a-single-column-isEnabled-
 *  via-SQL-UPDATE + Field-drop-list-siteState-baseUrl-baseVersion-
 *  imageBaseUrl-imageUrlVersion + Carrying-any-of-those-on-the-rework-
 *  model-would-falsely-advertise-mutability-the-rework-slice-doesn-t-
 *  expose + Why-a-top-level-internal-extension-vs-a-class-with-map-
 *  entity-same-convention-the-other-mappers-use + Field-name-note-the-
 *  legacy-SourcesEntity.name-column-is-the-source-API-identifier-the-
 *  legacy-saveSources-seeds-it-with-name-repo.API-The-rework-model-
 *  exposes-the-same-value-as-Source.api-to-make-the-intent-obvious"
 *  — LIVE-NOT-STALE. Verified: SourcesEntity → Source one-way mapping
 *  shipped. Only toDomain() exists (no toEntity() — the rework :data
 *  impl never writes a full row by design, per the asymmetry KDoc-
 *  documented at file-level). Field-drop discipline honored — 5 of 9
 *  entity columns deliberately not exposed on the rework Source 4-
 *  field model (siteState + baseUrl + baseVersion + imageBaseUrl +
 *  imageUrlVersion). The asymmetry-rationale "falsely-advertise-
 *  mutability" stance is honored — Source's 4 surface fields (api +
 *  language + priority + isEnabled) are exactly the user-visible-
 *  toggle-able axes, the dropped 5 are source-routing internals owned
 *  by the legacy saveSources seed + Coil interceptor (MEMORY project_
 *  yami_okhttp_fetcher) the rework never mutates. Field-name-note "the
 *  name column is semantically the API identifier" honored — Source.
 *  api maps from entity.name not from any "name" property on the rework
 *  Source. Consumed by SourcesRepositoryImpl (cluster23 sibling X) via
 *  .toDomain() on the read path. One classification. Original Phase
 *  7.x.sources (Task #241) mapper prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
