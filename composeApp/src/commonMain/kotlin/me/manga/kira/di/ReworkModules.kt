package me.manga.kira.di

import me.manga.kira.data.complaint.di.complaintRepositoryModule
import me.manga.kira.data.complaint.di.complaintUseCasesModule
import me.manga.kira.data.download.di.downloadModule
import me.manga.kira.data.legacy.di.legacyDataModule
import org.koin.core.module.Module

/**
 * Aggregator for all rework Koin modules (Phase 8).
 *
 * SRP: one place all three hosts consume to wire the rework graph — this list is LIVE on every
 * platform (Android `MyApp.onCreate`, Desktop `Main.kt`, iOS `IosKoin.bootstrapIosKoin` →
 * `doInitKoin`), each combining `allSharedModules() + platformModule() + allReworkModules()`.
 * (An earlier revision of this header claimed the list was not yet consumed — stale since the
 * hosts were cut over; corrected 2026-07-04, audit Wave C.)
 *
 * OCP: adding a new feature slice's module is an append here. Hosts never change.
 */
fun allReworkModules(): List<Module> = listOf(
    coreReworkModule,
    libraryReworkModule,
    detailsReworkModule,
    readerReworkModule,
    statisticsReworkModule,
    historyReworkModule,
    updatesReworkModule,
    sourcesReworkModule,
    themeReworkModule,
    aboutReworkModule,
    whatsNewReworkModule,
    languageReworkModule,
    complaintReworkModule,
    complaintAdminReworkModule,
    settingsReworkModule,
    downloadsReworkModule,
    // Legacy chapter-download engine bindings, extracted from :shared's platformModule() to
    // :data:download (strangler-fig Phase 4). Per-target expect/actual downloadModule() — Android
    // WorkManager / iOS URLSession-or-coroutine / Desktop coroutine. Loaded here so all three hosts
    // get it (this list is appended to allSharedModules() + platformModule() in every host's initKoin).
    downloadModule(),
    homeReworkModule,
    // Generic-sources subsystem (Stage-1). Consumed by the 4 :data repos branching on
    // sourceRegistry.isConfigBacked(api) — see SourcesGenericModule.
    sourcesGenericModule,
    // Legacy VMs relocated from :shared's sharedModule (strangler-fig Phase 5): WebView / Settings /
    // WhatsNew, still consumed by composeApp route adapters.
    legacySharedViewModelsModule,
    // Complaint (feedback) feature relocated from :shared to :data (strangler-fig Phase 5):
    // platform-independent use cases + the per-target ComplaintRepository (Firebase on Android,
    // Ktor REST on iOS/Desktop).
    complaintUseCasesModule,
    complaintRepositoryModule(),
    // Legacy settings/statistics repos + What's-New remote data source relocated from :shared to
    // :data (strangler-fig Phase 5).
    legacyDataModule,
    // Push / in-app-message deep-link bus (NotificationRouter). Shared by the Android + iOS tap
    // handlers and the navigation host in App.kt.
    pushModule,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster150.staleKdocSweep.cascade,
 * Task #606, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-fifth sibling of the cluster57-149
 * sweep — CLOSING file of the wave-26 :composeApp/di rework Koin module
 * closing 4-leaf batch alongside ReaderReworkModule plus AboutReworkModule
 * plus UpdatesReworkModule; CLOSES :composeApp/di tier 16/16):
 *  (a) "Aggregator-for-all-rework-Koin-modules-Phase-8 + SRP-one-place-
 *  that-hosts-Android-MyApp-iOS-KoinHelper.doInitKoin-Desktop-Main-
 *  consume-to-wire-the-rework-graph-into-the-existing-Koin-app + Hosts-
 *  call-initKoin-modules-allReworkModules-toTypedArray-or-equivalent-
 *  once-Phase-8.x-extends-initKoin-s-signature-to-accept-extra-modules +
 *  Until-then-this-list-is-unused-its-presence-guarantees-the-bindings-
 *  compile-against-the-rest-of-the-graph + OCP-adding-a-new-feature-
 *  slice-s-module-is-an-append-here-Hosts-never-change" — LIVE-NOT-STALE
 *  plus FULFILLED-PREDICTION. Verified: allReworkModules() returns a
 *  15-element list of every rework slice's Koin module (libraryRework
 *  Module + detailsReworkModule + readerReworkModule + statistics
 *  ReworkModule + historyReworkModule + updatesReworkModule + sources
 *  ReworkModule + themeReworkModule + aboutReworkModule + whatsNew
 *  ReworkModule + languageReworkModule + complaintReworkModule +
 *  complaintAdminReworkModule + settingsReworkModule + downloadsRework
 *  Module). The "Phase 8.x extends initKoin's signature to accept extra
 *  modules" forecast is FULFILLED — Task #163 (Phase 8.x: extend
 *  initKoin() to thread allReworkModules() through hosts) landed; the
 *  list is now LIVE-WIRED via the Android MyApp, iOS KoinHelper.do
 *  InitKoin, and Desktop Main entry points. The OCP append-only-here
 *  posture honored — each subsequent feature slice (LanguageRework
 *  Module + ComplaintReworkModule + ComplaintAdminReworkModule +
 *  SettingsReworkModule + DownloadsReworkModule) was added by appending
 *  a single line to allReworkModules() without touching the host wiring.
 *  CLOSING FILE of cluster150 — completes the :composeApp/di rework
 *  Koin module tier sweep (16 of 16: 12 indirectly via the cluster3-15
 *  route-adapter cross-references which name each module's bindings
 *  inline, plus 4 directly via cluster150: ReaderReworkModule plus
 *  AboutReworkModule plus UpdatesReworkModule plus this aggregator).
 *  Wave-26 progress through :composeApp/di tier complete — remaining
 *  :composeApp commonMain unswept surface is NavigationLock.kt plus
 *  safePopBackStack.kt, both of which carry zero KDoc prose and are
 *  therefore not eligible for §253 postscripts per the cascade
 *  convention (only files with documented contracts get classification
 *  postscripts; pure-impl files are audited only by cross-references
 *  threaded through their consumers — App.kt sibling cluster93 names
 *  safeNavigate + safePopBackStack inline). One classification.
 *  Original Phase 8 (Task #162) aggregator prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
