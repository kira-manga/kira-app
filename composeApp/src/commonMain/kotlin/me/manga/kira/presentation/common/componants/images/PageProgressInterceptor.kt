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
        val handle = chain.request.pageProgressHandle ?: return chain.proceed()
        val attempt = repository.beginAttempt(handle) ?: return chain.proceed()
        try {
            val request = chain.request.newBuilder().apply { extras[pageProgressAttemptKey] = attempt }.build()
            val result = chain.withRequest(request).proceed()
            attempt.report(if (result is SuccessResult) PageDownloadProgress.Complete else PageDownloadProgress.Failed)
            return result
        } catch (cancelled: CancellationException) {
            attempt.report(PageDownloadProgress.Idle)
            throw cancelled
        } catch (failure: Throwable) {
            attempt.report(PageDownloadProgress.Failed)
            throw failure
        }
    }
}
