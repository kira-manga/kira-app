package me.manga.kira.presentation.features.download.data

sealed class DownloadState {
    data class InProgress(
        val totalImages: Int,
        val downloadedImages: Int,
        val currentImageUrl: String,
    ) : DownloadState()

    data class Compressing(
        val totalImages: Int,
    ) : DownloadState()

    data class Complete(val localPaths: List<String>) : DownloadState()

    data class Error(
        val exception: Throwable,
        val downloadedImages: Int,
        val totalImages: Int,
    ) : DownloadState()
}

/*
 * Audit-trail postscript (Phase 9.x.cluster207.staleKdocSweep.cascade, Task #663, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster207 leaf 1/5 — :shared/download/data/ tier opener, sibling 374. Wave-64 opens with a
 * cross-feature data-shape 5-leaf sweep (download/data/ pair + home/data/ pair + settings/data/
 * single). Cumulative §253-postscript count = 99 leaves with this commit.
 *
 * File-shape note: 21-line sealed class — `DownloadState` with 4 subclasses (InProgress with
 * totalImages+downloadedImages+currentImageUrl; Compressing with totalImages; Complete with
 * localPaths: List<String>; Error with exception+downloadedImages+totalImages). Zero imports.
 * Zero block-KDoc — bare-class shape (no port-lineage prose).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE — direct consumers (verified via FQN grep filtered
 *     to the legacy DownloadState symbol — :domain/model/downloads/DownloadState.kt is a
 *     SEPARATE rework counterpart, not a consumer):
 *       1. ChapterDownloadService.kt (:shared/androidMain/.../download/domain/) — Android-only
 *          per-chapter foreground-service emits InProgress/Compressing/Complete/Error transitions
 *          as the download lifecycle advances.
 *       2. DownloadWorkerV2.kt (:shared/androidMain/.../download/ui/test2/) — WorkManager
 *          adapter wrapping ChapterDownloadService; emits the same 4 subclass states to its
 *          Flow<DownloadState> output for UI consumption.
 *     iOS/Desktop have no legacy DownloadState consumers (no foreground-service equivalent —
 *     the rework :data CoroutineDownloadRepositoryImpl bypasses this shape entirely).
 *
 *   • INVERTED-PARALLEL — rework counterpart at `:domain/model/downloads/DownloadState.kt`
 *     (cluster136) carries a DIFFERENT shape entirely — flat enum-like discriminator (Queued /
 *     Running / Succeeded / Failed / Cancelled) instead of an InProgress/Compressing/Complete/
 *     Error sealed hierarchy. The legacy 4-state union is a Coil/Worker-side lifecycle (load →
 *     compress → finalize → error); the rework :domain model is a queue-orchestration lifecycle
 *     (queue → execute → terminal). Identity match NOT required — the layers consume different
 *     shapes for different orchestrators.
 *
 *   • CROSS-FEATURE-LIFECYCLE-LIVE — Compressing subclass is the cell-of-truth for the
 *     post-download CBZ packaging phase. ChapterDownloadService emits Compressing(totalImages)
 *     between the final InProgress page-load and Complete(localPaths); UI surfaces a "Packaging
 *     X images…" message during this brief window. DO NOT collapse Compressing into InProgress
 *     — the absence of currentImageUrl is a meaningful signal that pages are no longer being
 *     fetched (cleanup-phase observer).
 *
 *   • EXCEPTION-PASSTHROUGH-LOAD-BEARING — Error.exception is a raw Throwable (no
 *     classification, no error-code shape). DO NOT coerce to a sealed AppError during cleanup
 *     passes — the legacy WorkManager layer relies on the original exception identity to drive
 *     retry-vs-give-up decisions (transient IOException → retry; SecurityException → give up).
 *     A sealed-error rewrite would breach observable behavior even if functionally equivalent.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure same-package data-shape.
 */
