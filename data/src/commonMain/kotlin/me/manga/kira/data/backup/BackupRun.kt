package me.manga.kira.data.backup

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.manga.kira.domain.model.backup.BackupProgress
import kotlin.coroutines.CoroutineContext

/** Per-run progress and cancellation checks; no file or database custody is held here. */
internal class BackupRun(
    private val progress: MutableStateFlow<BackupProgress>,
    private val shouldStop: () -> Boolean,
    private val context: CoroutineContext,
) {
    fun checkpoint() {
        context.ensureActive()
        if (shouldStop()) throw BackupStopped()
    }

    fun update(transform: (BackupProgress) -> BackupProgress) = progress.update(transform)
}

/** Cooperative user stop is distinct from cancellation of the owning coroutine. */
internal class BackupStopped : RuntimeException()
