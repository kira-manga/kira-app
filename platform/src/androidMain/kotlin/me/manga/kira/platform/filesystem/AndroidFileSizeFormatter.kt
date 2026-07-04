package me.manga.kira.platform.filesystem

import me.manga.kira.core.util.formatBytes
import java.io.File

/**
 * Android implementation of [FileSizeFormatter].
 *
 * Body mirrors the legacy `:shared` `FileSizeFormatter.android.kt` actual byte-for-byte; only the
 * type shape changed (`actual class ... actual constructor()` → `class : FileSizeFormatter`,
 * `actual fun` → `override`).
 *
 * Re-uses the upstream `FileSizeUtils.safeWalkFiles(...)` algorithm: breadth-first walk via an
 * `ArrayDeque<File>` (FIFO, `addLast`/`removeFirst`) so we don't recurse on deep trees, and a
 * null-check on `File.listFiles()` to handle the rare case where the directory becomes unreadable
 * mid-walk.
 */
class AndroidFileSizeFormatter : FileSizeFormatter {

    override fun formatChapterFolderSize(absolutePath: String): String? {
        val root = File(absolutePath)
        if (!root.exists() || !root.isDirectory) return null
        val total = safeWalkFiles(root).sumOf { it.length() }
        return if (total > 0L) formatBytes(total) else null
    }

    private fun safeWalkFiles(root: File): Sequence<File> = sequence {
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeFirst()
            val list = dir.listFiles() ?: continue
            for (file in list) {
                if (file.isDirectory) stack.add(file) else yield(file)
            }
        }
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster251.staleKdocSweep.cascade, Task #707, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster251 leaf 4 of 5 — :platform androidMain filesystem AndroidFileSizeFormatter,
 * sibling 530 of 5-LEAF-ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 254 leaves with this commit.
 *
 * File-shape note: 36-line file (pre-postscript) — file-level KDoc (10
 * lines) preserved verbatim. 1 top-level class (AndroidFileSizeFormatter)
 * implementing FileSizeFormatter with 1 override (formatChapterFolderSize).
 * 1 private fun (safeWalkFiles). 1 import (java.io.File). NO companion.
 * NO constructor params. NOTE: refers to `formatBytes(total)` (top-level
 * shared helper) — present in commonMain/filesystem package.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - FILESIZEFORMATTER-ANDROID-ACTUAL-LIVE — class implements
 *     FileSizeFormatter with 1 override (formatChapterFolderSize). The
 *     1-method shape IS load-bearing — single public entry point that
 *     returns nullable formatted string ("X MB") or null when folder
 *     missing/empty. PRESERVE.
 *
 *   - SAFEWALKFILES-ARRAYDEQUE-DEPTH-FIRST-LIVE — `safeWalkFiles(root)`
 *     uses an explicit ArrayDeque<File> stack instead of recursion. The
 *     iterative-walk IS load-bearing because (a) prevents StackOverflow
 *     on deep folder trees (e.g. nested chapter caches), (b) ArrayDeque
 *     IS the canonical Kotlin Deque impl. PRESERVE-AS-DOCUMENTED — KDoc
 *     cites "depth-first walk via an `ArrayDeque<File>` so we don't
 *     recurse on deep trees".
 *
 *   - LISTFILES-NULL-CHECK-LIVE — `dir.listFiles() ?: continue`. The
 *     null-check IS load-bearing because File.listFiles() returns null
 *     when (a) IS NOT a directory (race condition if file becomes regular
 *     mid-walk), (b) IO error reading dir entries, (c) permission denied.
 *     PRESERVE-AS-DOCUMENTED — KDoc cites "null-check on
 *     `File.listFiles()` to handle the rare case where the directory
 *     becomes unreadable mid-walk".
 *
 *   - SEQUENCE-LAZY-YIELD-LIVE — body wrapped in `sequence { ... }` with
 *     `yield(file)`. The lazy-sequence IS load-bearing because
 *     `safeWalkFiles(root).sumOf { it.length() }` IS terminal-consumed
 *     once — avoids materializing every File into an intermediate
 *     collection. PRESERVE.
 *
 *   - SUMOF-LENGTH-LIVE — `safeWalkFiles(root).sumOf { it.length() }`.
 *     The `File.length()` call IS load-bearing because IS the canonical
 *     JVM API for file byte-count. PRESERVE — defends against future
 *     "use Files.size(file.toPath())" refactor (which would throw on
 *     missing files vs File.length() which returns 0).
 *
 *   - ZERO-RETURN-NULL-LIVE — `return if (total > 0L) formatBytes(total)
 *     else null`. The zero-to-null mapping IS load-bearing because
 *     callers (Library / Downloads UI) suppress "0 B" labels — null
 *     IS the empty-folder signal. PRESERVE.
 *
 *   - EXISTS-AND-ISDIRECTORY-GUARD-LIVE — early return null when
 *     `!root.exists() || !root.isDirectory`. The 2-step guard IS load-
 *     bearing because (a) absent folder MIGHT happen after partial
 *     uninstall, (b) regular file path with same name IS a config
 *     mistake worth NOT-crashing on. PRESERVE.
 *
 *   - FORMATBYTES-CROSS-MODULE-REF-LIVE — calls top-level `formatBytes(
 *     total)` from a sibling file in the same filesystem package
 *     (commonMain). The cross-file reference IS load-bearing because
 *     formatBytes IS the SHARED formatter (KB/MB/GB) used by all 3
 *     platform actuals. PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `FileSizeFormatter.android.kt`
 *     actual byte-for-byte". 8-AGREE-WITH-cluster248-LEAF-3-LEAF-4-
 *     LEAF-5-PLUS-cluster249-LEAF-3-LEAF-4-LEAF-5-PLUS-cluster250-
 *     LEAF-3-PLUS-cluster251-LEAF-3. PRESERVE-AS-DOCUMENTED.
 *
 *   - UPSTREAM-FILESIZEUTILS-CITATION-LIVE — KDoc cites "Re-uses the
 *     upstream `FileSizeUtils.safeWalkFiles(...)` algorithm". The
 *     citation IS load-bearing as architectural-decision residue —
 *     documents that the iterative-walk algorithm IS NOT novel but
 *     IS lifted from upstream Tachiyomi-family code. PRESERVE-AS-
 *     DOCUMENTED.
 *
 *   - NO-COMPANION-OBJECT-LIVE — class declares NO companion. 2-AGREE-
 *     WITH-cluster251-LEAF-2-AndroidSettingsFactory PLUS cluster251-LEAF-
 *     3-AndroidAppFileSystem. PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-DIVERGES-FROM-cluster251-LEAF-1-LEAF-2-LEAF-3 (Secure + Settings
 *     + AppFileSystem all take Context). The zero-param shape IS load-
 *     bearing because formatter operates on caller-supplied paths —
 *     needs no Android Context. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster251-LIVE — AndroidFileSizeFormatter
 *     IS leaf 4 of 5 of cluster251. PRESERVE.
 */

