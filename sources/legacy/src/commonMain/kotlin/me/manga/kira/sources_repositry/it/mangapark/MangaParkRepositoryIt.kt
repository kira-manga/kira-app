package me.manga.kira.sources_repositry.it.mangapark

/**
 * Migration note (Phase 7.6): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Deferred port: this Italian variant extends the EN `MangaParkRepository` base class, which
 * lives under `me.manga.kira.sources_repositry.en.mangapark` and has not been ported to
 * commonMain yet (that work is part of Phase 7.2 — `en/` language repos). Writing the subclass
 * here would force a fake/stub of the entire EN MangaPark base class, which the migration rules
 * explicitly forbid. The original Android subclass only overrides four small properties
 * (language, API, LANGUAGE, BASE_URL) and adds no other behaviour, so the deferred port is
 * mechanical once the base class lands.
 *
 * The intended commonMain shape — preserved verbatim below as a comment — is:
 *
 * ```
 * class MangaParkRepositoryIt(
 *     api: ApiClient,
 *     dataStore: DataStoreHelper,
 *     sourcesRepository: SourcesDao,
 * ) : MangaParkRepository(api, dataStore, sourcesRepository) {
 *     override val language: String
 *         get() = "it"
 *
 *     override val API: String
 *         get() = MangaSource.MANGAPARK_IT.API
 *     override val LANGUAGE: String
 *         get() = MangaSource.MANGAPARK_IT.LANGUAGE.Language
 *     override val BASE_URL: String
 *         get() = MangaSource.MANGAPARK_IT.BASEURL
 * }
 * ```
 *
 * TODO(Phase 7.2): port EN `MangaParkRepository` to commonMain, then uncomment & wire this
 *   subclass. `MangaSource.MANGAPARK_IT` already exists in commonMain (verified in this
 *   session — see `data/MangaSource.kt` line 75) so no extra source-entry work is needed.
 */
