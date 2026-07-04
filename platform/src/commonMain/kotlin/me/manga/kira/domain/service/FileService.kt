package me.manga.kira.domain.service

import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.filesystem.mangaDir

/**
 * Migration notes (Phase 8.13 batch A):
 *  - `android.content.Context.filesDir` + `java.io.File("manga/$mangaId/chapter_$chapterId")` →
 *    [AppFileSystem.mangaDir] / [AppFileSystem.chapterDir] (same on-disk layout, but resolved per
 *    platform via Okio: Android `context.filesDir`, iOS Documents, Desktop `~/.kira-manga/files`).
 *  - `File.deleteRecursively()` → `AppFileSystem.fileSystem().deleteRecursively(path)`. Okio's
 *    `deleteRecursively(path, mustExist = false)` is a no-op when the path doesn't exist, matching
 *    the source's `if (exists()) deleteRecursively()` guard.
 *  - Hilt `@Singleton @Inject` + `@ApplicationContext` annotations dropped. The constructor takes
 *    [AppFileSystem] directly; Koin will provide it via the SharedModule in a follow-up step.
 *  - `deleteChapterFiles` keeps its swallow-all `try/catch` (matches source — file deletion races
 *    in the download cleanup path should never crash the caller).
 */
class FileService(
    private val appFileSystem: AppFileSystem,
) {
    fun deleteChapterFiles(mangaId: Long, chapterId: Long) {
        try {
            val chapterDir = appFileSystem.chapterDir(mangaId, chapterId)
            appFileSystem.fileSystem().deleteRecursively(chapterDir, mustExist = false)
        } catch (_: Exception) {
            // Silently ignore — mirrors source behavior.
        }
    }

    suspend fun deleteMangaFiles(mangaId: Long) = withContext(platformIoDispatcher) {
        val mangaDir = appFileSystem.mangaDir(mangaId)
        appFileSystem.fileSystem().deleteRecursively(mangaDir, mustExist = false)
    }
}

/*
 * §253 audit-trail postscript — cluster281 §253 sweep (2026-05-29)
 * Classification: LIVE / LEGACY (pre-rework :shared commonMain domain service, still wired).
 *
 * LIVE evidence (Koin binding + consumer ctor sites, verified by grep this sweep):
 *   - KOIN BINDING: SharedModule.kt:219 ships `single { FileService(get()) }` (the getter
 *     resolves AppFileSystem, the Phase 5.4 platform facade bound per-platform in platformModule).
 *   - CONSUMER 1: MangaRepository.kt:48 `private val fileService: FileService` (ctor) — reaches
 *     fileService.deleteMangaFiles(mangaId) inside removeManga(title) (MangaRepository.kt:69).
 *   - CONSUMER 2: LibraryRepository.kt:106 `private val fileService: FileService` (ctor) — the
 *     §415 inter-repository audit note in that file lists "1 domain.service.FileService ... LIVE".
 *   - CONSUMER 3 (androidMain): ChapterDownloadService.kt:56 `private val fileService:
 *     FileService` (ctor) — the download cleanup path consumes deleteChapterFiles.
 *
 * FULFILLED-PORT vs LEGACY: LEGACY. FileService is itself a plain commonMain class (no
 *   expect/actual fan), NOT a Phase 5.x platform relocation. It is, however, a CONSUMER of one:
 *   it delegates all disk work to AppFileSystem, whose expect-decl + 3 actuals were swept in the
 *   clusters 144-149 / 217 platform-fan sweeps. So this file is the legacy commonMain orchestrator
 *   layered on top of the FULFILLED-PORT AppFileSystem facade; it stays LIVE for the three
 *   consumers above and has not been strangler-figged into a rework :domain service yet.
 *
 * Delta-axes (this service's behavioural contract):
 *   1. Platform API — `android.content.Context.filesDir` + `java.io.File("manga/$mangaId/...")`
 *      were replaced Phase 8.13 batch A by AppFileSystem.mangaDir / .chapterDir (Okio Path),
 *      resolved per platform (Android context.filesDir, iOS Documents, Desktop home dir). On-disk
 *      layout is preserved; see top-of-file migration KDoc.
 *   2. Threading/dispatcher — ASYMMETRIC by design: deleteMangaFiles is `suspend` and wraps its
 *      body in withContext(platformIoDispatcher); deleteChapterFiles is plain (non-suspend) because its
 *      callers (download-cleanup race path) invoke it from contexts that must not suspend.
 *   3. Error handling — deleteChapterFiles keeps a swallow-all `try { } catch (_: Exception) { }`
 *      (mirrors upstream — a deletion race must never crash the caller); deleteMangaFiles relies on
 *      Okio's deleteRecursively(path, mustExist = false) being a no-op on a missing path (matching
 *      the source's `if (exists()) deleteRecursively()` guard), so it needs no try/catch.
 *   4. DI binding mechanism — Koin `single { ... }`; upstream Hilt `@Singleton @Inject` +
 *      `@ApplicationContext` annotations dropped, ctor now takes AppFileSystem directly.
 *
 * Nested-comment hazard check: this file has exactly 1 pre-existing KDoc opener (the
 * slash-star-star migration-notes block at the head, lines 9-21, properly closed at line 21);
 * this appended block adds exactly one more opener and one closer, with zero interior comment
 * delimiters in the prose. Both blocks balanced (2 openers, 2 closers, no nesting).
 */
