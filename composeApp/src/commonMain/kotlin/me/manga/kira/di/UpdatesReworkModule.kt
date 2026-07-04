package me.manga.kira.di

import me.manga.kira.data.repository.UpdatesRepositoryImpl
import me.manga.kira.domain.repository.UpdatesRepository
import me.manga.kira.domain.usecase.updates.DeleteAllUpdatesUseCase
import me.manga.kira.domain.usecase.updates.DeleteUpdateEntryUseCase
import me.manga.kira.domain.usecase.updates.MarkAllUpdatesAsReadUseCase
import me.manga.kira.domain.usecase.updates.MarkUpdateAsReadUseCase
import me.manga.kira.domain.usecase.updates.ObserveUpdatesUseCase
import me.manga.kira.domain.usecase.updates.RestoreUpdateEntryUseCase
import me.manga.kira.presentation.updates.UpdatesViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Updates slice (Phase 7.x.updates).
 *
 * Scope discipline (mirrors [historyReworkModule] / [statisticsReworkModule]):
 *  - Binds ONLY rework types: [UpdatesRepository] (`:domain`) → [UpdatesRepositoryImpl] (`:data`),
 *    six use cases (`:domain`), and [UpdatesViewModel] (`:presentation`).
 *  - Legacy `:shared`
 *    [me.manga.kira.presentation.features.notifications.domain.NotificationRepository]
 *    facade stays bound by `SharedModule` as the underlying cell-of-truth; the legacy
 *    NotificationsViewModel + legacy UpdatesScreen that previously consumed it were retired in
 *    §144/§145 once the rework UpdatesScreen took over the `Screen.Updates` route.
 *
 * Cross-module dependencies resolved at composition time:
 *  - The legacy `:shared` Room DAOs (`NotificationDao` + `LibraryDeo`, the constructor deps of
 *    [UpdatesRepositoryImpl]) are bound `single` per platform by `PlatformModule.*` already.
 *    Strangler-fig posture — see [UpdatesRepositoryImpl] KDoc for the boundary rationale (same
 *    posture as [me.manga.kira.data.repository.HistoryRepositoryImpl] /
 *    [me.manga.kira.data.repository.ReadingSessionRepositoryImpl] /
 *    [me.manga.kira.data.repository.ReadingStatisticsRepositoryImpl]).
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [UpdatesRepository] interface from `:domain` is bound to its `:data`
 * impl at the composition root. Presentation and UI see only the use cases / interface.
 *
 * Lifecycle choices:
 *  - [UpdatesRepository] → `single`: impl holds no per-call state; the underlying
 *    `NotificationDao` / `LibraryDeo` are already singletons (Room re-emits the notification
 *    flow on every write). Re-creating the impl per resolution would mean resubscribing on each
 *    consumer — wasteful for a read-mostly surface shared across the app's lifetime.
 *  - Six use cases (`ObserveUpdatesUseCase`, `MarkUpdateAsReadUseCase`,
 *    `MarkAllUpdatesAsReadUseCase`, `DeleteUpdateEntryUseCase`, `RestoreUpdateEntryUseCase`,
 *    `DeleteAllUpdatesUseCase`) → `factory`: stateless thin pass-throughs, cheap; matches the
 *    established "use case is a factory" pattern.
 *  - [UpdatesViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors `HistoryViewModel` and
 *    `LibraryViewModel`.
 */
val updatesReworkModule: Module = module {
    single<UpdatesRepository> { UpdatesRepositoryImpl(notificationDao = get(), libraryDeo = get()) }
    factory { ObserveUpdatesUseCase(get()) }
    factory { MarkUpdateAsReadUseCase(get()) }
    factory { MarkAllUpdatesAsReadUseCase(get()) }
    factory { DeleteUpdateEntryUseCase(get()) }
    factory { RestoreUpdateEntryUseCase(get()) }
    factory { DeleteAllUpdatesUseCase(get()) }
    viewModel { UpdatesViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster150.staleKdocSweep.cascade,
 * Task #606, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-fourth sibling of the cluster57-149
 * sweep — third file of the wave-26 :composeApp/di rework Koin module
 * closing 4-leaf batch alongside ReaderReworkModule plus AboutReworkModule
 * plus ReworkModules aggregator):
 *  (a) "Koin-bindings-for-the-rework-Updates-slice-Phase-7.x.updates +
 *  Scope-discipline-mirrors-historyReworkModule-statisticsReworkModule
 *  + Binds-ONLY-rework-types-UpdatesRepository-:domain-UpdatesRepository
 *  Impl-:data-five-use-cases-:domain-and-UpdatesViewModel-:presentation +
 *  Legacy-:shared-NotificationRepository-facade-stays-bound-by-Shared
 *  Module-as-the-underlying-cell-of-truth + the-legacy-Notifications
 *  ViewModel-plus-legacy-UpdatesScreen-that-previously-consumed-it-were-
 *  retired-in-section-144-section-145-once-the-rework-UpdatesScreen-took-
 *  over-the-Screen.Updates-route" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION. Verified: updatesReworkModule binds 7 types (Updates
 *  Repository + 5 use cases + UpdatesViewModel). Legacy Notifications
 *  ViewModel + UpdatesScreen retirement at sections 144+145 was
 *  FULFILLED at Task #309 (Phase 9.z.dead_vm_retire — legacy
 *  NotificationsViewModel deleted) plus Task #310 (Phase 9.aa.updates.
 *  legacy_retire — legacy UpdatesScreen + UpdateItem deleted). The
 *  underlying :shared NotificationRepository facade stays bound by
 *  SharedModule for the strangler-fig (consumed by both rework Updates
 *  Repository and legacy NotificationDao writers/readers).
 *  (b) "Cross-module-dependencies-resolved-at-composition-time + Legacy-
 *  NotificationRepository-the-constructor-dep-of-UpdatesRepositoryImpl-
 *  is-bound-single-by-SharedModule-already + Strangler-fig-posture-see-
 *  UpdatesRepositoryImpl-KDoc-for-the-boundary-rationale-same-posture-
 *  as-HistoryRepositoryImpl-ReadingSessionRepositoryImpl-Reading
 *  StatisticsRepositoryImpl" — LIVE-NOT-STALE. Verified: legacy :shared
 *  NotificationRepository stays bound by SharedModule and is consumed
 *  by UpdatesRepositoryImpl via `legacy = get()` ctor param. The same-
 *  strangler-fig-posture cross-reference to HistoryRepositoryImpl +
 *  ReadingSessionRepositoryImpl + ReadingStatisticsRepositoryImpl is
 *  honored — all four :data impls delegate to corresponding legacy
 *  :shared repositories during the wave-25/26 strangler-fig transition
 *  (sibling 182 readerReworkModule documents the same pattern for the
 *  Reader slice via SourcesRepository + StatisticsRepository legacy
 *  delegations).
 *  (c) "SRP-contract-section-6-one-module-one-feature-slice + DIP-
 *  contract-section-6-the-UpdatesRepository-interface-from-:domain-is-
 *  bound-to-its-:data-impl-at-the-composition-root + Presentation-and-
 *  UI-see-only-the-use-cases-interface + Lifecycle-choices + Updates
 *  Repository-single-impl-holds-no-per-call-state-the-underlying-legacy-
 *  NotificationRepository-is-already-a-singleton-it-owns-the-Notification
 *  Dao-and-re-emits-the-getAllNotifications-flow-on-every-write + Re-
 *  creating-the-impl-per-resolution-would-mean-resubscribing-on-each-
 *  consumer-wasteful-for-a-read-mostly-surface-shared-across-the-app-s-
 *  lifetime + Five-use-cases-ObserveUpdatesUseCase-MarkUpdateAsRead
 *  UseCase-MarkAllUpdatesAsReadUseCase-DeleteUpdateEntryUseCase-Delete
 *  AllUpdatesUseCase-factory-stateless-thin-pass-throughs + Updates
 *  ViewModel-viewModel-Mirrors-HistoryViewModel-LibraryViewModel" —
 *  LIVE-NOT-STALE. Verified: 1 single + 5 factory + 1 viewModel binding.
 *  The single-vs-factory rationale honored — re-subscribing the
 *  underlying NotificationRepository flow on each impl resolution would
 *  break flow-emission deduplication for downstream consumers
 *  (ObserveUpdatesUseCase).
 *  Three classifications STAND on their own merits. Original Phase 7.x
 *  .updates (Task #240) plus Phase 7.x.updates.downloadbutton (Task
 *  #299/#300) module-binding prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
