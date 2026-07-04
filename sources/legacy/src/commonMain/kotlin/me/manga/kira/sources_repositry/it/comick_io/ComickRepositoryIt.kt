package me.manga.kira.sources_repositry.it.comick_io

/**
 * Migration note (Phase 7.6): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Disabled in the upstream Android source: the original file
 * `app/src/main/java/me/manga/yami/sources_repositry/it/comick_io/ComickRepositoryIt.kt`
 * is fully commented out (the file declares no `package` and no live code). The corresponding
 * `MangaSource.COMICKIO_IT` enum entry also does not exist (verified in this session — only
 * `COMICKIO` (EN), `MANGAPARK_IT`, and `MANGAWORLD` are present in
 * commonMain `data/MangaSource.kt`), so there is no language wiring to attach this subclass to.
 *
 * We mirror the upstream "disabled" state in commonMain rather than inventing a working IT
 * variant. The intended shape — straight from the disabled Android source, retargeted to the
 * KMP package + `ApiClient` — is preserved below as a comment so that if/when Comick adds an
 * Italian feed, both this file and `MangaSource` can be re-enabled together:
 *
 * ```
 * class ComickRepositoryIt(
 *     api: ApiClient,
 *     dataStore: DataStoreHelper,
 *     sourcesRepository: SourcesDao,
 * ) : ComickRepository(api, dataStore, sourcesRepository) {
 *     override val API: String
 *         get() = MangaSource.COMICKIO_IT.API
 *
 *     override val LANGUAGE: String
 *         get() = MangaSource.COMICKIO_IT.LANGUAGE.Language
 *
 *     override val language: String = "it"
 * }
 * ```
 *
 * TODO(Phase 7.2): port EN `ComickRepository` to commonMain (prerequisite for this file).
 * TODO(Phase 7.6 — gated on upstream): add `COMICKIO_IT` to `MangaSource` if the upstream
 *   Android project ever re-enables the Italian Comick feed, then uncomment the body above.
 */
