package me.manga.kira.presentation.features.download.domain.clean

/**
 * Pure parser for the on-disk page-file naming convention `image_<n>.<ext>` — the index a name
 * parses to decides page ORDER in the finalized artifact and membership in the reconciler's
 * on-disk set, so this parse is a correctness surface, not cosmetics.
 *
 * The format is produced in three places (kept as inline templates for zero behavior churn; keep
 * them in lockstep with this parser):
 *  - `CoroutineDownloadRepositoryImpl.downloadOnePage` — `"image_$imageIndex.$extension"`,
 *  - `ChapterDownloadService` (Android WorkManager engine) — same template,
 *  - `IosBackgroundTransport` (`:platform` iosMain) — `"image_${d.pageIndex}.$ext"`.
 * Consumed by `BackgroundUrlSessionDownloadRepository`'s `pagesOnDiskSet` / `onDiskPagePaths`
 * (reconcile membership + finalize ordering).
 */
object PageFileNames {

    /**
     * Parses `image_<n>.<ext>` → `n`; returns null for any other file in the chapter dir
     * (`manifest.json`, the `chapter_<id>.cbz`, a stray `.part`, …) so non-page files can never
     * enter the page set.
     */
    fun pageIndexFromName(name: String): Int? {
        if (!name.startsWith("image_")) return null
        val dot = name.indexOf('.')
        if (dot < 0) return null
        return name.substring("image_".length, dot).toIntOrNull()
    }
}
