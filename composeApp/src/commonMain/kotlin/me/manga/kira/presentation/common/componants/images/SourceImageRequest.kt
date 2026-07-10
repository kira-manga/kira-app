package me.manga.kira.presentation.common.componants.images

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources_repositry.common.BaseManga
import org.koin.compose.koinInject

/**
 * Builds a Coil [ImageRequest] for a source image at [url] with per-source [headers] attached as
 * `NetworkHeaders` (Cookie / User-Agent / Referer). This mirrors what
 * `BaseMangaRepository.buildImageRequest` does on the repository side — call sites that already
 * have the headers in scope (e.g. the reader, where `ReaderItem.ImagePage.headers` was captured at
 * fetch time) use this helper directly instead of looking the repo up by host. Bypassing the
 * Coil ImageLoader interceptor host-match removes the silent-fail mode where image hosts on a
 * different subdomain than the source's stored base URL (e.g. Lekmanga's
 * `tempsolo.lek-manga.net` page CDN vs. its `lekmanga.net` default base) miss the lookup and
 * load without auth headers — the root cause of Bug 4 layer 4.
 *
 * Pass `screenWidthPx = 0` (default) to request the natural size (no downscale). Pass a positive
 * value to request a width-clamped image — height is left undefined so Coil preserves aspect.
 */
@Composable
fun rememberSourceImageRequest(
    url: String,
    headers: Map<String, String>,
    screenWidthPx: Int = 0,
): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(url, headers, screenWidthPx) {
        val networkHeaders = NetworkHeaders.Builder().apply {
            headers.forEach { (key, value) -> add(key, value) }
        }.build()
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(networkHeaders)
            // Coil 3 defaults `maxBitmapSize` to `Size(4096, 4096)` and the loader-level override
            // in `App.kt` doesn't propagate to every request. Webtoon strips are typically
            // 800×~14000; with the default cap, aspect-preservation collapses width to ~234 px
            // before the decoder runs, producing a low-resolution bitmap that Compose then has to
            // upscale at draw time. Setting it per-request to `(Undefined, Undefined)` lets the
            // `size(...)` hint be the only width constraint; height is left unrestricted so tall
            // webtoon strips decode at full resolution.
            .maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
            .apply {
                if (screenWidthPx != 0 && shouldConstrainImageSizeToScreen()) {
                    size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)
                }
            }
            .applyPlatformDecoderHints()
            .build()
    }
}

/**
 * Variant for cover / grid thumbnails where the call site knows the source [api] (e.g. from
 * `MangaItem.api` / `SavedMangaEntity.api`) but not the headers. Resolves the repo via
 * [SourcesRepository.getRepoByName] and reads its `defaultHeaders` map. If the repo hasn't been
 * hit by a fetch yet in this app session, `defaultHeaders` may be empty — in that case we kick
 * off a one-shot lazy [BaseManga.ensureSiteInitialized] inside a `LaunchedEffect` and re-emit the
 * request once headers are hydrated. This makes the library/history screens (which render covers
 * for sources that haven't been queried in this session) authenticate correctly on Cloudflare-
 * protected sources without forcing every screen to call `initSite()` manually.
 *
 * Pass `screenWidthPx = 0` (default) to request natural size. Pass a positive value to clamp
 * width and leave height undefined for aspect preservation.
 */
@Composable
fun rememberSourceImageRequest(
    url: String,
    api: String,
    screenWidthPx: Int = 0,
): ImageRequest {
    val context = LocalPlatformContext.current
    val sourcesRepository: SourcesRepository = koinInject()
    val sourceRegistry: SourceRegistry = koinInject()
    val dataStore: DataStoreHelper = koinInject()
    // MangaSource decoupling (2026-07): CONFIG-FIRST header resolution. A config-backed api's
    // headers come from the same stores the generic engine uses — the stanza's static headers
    // (available synchronously via the descriptor) merged UNDER the captured per-api headers
    // (loaded async below) — with NO compiled legacy repo needed. Non-config apis keep the legacy
    // repo hydration path unchanged.
    val configBacked = remember(api) { sourceRegistry.isConfigBacked(api) }
    val repo = remember(api, configBacked) { if (configBacked) null else sourcesRepository.getRepoByName(api) }
    var headersSnapshot by remember(api, configBacked) {
        mutableStateOf(
            if (configBacked) {
                sourceRegistry.descriptor(api)?.headers.orEmpty()
            } else {
                repo?.defaultHeaders.orEmpty()
            },
        )
    }

    LaunchedEffect(api, configBacked) {
        if (configBacked) {
            try {
                val captured = dataStore.getHeadersForApi(api).orEmpty()
                if (captured.isNotEmpty()) headersSnapshot = headersSnapshot + captured
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // static-only (or empty) headers; raw-URL fallback
            }
        } else if (repo is BaseManga && headersSnapshot.isEmpty()) {
            try {
                repo.ensureSiteInitialized()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // headers stay empty; raw-URL fallback
            }
            headersSnapshot = repo.defaultHeaders
        }
    }

    return remember(url, headersSnapshot, screenWidthPx) {
        val networkHeaders = NetworkHeaders.Builder().apply {
            headersSnapshot.forEach { (key, value) -> add(key, value) }
        }.build()
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(networkHeaders)
            // Coil 3 defaults `maxBitmapSize` to `Size(4096, 4096)` and the loader-level override
            // in `App.kt` doesn't propagate to every request. Webtoon strips are typically
            // 800×~14000; with the default cap, aspect-preservation collapses width to ~234 px
            // before the decoder runs, producing a low-resolution bitmap that Compose then has to
            // upscale at draw time. Setting it per-request to `(Undefined, Undefined)` lets the
            // `size(...)` hint be the only width constraint; height is left unrestricted so tall
            // webtoon strips decode at full resolution.
            .maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
            .apply {
                if (screenWidthPx != 0 && shouldConstrainImageSizeToScreen()) {
                    size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)
                }
            }
            .applyPlatformDecoderHints()
            .build()
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster156.staleKdocSweep.cascade,
 * Task #612, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundred-and-fifth sibling of the cluster57-155 sweep —
 * OPENING file of the wave-28 :composeApp/presentation/common/componants/
 * images/ 2-leaf batch alongside KtorPageProgressObserver; OPENS images/
 * tier 1/2):
 *  (a) "Builds-a-Coil-ImageRequest-for-a-source-image-at-url-with-per-
 *  source-headers-attached-as-NetworkHeaders-Cookie-User-Agent-Referer +
 *  This-mirrors-what-BaseMangaRepository.buildImageRequest-does-on-the-
 *  repository-side + Call-sites-that-already-have-the-headers-in-scope-
 *  e.g.-the-reader-where-ReaderItem.ImagePage.headers-was-captured-at-
 *  fetch-time-use-this-helper-directly-instead-of-looking-the-repo-up-by-
 *  host + Bypassing-the-Coil-ImageLoader-interceptor-host-match-removes-
 *  the-silent-fail-mode-where-image-hosts-on-a-different-subdomain-than-
 *  the-source-s-stored-base-URL-miss-the-lookup-and-load-without-auth-
 *  headers-the-root-cause-of-Bug-4-layer-4 + Pass-screenWidthPx-0-default
 *  -to-request-the-natural-size-no-downscale + Pass-a-positive-value-to-
 *  request-a-width-clamped-image-height-is-left-undefined-so-Coil-
 *  preserves-aspect + Coil-3-defaults-maxBitmapSize-to-Size-4096-4096-and
 *  -the-loader-level-override-in-App.kt-does-not-propagate-to-every-
 *  request + Webtoon-strips-are-typically-800-by-14000-with-the-default-
 *  cap-aspect-preservation-collapses-width-to-234-px-before-the-decoder-
 *  runs-producing-a-low-resolution-bitmap-that-Compose-then-has-to-
 *  upscale-at-draw-time + Setting-it-per-request-to-Undefined-Undefined-
 *  lets-the-size-hint-be-the-only-width-constraint-height-is-left-
 *  unrestricted-so-tall-webtoon-strips-decode-at-full-resolution +
 *  Variant-for-cover-grid-thumbnails-where-the-call-site-knows-the-source
 *  -api-e.g.-from-MangaItem.api-SavedMangaEntity.api-but-not-the-headers
 *  + Resolves-the-repo-via-SourcesRepository.getRepoByName-and-reads-its-
 *  defaultHeaders-map + If-the-repo-has-not-been-hit-by-a-fetch-yet-in-
 *  this-app-session-defaultHeaders-may-be-empty-in-that-case-we-kick-off-
 *  a-one-shot-lazy-BaseManga.ensureSiteInitialized-inside-a-LaunchedEffect
 *  -and-re-emit-the-request-once-headers-are-hydrated + This-makes-the-
 *  library-history-screens-which-render-covers-for-sources-that-have-not
 *  -been-queried-in-this-session-authenticate-correctly-on-Cloudflare-
 *  protected-sources-without-forcing-every-screen-to-call-initSite-
 *  manually" — LIVE-NOT-STALE. Verified: two @Composable
 *  rememberSourceImageRequest overloads shipped — (url, headers,
 *  screenWidthPx) headers-known variant + (url, api, screenWidthPx) repo-
 *  lookup variant. Both honor: (i) per-source NetworkHeaders (Cookie/UA/
 *  Referer) attachment via httpHeaders; (ii) maxBitmapSize(Undefined,
 *  Undefined) per-request override defeating Coil 3 default 4096×4096
 *  cap on tall webtoon strips; (iii) screenWidthPx-conditional
 *  size(Pixels, Undefined) clamp gated on shouldConstrainImageSizeToScreen;
 *  (iv) applyPlatformDecoderHints chain. The repo-lookup variant
 *  additionally honors the LaunchedEffect-driven ensureSiteInitialized
 *  lazy-hydration for Cloudflare-protected covers on first-session
 *  surfaces (library/history). The "Bug 4 layer 4 silent-fail bypass"
 *  load-bearing rationale honored — direct header attachment avoids
 *  Coil interceptor host-match misses on CDN-subdomain image hosts (e.g.
 *  Lekmanga's tempsolo.lek-manga.net page CDN vs. lekmanga.net default
 *  base). Consumed by ReaderScreen + ReaderPageItem + MangaCard +
 *  Details cover + Library MangaCard call sites. OPENING FILE of the
 *  cluster156 :composeApp/presentation/common/componants/images/ 2-leaf
 *  batch (1 of 2: SourceImageRequest + KtorPageProgressObserver). One
 *  classification. Original Phase 7.x.images.headerinjection prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
