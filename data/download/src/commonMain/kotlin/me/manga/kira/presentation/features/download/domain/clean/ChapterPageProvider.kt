package me.manga.kira.presentation.features.download.domain.clean

/**
 * A single chapter page image to download: its URL and the HTTP headers that must accompany the
 * request (Referer / cookies / captured Cloudflare clearance / User-Agent, etc.).
 */
data class DownloadPage(
    val url: String,
    val headers: Map<String, String>,
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
 * Contract (config-backed sources are GENERIC-ONLY — `FallbackSourceClient` is retained-but-unwired,
 * there is no legacy fallback for a routed failure; doc corrected 2026-07 source-lifecycle hardening):
 *  - Returns the ordered page URLs (+ per-page headers) for a chapter of a **config-backed** source,
 *    resolved through `SourceRegistry.get(api)` — i.e. the generic config-driven client.
 *  - Returns **null** ONLY when [api] is NOT config-backed (`isConfigBacked == false`) — the caller
 *    then runs its existing legacy download path unchanged.
 *  - A routed failure (generic fetch failed, empty page list, or no client) **throws**
 *    `GenericPagesFailedException` — it does NOT return null, so a config-backed source's failure is
 *    recorded as a failed download instead of silently regressing to the legacy scraper (the owner's
 *    "100% generic or fully legacy" rule).
 */
interface ChapterPageProvider {
    suspend fun pagesOrNull(
        api: String,
        mangaUrl: String,
        mangaLanguage: String,
        chapterUrl: String,
    ): List<DownloadPage>?
}
