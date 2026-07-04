# Architecture Rework — Condensed Goal (fits `/goal` 4000-char cap)

Paste the block between the rulers into `/goal`. Full version with all nuances lives in
`REWORK_GOAL.md` and the technical contract at `ARCHITECTURE_REWORK_CONTRACT.md`.

---

Continuing the Yami KMP architecture rework. Branch `architecture-rework` off `kmp-migration@98bf8ed`. The Yami app already works on Android + iOS + Desktop. Refactor to clean architecture + strict MVI + strict layer separation WITHOUT changing user-visible behavior, breaking features, or losing data. Functionality preservation is non-negotiable.

Permanent technical contract: `migration/ARCHITECTURE_REWORK_CONTRACT.md`. Read once per session, obey strictly. Contract wins on conflicts.

RULES:
1. Branch: stay on local `architecture-rework`. Never push to main, kmp-migration, or this branch (local-only until owner asks).
2. Phase 0 is mandatory FIRST. Produce `migration/ARCHITECTURE_BASELINE.md` covering: every feature (home/library/reader/downloads/search/settings/details/history/notifications/webview/repos/etc), each one's entry-point composable + ViewModel + repository chain + DAOs + network calls + side effects, package boundaries, all expect/actual declarations, libs.versions.toml versions (Kotlin, CMP, Coil, Room, Ktor, Koin), and where the load-bearing image-quality fixes live (HighQualitySkiaImageDecoder, AVIF decoder, OkHttp fetcher, maxBitmapSize). Commit it before any refactor.
3. Then follow contract Section 15 Build Order steps 1-12. Auto-continue between phases. Run SOLID Guardian checklist (Section 7) after EVERY file and EVERY phase. Log to `SOLID_AUDIT.md`. Checklist not optional.
4. After every phase: `gradlew.bat :composeApp:compileDebugKotlinAndroid` must pass; if commonMain/iosMain touched, also `:composeApp:compileKotlinIosArm64` and `:composeApp:compileKotlinIosSimulatorArm64`. Red = stop.
5. Safeguards: don't delete a file before its replacement compiles and callers are rewired. Don't change feature public surfaces (route, intent shape, DB schema) without documenting in ARCHITECTURE.md. Preserve Room schemas exactly. Preserve these load-bearing fixes verbatim: HighQualitySkiaImageDecoder registration (iOS+Desktop), per-request maxBitmapSize(Undefined,Undefined), singleton maxBitmapSize(Size.ORIGINAL), Android RGB_565+allowHardware(false), OkHttp fetcher Android override, AVIF decoder Android registration.
6. Standing rules: max 5 files/commit, never touch `yami-manga-apk-main\`, never commit secrets/local.properties/keystore/google-services.json, no force-push, no --no-verify, no !! / Any / lateinit in domain/presentation.
7. Tooling honesty: never claim a build/test/lint ran unless it actually ran. Mark Windows-impossible checks (iOS framework linking, Xcode) as deferred to macOS.
8. Block-and-ask ONLY for: (a) contract library blockers, (b) refactor would change observable behavior, (c) feature stops compiling, (d) unresolvable SOLID violation. Otherwise keep going without asking permission.

Begin: read existing code, write `migration/ARCHITECTURE_BASELINE.md`, commit (≤5 files), then proceed to contract Build Order Phase 1 automatically.
