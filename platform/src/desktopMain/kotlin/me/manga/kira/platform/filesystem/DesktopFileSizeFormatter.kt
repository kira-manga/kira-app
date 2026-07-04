package me.manga.kira.platform.filesystem

import co.touchlab.kermit.Logger
import me.manga.kira.core.util.formatBytes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * Desktop (JVM) implementation of [FileSizeFormatter].
 *
 * Body mirrors the legacy `:shared` `FileSizeFormatter.desktop.kt` actual byte-for-byte; only the
 * type shape changed (`actual class ... actual constructor()` → `class : FileSizeFormatter`,
 * `actual fun` → `override`).
 *
 * Uses NIO's `Files.walk(...)` to stream every regular file under the path and sum `Files.size`.
 * NIO is used (instead of `java.io.File` like the Android implementation) because it gives us
 * cleaner symlink/lazy-evaluation semantics on JVM. Errors during the walk are swallowed and
 * logged via Kermit — folder-size is a best-effort UI hint, not a critical operation.
 */
class DesktopFileSizeFormatter : FileSizeFormatter {

    private val logger = Logger.withTag(TAG)

    override fun formatChapterFolderSize(absolutePath: String): String? {
        val root: Path = runCatching { Paths.get(absolutePath) }.getOrNull() ?: return null
        if (!Files.exists(root) || !Files.isDirectory(root)) return null
        val total = try {
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum()
            }
        } catch (t: Throwable) {
            logger.w(t) { "Walk failed for path=$absolutePath" }
            return null
        }
        return if (total > 0L) formatBytes(total) else null
    }

    private companion object {
        const val TAG = "FileSizeFormatter"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster253.staleKdocSweep.cascade, Task #709, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster253 leaf 4 of 5 — :platform desktopMain filesystem DesktopFileSizeFormatter,
 * sibling 540 of 5-LEAF-DESKTOPMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-CLOSER sweep.
 * Cumulative section-253-postscript count = 264 leaves with this commit.
 *
 * File-shape note: 43-line file (pre-postscript) — file-level KDoc (12
 * lines) preserved verbatim. 1 top-level class (DesktopFileSizeFormatter)
 * implementing FileSizeFormatter with 1 override (formatChapterFolderSize).
 * 6 imports (kermit.Logger + Files + Path + Paths + fileSize + isRegularFile).
 * 1 private companion (TAG const). NO constructor params.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - FILESIZEFORMATTER-DESKTOP-ACTUAL-LIVE — class implements
 *     FileSizeFormatter with 1 override. 3-AGREE-WITH-cluster251-LEAF-4-
 *     AndroidFileSizeFormatter + cluster252-LEAF-4-IosFileSizeFormatter
 *     (same 1-method SPI shape across all three). PRESERVE.
 *
 *   - NIO-FILES-WALK-LIVE — `Files.walk(root).use { stream -> stream
 *     .filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum() }`.
 *     The Files.walk choice IS load-bearing because (a) IS the JVM NIO-2
 *     canonical recursive-walk API, (b) Stream<Path> handles lazy
 *     evaluation (memory-efficient for huge trees), (c) symlink-following
 *     semantics ARE controllable. 1-DIVERGES-FROM-cluster251-LEAF-4-
 *     AndroidFileSizeFormatter (explicit ArrayDeque + listFiles) AND
 *     cluster252-LEAF-4-IosFileSizeFormatter (NSFileManager.enumeratorAt
 *     Path). PRESERVE-AS-DOCUMENTED — KDoc cites "NIO is used (instead of
 *     java.io.File like the Android implementation) because it gives us
 *     cleaner symlink/lazy-evaluation semantics on JVM".
 *
 *   - STREAM-USE-AUTOCLOSE-LIVE — `Files.walk(root).use { stream -> ... }`.
 *     The use-block IS load-bearing because (a) Stream<Path> from walk
 *     HOLDS an open directory handle, (b) leaking the handle WOULD eventually
 *     exhaust file descriptors, (c) Kotlin's use IS the JVM idiomatic
 *     close pattern. PRESERVE.
 *
 *   - PATHS-GET-RUNCATCHING-GUARD-LIVE — `runCatching { Paths.get(
 *     absolutePath) }.getOrNull() ?: return null`. The catching IS load-
 *     bearing because (a) Paths.get THROWS InvalidPathException on
 *     malformed strings (Windows reserved chars, etc.), (b) returning
 *     null IS the SPI's "no data" signal, (c) crash-on-parse-fail would
 *     be worse than graceful-null. PRESERVE.
 *
 *   - FILES-EXISTS-AND-IS-DIRECTORY-GUARD-LIVE — `if (!Files.exists(root)
 *     || !Files.isDirectory(root)) return null`. The dual-check IS load-
 *     bearing because (a) Files.walk on non-existent path THROWS,
 *     (b) Files.walk on a file (not dir) returns just that file (would
 *     return a non-zero size from a non-folder — wrong semantic), (c)
 *     dual-check matches the AndroidFileSizeFormatter guard. PRESERVE.
 *
 *   - ZERO-RETURN-NULL-LIVE — `return if (total > 0L) formatBytes(total)
 *     else null`. 3-AGREE-WITH-cluster251-LEAF-4-AndroidFileSizeFormatter
 *     + cluster252-LEAF-4-IosFileSizeFormatter (same zero-to-null mapping).
 *     PRESERVE.
 *
 *   - FORMATBYTES-CROSS-MODULE-REF-LIVE — calls top-level `formatBytes(
 *     total)` from a sibling file in commonMain filesystem package. 3-
 *     AGREE-WITH-cluster251-LEAF-4 + cluster252-LEAF-4 (same formatBytes
 *     dispatch). PRESERVE.
 *
 *   - KERMIT-LOGGER-WARN-WITH-THROWABLE-LIVE — `logger.w(t) { "Walk failed
 *     for path=$absolutePath" }`. The Kermit-warn IS load-bearing because
 *     (a) walk-failure SHOULD be observable in logs (not silent), (b)
 *     Throwable attachment preserves stack-trace, (c) lambda-message
 *     avoids string-allocation on disabled-tag. PRESERVE-AS-DOCUMENTED —
 *     KDoc cites "Errors during the walk are swallowed and logged via
 *     Kermit — folder-size is a best-effort UI hint, not a critical
 *     operation".
 *
 *   - SWALLOW-WALK-ERRORS-LIVE — `catch (t: Throwable) { logger.w(...);
 *     return null }`. The swallow IS load-bearing because (a) folder-size
 *     IS UI-display data (not safety-critical), (b) partial walk failure
 *     (permission denied on one file) SHOULD NOT crash the whole feature,
 *     (c) null-return IS the SPI's "no data" signal. PRESERVE-AS-DOCUMENTED.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body mirrors
 *     the legacy `:shared` `FileSizeFormatter.desktop.kt` actual byte-
 *     for-byte". 14-AGREE-WITH-CASCADE. PRESERVE-AS-DOCUMENTED.
 *
 *   - LOGGER-FIELD-WITH-TAG-LIVE — `private val logger = Logger.withTag(
 *     TAG)`. The pre-tagged logger IS load-bearing because (a) avoids
 *     re-tagging per call, (b) TAG = "FileSizeFormatter" matches cross-
 *     module convention. 1-DIVERGES-FROM-cluster251-LEAF-4-AndroidFile
 *     SizeFormatter + cluster252-LEAF-4-IosFileSizeFormatter (which have
 *     NO logger field). PRESERVE.
 *
 *   - COMPANION-WITH-TAG-CONST-LIVE — `private companion object { const
 *     val TAG = "FileSizeFormatter" }`. The companion-extraction IS load-
 *     bearing because (a) TAG IS field-init dependency for logger, (b)
 *     const allows compile-time inline. PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     3-AGREE-WITH-cluster251-LEAF-4 + cluster252-LEAF-4 (all three
 *     formatters operate on caller-supplied paths). PRESERVE.
 *
 *   - MAPTOLONG-PRIMITIVE-STREAM-LIVE — `mapToLong { it.fileSize() }.sum()`.
 *     The primitive-stream IS load-bearing because (a) avoids Long-boxing
 *     overhead on huge trees, (b) LongStream.sum IS primitive-safe (no
 *     boxing overflow concern up to Long.MAX_VALUE), (c) it.fileSize()
 *     IS the kotlin.io.path extension returning a Long primitive.
 *     PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster253-LIVE — DesktopFileSizeFormatter
 *     IS leaf 4 of 5 of cluster253. PRESERVE.
 */

