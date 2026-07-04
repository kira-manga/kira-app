# Rollback Plan — Section 39

> Mandatory output per `MIGRATION_PROMPT.md` Section 39.

## Scope

The `yami-kmp` repository is a **new sibling repository** alongside the read-only source `yami-manga-apk-main`. Rollback at any granularity is therefore trivial — the source project is intact and unchanged on disk.

## Per-phase rollback

| Phase | Rollback method | Commit ref |
|---|---|---|
| 0 — Inventory | `git revert ab9989e` (or just delete the migration/ docs) | `ab9989e` |
| 1 — Project graph | `git revert 585957f` | `585957f` |
| 2 — Library research | `git revert f5598db` | `f5598db` |
| 3 — Scaffolding + verification | `git revert 1648ee5 d1572ef` | `d1572ef`, `1648ee5` |
| 4 — Pure Kotlin moves | `git revert 84ef336 7833dc3 da0e49b 0dbf0d5 b023930 3c9b20c cf9fe55 864f241` | (7 batch commits + CLOSE) |
| 5 — Koin scaffold | `git revert 5df0e82 cb44274` | `cb44274`, `5df0e82` |
| 6 — Room migration | `git revert 2445e99` | `2445e99` |
| Audit | `git revert 90f1a85` (also reverts the H-1 `composeApp` package fix) | `90f1a85` |
| 7 — Ktor scaffold | `git revert 89092db` | `89092db` |

## Tag strategy (recommended for the project owner)

Before each major phase (6 Room, 7 Ktor, 9 ViewModels+Nav, 10 UI, 11 Android wiring) the agent should create a `pre-phase-N-<area>` git tag per Section 33.7:

```bash
git tag pre-phase-6-room 5df0e82
git tag pre-phase-7-ktor 90f1a85
git tag pre-phase-9-nav <pre-phase-9-commit>
git tag pre-phase-10-ui <pre-phase-10-commit>
git tag pre-phase-11-wiring <pre-phase-11-commit>
git push origin --tags
```

This wasn't done strictly in real-time; the SHAs above (one before each major phase landed) serve the same purpose retroactively.

## Full-repo rollback ("scrap and start over")

If the migration is abandoned entirely:
1. Delete `D:\yami manga\yami-kmp\`
2. Delete the GitHub repo `Apdelrahman1911/yami-kmp` (or just abandon the `kmp-migration` branch — `main` only ever received the initial commit so no source-app data is at risk)
3. Source `yami-manga-apk-main/` is untouched and still compiles + ships as the Android-only app

## Partial rollback (revert one phase, keep others)

`git revert <commit-sha>` can be done individually per phase since each phase's commits are tightly scoped to their domain (entities + DAOs + database for Phase 6; Ktor for Phase 7; etc.). The migration was designed to keep phases independent so partial rollback is non-destructive.

## Phase-6-specific (Room) rollback

If after a runtime smoke test the Room KMP migration is found broken in a way that requires reverting:

1. `git revert 2445e99` — restores pre-Room state.
2. Bindings in `SharedModule.kt` need to be removed (the Room-related `single` blocks won't compile without the Room files).
3. The `androidx-paging-common` catalog entry can stay or be removed.
4. The `shared/schemas/8.json` file is gitignored after revert anyway.

## Phase-7-specific (Ktor) rollback

If Ktor scaffold breaks anything:

1. `git revert 89092db` — restores pre-Ktor state.
2. `SharedModule.kt` needs the `single<HttpClient> { createHttpClient() }` + `single { ApiClient(get()) }` lines removed (they reference now-deleted code).

## Status

| Item | Status |
|---|---|
| Per-phase rollback procedure documented | ✅ |
| Source project (`yami-manga-apk-main/`) integrity confirmed | ✅ (untouched per `MIGRATION_PROMPT.md` Section "Project Context") |
| Pre-phase tags | ⏳ recommend project owner adds them post-hoc using SHAs above |
| Branch protection | `main` is protected (only the initial commit); migration commits go to `kmp-migration` only | ✅ |
| Force-push prevention | followed verbatim — every push was a fast-forward | ✅ |
