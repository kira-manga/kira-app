# iOS background downloads — architecture, behavior & test plan

A production-grade background-download system for iOS, built across five milestones (M1–M5). It keeps
chapter **file transfers** running on a background `URLSession` while the app is suspended, and keeps
download **orchestration** (preparation, reconciliation, retry, finalization) advancing in
OS-granted background CPU windows — `BGContinuedProcessingTask` on iOS 26+, `BGProcessingTask` before
26.

> **Status: behind a feature flag, default OFF.** The whole system is gated by
> `DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED`
> (`shared/src/commonMain/.../presentation/features/download/DownloadEngineFlags.kt`). While it is
> `false`, iOS binds the proven in-process `CoroutineDownloadRepositoryImpl` and **behaves exactly as
> before** — every Swift/bridge entry point no-ops, no background task is ever scheduled, and the new
> engine is never even constructed. Do **not** flip it on until the manual real-device test plan
> below passes.

## How to enable (after real-device verification)
1. Set `IOS_BACKGROUND_ENGINE_ENABLED = true` in `DownloadEngineFlags.kt` and rebuild.
2. (Already in place.) `Info.plist` declares `UIBackgroundModes: [processing]` and
   `BGTaskSchedulerPermittedIdentifiers` for the two fixed identifiers.
3. To roll back: set it back to `false` and rebuild → instant return to the legacy engine (kept in
   `nonAndroidMain`). The flag can later be promoted to a DataStore/remote toggle for rebuild-free
   rollback. If the feature is ever shipped long-term OFF, drop the `processing` background-mode
   declaration for App-Review tidiness.

---

## Architecture (clean boundaries)
Shared, platform-neutral Kotlin owns the orchestration; thin platform ports + a Swift host own the
iOS specifics. Android (WorkManager) and Desktop are untouched.

| Concern | Where | Notes |
|---|---|---|
| Page-URL/header resolution | `ChapterPageResolver` (`:shared/nonAndroidMain`) | M1; shared with Desktop |
| CBZ + size + library/notification bookkeeping + SUCCESS | `ChapterFinalizer` (`:shared/nonAndroidMain`) | M1; idempotent |
| Per-chapter manifest (page list + headers + attempt counts) | `DownloadManifest` / `DownloadManifestStore` (`:shared/commonMain`) | M3; `chapter_<id>/manifest.json` |
| Reconciliation decision (pure) | `BackgroundReconciler` (`:shared/commonMain`) | M3; unit-tested |
| iOS engine | `BackgroundUrlSessionDownloadRepository` (`:shared/iosMain`) | implements `DownloadRepository` |
| Durable file transport | `BackgroundTransport` / `IosBackgroundTransport` (`:platform`) | M2; background `NSURLSession` + delegate |
| BG-CPU scheduling | `BackgroundScheduler` / `IosBackgroundScheduler` + `BackgroundWorkSignal` (`:platform`) | M4; bridges to Swift |
| Swift host | `iosApp/iosApp/AppDelegate.swift` + `IosBackgroundBridge.kt` (`:composeApp/iosMain`) | BG tasks + URLSession events |
| Rollback flag | `DownloadEngineFlags` (`:shared/commonMain`) | default OFF |

**Why the split:** the background `URLSession` runs file transfers even while the app is suspended,
but it cannot run our CPU work (scraping the next chapter's page list, building the CBZ). The BG-task
layer asks iOS for CPU windows to do that work. The newest API (`BGContinuedProcessingTask`) lives in
**Swift** (it needs the installed Xcode/iOS-26 SDK and is a host-lifecycle concern); Kotlin reaches it
only through the `BackgroundScheduler` hook + bridge entry points, so we never depend on Kotlin/Native
bindings for a brand-new SDK symbol.

---

## Background behavior by iOS version

### iOS 26+ (primary path: `BGContinuedProcessingTask` + background `URLSession`)
- The host submits a `BGContinuedProcessingTask` (fixed id `me.manga.kira.download.continued`, with a
  **system Live Activity / Dynamic Island** progress indicator) the instant download work becomes
  pending — **while the app is still foreground** (`reason=workPending`, driven by the engine's
  `scheduleProcessing` signal). This is deliberate: a continued task must be submitted while the app is
  running so the system starts it promptly; it then **continues across backgrounding**. Submitting only
  at `didEnterBackground` races with suspension and frequently never starts — so that path (and the
  `handleEventsForBackgroundURLSession` relaunch) are kept as guarded fallbacks (skipped when one is
  already running).
- While that task has execution time, Kotlin loops: reconcile → enqueue any missing page transfers →
  finalize chapters whose pages have all landed → report progress → wait briefly so the URLSession
  makes progress → repeat, until no work remains or the OS expires the task.
- File transfers run on the background `URLSession` independently and **continue across suspension and
  termination**; iOS relaunches the app in the background to deliver their completions.
- **Honest caveat:** `BGContinuedProcessingTask` is best-effort and OS-bounded (battery, thermal,
  usage, hardware). Field reports show device-inconsistent behavior even on iOS 26, so we never depend
  on it for correctness — it accelerates background progress; the durable guarantees come from the
  URLSession + the persisted queue.

### pre-iOS 26 (fallback: `BGProcessingTask` + background `URLSession`)
- File transfers still run durably on the background `URLSession` (this is the strong guarantee on
  every version).
- CPU orchestration runs only in best-effort windows: the immediate `BGProcessingTask` (fixed id
  `me.manga.kira.download.processing`) that the engine asks the OS to schedule whenever work pends,
  plus whenever the app is next opened. The OS decides **if and when** to run a `BGProcessingTask` —
  often only while charging/idle, possibly hours later, possibly not until next launch.
- **Honest caveat:** a large multi-chapter queue will **not** fully finish purely in the background on
  pre-26, because preparing each new chapter's page list (a network scrape) needs either the
  foreground or an OS-granted `BGProcessingTask`. Transfers already handed off are durable regardless.

---

## Behavior by app lifecycle state

- **Foreground (app open):** transfers run on the URLSession; the in-process engine reconciles as
  pages land, prepares the next window, and **finalizes (builds the CBZ) immediately**. Normal,
  fastest path.
- **Backgrounded, not force-quit:** the URLSession keeps transferring while suspended. CPU work
  (prepare **and finalize/CBZ**) runs in any granted BG-task window — a `BGContinuedProcessingTask` on
  26+ (submitted **while foreground** when downloads start, so it is already running as you leave), or
  an opportunistic `BGProcessingTask` before 26. The engine finalizes (builds the CBZ) **in that
  window** — gated on
  `appActive || BackgroundWorkSignal.backgroundProcessingActive`, the latter set by the host bridge
  only while a task window is actively driving the engine. So on 26+ a chapter that finishes
  transferring while you're away typically reaches **complete on its own**. A chapter sits at the
  visible **DOWNLOADED** ("finishing") state only while it waits for a window; if none is granted (or
  one expires mid-encode) it builds the CBZ on next foreground. CBZ/Skia still cannot run while plain
  **suspended** (no window) — but the `.cbz.part`→`.cbz` rename is atomic, so an interrupted encode
  never corrupts and simply re-runs next window/foreground.
- **Suspended → relaunched by iOS for URLSession events:** iOS wakes the app in the background; the
  Swift `handleEventsForBackgroundURLSession` hook hands the completion handler to the Kotlin
  transport, which finalizes file moves and posts notifications, then signals the OS it's done. If
  pages just landed (chapters now DOWNLOADED), the host also requests a finalize window from this hook
  — a continued task on 26+, else the opportunistic processing task — since the brief handleEvents
  window is not safe for Skia.
- **Force-quit (user swipe-kills the app):** iOS **cancels the background session's in-flight
  transfers** and does **not** relaunch the app for them — this is an OS rule, not a bug. On the next
  **manual** launch, the engine reconciles from the persisted `manifest.json`: pages already on disk
  are kept, and the missing ones are re-enqueued. **No progress is lost; the queue resumes.**

---

## Guaranteed vs best-effort

| Guaranteed (durable, survives suspend/kill/relaunch) | Best-effort (OS-bounded) |
|---|---|
| Page transfers already handed to the background `URLSession` complete and resume across suspension/termination | Continuing to *prepare* (scrape) further chapters while backgrounded |
| The queue, page list, and per-page attempt counts persist (Room + `manifest.json`) and resume on next launch | `BGContinuedProcessingTask` / `BGProcessingTask` actually being granted CPU time (and when) |
| No duplicate transfers, no truncated CBZ, no lost progress (see below) | A large multi-chapter queue finishing *entirely* in the background on pre-26 |
| Bounded retry of transient page failures (e.g. `-1005` connection-lost) | The exact timing of opportunistic `BGProcessingTask` runs |

---

## Notifications (foreground / background / relaunch)
Chosen UX: **silent** per-page progress ("X/Y pages") and a **silent** "Finalizing chapter…" state in
Notification Center while working; a **banner + sound** only when the chapter is actually ready to read,
or on failure. The rule: **user-facing "complete" must mean the durable artifact (the CBZ) exists**, not
merely that the network transfer finished.
- **Progress** updates fire from the engine as each page lands — including when the URLSession
  delivered it while the app was backgrounded or relaunched (the notification posts via
  `UNUserNotificationCenter`, which works in the background).
- **Finalizing (silent)** replaces progress in place at `RUNNING→DOWNLOADED`: all pages have
  transferred but the CBZ is still being built (or is waiting for a CPU window to build). Same quiet
  `DOWNLOAD_PROGRESS` presentation — no banner, no sound — so the user sees it's being processed, not
  stuck, and is never falsely told it's complete. Driven by `DownloadNotifier.onFinalizing`.
- **Completion (alerting)** fires **only at `finalize.success`** — once the `.cbz` is on disk and the
  chapter is readable — *not* at transfer completion. So a finalize that is deferred (no CPU window),
  fails, or expires mid-encode never shows "complete"; the chapter stays in the silent "Finalizing"
  state and re-finalizes on the next window/foreground, posting "complete" only when the CBZ is built.
  (When CBZ packaging is off, the loose pages already are the artifact, so finalize completes at once.)
- **Failure** fires when a page exhausts its bounded retries, in any state (foreground or a
  background/relaunch window).
- Foreground presentation is controlled by `AppDelegate.userNotificationCenter(_:willPresent:)`:
  `DOWNLOAD_PROGRESS` → silent (Notification Center only); `DOWNLOAD_DONE` → banner + sound.

---

## Data consistency
- **No duplicate transfers** — the reconciler never enqueues a page already on disk or already
  in-flight (recovered via `URLSession.getAllTasks`).
- **No corrupt partial files** — the OS only reports a download on success; the transport then moves
  the temp file into place. A page's final `image_<n>.<ext>` is therefore always complete.
- **No truncated CBZ** — `IosCbzWriter` writes `chapter_<id>.cbz.part`, then `atomicMove`s to `.cbz`
  only on success. A kill mid-write leaves a `.part` (overwritten next attempt), never a
  valid-looking-but-corrupt `.cbz`.
- **No lost progress** — write-ahead across three durable stores: Room (queue state + percent), the
  on-disk `manifest.json` (page list + attempt counts), and the URLSession's own task list. Reconcile
  from all three on launch.
- **Bounded retry/backoff** — a failed page is retried up to 3 times with exponential backoff
  (2s/4s, cap 30s); exhaustion fails the chapter with the offending page index.

---

## Manual iOS test plan (REAL DEVICE — required)
The iOS Simulator does **not** faithfully exercise true suspension, `BGTaskScheduler`, or background-
`URLSession` relaunch. Run these on a physical device after setting
`IOS_BACKGROUND_ENGINE_ENABLED = true` and rebuilding (`( cd iosApp && xcodegen generate )`, then run
the `iosApp` scheme). Grant the notification permission (onboarding) first.

1. **Foreground happy path** — queue 1 chapter, stay in the app. Expect: silent "X/Y pages" progress
   in Notification Center; a banner+sound on completion; the chapter appears under Completed and opens
   in the reader. No duplicate pages; a single `chapter_<id>.cbz`, no `.part` left behind.
2. **Background continuation** — queue 3+ chapters, immediately lock the phone / switch apps; wait a
   few minutes. On 26+ expect a system progress indicator while backgrounded. Reopen: progress should
   have advanced while away; chapters that finished show as completed (or briefly "finishing" then
   complete as the CBZ builds). No duplicates, no corrupt archives.
3. **Suspended completion notification** — queue a chapter, background the app, wait for it to finish
   transferring while backgrounded. Expect the **completion banner+sound to arrive while the app is
   not active**.
4. **Force-quit recovery** — start a multi-page chapter, then swipe-kill the app mid-download.
   Relaunch manually. Expect: the queue resumes, already-downloaded pages are kept (not re-fetched),
   missing pages re-enqueue, and the chapter completes. No duplicates.
5. **Transient network failure / retry** — start a download, then drop the network (airplane mode) for
   a few seconds and restore it. Expect: the affected page retries (bounded backoff) and the chapter
   completes; only after exhausting retries does the chapter show Failed with a clear reason.
6. **Cancel** — cancel a running chapter and a queued chapter. Expect: transfers stop, partial files
   are cleaned for a running cancel, and the row reflects the cancel.
7. **Rollback** — set the flag back to `false`, rebuild, confirm downloads still work via the legacy
   engine exactly as before.

---

## Known limitations / future
- `BGContinuedProcessingTask` is best-effort (see caveats above); not guaranteed or unlimited.
- Pre-26 cannot fully drain a large queue purely in the background (page-list scraping needs
  foreground or an opportunistic `BGProcessingTask`).
- CBZ for a chapter that finishes transferring while suspended builds **in a granted background task
  window** (continued task on 26+, opportunistic processing before) — so it usually completes without
  reopening on 26+. Only when **no** window is granted (or one expires mid-encode) does it build on
  next foreground. Until the CBZ exists the chapter shows the silent "Finalizing chapter…" notification;
  the alerting "Download complete" is posted only once the `.cbz` is built (`finalize.success`).
- Cloudflare 429/403 during a background prep can't show a WebView solver; that source defers to
  foreground.
- A Cloudflare-gated source's page-list scrape still needs the foreground solver before its transfers
  can be enqueued.

## Desktop / Android (unchanged)
- **Desktop:** in-process coroutine engine (`CoroutineDownloadRepositoryImpl`); downloads while open
  or minimized; `DownloadNotifier.NoOp` / `BackgroundScheduler` unused.
- **Android:** WorkManager foreground worker (`DownloadWorkerV2`) — its own rich progress
  notifications; entirely untouched by this system.
