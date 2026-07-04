package me.manga.kira.domain.model.settings

/**
 * Progress snapshot for the "compress existing downloads" CBZ bulk-conversion run (GAP-SET-16).
 *
 * Native-parity port of the native `CbzConversionViewModel.ConversionProgress` data class. The
 * `:data` [me.manga.kira.domain.repository.SettingsRepository] impl owns a single hot
 * `MutableStateFlow<CbzConversionProgress>` and re-emits a copy of this snapshot per converted
 * chapter; the `:presentation` `SettingsViewModel` observes it via
 * [me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase] and projects each field
 * into its MVI state so the `:ui` `CbzConversionDialog` renders the determinate progress bar,
 * the converted/total counts, the current manga title + chapter number, a Stop button, and the
 * terminal Error / Success / Stopped states.
 *
 * Field semantics (faithful to native):
 *  - [isConverting] — `true` while the chapter walk is in flight; flips to `false` on every
 *    terminal state (success / stopped / error). The dialog renders the converting shell only
 *    while this is `true`; the terminal states render once it flips to `false` AND one of
 *    [error] / [successMessage] is non-null.
 *  - [totalChapters] — count of loose-image chapters that need converting (computed once after
 *    the DAO walk + already-`.cbz` filter; `0` until known, so the dialog shows an indeterminate
 *    bar for the first frame).
 *  - [convertedChapters] — count converted so far; drives the determinate bar
 *    (`convertedChapters / totalChapters`) and the "Completed X / Y" counts.
 *  - [currentMangaTitle] / [currentChapterNumber] — the item currently being packed (the dialog
 *    shows the "Current:" block only when [currentMangaTitle] is non-blank).
 *  - [error] — non-null when the DAO walk itself threw; renders the Error terminal state. Treat as
 *    a presence-only flag: because `:data` has no compose-resources access, the impl writes a
 *    stable non-localized terminal marker here, not renderable text.
 *  - [successMessage] — non-null on a completed OR stopped run; renders the Success / Stopped
 *    terminal state. Like [error], this is a presence-only terminal-state marker (a fixed
 *    non-localized sentinel), NOT a message carrier — the `:ui` `CbzConversionDialog` builds all
 *    user-visible copy (converted / remaining / failed summary) from the structured count fields.
 *    The `String` type is retained only for native wire-shape parity.
 *  - [wasStopped] — `true` when the user pressed Stop; the terminal state renders the Stopped
 *    (Warning) variant rather than the Success (CheckCircle) variant.
 *
 * Default instance ([isConverting] = `false`, all counts `0`, both message fields `null`) is the
 * idle baseline the flow replays to a fresh subscriber before / after a run — the dialog treats
 * it as "nothing to show" and stays hidden.
 */
data class CbzConversionProgress(
    val isConverting: Boolean = false,
    val totalChapters: Int = 0,
    val convertedChapters: Int = 0,
    val currentMangaTitle: String = "",
    val currentChapterNumber: String = "",
    val error: String? = null,
    val successMessage: String? = null,
    val wasStopped: Boolean = false,
)
