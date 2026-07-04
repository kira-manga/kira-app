# iOS background downloads — real-device test guide

Step-by-step manual tests for the iOS background-download system, with the **exact log lines** to
expect. The feature flag `DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED` is **ON** for this test
build. Architecture/behavior reference: `IOS_BACKGROUND_DOWNLOADS.md`.

> Run on a **real iPhone**. The Simulator does not faithfully exercise suspension, `BGTaskScheduler`,
> or background-`URLSession` relaunch.

---

## 0. Build & install on the iPhone
```bash
# from the repo root
cd iosApp
xcodegen generate            # regenerates iosApp.xcodeproj (gitignored) incl. the new Info.plist keys
open iosApp.xcodeproj
```
In Xcode: pick the **iosApp** scheme + your connected iPhone, set your signing team if prompted, then
**Run** (⌘R). On first launch, **grant the notification permission** (onboarding / Theme screen).

Confirm the engine is active — at first download you should see:
```
KiraBgDownload | engine.selected | engine=BackgroundUrlSession flag=true
KiraBgDownload | engine.init | engine=BackgroundUrlSession
```
If you instead see `engine=CoroutineLegacy`, the flag is off — stop and tell me.

## How to collect logs
- **Xcode Console** (simplest, app run from Xcode): the bottom pane streams logs live. Type
  `KiraBgDownload` in the filter box. Both Kotlin (Kermit tag `KiraBgDownload:`) and Swift (NSLog
  `KiraBgDownload |`) lines appear.
- **Console.app** (device logs, incl. while detached / after relaunch): open **Console.app** on the
  Mac → select your iPhone in the sidebar → **Start streaming** → filter by `KiraBgDownload`. This is
  the one to use for background/relaunch tests (Xcode may detach when the app suspends).
- Every line is prefixed `KiraBgDownload` and carries identifiers: `chapterId`, `mangaId`,
  `pageIndex`, `attempt`, `taskId`, `path`, `current/total`, etc.

## If a test fails, send me:
1. The **full `KiraBgDownload` log** for the run (copy from Console.app / Xcode), ideally from app
   launch through the failure.
2. **Which test # + step** failed and **what you saw** vs. what's expected below.
3. **iOS version** of the device + whether it was **foreground / backgrounded / force-quit** at the
   moment.
4. The **chapter(s)** involved (so I can match `chapterId` in the logs).
5. If a notification was wrong/missing: what appeared (banner? silent? nothing?) and when.

---

## Test 1 — Foreground download
**Do:** Open a manga → tap **Download** on **1 chapter** → stay in the app and watch it.

**Expect (logs):**
```
KiraBgDownload | enqueue.request | chapterId=… mangaId=… existingState=null
KiraBgDownload | enqueue.inserted | chapterId=… state=QUEUED
KiraBgDownload | state.transition | chapterId=… from=QUEUED to=RUNNING
KiraBgDownload | manifest.created | chapterId=… pages=N
KiraBgDownload | manifest.store.write | chapterId=… pages=N path=…/chapter_…/manifest.json
KiraBgDownload | reconcile.plan | … manifestPages=N onDisk=0 inFlight=0 toEnqueue=N complete=false
KiraBgDownload | reconcile.enqueue | chapterId=… pages=[0, 1, … ]
KiraBgDownload | task.enqueued | chapterId=… pageIndex=0 taskId=… host=… headerNames=…   (×N)
KiraBgDownload | task.didWriteData.started | chapterId=… pageIndex=… bytesExpected=…
KiraBgDownload | task.didFinishDownloading | chapterId=… pageIndex=0 httpStatus=200 tempPath=…
KiraBgDownload | file.move.success | chapterId=… pageIndex=0 finalPath=…/image_0.jpg
KiraBgDownload | notif.progress.posted | chapterId=… current=1 total=N percent=…           (rising)
… (all pages) …
KiraBgDownload | state.transition | chapterId=… from=RUNNING to=DOWNLOADED
KiraBgDownload | notif.finalizing.posted | chapterId=…                       (silent "Finalizing chapter…")
KiraBgDownload | finalize.start | chapterId=… pages=N
KiraBgDownload | cbz.partWrite.start | chapterId=… tempPath=…/chapter_….cbz.part
KiraBgDownload | cbz.loosePagesDeleted | chapterId=… count=N
KiraBgDownload | cbz.atomicRename.success | chapterId=… finalPath=…/chapter_….cbz
KiraBgDownload | finalize.success | chapterId=… transition=->SUCCESS
KiraBgDownload | notif.complete.posted | chapterId=…                          (alerting — AFTER the CBZ)
KiraBgDownload | manifest.store.delete | chapterId=… path=…
```
**Note the order:** `notif.complete.posted` now fires **after** `finalize.success` (the CBZ is built),
never at `RUNNING→DOWNLOADED`. Between them you get the **silent** `notif.finalizing.posted`.
**Expect (UI):** progress on the chapter, then it moves to the **Completed** tab; reader opens it.
**Expect (notification):** silent “X/Y pages” updating in Notification Center → silent “Finalizing
chapter…” while the CBZ builds → a **banner + sound** “Download complete” **only after** the `.cbz` is
ready. You must NOT get the banner+sound while it still says compressing/finalizing.
**Expect (files):** one `chapter_<id>.cbz`, **no** `.cbz.part`, **no** `manifest.json` left, no
loose `image_*` files (CBZ on; if your CBZ pref is off, expect loose `image_*` kept and no `.cbz`).

## Test 2 — Backgrounded, not force-quit
**Do:** Queue **3+ chapters** → immediately **lock the phone / switch to another app**. Wait **3–5
minutes**. Then reopen Kira. (Use **Console.app** to watch while away.)

**Expect (logs):** on iOS 26+ the continued task is submitted **while still foreground**, the instant
download work becomes pending (`reason=workPending`) — that is what makes it reliably **start** and
then continue across backgrounding (the system Live Activity / Dynamic Island appears now, even before
you leave). It is NOT submitted only at `didEnterBackground` (that races with suspension and may never
start — the earlier symptom of "first time out, no task in Dynamic Island").
```
KiraBgDownload | scheduler.requestProcessing | reason=workBecamePending     (foreground, at download start)
KiraBgDownload | bgtask.continued.submitted reason=workPending              (iOS 26+, while foreground)
KiraBgDownload | bgtask.continued.started                                   (system starts it promptly)
KiraBgDownload | lifecycle.didEnterBackground hasPendingWork=true           (you leave the app)
KiraBgDownload | bgtask.continued.skip reason=didEnterBackground alreadyRunning   (guard: already running)
KiraBgDownload | task.didFinishDownloading | …                             (transfers keep completing while away)
KiraBgDownload | file.move.success | …
```
On pre-26 you instead get `bgtask.processing.submitted reason=workPending` (opportunistic — may run
much later). On iOS 26+, also expect periodic `bgtask.continued.progress pct=…`, and —
new — chapters now **finalize in the background window** (no reopen needed):
```
KiraBgDownload | finalize.start | chapterId=… pages=N           (while still backgrounded)
KiraBgDownload | cbz.atomicRename.success | chapterId=…
KiraBgDownload | finalize.success | chapterId=… transition=->SUCCESS
```
On reopen, any chapters that could NOT finalize in a window (none granted, or it expired mid-encode)
finish now:
```
KiraBgDownload | lifecycle.didBecomeActive
KiraBgDownload | pump.start | reason=didBecomeActive …
KiraBgDownload | finalize.start … / finalize.success …      (only for chapters not finalized while away)
```
**Expect (UI/notification):** progress advanced while away; on iOS 26+ finished chapters reach
**complete on their own** (CBZ built in the background window); on pre-26 / if no window was granted,
they show complete-but-finishing and the `.cbz` builds at the next window or on reopen. On iOS 26+ a
**system progress indicator** (Dynamic Island / banner) appears while backgrounded.
**Expect:** no duplicate pages, no corrupt `.cbz`.

## Test 3 — "Complete" means READY (notification semantics)
**Do:** Start **1 chapter** → background the app → wait until it should finish transferring.

The rule: **the alerting "Download complete" (banner + sound) fires only when the chapter is actually
readable — i.e. after `finalize.success` (the `.cbz` exists)**, never at mere transfer completion.

**Expect (logs), in this order:**
```
KiraBgDownload | state.transition | chapterId=… from=RUNNING to=DOWNLOADED
KiraBgDownload | notif.finalizing.posted | chapterId=…       ← SILENT "Finalizing chapter…" (no sound)
KiraBgDownload | finalize.start | chapterId=… pages=N
KiraBgDownload | cbz.atomicRename.success | chapterId=… finalPath=…/chapter_….cbz
KiraBgDownload | finalize.success | chapterId=… transition=->SUCCESS
KiraBgDownload | notif.complete.posted | chapterId=…         ← ALERTING "Download complete" (banner+sound)
```
**Expect (notification):** at the `RUNNING→DOWNLOADED` moment the entry quietly changes to
**“Finalizing chapter…”** (no banner, no sound) — this is the visible "downloaded, being processed,
not stuck" state. The **banner + sound** arrives **only after** `finalize.success`. You must NOT get a
"complete" alert while it is still finalizing.

**If no CPU window is granted** to finalize (app plain-suspended, none of the BG-task paths ran), you
instead see `finalize.deferred | chapterId=… reason=noCpuWindow` and the notification **stays** on the
silent “Finalizing chapter…”. **No "complete" is posted** until a later window/foreground reaches
`finalize.success`. If the `.cbz` encode is interrupted (window expires mid-encode), the atomic
`.cbz.part`→`.cbz` means it simply re-runs next time — and still, "complete" only fires on the eventual
`finalize.success`. Confirm you never see a "complete" alert for a chapter whose `.cbz` isn't on disk.

## Test 4 — Force-quit recovery
**Do:** Start a multi-page chapter → **swipe-kill** the app (app switcher → swipe up) mid-download →
wait ~30s → **relaunch manually**.

**Expect (logs on relaunch):**
```
KiraBgDownload | lifecycle.launch …
KiraBgDownload | reconcile.requested        (or pump.start reason=startup)
KiraBgDownload | manifest.store.read.hit | chapterId=… pages=N path=…
KiraBgDownload | reconcile.plan | … onDisk=K inFlight=0 toEnqueue=(N-K) complete=false
KiraBgDownload | reconcile.enqueue | chapterId=… pages=[…only the missing indices…]
```
**Expect:** already-downloaded pages **kept** (the `onDisk=K` count > 0, and `toEnqueue` lists only
the missing indices — **not** 0..N-1 again); the chapter then completes. **No duplicate files.**
(Note: force-quit cancels in-flight transfers and iOS won’t relaunch for them — recovery happens on
this manual relaunch.)

## Test 5 — Network failure / retry
**Do:** Start a download → toggle **Airplane Mode ON for ~5–10s**, then **OFF**.

**Expect (logs):**
```
KiraBgDownload | task.didCompleteWithError | chapterId=… pageIndex=… errorCode=-1005 msg=…
KiraBgDownload | retry.attemptIncremented | chapterId=… pageIndex=… attempt=1 max=3
KiraBgDownload | retry.scheduled | chapterId=… pageIndex=… attempt=1 delayMs=2000
KiraBgDownload | retry.enqueue | chapterId=… pageIndex=…
KiraBgDownload | file.move.success | …                       (after network returns)
```
**Expect:** the page retries (2s, then 4s backoff) and the chapter **completes**. Only if a page fails
**3×** do you see `retry.exhausted` then `state.transition … to=FAILED` + `notif.failed.posted`, and
the chapter shows **Failed** with a clear reason.

## Test 6 — Cancel
**Do:** Start a chapter, then **cancel** it (running). Also try cancelling a still-**queued** chapter.

**Expect (logs):**
```
KiraBgDownload | cancel.running | chapterId=… mangaId=… transition=->FAILED(cancelled)
KiraBgDownload | task.cancelChapter | chapterId=… cancelled=K
```
**Expect:** transfers stop; for a running cancel the chapter’s partial files + manifest are removed;
the row reflects the cancel (it’s mapped to the localized “cancelled by user”, not an error). The next
queued chapter (if any) begins (`window.fill` / `prepare`).

## Test 7 — Rollback (prove the flag works)
**Do:** Set `IOS_BACKGROUND_ENGINE_ENABLED = false` in
`shared/src/commonMain/.../presentation/features/download/DownloadEngineFlags.kt`, rebuild, run.

**Expect (logs):**
```
KiraBgDownload | engine.selected | engine=CoroutineLegacy flag=false
KiraBgDownload | bridge.ensureReady.skipped | reason=flagOff
``` س
**Expect:** downloads work exactly as before (the legacy in-process engine); **no** background URLSession
or BG-task activity. Set the flag back to `true` to resume testing the new system.

---

## Known expected iOS behavior differences (not bugs)
- **Backgrounded vs force-quit:** backgrounded (not killed) → transfers continue suspended + resume;
  force-quit → iOS cancels the session’s in-flight transfers and won’t relaunch for them; recovery is
  on next manual launch from the manifest.
- **iOS 26+ vs pre-26:** 26+ uses `BGContinuedProcessingTask`, submitted **while foreground** the
  instant downloads start (so it starts promptly + shows the system Live Activity, then continues across
  backgrounding); pre-26 uses `BGProcessingTask` (opportunistic, may run much later). Both rely on the
  durable background `URLSession` for the actual transfers.
- **The Live Activity / Dynamic Island appears as soon as you start downloading** (foreground), not only
  after you leave the app — that early submit is deliberate; it is what makes the task reliably run when
  you do leave.
- **`BGContinuedProcessingTask` is best-effort:** the OS may grant less time than you expect (battery,
  thermal, usage, hardware). It accelerates background progress; it is not a guarantee.
- **`BGProcessingTask` is opportunistic:** iOS decides if/when to run it — often only while
  charging/idle, possibly much later, possibly not until next launch.
- **CBZ finalizes in a background window when one is granted, else on foreground:** a chapter that
  finishes transferring while suspended is reported **complete** (notification) immediately. Its `.cbz`
  is then packaged **in the background** during the next granted CPU window — a `BGContinuedProcessingTask`
  on iOS 26+, or an opportunistic `BGProcessingTask` before 26 — so on 26+ it usually reaches *complete*
  without reopening. CBZ/Skia still can’t run while plain-**suspended** (no window), so if none is
  granted it packages on next open. The `.cbz.part`→`.cbz` rename is atomic, so a window that expires
  mid-encode never corrupts the file — it just re-runs next window/foreground.
- **Many small files:** background sessions favor fewer/larger transfers, so a very large queue may be
  paced by iOS.
