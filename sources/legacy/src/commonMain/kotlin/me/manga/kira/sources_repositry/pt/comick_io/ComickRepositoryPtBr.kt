package me.manga.kira.sources_repositry.pt.comick_io

/**
 * Migration note (Phase 7.7): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * Disabled in the upstream Android source: the original file
 * `app/src/main/java/me/manga/yami/sources_repositry/pt/comick_io/ComickRepositoryPtBr.kt`
 * is fully commented out (declares no `package` and no live code). The corresponding
 * `MangaSource.COMICKIO_PT` enum entry also does not exist in commonMain `data/MangaSource.kt`
 * (verified in this session — only `COMICKIO` (EN), `MANGAPARK_IT`, and the various pt sources
 * `MANHASTRO`, `FLOWERMANGA`, `MEDIOCRETOONS`, `SUSSYTOONS` are present), so there is no
 * language wiring to attach this subclass to.
 *
 * We mirror the upstream "disabled" state in commonMain rather than inventing a working PT-BR
 * variant. The intended shape — straight from the disabled Android source, retargeted to the
 * KMP package + `ApiClient` — is preserved below as a comment so that if/when Comick adds a
 * Portuguese feed, both this file and `MangaSource` can be re-enabled together:
 *
 * ```
 * class ComickRepositoryPtBr(
 *     api: ApiClient,
 *     dataStore: DataStoreHelper,
 *     sourcesRepository: SourcesDao,
 * ) : ComickRepository(api, dataStore, sourcesRepository) {
 *     override val API: String
 *         get() = MangaSource.COMICKIO_PT.API
 *
 *     override val LANGUAGE: String
 *         get() = MangaSource.COMICKIO_PT.LANGUAGE.Language
 *
 *     override val language: String = "pt-br"
 * }
 * ```
 *
 * TODO(Phase 7.2): port EN `ComickRepository` to commonMain (prerequisite for this file).
 * TODO(Phase 7.7 — gated on upstream): add `COMICKIO_PT` to `MangaSource` if the upstream
 *   Android project ever re-enables the Brazilian Portuguese Comick feed, then uncomment the
 *   body above.
 */
