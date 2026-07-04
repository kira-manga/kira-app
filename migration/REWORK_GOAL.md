# Architecture Rework — Kickoff Goal

> Paste the block below into a fresh Claude Code session opened against
> `D:\yami manga\yami-kmp\` to start the rework. The session must already
> be on branch `architecture-rework` (created on 2026-05-25 off `kmp-migration`
> at commit `98bf8ed`).

---

## GOAL

You are continuing the Yami Manga KMP **architecture rework**. The Yami app already works on
Android + iOS + Desktop (commit `98bf8ed` on branch `architecture-rework`). The goal of this work
is to refactor the existing codebase to clean architecture + strict MVI + strict layer separation
**without changing any user-visible behavior, breaking any feature, or losing any data the user
currently has**. Functionality preservation is non-negotiable.

The permanent technical contract for every architectural decision is at
`migration/ARCHITECTURE_REWORK_CONTRACT.md`. Read it once at the start of every session and obey
it. If a rule in the contract conflicts with something you'd otherwise do, the contract wins.

### Operating rules for this rework

1. **Branch.** All work happens on the local branch `architecture-rework`. Do not push to `main`.
   Do not push to `kmp-migration`. The rework branch is local-only until the owner explicitly
   asks for a push.

2. **Phase 0 is mandatory and comes first — DO NOT skip it.** Before refactoring a single line,
   review the existing code and produce `migration/ARCHITECTURE_BASELINE.md`. It must contain:
   - Every feature the app ships today (home, library, reader, downloads, search, settings,
     details, history, notifications, webview, repos, etc. — the full list).
   - For each feature: entry-point composable, ViewModel, repository chain, DAOs, network calls,
     side effects (navigation routes, snackbars, dialogs, notifications, intents).
   - Current package boundaries and what cross-feature dependencies exist today (the rework's
     boundary plan flows from this).
   - The exact list of `expect`/`actual` declarations and what they do.
   - Build configuration: Kotlin version, CMP version, Coil version, Room version, Ktor version,
     Koin version, all relevant `libs.versions.toml` entries.
   - Where image-quality fixes, AVIF decoder registration, OkHttp fetcher override, and
     `HighQualitySkiaImageDecoder` live today — these are LOAD-BEARING and must survive the
     rework intact. (See `MEMORY.md` index → image-quality entries.)

   No code edits until the baseline doc is committed.

3. **Then follow the contract's Build Order (Section 15, steps 1–12).** Treat
   "continue automatically" as in effect for this rework — proceed between phases without
   pausing to ask for confirmation — BUT continue to run the SOLID Guardian checklist after
   every file and every phase (Section 7), and log every result to `SOLID_AUDIT.md`. The
   checklist is not optional even in auto-continue mode.

4. **Functionality preservation gate.** After each phase, verify against the baseline:
   - Every feature listed in `ARCHITECTURE_BASELINE.md` must still compile and (where buildable)
     still work end-to-end. Android compile is the cheapest signal on Windows — run
     `gradlew.bat :composeApp:compileDebugKotlinAndroid` after any phase that touched
     Kotlin source. iOS klib compile (`gradlew.bat :composeApp:compileKotlinIosArm64
     :composeApp:compileKotlinIosSimulatorArm64`) after any phase that touched commonMain or
     iosMain. Do not move to the next phase if either is red.
   - If a refactor would change observable behavior, STOP and ask. The contract's Section 16
     "Tooling Honesty & Fallback Rules" already requires this for library compatibility
     issues — extend the same rule to behavioral changes.

5. **Working-functionality safeguard rules.** During refactors:
   - Do not delete a working file until its replacement compiles AND any caller is rewired.
   - Do not change the public surface of a feature (route, intent shape observable from
     outside, persisted DB schema) without bumping it as a documented decision in
     `ARCHITECTURE.md` and updating callers.
   - Preserve Room schemas exactly — column names, types, indices, foreign keys. If a refactor
     would migrate data, write the Room migration and verify it round-trips a sample DB.
   - Preserve every existing memory-noted load-bearing fix:
     • `HighQualitySkiaImageDecoder` registration on iOS + Desktop (commit `98bf8ed`).
     • Per-request `.maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))`.
     • Singleton `ImageLoader.maxBitmapSize(Size.ORIGINAL)` in App.kt.
     • Android `applyPlatformDecoderHints` with `allowHardware(false)` + `RGB_565`.
     • OkHttp `NetworkFetcher.Factory` override on Android.
     • AVIF decoder registration on Android.
     • The 5 reader image-quality memory files in `MEMORY.md` are mandatory reading.

6. **Standing project rules still apply.**
   - Max 5 files per commit.
   - Never modify `D:\yami manga\yami-manga-apk-main\` (read-only reference).
   - Never commit secrets, `local.properties`, `*.keystore`, `google-services.json`.
   - No force-push, no `--no-verify`, no skipping hooks.
   - No `!!`, no `Any`, no `lateinit var` in domain/presentation per the contract.

7. **Tooling honesty.** Never claim a Gradle / lint / detekt / test command ran unless it
   actually ran. If a verification step is impossible on Windows (e.g. iOS framework linking,
   Xcode build), say so and mark it deferred to the macOS host.

8. **Block-and-ask conditions** — the ONLY situations where you stop and wait:
   a. The contract's library-compatibility blockers (e.g. Navigation 3 not stable, Room KMP
      requires a per-platform decision you can't make alone).
   b. A refactor would change user-observable behavior.
   c. A pre-rework feature can no longer be made to compile after a phase.
   d. A SOLID Guardian violation cannot be fixed without making a scoping decision.

   Otherwise: keep going. Do not stop after each phase asking for permission — the user has
   already granted that permission for this rework.

### Start with Phase 0

Begin now by reading the existing code and producing `migration/ARCHITECTURE_BASELINE.md`.
Commit it (≤5 files per commit) before touching anything else. Then proceed to Phase 1 of
the contract's Build Order without waiting for further input.
