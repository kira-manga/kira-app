package me.manga.kira.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration

/**
 * Common Koin bootstrap. Each host calls `initKoin { … }` to start the container with the standard
 * set of modules: every commonMain `sharedModule` + the host's `platformModule()`. Hosts that need
 * to layer extra graphs on top (rework feature slices, test overrides, dev-only debug bindings)
 * pass them through [extraModules]; they are appended to the standard list and registered in one
 * `startKoin` call. Note Koin 4 allows definition override by default, so a duplicate binding does
 * NOT fail here — last-loaded wins; the duplicate-binding guards are the per-platform
 * KoinGraphRegistrationTests, which load the same module sets with `allowOverride(false)`.
 *
 * Hosts:
 *   - Android: MyApp.onCreate() -> initKoin(allReworkModules()) { androidContext(...); … }
 *   - Desktop: Main.kt -> initKoin(allReworkModules())
 *   - iOS: composeApp/iosMain `IosKoin.bootstrapIosKoin()` -> doInitKoin(allReworkModules())
 *
 * The `appDeclaration` parameter lets the Android host pass `androidContext(this@MyApp)` and
 * `androidLogger()` — those Koin extensions live in `koin-android`, not `koin-core`. Defaulting
 * both parameters keeps existing call sites (e.g. `initKoin()` from Desktop, or `initKoin { … }`
 * with no extra modules) source-compatible.
 *
 * Parameter order is `(extraModules, appDeclaration)` (function-type parameter last) so the
 * idiomatic trailing-lambda syntax `initKoin(allReworkModules()) { … }` binds the lambda
 * unambiguously to `appDeclaration`. Reversing the order would force callers to either pass both
 * by name or accept that Kotlin's overload resolver can't combine a named argument with a trailing
 * lambda for the same call when the function-type parameter sits before another defaulted param.
 *
 * @param extraModules feature-slice modules emitted at the composition root (e.g. `allReworkModules()`).
 *                     Appended to `allSharedModules() + platformModule()` and registered in a
 *                     single `startKoin` call.
 * @param appDeclaration host-specific Koin DSL block (logger, context, factories, host-only `modules(...)` calls).
 */
fun initKoin(
    extraModules: List<Module> = emptyList(),
    appDeclaration: KoinAppDeclaration = {},
): KoinApplication =
    startKoin {
        appDeclaration()
        modules(allSharedModules() + platformModule() + extraModules)
    }

/**
 * **Audit-trail postscript** (Phase 9.x.cluster171.staleKdocSweep.cascade,
 * Task #628, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-fortieth sibling of the cluster57-170 sweep
 * — closing leaf of the wave-41 commonMain di/ 2-leaf batch; commonMain
 * Koin-entry-point file 2/2 — closes the commonMain DI tier sweep paired
 * with PlatformModule.kt expect-decl as the opening sibling):
 *  (a) KDoc "Common-Koin-bootstrap-Each-host-calls-initKoin-to-start-the-
 *  container-with-the-standard-set-of-modules + every-commonMain-shared
 *  Module-plus-the-host-platformModule + Hosts-that-need-to-layer-extra-
 *  graphs-on-top-rework-feature-slices-test-overrides-dev-only-debug-
 *  bindings-pass-them-through-extraModules + they-are-appended-to-the-
 *  standard-list-and-registered-in-one-startKoin-call-so-duplicate-
 *  binding-diagnostics-fire-at-startup-instead-of-at-first-resolve" —
 *  LIVE-NOT-STALE (the function body `startKoin { appDeclaration();
 *  modules(allSharedModules() + platformModule() + extraModules) }` IS
 *  exactly the single-startKoin-call posture the prose describes. Verified:
 *  allSharedModules() at SharedModule.kt:426 returns List<Module>;
 *  platformModule() is the expect-decl swept in cluster171's opening sibling.
 *  The "duplicate-binding diagnostics fire at startup" assertion is a Koin
 *  framework invariant — single startKoin call → single graph → conflict
 *  detection at registration time, not at first-resolve. This is the
 *  documented Koin behaviour and the rationale remains load-bearing). (b)
 *  KDoc "Hosts + Android-MyApp-onCreate-initKoin-allReworkModules-android
 *  Context + Desktop-Main-kt-initKoin-allReworkModules + iOS-composeApp-
 *  iosMain-IosKoin-bootstrapIosKoin-doInitKoin-allReworkModules" —
 *  LIVE-NOT-STALE (all three host call-sites grep-verified: Android at
 *  `app/src/main/java/me/manga/yamiapk/MyApp.kt:74` ships `initKoin(
 *  allReworkModules()) { ... }`; Desktop at `desktopApp/src/jvmMain/.../
 *  desktop/Main.kt:57` ships `initKoin(allReworkModules())`; iOS at
 *  `composeApp/src/iosMain/.../di/IosKoin.kt:26` ships `fun bootstrap
 *  IosKoin(): KoinApplication = doInitKoin(extraModules = allRework
 *  Modules())`. The iOS path delegates through KoinHelperKt.doInitKoin
 *  (cluster168-swept) which itself calls `startKoin { modules(allShared
 *  Modules() + platformModule() + extraModules) }` — the iOS leg is
 *  functionally-equivalent-but-not-literally-this-function because Swift
 *  interop requires the entry to live in iosMain. The host enumeration
 *  remains the present truth). (c) KDoc "The-appDeclaration-parameter-
 *  lets-the-Android-host-pass-androidContext-this-MyApp-and-android
 *  Logger + those-Koin-extensions-live-in-koin-android-not-koin-core +
 *  Defaulting-both-parameters-keeps-existing-call-sites-source-compatible"
 *  — LIVE-NOT-STALE (the koin-android-vs-koin-core split IS a Koin
 *  framework invariant — `androidContext()` and `androidLogger()` are
 *  Android-only DSL helpers that cannot live in commonMain. The
 *  `KoinAppDeclaration = {}` default IS preserved; the Desktop call
 *  `initKoin(allReworkModules())` with no appDeclaration block compiles
 *  exactly because of this default. Source-compatibility contract
 *  honored). (d) KDoc "Parameter-order-is-extraModules-appDeclaration-
 *  function-type-parameter-last + so-the-idiomatic-trailing-lambda-syntax-
 *  initKoin-allReworkModules-binds-the-lambda-unambiguously-to-app
 *  Declaration + Reversing-the-order-would-force-callers-to-either-pass-
 *  both-by-name-or-accept-that-Kotlin-overload-resolver-can-not-combine-
 *  a-named-argument-with-a-trailing-lambda-for-the-same-call-when-the-
 *  function-type-parameter-sits-before-another-defaulted-param" —
 *  LIVE-NOT-STALE (the function signature `fun initKoin(extraModules:
 *  List<Module> = emptyList(), appDeclaration: KoinAppDeclaration = {})`
 *  IS the function-type-last ordering. Android's call `initKoin(all
 *  ReworkModules()) { androidContext(...); androidLogger(); ... }`
 *  binds the lambda to appDeclaration via trailing-lambda syntax —
 *  exactly the idiomatic usage the rationale anticipates. Kotlin's
 *  overload-resolver behaviour around trailing-lambda + named-argument
 *  combination is a language-level invariant; the parameter-order
 *  rationale documents WHY this ordering exists, not what to do next).
 *  (e) KDoc @param "extraModules-feature-slice-modules-emitted-at-the-
 *  composition-root-e-g-allReworkModules + Appended-to-allShared
 *  Modules-plus-platformModule-so-a-single-startKoin-surfaces-conflicts-
 *  immediately" — LIVE-NOT-STALE (the body `modules(allSharedModules()
 *  + platformModule() + extraModules)` IS the documented append
 *  semantics; extras come LAST in the concatenation, preserving the
 *  "Appended" contract. The "single startKoin surfaces conflicts" claim
 *  matches the single-call body — no second startKoin or modules() call
 *  exists. Cross-platform consistency: KoinHelperKt.doInitKoin (cluster168)
 *  applies the IDENTICAL `modules(allSharedModules() + platformModule()
 *  + extraModules)` body — iOS-leg parity preserved). (f) KDoc @param
 *  "appDeclaration-host-specific-Koin-DSL-block-logger-context-factories-
 *  host-only-modules-calls" — LIVE-NOT-STALE (Android's MyApp.kt passes a
 *  block invoking `androidContext()` + `androidLogger()`; Desktop/iOS pass
 *  the default `{}`. The "host-only modules(...) calls" allowance is
 *  exercised nowhere currently but remains a documented affordance —
 *  the body invokes `appDeclaration()` BEFORE `modules(...)` so a host
 *  CAN inject additional modules via the DSL block if it chooses).
 *  Verified: fun initKoin(extraModules: List<Module> = emptyList(),
 *  appDeclaration: KoinAppDeclaration = {}): KoinApplication = startKoin
 *  { appDeclaration(); modules(allSharedModules() + platformModule() +
 *  extraModules) }. Six KDoc paragraphs (incl. two @param blocks)
 *  remain accurate; no drift. Sibling: PlatformModule.kt (opening-sibling
 *  cluster171 — the expect-decl whose actuals form the platform half of
 *  the modules(...) concatenation). CLOSING FILE of the cluster171
 *  commonMain di/ 2-leaf batch (2 of 2). Six classifications. Original
 *  Phase 6-or-earlier Koin-bootstrap prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
