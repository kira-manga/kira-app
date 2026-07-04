package me.manga.yamiapk.core.progress




import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe progress manager for tracking download/image loading progress.
 * Uses ConcurrentHashMap for thread safety and proper flow management.
 */


object ProgressManager {

    // Use ConcurrentHashMap for thread-safe operations
    private val _progressMap = ConcurrentHashMap<String, MutableStateFlow<ProgressState>>()

    /**
     * Get or create a progress flow for a given URL.
     * Returns StateFlow (read-only) to prevent external modifications.
     */
    fun getProgressFlow(url: String): StateFlow<ProgressState> {
        return _progressMap.getOrPut(url) {
            MutableStateFlow(ProgressState.Idle)
        }.asStateFlow()
    }

    /**
     * Update progress for a specific URL.
     * @param url The resource URL
     * @param readBytes Bytes read so far
     * @param totalBytes Total bytes to read
     */
    fun updateProgress(url: String, readBytes: Long, totalBytes: Long) {
        if (totalBytes <= 0) return

        val percent = ((readBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        val flow = _progressMap.getOrPut(url) { MutableStateFlow(ProgressState.Idle) }

        flow.value = ProgressState.Loading(
            percent = percent,
            bytesRead = readBytes,
            totalBytes = totalBytes
        )
    }

    /**
     * Mark URL as completed successfully.
     */
    fun markCompleted(url: String) {
        _progressMap[url]?.value = ProgressState.Completed
    }

    /**
     * Mark URL as failed with error.
     */
    fun markFailed(url: String, error: Throwable? = null) {
        _progressMap[url]?.value = ProgressState.Failed(error?.message)
    }

    /**
     * Reset progress for a specific URL (useful for retries).
     */
    fun reset(url: String) {
        _progressMap[url]?.value = ProgressState.Idle
    }

    /**
     * Clear progress for a specific URL and remove from tracking.
     * Call this when an item is no longer needed (e.g., user navigates away).
     */
    fun clear(url: String) {
        _progressMap.remove(url)
    }

    /**
     * Clear all progress tracking.
     * Useful for cleanup when exiting the reader or app.
     */
    fun clearAll() {
        _progressMap.clear()
    }

    /**
     * Get current progress count (for debugging/monitoring).
     */
    fun getActiveProgressCount(): Int = _progressMap.size
}
