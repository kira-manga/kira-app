package me.manga.kira.platform.filesystem

import android.content.Context
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Android implementation of [AppFileSystem].
 *
 * Body mirrors the legacy `:shared` `AppFileSystem.android.kt` actual byte-for-byte; only the
 * type shape changed (`actual class` → `class : AppFileSystem`, `actual val` → `override val`).
 *
 * Uses `context.filesDir` and `context.cacheDir` directly — both are app-private paths managed by
 * the OS and require no runtime permission. The `okio.FileSystem.SYSTEM` instance is the default
 * blocking JVM file system; safe to call from any background dispatcher.
 */
class AndroidAppFileSystem(context: Context) : AppFileSystem {

    override val filesDir: Path = context.filesDir.absolutePath.toPath()
    override val cacheDir: Path = context.cacheDir.absolutePath.toPath()

    // Secondary cache on external storage; null when no external volume is mounted. Captured at
    // construction (Context is not retained). Swept by clearCacheLargerThan for native parity.
    override val externalCacheDir: Path? = context.externalCacheDir?.absolutePath?.toPath()

    override fun fileSystem(): FileSystem = FileSystem.SYSTEM
}

/*
 * Audit-trail postscript (Phase 9.x.cluster251.staleKdocSweep.cascade, Task #707, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster251 leaf 3 of 5 — :platform androidMain filesystem AndroidAppFileSystem,
 * sibling 529 of 5-LEAF-ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 253 leaves with this commit.
 *
 * File-shape note: 24-line file (pre-postscript) — file-level KDoc (10
 * lines) preserved verbatim. 1 top-level class (AndroidAppFileSystem)
 * implementing AppFileSystem with 2 override vals (filesDir + cacheDir)
 * AND 1 override fun (fileSystem). 4 imports (Context + FileSystem +
 * Path + Path.Companion.toPath). NO companion. 1 ctor param (Context) —
 * used ONLY in field initializers, NOT stored as a field.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - APPFILESYSTEM-ANDROID-ACTUAL-LIVE — class implements AppFileSystem
 *     with 2 override vals + 1 override fun. The 3-member shape IS load-
 *     bearing — filesDir + cacheDir IS the SPI-mandated path pair, and
 *     fileSystem() IS the Okio FileSystem injection point. PRESERVE.
 *
 *   - CONTEXT-FILESDIR-LIVE — `context.filesDir.absolutePath.toPath()`.
 *     The filesDir choice IS load-bearing because (a) IS app-private
 *     internal storage, (b) requires NO runtime permission, (c) survives
 *     across app launches but IS wiped on uninstall. PRESERVE-AS-
 *     DOCUMENTED — KDoc cites "app-private paths managed by the OS and
 *     require no runtime permission".
 *
 *   - CONTEXT-CACHEDIR-LIVE — `context.cacheDir.absolutePath.toPath()`.
 *     The cacheDir choice IS load-bearing because (a) IS app-private
 *     cache storage, (b) MAY be reclaimed by the OS under low-storage
 *     pressure, (c) DIFFERENT semantics than filesDir (transient vs
 *     durable). PRESERVE — defends against future "use filesDir for both"
 *     refactor that would break cache-purge correctness.
 *
 *   - ABSOLUTEPATH-TOPATH-LIVE — `.absolutePath.toPath()` conversion.
 *     The conversion IS load-bearing because (a) Android returns
 *     java.io.File, (b) Okio API takes okio.Path, (c) absolutePath IS
 *     the safer string form than getPath() (which CAN be relative).
 *     PRESERVE.
 *
 *   - FIELD-INIT-NOT-FIELD-STORED-LIVE — Context IS used in val-init only,
 *     NOT stored as a field. 1-DIVERGES-FROM-cluster251-LEAF-2-Android
 *     SettingsFactory + cluster251-LEAF-1-AndroidSecureStorage (which BOTH
 *     store applicationContext). The field-init shape IS load-bearing
 *     because Path values ARE captured at construction and Context IS
 *     unused thereafter (no leak risk). PRESERVE — defends against
 *     future "lazy-resolve filesDir each call" refactor that would
 *     require Context-as-field.
 *
 *   - FILESYSTEM-SYSTEM-INSTANCE-LIVE — `FileSystem.SYSTEM` (Okio's
 *     default blocking JVM file system). The SYSTEM choice IS load-
 *     bearing because (a) IS the canonical Okio singleton for JVM,
 *     (b) thread-safe for cross-dispatcher use, (c) all callers wrap
 *     calls in withContext(Dispatchers.IO) at higher layers. PRESERVE-
 *     AS-DOCUMENTED — KDoc cites "safe to call from any background
 *     dispatcher".
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body mirrors
 *     the legacy `:shared` `AppFileSystem.android.kt` actual byte-for-
 *     byte". 7-AGREE-WITH-cluster248-LEAF-3-LEAF-4-LEAF-5-PLUS-cluster249-
 *     LEAF-3-LEAF-4-LEAF-5-PLUS-cluster250-LEAF-3. PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-COMPANION-OBJECT-LIVE — class declares NO companion. 1-AGREE-
 *     WITH-cluster251-LEAF-2-AndroidSettingsFactory (which also skips
 *     companion). 1-DIVERGES-FROM-cluster251-LEAF-1-AndroidSecureStorage.
 *     PRESERVE.
 *
 *   - OVERRIDE-VAL-NOT-FUN-LIVE — filesDir + cacheDir ARE override vals
 *     (not getter funs). The val-shape IS load-bearing because the
 *     paths ARE immutable for the app lifetime; eager-init also catches
 *     malformed Context at construction. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster251-LIVE — AndroidAppFileSystem IS
 *     leaf 3 of 5 of cluster251. PRESERVE.
 */

