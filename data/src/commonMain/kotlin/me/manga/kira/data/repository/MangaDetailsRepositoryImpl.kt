package me.manga.kira.data.repository

import kotlin.coroutines.cancellation.CancellationException
// NOTE: the cancelling `kotlinx.coroutines.flow.first { it !is Loading }` terminal was removed — it
// tripped Flow exception-transparency over the legacy emit-from-try/catch flow; replaced with the
// non-cancelling `awaitTerminalState()` (see LegacySourceFlow.kt).
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.error.TransportErrorMessages
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.data.mapper.toDomain
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources.contracts.SourceRegistry

/**
 * Source-backed [MangaDetailsRepository] implementation.
 *
 * SRP (contract §6): owns ONE rule — "route a [Manga] to its legacy source repo, run the legacy
 * `fetchMangaChaptersF` flow, project the first non-Loading emission into a typed [AppResult]".
 * Source-routing itself (the registry of `:shared/sources_repositry/...` instances) lives in
 * [SourcesRepository] — this impl is a thin classifier on top of it.
 *
 * DIP: depends on [MangaDetailsRepository] (declared in `:domain`), [SourcesRepository] (legacy
 * `:shared` — transitional, removed in a later phase when sources move into `:data`), and
 * [DispatcherProvider] (`:core`). No Compose, no UI types, no platform-specific APIs.
 *
 * Why the legacy fetch is folded into a single suspend call:
 *  - Legacy `BaseMangaRepository.fetchMangaChaptersF(query): Flow<State<MangaInfo>>` emits
 *    `State.Loading` (sometimes), then exactly one terminal `State.Success` or `State.Error`.
 *    The Details screen has never used the Loading emission for anything more than a spinner —
 *    the rework's `MangaDetailsViewModel` (Phase 6.3.3) tracks loading via its MVI state, not
 *    via the source flow. So we drop Loading at this seam and surface only the terminal value.
 *  - Using `.first { it !is Loading }` keeps the contract a single suspend call — the seam the
 *    [MangaDetailsRepository] KDoc commits to.
 *
 * Error classification (matches the legacy [LegacyState.Error.fromException] heuristics so that
 * preserved-functionality tests downstream see the same buckets):
 *  - HTTP status in 400..599 → [AppError.Network.Http] with the original status code.
 *  - code == 0 with a connectivity hint in the message → [AppError.Network.NoConnectivity].
 *  - code == 0 with a timeout hint → [AppError.Network.Timeout].
 *  - Anything else → [AppError.Unexpected] carrying the original message for telemetry.
 *
 * Unknown source (i.e. [SourcesRepository.getOrRepoByName] returns `null` for [Manga.api]) is
 * surfaced as [AppError.Unexpected]. This is the one observable-behavior difference from the
 * legacy code path: legacy `getRepoByName` silently substitutes `EmptyMangaRepository` whose
 * `fetchMangaChaptersF` returns a Success with all-empty fields, hiding the integrity issue.
 * The rework's Details ViewModel (Phase 6.3.3) hasn't been wired to a Library entry-point yet,
 * so there is no live consumer whose behavior is changing here — the new contract is chosen
 * fresh.
 *
 * Cancellation: [CancellationException] propagates unchanged (structured-concurrency invariant).
 * Any other [Throwable] thrown by the flow collector lands in [AppError.Unexpected].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster25.staleKdocSweep.cascade,
 * Task #481, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Lines 48-50 ("The rework's Details ViewModel (Phase 6.3.3)
 *    hasn't been wired to a Library entry-point yet, so there is no
 *    live consumer whose behavior is changing here — the new contract
 *    is chosen fresh"). FACTUALLY INVERTED — Phase 7.x.details.parity
 *    campaign landed across §§427 (bookmark) + §428 (downloads) +
 *    §428.5 (webview) + §429 (mangadetails.swap) + §430
 *    (mangadetails.retire). §429 re-pointed `Screen.MangaDetails`'s
 *    `composable<>` block in `composeApp/.../App.kt` to the rework
 *    `MangaDetailsReworkScreenRoute` via the new `OnEnterByUrl(api,
 *    mangaUrl)` intent (Option B of the slice-4 ADR-6 args-shape
 *    resolution — keep the 4 caller nav sites untouched: Home +
 *    Library-rework + History-rework + Updates-rework); §430 deleted
 *    the legacy `MangaDetailsScreen` + `MangaDetailsScreenRoute` +
 *    `DetailsContent` + `HeaderSection` + `ChapterItem` components +
 *    the `MangaDerailsViewModel` (conditional Koin binding pruned).
 *    The rework Details VM IS now wired to the full 4-caller
 *    entry-point set (legacy `Screen.MangaDetails` route key kept in
 *    `Screen.kt` per ADR-8 to avoid rewriting every caller's nav
 *    site); the new-contract-no-live-consumer framing was correct at
 *    §253-era authoring (pre-slice-4) but was inverted by §§427-430.
 *    The classifier rules above (HTTP 400-599 → `Network.Http`,
 *    code-0 + connectivity hint → `Network.NoConnectivity`, code-0 +
 *    timeout hint → `Network.Timeout`, anything else →
 *    `Unexpected`) + the unknown-source `Unexpected` behaviour ARE
 *    now live-consumer-affecting via the rework `OnEnterByUrl` fetch
 *    path. HOWEVER — the [SourcesRepository] legacy `:shared` facade
 *    + the [DispatcherProvider] `:core` dep STILL EXIST as the cell
 *    of truth that this impl delegates to via constructor injection
 *    (verified at the constructor signature below — `private val
 *    sourcesRepository: SourcesRepository` + `private val
 *    dispatchers: DispatcherProvider`). Mirror of §§475-480
 *    cluster-tier fulfilled-deferral-inversion precedent. The SRP /
 *    DIP / fold-single-suspend-rationale / classifier-rules /
 *    unknown-source-behaviour-difference / cancellation
 *    sub-sections all stand on their own merits past the §§427-430
 *    fulfilled landings. The MangaDetailsRepositoryImpl remains LIVE
 *    as the canonical source-routing classifier for the rework
 *    details-fetch surface. Original §253-era prose preserved
 *    verbatim per the audit-trail-preservation convention — the
 *    citation is historical record of the design lineage including
 *    the pre-slice-4 no-live-consumer assumption that was
 *    subsequently fulfilled across §§427-430.
 */
class MangaDetailsRepositoryImpl(
    private val sourcesRepository: SourcesRepository,
    private val dispatchers: DispatcherProvider,
    private val sourceRegistry: SourceRegistry,
) : MangaDetailsRepository {

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> =
        withContext(dispatchers.io) {
            // Config-backed source (Stage-1: Azora) → the config-driven engine, which already returns a
            // domain MangaDetails + typed AppError and carries its own legacy fallback (Cloudflare /
            // error / engine-gap → legacy). CancellationException propagates unchanged. Every other
            // source falls through to the unchanged legacy path below.
            if (sourceRegistry.isConfigBacked(manga.api)) {
                return@withContext sourceRegistry.get(manga.api)?.details(manga)
                    ?: AppResult.Failure(AppError.Unexpected(message = "No source client for api=${manga.api}"))
            }

            val sourceRepo = sourcesRepository.getOrRepoByName(manga.api)
                ?: return@withContext AppResult.Failure(
                    AppError.Unexpected(message = "Unknown source api=${manga.api}"),
                )

            try {
                val terminal = sourceRepo.fetchMangaChaptersF(manga.url)
                    .awaitTerminalState()
                when (terminal) {
                    is LegacyState.Success -> AppResult.Success(terminal.data.toDomain())
                    is LegacyState.Error -> AppResult.Failure(terminal.toAppError())
                    LegacyState.Loading -> error("Filtered above")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(classifyThrowable(t))
            }
        }

    /**
     * Map a legacy [LegacyState.Error] (HTTP status code OR transport sentinel code = 0) to the
     * matching [AppError]. The string-pattern matching for code == 0 mirrors
     * [LegacyState.Error.Companion.fromException] so the surfaced buckets line up.
     */
    private fun LegacyState.Error.toAppError(): AppError {
        val status = code ?: 0
        if (status in 400..599) {
            return AppError.Network.Http(statusCode = status)
        }
        val raw = message.lowercase()
        return when {
            // A code-0 emission whose body/message betrays a Cloudflare / anti-bot interstitial is a
            // SOLVABLE challenge, not a hard failure (bug #2). Many sources serve the challenge HTML
            // with a 200/0 that the parser rejects, so the status code is lost — re-surface it as a
            // 403 so the Details/Reader VMs route the user to the WebView solver instead of a
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
     * Map a [Throwable] thrown during the flow collect (vs. a [LegacyState.Error] emission) to
     * an [AppError]. Uses the same connectivity/timeout heuristics so both paths classify the
     * same way — many legacy sources throw on bad responses instead of emitting `State.Error`.
     */
    private fun classifyThrowable(t: Throwable): AppError {
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
}
