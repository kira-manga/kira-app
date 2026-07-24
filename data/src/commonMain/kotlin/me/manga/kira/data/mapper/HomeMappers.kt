package me.manga.kira.data.mapper

import me.manga.kira.core.error.AppError
import me.manga.kira.core.error.TransportErrorMessages
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.sources.contracts.isValidSourceBaseUrl
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.domain.model.ChapterItem as LegacyChapterItem
import me.manga.kira.domain.model.MangaItem as LegacyMangaItem
import me.manga.kira.domain.model.PopularManga as LegacyPopularManga

/**
 * Mappers for the Home + Search slice (Epic H2).
 *
 * SRP (contract §6): one file owns the translation between the legacy `:shared` Home/Search types
 * (`MangaItem`, `PopularManga`, `ChapterItem`, `BaseMangaRepository`, `SourceState`, `SearchType`,
 * `core.states.State`) and the rework `:domain` Home models. The legacy types stay confined to
 * `:data` — the `:domain` interfaces ([me.manga.kira.domain.repository.HomeFeedRepository] /
 * [me.manga.kira.domain.repository.SearchRepository]) only ever see the rework models, and the
 * legacy `SearchType` is built here (never exposed to `:domain`, locked decision H-§87).
 *
 * Same convention as [SourcesMappers.kt] / [MangaDetailsMappers.kt]: top-level `internal`
 * extension functions so the call sites in `HomeFeedRepositoryImpl` / `SearchRepositoryImpl` read
 * naturally (`item.toHomeFeedItem()`), and the mapping stays an implementation detail of `:data`.
 *
 * **Transitional `:data` → `:shared` seam**: every legacy type imported here lives in `:shared`
 * (`me.manga.kira.domain.model.*`, `me.manga.kira.sources_repositry.*`,
 * `me.manga.kira.presentation.features.*`). This is the same strangler-fig boundary the Details
 * slice uses ([MangaDetailsMappers.kt]); the `:shared` dep is removed once the source-routing /
 * Room layers relocate into `:data`. The `sources_repositry/` subtree is out of scope (left as-is
 * per user direction); this mapper bridges it without modifying it.
 */

/**
 * Map a legacy [LegacyMangaItem] (a Home-grid / search-row record) → the rework [HomeFeedItem].
 *
 *  - `imageUrl` → [HomeFeedItem.coverUrl] (rename to match the rework `Manga.coverUrl` convention;
 *    same meaning — an empty string when the source ships no cover).
 *  - `chapters: List<ChapterItem>?` → [HomeFeedItem.recentChapters]: a non-null (possibly empty)
 *    list of lightweight [HomeChapterRef]s carrying only the `(number, url, isDownloaded)` triple
 *    the Home chapter chips render + tap into the Reader (locked decision H-§74-(1)). Null/empty
 *    legacy chapter lists collapse to an empty list.
 *  - `api` / `language` / `title` / `url` / `rating` / `genres` carried verbatim. `language` is
 *    retained so the heart-sync use cases can key library membership on (api + language + title).
 */
internal fun LegacyMangaItem.toHomeFeedItem(): HomeFeedItem = HomeFeedItem(
    api = api,
    language = language,
    title = title,
    url = url,
    coverUrl = imageUrl,
    rating = rating,
    genres = genres,
    recentChapters = chapters?.map { it.toHomeChapterRef() } ?: emptyList(),
)

/**
 * Map a legacy [LegacyChapterItem] → the rework [HomeChapterRef].
 *
 * Drops the legacy `name` / `date` / `isBookmarked` fields — the Home chip only needs the chapter
 * [HomeChapterRef.number] it displays, the [HomeChapterRef.url] to open in the Reader, and the
 * [HomeChapterRef.isDownloaded] flag that drives the offline-available affordance. Full chapter
 * metadata lives on the rework `Chapter` in the Details/Reader surfaces.
 */
internal fun LegacyChapterItem.toHomeChapterRef(): HomeChapterRef = HomeChapterRef(
    number = number,
    url = url,
    isDownloaded = isDownloaded,
)

/**
 * Map a legacy [LegacyPopularManga] (a carousel record) → the rework [FeaturedManga].
 *
 * 1:1 field mirror with the `imageUrl` → [FeaturedManga.coverUrl] rename; no speculative fields
 * (the legacy carousel renders exactly cover + title and taps into Details).
 */
internal fun LegacyPopularManga.toFeatured(): FeaturedManga = FeaturedManga(
    api = api,
    language = language,
    title = title,
    url = url,
    coverUrl = imageUrl,
)

/**
 * Map an enabled sources row (+ its validated config descriptor) → the rework [SourceTab].
 *
 *  - row `name` → [SourceTab.api] (the tab identity + fetch/search key).
 *  - row `language` → [SourceTab.language] (label/grouping).
 *  - `name` also seeds the vestigial [SourceTab.iconKey]; icon resolution happens by api through
 *    the app-root icon seam (`LocalSourceIconResolver`), per the [SourceTab] KDoc.
 *  - [SourceTab.displayName] joins from the validated config descriptor (MangaSource decoupling,
 *    2026-07) — the tab needs NO compiled BaseMangaRepository.
 */
internal fun SourcesEntity.toSourceTab(descriptor: RuntimeSourceDescriptor?): SourceTab = SourceTab(
    api = name,
    language = language,
    iconKey = name,
    siteState = siteState.toSiteState(),
    // The source's base URL — Home's "open in WebView" opens this (native parity). The Room row is
    // the live field (asserted to config truth by the catalog sync, and user-mirror-editable);
    // the descriptor's baseUrl is the fallback for a not-yet-synced row.
    // A legacy/user-editable Room row may contain a stale non-network value (observed:
    // `about:about`). It must not override the validated active descriptor or reach WKWebView.
    baseUrl = baseUrl.takeIf(::isValidSourceBaseUrl) ?: descriptor?.baseUrl.orEmpty(),
    // MangaSource decoupling (2026-07): the tab is built from the row + the validated config
    // descriptor — no BaseMangaRepository required, so a config-only source appears like any other.
    displayName = descriptor?.displayName ?: name,
)

/**
 * Map the legacy [SourceState] enum → the rework [SiteState] enum (value-for-value; the constants
 * share names because [SiteState] mirrors [SourceState] verbatim — see [SiteState] KDoc).
 */
internal fun SourceState.toSiteState(): SiteState = when (this) {
    SourceState.WORKING -> SiteState.WORKING
    SourceState.UNDER_MAINTENANCE -> SiteState.UNDER_MAINTENANCE
    SourceState.STOPPED -> SiteState.STOPPED
    SourceState.ADULT_18_PLUS -> SiteState.ADULT_18_PLUS
}

/**
 * Map a legacy [LegacyState.Error] (HTTP status code OR transport sentinel code = 0) to the
 * matching [AppError]. Mirrors the [LegacyState.Error.Companion.fromException] heuristics so the
 * surfaced buckets line up with the rest of the rework `:data` boundary (same approach as
 * [MangaDetailsRepositoryImpl][me.manga.kira.data.repository.MangaDetailsRepositoryImpl]).
 */
internal fun LegacyState.Error.toAppError(): AppError {
    val status = code ?: 0
    if (status in 400..599) {
        return AppError.Network.Http(statusCode = status)
    }
    val raw = message.lowercase()
    return when {
        // A code-0 emission whose body/message betrays a Cloudflare / anti-bot interstitial is a
        // SOLVABLE challenge, not a hard failure (bug #2). Many sources serve the challenge HTML
        // with a 200/0 that the parser rejects, so the status code is lost — re-surface it as a
        // 403 so the Home/Search VMs route the user to the WebView solver instead of a
        // dead-end "failed to load". See [isChallengeMessage].
        isChallengeMessage(raw) ->
            AppError.Network.Http(statusCode = 403)
        TransportErrorMessages.isConnectivityMessage(raw) ->
            AppError.Network.NoConnectivity()
        TransportErrorMessages.isTimeoutMessage(raw) ->
            AppError.Network.Timeout()
        else ->
            AppError.Unexpected(message = message)
    }
}

/**
 * Map a [Throwable] thrown while collecting a legacy source flow (vs. a [LegacyState.Error]
 * emission) to an [AppError], using the same connectivity/timeout heuristics so both paths
 * classify identically — many legacy sources throw on bad responses rather than emitting
 * `State.Error`. Mirror of `MangaDetailsRepositoryImpl.classifyThrowable`.
 *
 * [CancellationException] is re-thrown by callers before this is reached (structured-concurrency
 * invariant); this helper assumes a non-cancellation throwable.
 */
internal fun classifyHomeThrowable(t: Throwable): AppError {
    val raw = (t.message ?: "").lowercase()
    return when {
        // Same Cloudflare-challenge re-surfacing as [toAppError] (bug #2): a source that THROWS
        // on the interstitial (TLS quirk, parser choke on the challenge body, "403 forbidden"
        // in the exception message) loses its status code here. Map it back to 403 so the VM
        // offers the WebView solver rather than a non-recovering generic error.
        isChallengeMessage(raw) ->
            AppError.Network.Http(statusCode = 403)
        TransportErrorMessages.isConnectivityMessage(raw) ->
            AppError.Network.NoConnectivity(cause = t)
        TransportErrorMessages.isTimeoutMessage(raw) ->
            AppError.Network.Timeout(cause = t)
        else ->
            AppError.Unexpected(message = t.message ?: t::class.simpleName.orEmpty(), cause = t)
    }
}

/**
 * Heuristic: does a (lowercased) error/exception message look like a Cloudflare / anti-bot
 * interstitial the user can clear in a WebView? Kept conservative — only well-known challenge
 * signatures, not a bare "forbidden", so genuine 4xx/5xx app errors still surface normally.
 */
private fun isChallengeMessage(raw: String): Boolean =
    raw.containsAny(
        "cloudflare",
        "just a moment",
        "checking your browser",
        "attention required",
        "cf-ray",
        "cf_chl",
        "ddos-guard",
        "ddos guard",
        "403 forbidden",
        "access denied",
    )

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { this.contains(it) }
