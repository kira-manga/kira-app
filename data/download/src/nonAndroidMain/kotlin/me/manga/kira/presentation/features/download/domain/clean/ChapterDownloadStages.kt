package me.manga.kira.presentation.features.download.domain.clean

/**
 * The shared before/after-transfer chapter stages used by both non-Android queue engines.
 * Resolution and finalization remain independent collaborators, with their existing call order,
 * cancellation checks, and iOS CPU-window gating owned by the selected engine.
 */
class ChapterDownloadStages(
    val resolver: ChapterPageResolver,
    val finalizer: ChapterFinalizer,
)
