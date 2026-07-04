package me.manga.kira.platform.cbz

import okio.Path

/**
 * Pure rule for the B7 stale-source-path fix in the CBZ writers. No I/O (existence is injected) —
 * fully unit-tested.
 *
 * The bug: the manual "Yami Compressor" passes stored absolute `localImagePaths` captured at
 * download time; on iOS the sandbox container UUID changes across reinstall/backup-restore, so
 * every stored path goes stale while the file still exists under the LIVE chapter dir. Without
 * re-derivation the writer silently skipped all pages → a 0-page failure.
 */
object PagePathRederivation {

    /**
     * Resolves one source page. Precedence, in order:
     *  1. the [stored] path when it exists — never hijack a valid path (the background finalize
     *     passes live paths, so this makes the rule a no-op there);
     *  2. `chapterDir / stored.name` when that exists — the container-drift rescue (same filename,
     *     current sandbox root);
     *  3. the [stored] path unchanged — so the downstream warn-and-skip logs the caller's original
     *     path, not a synthesized one.
     */
    fun resolveSourcePage(stored: Path, chapterDir: Path, exists: (Path) -> Boolean): Path =
        if (exists(stored)) {
            stored
        } else {
            (chapterDir / stored.name).let { if (exists(it)) it else stored }
        }
}
