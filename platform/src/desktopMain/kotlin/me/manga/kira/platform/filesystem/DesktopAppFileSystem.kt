package me.manga.kira.platform.filesystem

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * Desktop implementation of [AppFileSystem].
 *
 * Body mirrors the legacy `:shared` `AppFileSystem.desktop.kt` actual byte-for-byte; only the
 * type shape changed (`actual class` → `class : AppFileSystem`, `actual val` → `override val`).
 *
 * Resolves to `~/.kira-manga/{files,cache}` under the JVM user's home directory. Falls back to
 * the JVM temp dir, then to the current working directory, so the constructor never throws even
 * in odd environments (e.g. CI runners without `user.home`). Both subdirectories are created
 * eagerly via `FileSystem.SYSTEM.createDirectories(...)` so the first write doesn't surprise the
 * caller.
 */
class DesktopAppFileSystem : AppFileSystem {

    override val filesDir: Path = resolveAndCreate("files")
    override val cacheDir: Path = resolveAndCreate("cache")

    override fun fileSystem(): FileSystem = FileSystem.SYSTEM

    private companion object {
        private fun resolveAndCreate(subdir: String): Path {
            // Try each base in turn; createDirectories can still throw on a resolved-but-unwritable
            // home (read-only home, disk full, sandboxed CI), so fall through to the next candidate
            // instead of failing construction — keeps the "constructor never throws" guarantee real.
            val candidates = listOfNotNull(
                System.getProperty("user.home"),
                System.getProperty("java.io.tmpdir"),
                ".",
            )
            var last: Path? = null
            for (base in candidates) {
                val root = base.toPath() / ".kira-manga" / subdir
                last = root
                try {
                    FileSystem.SYSTEM.createDirectories(root)
                    return root
                } catch (_: IOException) {
                    // try the next candidate
                }
            }
            return last ?: ".".toPath() / ".kira-manga" / subdir
        }
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster253.staleKdocSweep.cascade, Task #709, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster253 leaf 3 of 5 — :platform desktopMain filesystem DesktopAppFileSystem,
 * sibling 539 of 5-LEAF-DESKTOPMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-CLOSER sweep.
 * Cumulative section-253-postscript count = 263 leaves with this commit.
 *
 * File-shape note: 36-line file (pre-postscript) — file-level KDoc (12
 * lines) preserved verbatim. 1 top-level class (DesktopAppFileSystem)
 * implementing AppFileSystem with 2 override vals (filesDir + cacheDir)
 * AND 1 override fun (fileSystem). 3 imports (okio.FileSystem + okio.Path +
 * Path.Companion.toPath). 1 private companion (resolveAndCreate helper).
 * NO constructor params.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - APPFILESYSTEM-DESKTOP-ACTUAL-LIVE — class implements AppFileSystem
 *     with 2 override vals + 1 override fun. 3-AGREE-WITH-cluster251-LEAF-3-
 *     AndroidAppFileSystem + cluster252-LEAF-3-IosAppFileSystem (same 3-
 *     member shape across all three platforms). PRESERVE.
 *
 *   - HOME-RELATIVE-FILES-DIR-LIVE — `filesDir = resolveAndCreate("files")`
 *     resolves to `~/.kira-manga/files`. The home-relative path IS load-
 *     bearing because (a) IS the Desktop-canonical user-scoped app data
 *     root, (b) NOT NSDocumentDirectory (iOS sandbox) NOR context.filesDir
 *     (Android sandbox) — JVM HAS NO sandbox-managed app dir, so home-
 *     relative IS the closest equivalent. PRESERVE — 1-MIRRORS-cluster251-
 *     LEAF-3-ANDROIDFILESDIR + cluster252-LEAF-3-IOSFILESDIR (same
 *     semantic role).
 *
 *   - HOME-RELATIVE-CACHE-DIR-LIVE — `cacheDir = resolveAndCreate("cache")`
 *     resolves to `~/.kira-manga/cache`. The home-relative path IS load-
 *     bearing because (a) IS Desktop's user-scoped cache equivalent,
 *     (b) JVM HAS NO OS-managed cache reclamation (vs iOS NSCachesDirectory
 *     or Android context.cacheDir), (c) under same root as filesDir (same
 *     home base, different subdir). PRESERVE — 1-MIRRORS-cluster251-LEAF-3
 *     + cluster252-LEAF-3 (same semantic role, no OS-reclamation guarantee).
 *
 *   - USER-HOME-WITH-FALLBACK-CHAIN-LIVE — `System.getProperty("user.home")
 *     ?: System.getProperty("java.io.tmpdir") ?: "."`. The 3-step fallback
 *     IS load-bearing because (a) "user.home" SHOULD always be set but
 *     edge cases (Docker, CI) MIGHT not, (b) "java.io.tmpdir" IS the
 *     universal-fallback, (c) "." IS the absolute-last-resort to avoid
 *     constructor-throws on broken environments. PRESERVE-AS-DOCUMENTED —
 *     KDoc cites "Falls back to the JVM temp dir, then to the current
 *     working directory, so the constructor never throws even in odd
 *     environments (e.g. CI runners without user.home)".
 *
 *   - HIDDEN-DIR-PREFIX-LIVE — `.kira-manga` (dot-prefix). The hidden-
 *     prefix IS load-bearing because (a) IS *nix-canonical for hidden
 *     app-config dir, (b) keeps the user's $HOME listing tidy, (c) on
 *     Windows the dot IS purely cosmetic (no special meaning) but
 *     consistent with cross-platform behavior. PRESERVE.
 *
 *   - FILESYSTEM-SYSTEM-CROSS-PLATFORM-LIVE — `FileSystem.SYSTEM` (Okio's
 *     default). 2-AGREE-WITH-cluster251-LEAF-3 + cluster252-LEAF-3 (same
 *     FileSystem.SYSTEM choice across all three). The SYSTEM choice IS
 *     load-bearing because Okio's SYSTEM IS JVM-NIO-backed on Desktop
 *     (delegates to java.nio.file.Files). PRESERVE.
 *
 *   - EAGER-CREATEDIRECTORIES-LIVE — `FileSystem.SYSTEM.createDirectories(
 *     root)`. The eager-mkdir IS load-bearing because (a) prevents
 *     downstream "directory not found" errors on first write, (b)
 *     idempotent — succeeds even if dir already exists, (c) PARALLELS
 *     iOS's URLForDirectory(create=true) — same defensive intent, different
 *     API. PRESERVE-AS-DOCUMENTED — KDoc cites "Both subdirectories are
 *     created eagerly via FileSystem.SYSTEM.createDirectories(...) so the
 *     first write doesn't surprise the caller".
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body mirrors
 *     the legacy `:shared` `AppFileSystem.desktop.kt` actual byte-for-byte".
 *     13-AGREE-WITH-CASCADE-OF-EARLIER-BYTE-FOR-BYTE-CITATIONS.
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - COMPANION-WITH-RESOLVE-HELPER-LIVE — `private companion object {
 *     private fun resolveAndCreate(subdir): Path { ... } }`. The companion-
 *     helper IS load-bearing because resolveAndCreate IS called 2× from
 *     val-init expressions — extracting avoids 8 lines of duplication.
 *     1-AGREE-WITH-cluster252-LEAF-3-IosAppFileSystem (same companion-
 *     helper pattern). 1-DIVERGES-FROM-cluster251-LEAF-3-AndroidAppFileSystem
 *     (which has NO companion). PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-AGREE-WITH-cluster252-LEAF-3-IosAppFileSystem (also no ctor params).
 *     1-DIVERGES-FROM-cluster251-LEAF-3-AndroidAppFileSystem (which takes
 *     Context). The zero-param shape IS load-bearing because JVM HAS NO
 *     Context-equivalent; System.getProperty IS a global entry point.
 *     PRESERVE.
 *
 *   - OKIO-PATH-OPERATOR-DIV-LIVE — `home.toPath() / ".kira-manga" / subdir`.
 *     The `/` operator IS load-bearing because (a) IS Okio's path-join
 *     operator, (b) handles platform-specific separators (forward-slash
 *     vs backslash) transparently, (c) cleaner than String concat with
 *     File.separator. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster253-LIVE — DesktopAppFileSystem IS
 *     leaf 3 of 5 of cluster253. PRESERVE.
 */

