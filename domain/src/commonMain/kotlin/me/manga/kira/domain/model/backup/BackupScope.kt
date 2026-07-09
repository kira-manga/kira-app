package me.manga.kira.domain.model.backup

import me.manga.kira.domain.repository.MangaKey

/**
 * What an export covers. The produced archive format is identical for both scopes — a scoped
 * backup is simply a `BackupFile` whose mangas collection holds the selected entries (import
 * neither knows nor cares which scope produced a file).
 */
sealed interface BackupScope {
    /** Every manga in the library (+ full history). */
    data object FullLibrary : BackupScope

    /**
     * Only the given mangas (one entry = single-manga export from Details; several = Library
     * multi-select). History is filtered to these mangas.
     */
    data class Mangas(val keys: List<MangaKey>) : BackupScope
}
