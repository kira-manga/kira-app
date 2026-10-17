package me.manga.kira.presentation.features.download.domain.clean

/**
 * A single chapter page image to download: its URL and the HTTP headers that must accompany the
 * request (Referer / cookies / captured Cloudflare clearance / User-Agent, etc.).
 */
data class DownloadPage(
    val url: String,
    val headers: Map<String, String>,
)

/** Current catalog permission to refresh captured headers; an unavailable policy denies captures. */
data class DownloadHeaderPolicy(
    val usesCapturedHeaders: Boolean = false,
    val staticHeaders: Map<String, String> = emptyMap(),
)

/**
 * Sources Migration — Phase 3. The download engines' routing seam to the `SourceRegistry`.
 *
 * The two download engines (`DownloadWorkerV2` on Android, `CoroutineDownloadRepositoryImpl` on
 * iOS/Desktop) live in `:shared`, which by the module contract cannot see `:domain`
 * ([me.manga.kira.domain.model.reader.Page]) or `:sources:contracts`
 * ([me.manga.kira.sources.contracts.SourceRegistry]). This interface is therefore declared here in
 * `:shared` over `:shared`-local types, and the registry-aware implementation
 * (`RegistryChapterPageProvider`) is provided at the composition root `:composeApp` and bound by Koin
 * — plain DIP, with the dependency direction preserved.
 *
 * Contract: resolve through the authoritative active generic catalog or fail. The return is
 * deliberately non-null: absence, retirement, invalid configuration, an empty result, and network
 * failure must all fail the queued download rather than activating a legacy scraper.
 */
interface ChapterPageProvider {
    /** Resolve current signed policy without fetching pages or trusting a saved manifest's headers. */
    fun headerPolicy(api: String): DownloadHeaderPolicy = DownloadHeaderPolicy()

    suspend fun pagesOrNull(
        api: String,
        mangaUrl: String,
        mangaLanguage: String,
        chapterUrl: String,
    ): List<DownloadPage>
}
