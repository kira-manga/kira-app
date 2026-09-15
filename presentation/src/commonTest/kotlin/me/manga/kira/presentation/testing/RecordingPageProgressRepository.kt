package me.manga.kira.presentation.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressAttempt
import me.manga.kira.domain.model.reader.PageProgressHandle
import me.manga.kira.domain.model.reader.PageProgressObservation
import me.manga.kira.domain.repository.PageProgressRepository

/** One shared ownership/collector recorder for both Reader progress use cases. */
class RecordingPageProgressRepository : PageProgressRepository {
    private val streams = mutableMapOf<PageProgressHandle, MutableStateFlow<PageDownloadProgress>>()
    private val collectors = mutableMapOf<PageProgressHandle, Int>()
    val acquired = mutableListOf<PageProgressHandle>()
    val cleared = mutableListOf<PageProgressHandle>()
    val cancelled = mutableListOf<String>()
    val activeHandles: Set<PageProgressHandle> get() = streams.keys.toSet()
    val activeUrls: Set<String>
        get() =
            collectors
                .filterValues { it > 0 }
                .keys
                .map { it.url }
                .toSet()

    val collectorCount: Int get() = collectors.values.sum()

    override fun observe(url: String): PageProgressObservation {
        val handle = PageProgressHandle(url)
        val stream = MutableStateFlow<PageDownloadProgress>(PageDownloadProgress.Idle)
        acquired += handle
        streams[handle] = stream
        return PageProgressObservation(
            handle,
            flow {
                collectors[handle] = collectors.getOrElse(handle) { 0 } + 1
                try {
                    emitAll(stream)
                } finally {
                    collectors[handle] = collectors.getValue(handle) - 1
                    cancelled += url
                }
            },
        )
    }

    override fun beginAttempt(handle: PageProgressHandle): PageProgressAttempt? {
        if (handle !in streams) return null
        report(handle, PageDownloadProgress.Started)
        var finished = false
        return PageProgressAttempt { status ->
            if (!finished) {
                report(handle, status)
                finished =
                    status == PageDownloadProgress.Idle ||
                    status == PageDownloadProgress.Complete ||
                    status == PageDownloadProgress.Failed
            }
        }
    }

    fun report(
        handle: PageProgressHandle,
        status: PageDownloadProgress,
    ) {
        streams[handle]?.value = status
    }

    // Convenience only for existing active-chapter tests; not a production URL-reporting API.
    fun report(
        url: String,
        status: PageDownloadProgress,
    ) {
        streams.filterKeys { it.url == url }.values.forEach { it.value = status }
    }

    override fun clear(handle: PageProgressHandle) {
        streams.remove(handle)?.let {
            cleared += handle
            it.value = PageDownloadProgress.Idle
        }
    }
}
