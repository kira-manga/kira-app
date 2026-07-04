package me.manga.kira.presentation.features.download.domain.clean

import kotlinx.coroutines.flow.first
import me.manga.kira.core.states.State
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources_repositry.BaseMangaRepository

/**
 * The resolved page set for a queued chapter: the ordered image URLs plus the header strategy the
 * page-fetch step must apply.
 *
 * Two shapes, mirroring the two source paths the engine already supported:
 *  - **Config/generic path** ([repo] == null): [overrideHeaders] are the per-chapter headers from
 *    the `SourceRegistry` ([ChapterPageProvider]); they are applied verbatim to every page.
 *  - **Legacy path** ([overrideHeaders] == null): [repo] is the resolved [BaseMangaRepository] whose
 *    `defaultHeaders` / MangamelloPlus `imgsHeader` rule is applied per page by the caller.
 *
 * This is the exact `(imageUrls, overrideHeaders, repo)` triple the engine's inline resolution block
 * produced before M1 — extracted unchanged so page-fetch and header semantics stay byte-identical.
 * (A later milestone evolves this into a fully pre-resolved per-page header list for the iOS
 * background `URLSession` engine.)
 */
data class ResolvedChapter(
    val imageUrls: List<String>,
    val overrideHeaders: Map<String, String>?,
    val repo: BaseMangaRepository?,
)

/**
 * Resolves the page URLs (+ header strategy) for a queued chapter.
 *
 * M1 (clean seam): extracted verbatim from `CoroutineDownloadRepositoryImpl.processJob` so the shared
 * download orchestration has one reusable "what are this chapter's pages?" step that both the Desktop
 * coroutine engine and the iOS background engine reuse. Pure resolution — it never touches the
 * download queue / Room state; the caller decides what to do with an empty result.
 */
class ChapterPageResolver(
    private val mangaDao: MangaDao,
    // Sources Migration Phase 3: routes config-backed downloads through SourceRegistry (generic-
    // ONLY — the registry has no legacy fallback); returns null for non-config sources → legacy
    // path below.
    private val chapterPageProvider: ChapterPageProvider,
    private val sourcesRepository: SourcesRepository,
) {

    suspend fun resolve(entity: ChapterDownloadEntity): ResolvedChapter {
        // Sources Migration Phase 3: route config-backed sources through the SourceRegistry
        // (generic-only). For a non-config source the provider returns null and we fall through to
        // the legacy scraper path below, byte-identical to before.
        val manga = mangaDao.getMangaById(entity.mangaId)
        val providerPages = chapterPageProvider.pagesOrNull(
            api = entity.api,
            mangaUrl = manga?.url.orEmpty(),
            mangaLanguage = manga?.language.orEmpty(),
            chapterUrl = entity.url,
        )

        return if (providerPages != null) {
            // Generic/config-driven path — page URLs + headers from the registry; no legacy repo touched.
            ResolvedChapter(
                imageUrls = providerPages.map { it.url },
                overrideHeaders = providerPages.firstOrNull()?.headers ?: emptyMap(),
                repo = null,
            )
        } else {
            // Legacy path (non-config source) — unchanged.
            val repo = resolveRepo(entity.api)
            repo.initSite()
            ResolvedChapter(
                imageUrls = collectImageUrls(repo, entity),
                overrideHeaders = null,
                repo = repo,
            )
        }
    }

    private suspend fun resolveRepo(apiName: String?): BaseMangaRepository {
        return if (apiName.isNullOrBlank()) {
            sourcesRepository.activeRepo.first()
        } else {
            sourcesRepository.getRepoByName(apiName)
        }
    }

    private suspend fun collectImageUrls(
        repo: BaseMangaRepository,
        entity: ChapterDownloadEntity,
    ): List<String> {
        // Walk the source's chapter-image flow to a terminal State. Mirrors the Android worker's
        // logic without the ProMangaChapter streaming-batch fork (Prochan-specific batched URLs
        // would need a separate non-suspend interface to consume — out of scope for the first
        // iOS/Desktop port).
        var result: List<String> = emptyList()
        repo.fetchChapterDataF(entity.url).collect { state ->
            when (state) {
                is State.Success -> result = state.data
                is State.Error -> throw IllegalStateException(state.message)
                State.Loading -> Unit
            }
        }
        return result
    }
}
