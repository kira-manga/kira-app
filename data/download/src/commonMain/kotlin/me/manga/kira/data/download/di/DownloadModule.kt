package me.manga.kira.data.download.di

import org.koin.core.module.Module

/**
 * Per-target Koin bindings for the legacy chapter-download engine (strangler-fig Phase 4).
 *
 * Extracted from `:shared`'s `platformModule()` actuals so `:shared` keeps ZERO references to the
 * download engine — that is what lets `:data:download -> :shared` (for the legacy Library/Sources
 * repos) stay acyclic. Only the bindings that *construct* a `:data:download` type moved here
 * (`ChapterPageResolver`/`ChapterFinalizer`/`DownloadManifestStore` + the `DownloadRepository`
 * impls + Android's `ChapterDownloadService`); the `:platform` background/CBZ/WorkManager bindings
 * they resolve via `get()` stay in `platformModule()`.
 *
 * Appended to `allReworkModules()` in `:composeApp`, so all three hosts (Android `MyApp`, iOS
 * `bootstrapIosKoin`, Desktop `Main`) load it alongside `allSharedModules() + platformModule()`.
 * Koin resolves each collaborator by type across the combined graph, so binding order is
 * irrelevant (every binding is a lazy `single`).
 */
expect fun downloadModule(): Module
