package me.manga.kira.data.repository

import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.error.TransportErrorMessages
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.sources.contracts.SourceRegistry
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches details only through a client in the authoritative active generic catalog.
 *
 * A missing client is an explicit typed failure. It must never reactivate an old Kotlin adapter
 * for a source omitted, disabled, retired, or removed by the catalog.
 */
class MangaDetailsRepositoryImpl(
    private val dispatchers: DispatcherProvider,
    private val sourceRegistry: SourceRegistry,
) : MangaDetailsRepository {

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> =
        withContext(dispatchers.io) {
            val client =
                sourceRegistry.get(manga.api)
                    ?: return@withContext AppResult.Failure(
                        AppError.Validation.SourceUnavailable(api = manga.api),
                    )
            try {
                client.details(manga)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(classifyThrowable(t))
            }
        }

    private fun classifyThrowable(t: Throwable): AppError {
        val raw = t.message.orEmpty().lowercase()
        return when {
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
        needles.any(::contains)
}
