package me.manga.kira.platform.filesystem

import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of [AppFileSystem].
 *
 * Body mirrors the legacy `:shared` `AppFileSystem.ios.kt` actual byte-for-byte; only the type
 * shape changed (`actual class` → `class : AppFileSystem`, `actual val` → `override val`).
 *
 * Uses `NSFileManager.URLForDirectory(...)` with `create = true` so the Documents / Caches
 * directories are guaranteed to exist on first access. If the resolution fails (extremely
 * unlikely on a real device — would imply the sandbox is in a broken state), the constructor
 * throws because there's nothing useful the caller can do at that point.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAppFileSystem : AppFileSystem {

    override val filesDir: Path = resolveDir(NSDocumentDirectory)
    override val cacheDir: Path = resolveDir(NSCachesDirectory)

    override fun fileSystem(): FileSystem = FileSystem.SYSTEM

    private companion object {
        private fun resolveDir(directory: NSSearchPathDirectory): Path {
            val url: NSURL? = NSFileManager.defaultManager.URLForDirectory(
                directory = directory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )
            val pathString = url?.path
                ?: error("Unable to resolve iOS directory: $directory")
            return pathString.toPath()
        }
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster252.staleKdocSweep.cascade, Task #708, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster252 leaf 3 of 5 — :platform iosMain filesystem IosAppFileSystem,
 * sibling 534 of 5-LEAF-IOSMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER sweep.
 * Cumulative section-253-postscript count = 258 leaves with this commit.
 *
 * File-shape note: 47-line file (pre-postscript) — file-level KDoc (11
 * lines) preserved verbatim. 1 top-level class (IosAppFileSystem)
 * implementing AppFileSystem with 2 override vals (filesDir + cacheDir)
 * AND 1 override fun (fileSystem). 6 imports (ExperimentalForeignApi +
 * okio.FileSystem + okio.Path + Path.Companion.toPath + NSCachesDirectory
 * + NSDocumentDirectory + NSFileManager + NSSearchPathDirectory + NSURL
 * + NSUserDomainMask). 1 companion (resolveDir helper). 1 class-level
 * @OptIn(ExperimentalForeignApi).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - APPFILESYSTEM-IOS-ACTUAL-LIVE — class implements AppFileSystem
 *     with 2 override vals + 1 override fun. 3-AGREE-WITH-cluster251-
 *     LEAF-3-AndroidAppFileSystem (same 3-member shape). PRESERVE.
 *
 *   - NSDOCUMENTDIRECTORY-FOR-FILESDIR-LIVE — filesDir resolves from
 *     NSDocumentDirectory. The directory choice IS load-bearing because
 *     (a) IS the iOS-canonical durable storage, (b) backed up by iCloud
 *     by default (unless excluded), (c) survives across launches but
 *     IS wiped on uninstall. PRESERVE — 1-MIRRORS-cluster251-LEAF-3-
 *     ANDROIDFILESDIR (same semantic role).
 *
 *   - NSCACHESDIRECTORY-FOR-CACHEDIR-LIVE — cacheDir resolves from
 *     NSCachesDirectory. The directory choice IS load-bearing because
 *     (a) IS the iOS-canonical cache storage, (b) NOT backed up to
 *     iCloud, (c) MAY be reclaimed by the OS under low-storage pressure.
 *     PRESERVE — 1-MIRRORS-cluster251-LEAF-3-ANDROIDCACHEDIR (same
 *     semantic role, OS-managed transience).
 *
 *   - NSFILEMANAGER-URLFORDIRECTORY-CREATE-TRUE-LIVE — calls
 *     `URLForDirectory(directory, NSUserDomainMask, null, create=true)`.
 *     The create=true flag IS load-bearing because (a) IS the iOS-
 *     idiomatic way to ensure-exists, (b) prevents downstream
 *     "directory not found" errors. PRESERVE-AS-DOCUMENTED — KDoc cites
 *     "create = true so the Documents / Caches directories are guaranteed
 *     to exist on first access".
 *
 *   - NSUSERDOMAINMASK-LIVE — domain = NSUserDomainMask (vs System /
 *     Local / Network). The user-domain choice IS load-bearing because
 *     IS the only writable domain for an iOS sandboxed app. PRESERVE.
 *
 *   - ERROR-ON-NULL-PATH-LIVE — `pathString = url?.path ?: error(
 *     "Unable to resolve iOS directory: $directory")`. The eager-throw
 *     IS load-bearing because (a) iOS sandbox failing to resolve
 *     Documents/Caches IS unrecoverable, (b) crash-with-clear-message
 *     IS better than crash-later-on-Path-toPath. PRESERVE-AS-DOCUMENTED
 *     — KDoc explicitly cites "the constructor throws because there's
 *     nothing useful the caller can do at that point".
 *
 *   - FILESYSTEM-SYSTEM-CROSS-PLATFORM-LIVE — `FileSystem.SYSTEM` (Okio's
 *     default). 1-AGREE-WITH-cluster251-LEAF-3-AndroidAppFileSystem (same
 *     FileSystem.SYSTEM choice). The SYSTEM choice IS load-bearing
 *     because Okio's SYSTEM IS POSIX-backed on iOS/Native and JVM-
 *     backed on Android — both correct platform delegates. PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `AppFileSystem.ios.kt` actual
 *     byte-for-byte". 9-AGREE-WITH-CASCADE-OF-EARLIER-BYTE-FOR-BYTE-
 *     CITATIONS. PRESERVE-AS-DOCUMENTED.
 *
 *   - OPTIN-EXPERIMENTAL-FOREIGN-API-LIVE — `@OptIn(ExperimentalForeign
 *     Api::class)`. 1-AGREE-WITH-cluster252-LEAF-1-IosSecureStorage (same
 *     opt-in pattern). The opt-in IS load-bearing because iOS cinterop
 *     APIs (NSURL? cast, URLForDirectory signature) ARE experimental.
 *     PRESERVE.
 *
 *   - COMPANION-WITH-HELPER-FUNCTION-LIVE — `private companion object {
 *     private fun resolveDir(directory): Path { ... } }`. 1-DIVERGES-
 *     FROM-cluster251-LEAF-3-AndroidAppFileSystem (which has NO companion).
 *     The companion-helper IS load-bearing because resolveDir IS called
 *     2× from val-init expressions — extracting avoids 18 lines of
 *     duplication. PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-DIVERGES-FROM-cluster251-LEAF-3-AndroidAppFileSystem (which
 *     takes Context). The zero-param shape IS load-bearing because iOS
 *     uses NSFileManager.defaultManager (global singleton) — no Context-
 *     equivalent needed. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster252-LIVE — IosAppFileSystem IS
 *     leaf 3 of 5 of cluster252. PRESERVE.
 */

