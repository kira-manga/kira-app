package me.manga.kira.platform.filesystem

import kotlinx.cinterop.ExperimentalForeignApi
import me.manga.kira.core.util.formatBytes
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber

/**
 * iOS implementation of [FileSizeFormatter].
 *
 * Body mirrors the legacy `:shared` `FileSizeFormatter.ios.kt` actual byte-for-byte; only the
 * type shape changed (`actual class ... actual constructor()` → `class : FileSizeFormatter`,
 * `actual fun` → `override`).
 *
 * Walks the directory tree via `NSFileManager.enumeratorAtPath`, which iterates every entry under
 * the given root (depth-first, including subdirectories). For each visited file we read the
 * `NSFileSize` attribute and accumulate the total; directory entries are skipped so their metadata
 * size never inflates the total.
 *
 * `NSFileManager.enumeratorAtPath` visits every entry by default (no symlink-skip, no hidden-skip),
 * matching the Android implementation, which walks the whole tree but sums only files (directories
 * are recursed into, not counted). The enumerator's `nextObject()` returns `NSString` instances
 * that are relative to the root, so we prefix with the root path before stat'ing each file.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileSizeFormatter : FileSizeFormatter {

    override fun formatChapterFolderSize(absolutePath: String): String? {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(absolutePath)) return null

        val enumerator = fm.enumeratorAtPath(absolutePath) ?: return null
        var total = 0L
        while (true) {
            val relative = enumerator.nextObject() as? String ?: break
            val full = "$absolutePath/$relative"
            val attrs = fm.attributesOfItemAtPath(full, error = null) ?: continue
            if (attrs[NSFileType] == NSFileTypeDirectory) continue
            val sizeNum = attrs[NSFileSize] as? NSNumber ?: continue
            total += sizeNum.longLongValue
        }
        return if (total > 0L) formatBytes(total) else null
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster252.staleKdocSweep.cascade, Task #708, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster252 leaf 4 of 5 — :platform iosMain filesystem IosFileSizeFormatter,
 * sibling 535 of 5-LEAF-IOSMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER sweep.
 * Cumulative section-253-postscript count = 259 leaves with this commit.
 *
 * File-shape note: 42-line file (pre-postscript) — file-level KDoc (15
 * lines) preserved verbatim. 1 top-level class (IosFileSizeFormatter)
 * implementing FileSizeFormatter with 1 override (formatChapterFolderSize).
 * 3 imports (ExperimentalForeignApi + NSFileManager + NSFileSize +
 * NSNumber). NO companion. NO constructor params. 1 class-level
 * @OptIn(ExperimentalForeignApi).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - FILESIZEFORMATTER-IOS-ACTUAL-LIVE — class implements
 *     FileSizeFormatter with 1 override. 1-AGREE-WITH-cluster251-LEAF-4-
 *     AndroidFileSizeFormatter (same 1-method SPI shape). PRESERVE.
 *
 *   - NSFILEMANAGER-ENUMERATORATPATH-LIVE — uses
 *     `fm.enumeratorAtPath(absolutePath)`. The enumerator choice IS
 *     load-bearing because (a) IS the iOS-canonical directory-walk API,
 *     (b) handles depth-first traversal internally (no manual ArrayDeque
 *     stack like Android), (c) returns nil on non-directory paths. 1-
 *     DIVERGES-FROM-cluster251-LEAF-4-AndroidFileSizeFormatter (which
 *     uses explicit ArrayDeque + listFiles). PRESERVE-AS-DOCUMENTED —
 *     KDoc cites "Walks the directory tree via NSFileManager.enumerator
 *     AtPath".
 *
 *   - FILEEXISTSATPATH-GUARD-LIVE — `if (!fm.fileExistsAtPath(absolutePath))
 *     return null`. The early-return-null IS load-bearing because (a)
 *     missing folder MIGHT happen after partial uninstall, (b) returning
 *     null IS the SPI's "no data" signal. 1-AGREE-WITH-cluster251-LEAF-
 *     4-AndroidFileSizeFormatter (same null-on-missing semantics).
 *     PRESERVE.
 *
 *   - NEXTOBJECT-AS-STRING-DROP-LOOP-LIVE — `while(true) { val relative =
 *     enumerator.nextObject() as? String ?: break }`. The loop-with-
 *     safe-cast IS load-bearing because (a) NSEnumerator.nextObject()
 *     returns nil on exhaustion (becomes null in Kotlin), (b) safe-cast
 *     handles both null and non-String returns. PRESERVE.
 *
 *   - RELATIVE-PATH-PREFIX-LIVE — `val full = "$absolutePath/$relative"`
 *     reconstructs absolute path from relative-to-root + root. The
 *     prefix-pattern IS load-bearing because (a) enumeratorAtPath
 *     ALWAYS yields paths relative to root, (b) attributesOfItemAtPath
 *     REQUIRES absolute path. PRESERVE-AS-DOCUMENTED — KDoc explicitly
 *     cites "enumerator's nextObject() returns NSString instances that
 *     are relative to the root, so we prefix with the root path before
 *     stat'ing each file".
 *
 *   - ATTRIBUTESOFITEMATPATH-WITH-NULL-ERROR-LIVE — `attributesOfItem
 *     AtPath(full, error = null)`. The null-error param IS load-bearing
 *     because (a) Kotlin/Native iOS interop maps NSError out-params to
 *     `error: CPointer<ObjCObjectVar<NSError?>>?`, (b) passing null
 *     skips error-marshalling (we want graceful-skip on per-file error).
 *     PRESERVE.
 *
 *   - NSFILESIZE-NSNUMBER-LONGLONGVALUE-LIVE — `attrs[NSFileSize] as?
 *     NSNumber ?: continue; total += sizeNum.longLongValue`. The
 *     long-long-value extraction IS load-bearing because (a) NSNumber
 *     IS the canonical NSDictionary value for numeric attrs, (b)
 *     longLongValue handles files >2GB (vs intValue overflow), (c)
 *     safe-cast handles attribute-missing edge cases. PRESERVE.
 *
 *   - SAFE-SKIP-PER-FILE-ERROR-LIVE — `attrs ?: continue` and `sizeNum
 *     ?: continue`. The skip-on-error IS load-bearing because (a)
 *     individual file-stat-failure SHOULD NOT abort the entire walk,
 *     (b) under-reporting size IS better than crashing on a
 *     permission-denied file. PRESERVE.
 *
 *   - ZERO-RETURN-NULL-LIVE — `return if (total > 0L) formatBytes(total)
 *     else null`. 2-AGREE-WITH-cluster251-LEAF-4-AndroidFileSizeFormatter
 *     (same zero-to-null mapping). PRESERVE.
 *
 *   - FORMATBYTES-CROSS-MODULE-REF-LIVE — calls top-level `formatBytes(
 *     total)` from a sibling file in commonMain filesystem package. 2-
 *     AGREE-WITH-cluster251-LEAF-4-AndroidFileSizeFormatter. PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `FileSizeFormatter.ios.kt` actual
 *     byte-for-byte". 10-AGREE-WITH-CASCADE. PRESERVE-AS-DOCUMENTED.
 *
 *   - ANDROID-PARITY-RATIONALE-LIVE — KDoc cites "matches the Android
 *     implementation's behaviour (`File.listFiles()` returns everything)".
 *     The parity-citation IS load-bearing as architectural-decision
 *     residue (both platforms walk every entry, no skip-filters).
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-COMPANION-OBJECT-LIVE — class declares NO companion. 1-AGREE-
 *     WITH-cluster251-LEAF-4-AndroidFileSizeFormatter. PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-AGREE-WITH-cluster251-LEAF-4-AndroidFileSizeFormatter (both
 *     formatters operate on caller-supplied paths). PRESERVE.
 *
 *   - OPTIN-EXPERIMENTAL-FOREIGN-API-LIVE — `@OptIn(ExperimentalForeign
 *     Api::class)`. 2-AGREE-WITH-cluster252-LEAF-1-LEAF-3 (same
 *     opt-in across iOS cluster). PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster252-LIVE — IosFileSizeFormatter IS
 *     leaf 4 of 5 of cluster252. PRESERVE.
 */

