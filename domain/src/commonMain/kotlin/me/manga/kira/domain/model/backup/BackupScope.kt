package me.manga.kira.domain.model.backup

import me.manga.kira.domain.model.identity.WorkLocator

/**
 * What an export covers. The produced archive format is identical for both scopes — a scoped
 * backup is simply a `BackupFile` whose mangas collection holds the selected entries (import
 * neither knows nor cares which scope produced a file).
 */
sealed interface BackupScope {
    /** Every manga in the library (+ full history). */
    data object FullLibrary : BackupScope

    /** A malformed/restored legacy route, never permission to export the entire library. */
    data object Invalid : BackupScope

    /**
     * Only the given mangas (one entry = single-manga export from Details; several = Library
     * multi-select). History is filtered to these mangas.
     */
    data class Mangas(
        val keys: List<BackupSelection>,
    ) : BackupScope
}

/** Only [work] is authority. [title] is an optional display hint, not a lookup key. */
data class BackupSelection(val work: WorkLocator, val title: String = work.url)

val BackupScope.isValid: Boolean
    get() = when (this) {
        BackupScope.FullLibrary -> true
        BackupScope.Invalid -> false
        is BackupScope.Mangas -> keys.isNotEmpty() && keys.all {
            it.work.api.isNotBlank() && it.work.url.isNotBlank()
        }
    }
