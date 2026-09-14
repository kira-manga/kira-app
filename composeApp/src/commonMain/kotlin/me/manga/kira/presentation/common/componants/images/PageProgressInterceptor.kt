package me.manga.kira.presentation.common.componants.images

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CancellationException
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressAttempt
import me.manga.kira.domain.repository.PageProgressRepository
import me.manga.kira.ui.reader.pageProgressHandle

internal val pageProgressAttemptKey = Extras.Key<PageProgressAttempt?>(null)

/** One immutable attempt per execution, including cache hits and AsyncImagePainter.restart(). */
internal class PageProgressInterceptor(
    private val repository: PageProgressRepository,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val attempt =
            chain.request.pageProgressHandle?.let(repository::beginAttempt) ?: return chain.proceed()
        var terminal: PageDownloadProgress = PageDownloadProgress.Failed
        return try {
            val request =
                chain.request
                    .newBuilder()
                    .apply { extras[pageProgressAttemptKey] = attempt }
                    .build()
            val result = chain.withRequest(request).proceed()
            terminal = if (result is SuccessResult) PageDownloadProgress.Complete else PageDownloadProgress.Failed
            result
        } catch (cancelled: CancellationException) {
            terminal = PageDownloadProgress.Idle
            throw cancelled
        } finally {
            // Retire on every exit, including a request-builder/decoder failure, without swallowing
            // any exception. Cancellation stays Idle and propagates to Coil's request lifecycle.
            attempt.report(terminal)
        }
    }
}
