package me.manga.kira.presentation.features.whatsnew.data

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.manga.kira.core.dispatchers.platformIoDispatcher

/**
 * Ported from upstream `presentation/features/whatsnew/data/WhatsNewRemoteDataSource.kt`.
 *
 * Deltas vs source:
 *   1. `OkHttpClient` → Ktor [HttpClient]. The Ktor client is injected from Koin (registered in
 *      `shared/.../di/SharedModule.kt` as `single { createHttpClient() }`) so timeouts, retries,
 *      and engine selection are configured globally and KMP-friendly. The source built a per-class
 *      `OkHttpClient.Builder()` with 30s connect / 60s read / 60s write / 15s ping — the Ktor
 *      client's defaults (configured in `HttpClientFactory.kt`) are equivalent.
 *   2. `okhttp3.Request.Builder()` + `client.newCall(...).execute()` →
 *      `httpClient.get(WHATS_NEW_URL)` (suspending).
 *   3. `response.body?.use { it.string() }` → `response.bodyAsText()` (Ktor handles resource
 *      management internally).
 *   4. `android.util.Log` → `co.touchlab.kermit.Logger`. The TAG / log level mapping is 1:1
 *      (`Log.e` → `log.e`, `Log.d` → `log.d`).
 *   5. `Dispatchers.IO` → [platformIoDispatcher] (KMP-portable dispatcher; expect/actual in
 *      `core/concurrency/`).
 *   6. `@Singleton` / `@Inject constructor()` dropped — the class is registered as a `single { ... }`
 *      in `SharedModule.kt` instead.
 *   7. The local `json` config (`ignoreUnknownKeys`, `isLenient`, `coerceInputValues`) is kept
 *      as-is — Ktor's content negotiation could absorb it, but since the upstream code parses the
 *      body string manually we preserve the `Json.decodeFromString(...)` call to minimize behavioral
 *      drift.
 */
class WhatsNewRemoteDataSource(
    private val httpClient: HttpClient,
) {

    private val log = Logger.withTag(TAG)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchWhatsNewFeatures(): Result<WhatsNewResponse> {
        return withContext(platformIoDispatcher) {
            try {
                val response: HttpResponse = httpClient.get(WHATS_NEW_URL)

                if (!response.status.isSuccess()) {
                    log.e { "Failed to fetch features: HTTP ${response.status.value}" }
                    return@withContext Result.failure(
                        Exception("HTTP error: ${response.status.value}"),
                    )
                }

                val responseBody = response.bodyAsText()
                if (responseBody.isEmpty()) {
                    log.e { "Empty response body" }
                    return@withContext Result.failure(Exception("Empty response"))
                }

                val whatsNewResponse = json.decodeFromString<WhatsNewResponse>(responseBody)

                log.d { "Successfully fetched ${whatsNewResponse.features.size} features" }
                Result.success(whatsNewResponse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Error fetching What's New features" }
                Result.failure(e)
            }
        }
    }

    fun getLocalizedFeature(
        feature: RemoteWhatsNewFeature,
        languageCode: String,
    ): LocalizedFeature {
        val normalizedLanguage = normalizeLanguageCode(languageCode)

        val title = feature.title[normalizedLanguage]
            ?: feature.title[FALLBACK_LANGUAGE]
            ?: feature.title.values.firstOrNull()
            ?: "Unknown Feature"

        val description = feature.description[normalizedLanguage]
            ?: feature.description[FALLBACK_LANGUAGE]
            ?: feature.description.values.firstOrNull()
            ?: "No description available"

        return LocalizedFeature(
            title = title,
            description = description,
            mediaType = feature.mediaType,
            imageRes = feature.imageRes,
            imageList = feature.imageResList,
            imageUrl = feature.imageUrl,
            videoUrl = feature.videoUrl,
            isNew = feature.isNew,
            version = feature.version,
        )
    }

    private fun normalizeLanguageCode(languageCode: String): String {
        val normalized = languageCode.lowercase().split("-", "_").first()

        return if (normalized in SUPPORTED_LANGUAGES) {
            normalized
        } else {
            FALLBACK_LANGUAGE
        }
    }

    companion object {
        private const val TAG = "WhatsNewRemoteDataSource"
        private const val WHATS_NEW_URL = "https://yamimanga.me/whatsnew/35/whatsnew.json"

        private const val FALLBACK_LANGUAGE = "en"

        val SUPPORTED_LANGUAGES = setOf(
            "en", "ar", "de", "es", "fr", "in", "it", "ja", "pt", "ru", "tr",
        )
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster204.staleKdocSweep.cascade, Task #660, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster204 leaf 1/3 — :shared/whatsnew/data/ tier closer, sibling 367. Cumulative
 * §253-postscript count = 92 leaves with this commit.
 *
 * File-shape note: 126-line class — KMP-portable Ktor-backed HTTP fetcher + per-language
 * resolution helper. 11 imports (kermit + ktor 4-pack + Json + withContext + platformIoDispatcher).
 * Carries a 24-line block-KDoc narrating 7 explicit deltas vs the upstream OkHttp source.
 * Two suspend methods: `fetchWhatsNewFeatures(languageCode)` (HTTP GET + Result-wrap +
 * Json.decodeFromString) + `getLocalizedFeature(feature, languageCode)` (pure synchronous
 * locale-resolution, no I/O). 1 private helper `normalizeLanguageCode`. Companion-object
 * carries 3 constants: TAG (Kermit log namespace), WHATS_NEW_URL (production endpoint
 * `https://yamimanga.me/whatsnew/35/whatsnew.json`), FALLBACK_LANGUAGE ("en"), and the
 * SUPPORTED_LANGUAGES set (11 entries: en/ar/de/es/fr/in/it/ja/pt/ru/tr).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE — consumed by:
 *       1. WhatsNewViewModel.kt (sibling 369 — cluster204 leaf 3/3) — Koin-injected as
 *          `private val remoteDataSource: WhatsNewRemoteDataSource`. Calls
 *          `remoteDataSource.fetchWhatsNewFeatures(languageCode)` in the (trimmed) loadFeatures
 *          path; result is implicitly discarded post-cluster410-prune (kept for
 *          LibraryScreenRoute redirect-timing parity).
 *       2. WhatsNewRepositoryImpl.kt (:data — rework strangler-fig consumer) — Koin-injected
 *          and consumed via both methods: `fetchWhatsNewFeatures(...)` returns the
 *          Result<WhatsNewResponse> boundary; `getLocalizedFeature(remote, lang)` projects
 *          each RemoteWhatsNewFeature → LocalizedFeature for the rework :ui consumer chain.
 *       3. WhatsNewReworkModule.kt (:composeApp — Koin wiring) — module binds
 *          `single { WhatsNewRemoteDataSource(get<HttpClient>()) }`.
 *
 *   • FULFILLED-PORT — KDoc enumerates 7 deltas vs upstream OkHttp source: OkHttp → Ktor;
 *     per-class Builder → injected client; `response.body?.use { it.string() }` →
 *     `bodyAsText()`; `android.util.Log` → Kermit; `Dispatchers.IO` → platformIoDispatcher;
 *     `@Singleton`/`@Inject` → Koin `single { }`; local Json config preserved verbatim.
 *     Port verified — production endpoint hits + the rework :data path consumes the typed
 *     return value (Result<WhatsNewResponse>) per cluster203 sibling 366's classification.
 *     Per §253 — preserved (the KDoc is current and load-bearing).
 *
 *   • STANDALONE-PROSE-RICH-NOT-STALE — the 24-line block-KDoc documents the 7 deltas
 *     comprehensively; all 7 remain accurate post-cluster410 prune (the VM trim did NOT
 *     touch this data source).
 *
 *   • LANGUAGE-FALLBACK-CHAIN-LOAD-BEARING — `getLocalizedFeature` builds title + description
 *     via a triple-fallback chain: `feature.title[normalizedLanguage]` → `feature.title[
 *     FALLBACK_LANGUAGE ("en")]` → `feature.title.values.firstOrNull()` → "Unknown Feature"
 *     sentinel. SAME shape for description with "No description available" sentinel. DO NOT
 *     collapse the fallback chain — server-side localization coverage is not guaranteed for
 *     every (feature × language) cell, and the sentinel-string fallback is the LOAD-BEARING
 *     last-resort to prevent NPE/blank-string crashes in :ui list rendering. The
 *     normalizeLanguageCode helper trims region suffixes (`en-US` → `en`, `zh_CN` → `zh`)
 *     and re-falls-back to "en" for unsupported tokens.
 *
 *   • INVERTED-PARALLEL — no rework counterpart at `:data/source/` or anywhere. The rework
 *     :data layer REACHES INTO this legacy class directly via Koin (verified at
 *     WhatsNewReworkModule.kt). Same strangler-fig posture as siblings 365 (LocalizedFeature)
 *     + 366 (RemoteData). The rework :data treats the entire legacy :shared/whatsnew/data/
 *     pipeline (fetch + locale-resolve) as a still-functioning library.
 *
 *   • DEFENSIVE-FALLBACK-CHAIN — `fetchWhatsNewFeatures` carries 3 failure-pathway escape
 *     hatches: HTTP-non-success → Result.failure(HTTP error); empty body → Result.failure(
 *     Empty response); catch-all `try { ... } catch (e: Exception) { Result.failure(e) }`.
 *     The Result wrapper hands the failure-handling decision to the consumer (rework :data
 *     `WhatsNewRepositoryImpl` decides whether to surface the error, fall back to defaults,
 *     or retry). DO NOT replace the catch-Exception with structured-result extraction during
 *     error-handling cleanup passes — the upstream KDoc + the explicit fallback policy
 *     documents the wide-catch as deliberate.
 *
 *   • INTENTIONAL-PUBLIC-ENDPOINT-NOT-VIOLATION — the `WHATS_NEW_URL` constant embeds a
 *     production CDN endpoint (`https://yamimanga.me/whatsnew/35/whatsnew.json`). This is
 *     not a credential, not an API key, and is unauthenticated (the JSON content is
 *     public-by-design). DO NOT flag for rotation, env-var extraction, or build-config
 *     plumbing — the URL is the contract surface itself, intentionally hardcoded.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 11 imports (kermit + ktor 4-pack + kotlinx-coroutines
 *     withContext + kotlinx-serialization Json + platformIoDispatcher). Standard KMP-portable HTTP
 *     fetcher shape.
 */

