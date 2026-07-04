# Local Manga Sharing — Implementation Plan (P2pKit evaluation + design)

Date: 2026-07-03 · Status: **PLAN ONLY — no feature code written**
Inputs: full read of `/Users/abdelrahman/Projects/P2pKit` (v0.6-dev, README/spec/audit docs/API source) + a line-anchored fact map of Kira's storage/import machinery (§6 below cites the exact files).

---

## 1. Verdict: is P2pKit suitable?

**Yes — it is the right foundation, with five qualifications that shape this design** (details §12):

1. **Target match is exact.** P2pKit ships `p2p-core` + `p2p-transport-lan` for Android (minSdk 24 ≤ Kira's 26), JVM desktop, and iOS (`iosArm64`/`iosSimulatorArm64` + `iosX64` extra) — precisely Kira's three targets. Cross-platform LAN is wire-identical (one Bonjour service type `_p2pkit._tcp`, same TXT keys); Android↔iOS was validated on real devices during P2pKit's v0.5 reconnect work.
2. **The file-transfer core is exactly the needed primitive.** Common-code `P2pSession.sendFile(name, sizeBytes, mimeType, source: RawSource)` and `incomingFiles: Flow<P2pFileOffer>` with `accept(sink: RawSink)`/`reject()`; streaming 64 KiB chunks (never whole-file in memory), per-transfer `state: StateFlow<FileTransferState>` + `bytesTransferred`, cancel from both sides, offer timeout, 2 GiB cap — all configurable. P2pKit's own loopback suites verify a 5 MiB file SHA-256-intact on JVM **and** on the iOS simulator.
3. **Peer isolation and identity are handled.** Discovery filters by `appId` in the SDK (verified in `IosLanDiscoveryTransport.emitPeer`: TXT `app` mismatch → peer dropped), so only Kira instances see each other; the outgoing handshake verifies the remote HELLO `peerId` matches the dialed peer (post-audit fix).
4. **Maturity is real but pre-1.0.** Two exhaustive self-audits (7-critical and 5-critical waves) with all criticals fixed and verified (frame-payload DoS caps, reassembler caps, lifecycle contract, hotspot release, identity check); 134 core tests + 17 JVM-LAN + 20 iOS-sim-LAN; v0.4–v0.6 shipped real-device reconnect/Wi-Fi-flap hardening. It is still `v0.6-dev`/internal tags — Kira must pin an exact version and own an integration smoke suite.
5. **What P2pKit does NOT give us** — and this plan supplies: transfer **metadata** (offers carry only id/name/size/mime → Kira needs its own control protocol), **resume** of an interrupted file (state machine has no Resume → we get recovery at chapter granularity instead), **encryption** (`SecurityMode.NoneForMvp` → consent-gated design + roadmap note), and **path-safe filenames** (offer `name` is untrusted upstream; partial sanitization only — we never derive paths from it).

---

## 2. What P2pKit provides today (verified)

| Concern | Facts (from source, v0.6-dev) |
|---|---|
| Modules Kira needs | `dev.p2pkit:p2p-core`, `dev.p2pkit:p2p-transport-lan` (0.6.0). Provisioning sidecars are optional/Android+JVM-only; NOT needed for v1. |
| API style | Coroutines/`Flow`/`StateFlow` throughout; typed `P2pError`s; no callbacks. Matches Kira's MVI stack. |
| Discovery | mDNS/Bonjour `_p2pkit._tcp` — JmDNS in-process on Android (SDK owns the cache since v0.5; MulticastLock acquired/released by the SDK) and JVM; `NWBrowser`/`NWListener` on iOS (cellular interface prohibited since v0.6). TXT: peerId, appId, deviceName, platform, capabilities, protocolVersion. |
| Sessions | `connect(peer)` / `incomingSessions`; keep-alive; `ReconnectPolicy` (outgoing only); simultaneous-open arbitration (never two sessions per peer); `Peer(id, name, platform, supportedTransports)`. |
| Messages | `P2pMessage.Text` / binary ≤ 4 MiB per send (8 MiB frame cap post-audit) — right size for control JSON. |
| Files | §1.2 above. `FileTransferState`: Offered→Accepted→Sending(progress)→Completed / Rejected / Cancelled / Failed. |
| Versions | Kotlin **2.3.21**, AGP 9.1.1, coroutines 1.11.0, kotlinx-serialization 1.10.0, **kotlinx-io 0.9.0** (the `RawSource`/`RawSink` seam), JmDNS 3.6.3 (Android/JVM). `maven-publish` is wired on all four library modules (`GROUP=dev.p2pkit`, `VERSION_NAME=0.6.0`). |

## 3. Compatibility with Kira's toolchain + how to consume it

- **Kotlin 2.3.21 (P2pKit) vs 2.4.0 (Kira)**: fine — a newer compiler consumes older klibs. Optionally bump P2pKit to 2.4.0 (owner controls both repos) for zero skew.
- **AGP 9.1.1 vs 9.2.1**: this is why **`includeBuild` (composite) is NOT recommended** — mixed AGP versions in one composite invocation is a known conflict class. **Recommended consumption: publish P2pKit to a Maven repo** — `./gradlew publishToMavenLocal` for development (add `mavenLocal()` in Kira's `settings.gradle.kts` `dependencyResolutionManagement` — module-level repos stay forbidden by `FAIL_ON_PROJECT_REPOS`), later a private/GitHub-Packages repo for CI. Pin `dev.p2pkit:*:0.6.0` exactly.
- **kotlinx-io enters Kira's graph** (transitively). Kira's file layer is okio (`AppFileSystem`); the share module needs a ~40-line adapter pair (okio `Sink`→kotlinx-io `RawSink` for receiving, okio `Source`→`RawSource` for sending). No other collision: coroutines/serialization minor-version deltas are transitive-safe.
- **P2pKit dependency stays confined to one Kira module** (`:data:share`, §4) — nothing in `:domain`/`:presentation`/`:ui` ever sees a P2pKit type (DIP, same posture as Ktor in `:data:remote`).
- **CI note**: Kira CI resolves online; the pinned P2pKit artifacts must be resolvable there (private repo credentials = a later owner step; until then the share module builds only where the artifacts exist — or vendor the two klib-producing modules if that blocks).

## 4. Where the feature lives in Kira's module graph

New leaf module + one slice per layer, following the `:data:download` precedent exactly:

| Module | New contents |
|---|---|
| `:domain` | Models: `SharePeer`, `ShareOfferSummary`, `ShareTransferProgress`, `ShareError` reasons folded into `AppError.Share` (or a dedicated sealed class carried in state). Ports (ISP, one verb each): `ShareDiscoveryRepository` (advertise/discover/peers), `ShareSendRepository` (offer + send to a peer), `ShareReceiveRepository` (incoming offers, accept/decline, progress), `ShareImportRepository` (staged-bundle → library import). Use cases: `ObserveNearbyPeersUseCase`, `SendMangaToPeerUseCase`, `ObserveIncomingShareUseCase`, `RespondToShareOfferUseCase`, `ObserveShareProgressUseCase`, `CancelShareUseCase`. |
| **`:data:share` (new)** | The ONLY module depending on `dev.p2pkit:*`. Contains: `KiraShareProtocol` (control-message codec, §5), `ShareSessionManager` (owns the `P2pKit` instance lifecycle — created lazily when the share screen opens, `stop()` on leave), sender/receiver engines, staging store, hash verification, import pipeline (§6), okio↔kotlinx-io adapters, `shareModule()` Koin module. Depends on `:core`, `:domain`, `:data:local`, `:platform`. |
| `:presentation` | `share/` MVI quartet: `ShareViewModel` (send tab + receive tab state: peer list, selection, offer dialog, per-chapter progress, terminal summary), plus `ShareState/Intent/Effect`. Depends only on use cases. |
| `:ui` | `share/ShareScreen.kt` (stateless `ShareScreenContent` per house pattern) + strings in a new `strings_pfix_share.xml` ×11 locale files (parity gate). |
| `:composeApp` | `Screen.Share` route + `ShareScreenRoute` adapter; append `shareModule()` in `allReworkModules()` (documented append-only slot, `ReworkModules.kt:20-60`); entry points wired from Settings and Library selection mode. |
| Hosts | `:app` manifest + `iosApp` Info.plist additions (§7). No Swift code needed — P2pKit's iOS transport is pure Kotlin/Native inside the framework. |

Rules honored: repo interfaces in `:domain`, impls in the data leaf; VMs see use cases only; `:sources:*` untouched; no new cross-module export from the iOS framework (Swift never calls share APIs directly).

## 5. The share protocol (Kira layer on top of P2pKit)

P2pKit gives sessions + raw file transfers; manga semantics ride a small versioned JSON protocol over `P2pMessage.Text` (kotlinx-serialization, `ignoreUnknownKeys=true` for forward compat). Every envelope: `{ "v": 1, "type": "...", "shareId": "<uuid>", "payload": {...} }`.

Message flow (one manga per share session; N chapters):

```
SENDER                                            RECEIVER
pick manga+chapters → connect(peer)
  ── share.offer ────────────────────────────────▶  show consent dialog
     manga: {api, language, url, title, imageUrl,   (title, N chapters, total
       description, status, genres, rating}          bytes, sender name/platform,
     chapters: [{key, url, name, number, date,       free-space check)
       kind: CBZ|PAGES, pageCount, bytes, sha256}]
     cover: {bytes, sha256}? (optional, ≤1 MiB)
  ◀─ share.offer.response ───────────────────────
     accepted: [chapterKey...]   // dedupe + resume: receiver excludes chapters it
                                 // already has (library match) or already staged+verified
  ── file transfers (P2pKit sendFile), one per accepted chapter ──▶
     offer.name = "<shareId>/<chapterKey>"       receiver accepts into staging sink
     (correlation ONLY — never used as a path)   (hashing while streaming)
  ◀─ share.chapter.result {key, ok|failReason} ──   after per-file sha256 verify
  ── share.complete {sentKeys} ──────────────────▶  import verified bundle → library
  ◀─ share.imported {importedKeys} ──────────────   both sides show summary
(either side, any time) share.cancel {reason} + P2pFileTransfer.cancel()
```

Design points:
- **`chapterKey` = stable digest of `(manga.url, chapter.url)`** — never an array index, so retries/reordering are unambiguous.
- **Only downloaded chapters are sendable in v1.** A chapter whose local artifact is a CBZ sends the `.cbz` verbatim (`kind=CBZ`); a loose-pages chapter (CBZ pref off) is sent as a one-off CBZ built into the sender's cache dir via the existing `CbzWriter` (`kind=CBZ` too) — one file per chapter keeps the protocol and resume simple. (`kind=PAGES` is reserved, not used in v1.)
- **Version negotiation**: `share.offer` is only sent after a `share.hello` exchange carrying `protocolVersion` + app version; unknown major → typed "update the other device" error. P2pKit already TXT-advertises its own transport `protocolVersion` underneath.
- **The cover** rides as an optional small inline payload (base64 in the offer if ≤ 256 KiB, else its own small file transfer). On import the receiver pre-seeds Coil's disk cache under the manga's `imageUrl` key (Coil 3 `diskCache.openEditor(url)`) — covers then render fully offline with **zero schema change**. If pre-seeding fails, degrade silently (cover loads when online, exactly today's behavior).

## 6. Receiving, verifying, importing (grounded in the storage fact map)

Kira facts this design is built on (verified 2026-07-03):
- Layout: `filesDir/manga/<mangaId>/chapter_<chapterId>/` where **both ids are receiver-local Room autoincrement ids** (`AppFileSystem.kt:97-101`) — sender ids are meaningless on the receiver, so **no received path is ever transplanted**; the import derives fresh paths from the receiver's own ids (the codebase already treats stored absolute paths as advisory and re-derives — `ChapterPagesRepositoryImpl.kt:181-247`, `PagePathRederivation.kt`).
- CBZ = image-only zip `chapter_<chapterId>.cbz` (`page_NNNN.webp` entries; no ComicInfo.xml) — all metadata must come from the protocol manifest (§5), which is why the offer carries the full 14-column `saved_manga` shape + per-chapter fields matching `SavedChapterEntity`.
- Library insert chain to REUSE, not duplicate: `LibraryRepositoryImpl.addToLibrary` → `LibraryDeo.saveMangaWithChapters` (`@Transaction`; manga IGNORE-insert deduped by unique `url` index, id re-resolved via `getMangaIdByUrl`; chapters deduped by `(mangaId, url)` unique index).
- Downloaded-marking chain to REUSE (same 4 writes every engine does): `ChapterDao.updateChapterLocalPaths` + `ChapterDao.markChapterDownloaded` + `NotificationDao.addLocalImagePathByChapterId` + a `chapter_downloads` row at `SUCCESS/100` — with `ChapterFinalizer.adoptExistingArchive` (`ChapterFinalizer.kt:141-152`) as the exact "adopt an existing CBZ" precedent.
- There is **no existing import/export/backup feature** to collide with; Room stays at **v11 — no schema change anywhere in this plan**.

Pipeline (per accepted share):
1. **Stage**: each accepted chapter streams into `filesDir/share_staging/<shareId>/<chapterKey>.cbz.part` through an okio `HashingSink(SHA-256)` adapter → on `Completed`, compare digest + byte count against the manifest → atomic rename to `.cbz` (staged-verified). Mismatch → delete `.part`, report `share.chapter.result{ok=false}`, sender may re-offer.
2. **Import** (only verified files; runs on `dispatchers.io`):
   a. `addToLibrary(manga, chapters)` with the manifest metadata → dedupe/id-resolution free thanks to the existing unique indexes; re-query receiver-local `mangaId` + per-chapter ids.
   b. Per chapter: `mkdirs(chapterDir(mangaId, chapterId))` → move staged CBZ to `chapter_<chapterId>.cbz` (rename within `filesDir` = atomic) → run the 4-write downloaded-marking chain with the receiver-derived path.
   c. Delete `share_staging/<shareId>/` remnants; emit `share.imported`.
3. **Crash/interruption recovery**: staging survives process death. On next share-screen open (or a cheap app-start sweep), `share_staging` entries older than N days are deleted; a re-offer of the same manga finds staged-verified chapters and skips their transfer (**chapter-level resume** — this is the mitigation for P2pKit's lack of byte-level resume). A crash between 2a and 2b is safe: import is idempotent (IGNORE inserts + unique indexes + re-derived paths), so the sweep can re-attempt import of verified staged files before deleting.
4. **Already-in-library behavior**: manga exists → chapters top-up (existing `(mangaId,url)` dedupe); chapter exists but not downloaded → import marks it downloaded; chapter already downloaded → excluded in `share.offer.response` (never transferred). Reading progress/bookmarks are **not** shared in v1 (receiver-owned state stays untouched).

## 7. Permissions & platform config (all new — the app has zero local-network surface today)

| Platform | Addition | Prompt behavior |
|---|---|---|
| Android (`app/src/main/AndroidManifest.xml`) | `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (new; `INTERNET`/`ACCESS_NETWORK_STATE` already present) | Install-time only — **no runtime prompt** on API 24–36 for the JmDNS path. (`NEARBY_WIFI_DEVICES`/location would only matter for the OPTIONAL hotspot-provisioning sidecar — out of v1.) |
| iOS (`iosApp/iosApp/Info.plist`) | `NSLocalNetworkUsageDescription` (user-facing rationale string, localized) + `NSBonjourServices = ["_p2pkit._tcp"]` | iOS shows the one-time **Local Network** permission prompt on first advertise/browse. Works under the Personal-Team Debug signing (no entitlement needed for Bonjour with declared service types; P2pKit's own sample ships exactly these two keys). Denied → P2pKit surfaces errors → typed "enable Local Network in Settings" UI state. |
| Desktop | none | First-run OS firewall prompts (Windows Defender / macOS firewall) — handled as a help-text note on the share screen. |

Both peers must be on the same LAN (or one device's OS-level hotspot). v1 ships a "no peers found" troubleshooting sheet (same Wi-Fi? guest/hotel network blocking mDNS?). Android `LocalOnlyHotspot` provisioning via P2pKit's sidecar is a deliberate **later phase**; iOS provisioning is impossible platform-wide (Apple policy), so it is never planned.

## 8. Foreground/background model

**v1: transfers are foreground affairs on all platforms, by design.**
- The `P2pKit` instance exists only while the share UI is open (`stop()` on screen exit → SDK releases sockets, JmDNS, MulticastLock). No always-on advertising: privacy + battery.
- iOS suspends sockets in background; P2pKit's `BackgroundPolicy.CloseActiveSessions` matches. The share screen holds a keep-screen-awake hint while a transfer is active (`UIApplication.idleTimerDisabled` / `FLAG_KEEP_SCREEN_ON` / no-op desktop — one 3-actual `:platform` facade, same pattern as the existing facades).
- Interruption (backgrounded mid-transfer, Wi-Fi drop) → transfer `Failed` → staged-verified chapters keep their progress; the user re-runs the share and only missing chapters go over (§6.3).
- **Later phase (Android only)**: move an active receive/send into the existing `dataSync` foreground-service machinery for screen-off transfers. Not v1 — it multiplies the QA matrix.

## 9. UI/UX flow

Entry points: **Settings → "Share nearby"** row, and **Library selection mode → "Send" action** (preselects the chosen manga).

One `ShareScreen`, two tabs:
- **Send**: manga picker (library, downloaded-filter) → chapter multi-select (downloaded chapters; shows per-chapter size from `chapter_downloads.sizeBytes`) → nearby-peers list (device name + platform icon, live via `p2p.peers` StateFlow) → tap peer → "waiting for <name> to accept" → per-chapter progress rows (reusing the Updates/Downloads row patterns + `formatByteSize`) → summary (sent / skipped-already-had / failed).
- **Receive**: big "visible as <device name>" state + spinner while advertising; incoming offer → **consent dialog**: sender name/platform, manga title+cover, chapter count, total size, free-space line; Accept/Decline → progress → "Imported N chapters" + the Library screen updates by itself (Room flow re-emission — no manual refresh needed).
- Errors are typed → localized at the call site per the MVI contract (peer lost, verification failed, storage full, permission denied, version mismatch). Cancel buttons on both sides at every stage.
- All strings in `strings_pfix_share.xml` across the 11 locale folders (`:ui:checkLocaleKeyParity` enforces).

## 10. Safety checks (explicit requirements list)

- **File validation**: byte-count + SHA-256 per chapter file (manifest-declared, verified while streaming); received archive additionally opened once with the existing `CbzReader` entry-listing before import (structural sanity: is a readable zip, ≥1 page entry, entries match `page_*` shape); reject otherwise.
- **Path safety**: transfer names/`chapterKey`s are correlation tokens only; every write path is derived receiver-side from `AppFileSystem` + fresh Room ids. Offer `name` is never touched as a path (also covers P2pKit's known partial filename sanitization).
- **Duplicate detection**: pre-transfer, receiver-side, by the existing unique keys (`saved_manga.url`, `(mangaId, saved_chapters.url)`), answered in `share.offer.response` — duplicates cost zero bytes.
- **Partial-transfer recovery**: chapter-level staging + verify + idempotent import (§6.3).
- **Cancellation**: `share.cancel` control message + `P2pFileTransfer.cancel()` both directions; staging `.part` cleanup on cancel; `kit.stop()` on screen exit closes everything (P2pKit `closeAll`s in-flight transfers on session close).
- **Storage limits**: free-space check before accept — new `AppFileSystem.availableBytes()` (Android `File.getUsableSpace`, iOS `NSFileSystemFreeSize`, Desktop `getUsableSpace`); require `totalBytes × 1.2 + 100 MB` headroom; refuse with a typed error otherwise. P2pKit's 2 GiB per-file cap stays (one chapter never approaches it); a per-share soft cap (e.g. warn > 4 GB total) in the consent dialog.
- **Consent + trust**: nothing is ever received without the explicit accept dialog; advertising only while the screen is open; SDK-level appId filtering + peerId handshake verification. **Plaintext caveat** (P2pKit has no encryption yet): acceptable for the v1 threat model (user-initiated, same-LAN, consent-gated, public-content manga); revisit when P2pKit ships `SecurityMode.PairingCode` (roadmapped) — the plan's protocol needs no change for it.
- **Error handling**: every failure maps to a typed error → localized UI text; `CancellationException` rethrown everywhere per house rules; `share.chapter.result` makes failures per-chapter, never all-or-nothing.

## 11. Data-model changes

**None to Room (stays v11).** New non-schema state only: the staging directory convention, one DataStore key (`share.device_name`, default = platform device model), and the in-code protocol/domain models. This is the strongest simplification in the plan and follows directly from reusing the existing import chains.

## 12. P2pKit risks & gaps (and what each costs us)

| # | Gap/risk (verified) | Impact on Kira | Mitigation in this plan |
|---|---|---|---|
| 1 | No encryption (`SecurityMode.NoneForMvp`) | LAN sniffing could read transferred chapters/metadata | Consent-gated, user-initiated, same-LAN; revisit on P2pKit v0.4+ security milestone (§10) |
| 2 | No byte-level resume; `FileTransferState` has no Resume | Mid-transfer drop restarts that chapter | Chapter-granular staging+resume (§6.3); chapters are tens-of-MB, acceptable |
| 3 | Offers carry no metadata slot | Can't describe manga in the offer | Kira control protocol over `P2pMessage.Text` (§5) |
| 4 | Offer `name` untrusted; upstream sanitization explicitly partial (`../../etc/x` passes decode) | Path traversal if a receiver used names as paths | We never do — receiver-derived paths only (§10) |
| 5 | `blocking-sink-write-on-route-loop` open upstream: slow receive sinks stall the session's message routing | Slow disk could stall control messages mid-transfer | Stage to a plain local file sink (fast); do hashing inline (cheap) and ALL zip/import work after `Completed` |
| 6 | Kotlin 2.3.21 / AGP 9.1.1 vs Kira 2.4.0 / 9.2.1 | Composite-build conflicts | Consume published artifacts (mavenLocal → private repo), never `includeBuild` (§3); optionally align P2pKit versions upstream |
| 7 | v0.6-dev, internal tags, no published binaries yet | Supply-chain/reproducibility | Owner publishes + pins 0.6.0; Kira adds its own desktopTest loopback smoke (§13) |
| 8 | iOS background = sockets die | No screen-off transfers on iOS | Foreground-only v1 + keep-awake (§8) |
| 9 | Same-LAN requirement; mDNS blocked on guest/corporate Wi-Fi | "No peers found" support burden | Troubleshooting UX (§7); Android hotspot provisioning = later phase; manual-IP fallback exists in P2pKit if ever needed |
| 10 | No instrumented Android tests upstream (LAN paths hand-tested) | Regressions surface on devices | Kira device QA matrix (§13) + pinning versions |

## 13. Test strategy

- **Pure unit (desktopTest, CI)**: protocol codec round-trips + unknown-field/major-version cases; `chapterKey` stability; offer-response dedupe planner (already-library / already-staged matrices); staging-store state machine (part → verified → imported, crash points between every step); free-space gate; okio↔kotlinx-io adapter conformance (incl. mid-stream failure).
- **Import integration (desktopTest, CI)**: in-memory Room (`MangaDatabase` builder precedent from existing tests) + okio `FakeFileSystem`-backed `AppFileSystem` fake → full staged-bundle import → assert library rows, downloaded marks, derived CBZ path, `chapter_downloads` row, idempotency on double-import.
- **Loopback E2E (desktopTest, CI)**: two real `P2pKit` instances in one JVM (the pattern P2pKit's own `JvmLanLoopbackTest` proves works headless) driving sender-engine → receiver-engine → import: multi-chapter share, hash-mismatch chapter rejected while siblings import, mid-transfer cancel, duplicate re-share transfers zero files. This doubles as Kira's pin-the-SDK smoke suite.
- **VM tests (commonTest)**: `ShareViewModel` in the house style — peer-list projection, consent accept/decline wires, progress projection, typed-error surfacing, in-flight guards.
- **UI (compose, desktopTest)**: consent dialog + progress-row rendering with canned state (infra from backlog T2 already landed).
- **Device QA matrix (manual, checklist doc)**: Android↔Android, Android↔iOS, iOS↔Desktop, Desktop↔Android; first-run iOS Local-Network prompt accept/deny; Wi-Fi drop mid-transfer then re-share resume; storage-full refusal; large share (≥1 GB) timing; guest-network failure messaging; Personal-Team iOS build.
- **iOS sim (CI-able on macOS)**: protocol + staging tests run under `iosSimulatorArm64Test`; the P2pKit iOS loopback pattern can be mirrored later if wanted.

## 14. Phasing (each phase independently green + committable)

- **P0 — enablement (~½ day)**: publish P2pKit 0.6.0 to mavenLocal; Kira settings repo entry; empty `:data:share` module wired into the graph + a trivial loopback smoke test proving the dependency stack (this is the go/no-go gate for #6/#7 in §12).
- **P1 — protocol + engines (core)**: codec, session manager, sender, receiver, staging, verification + the unit/loopback suites. No UI.
- **P2 — import pipeline**: staged-bundle → library import + integration tests + crash-sweep.
- **P3 — feature surface**: domain ports/use cases, `ShareViewModel` + tests, `ShareScreen` + strings ×11, route + DI + entry points.
- **P4 — platform config + QA**: manifest/plist additions, keep-awake facade, free-space facade, device matrix pass, troubleshooting UX polish.
- **Later (explicitly out of v1)**: Android foreground-service transfers, Android hotspot provisioning, encryption adoption, multi-manga batches, reading-progress/bookmark sync, Wi-Fi Direct/Multipeer transports when P2pKit ships them.

## 15. Open decisions for the owner (blocking P0/P3, none blocking the plan)

1. **P2pKit distribution**: mavenLocal-only for now vs standing up a private Maven (GitHub Packages) so CI can build `:data:share`? (Plan assumes mavenLocal now, private repo before merge.)
2. **Align P2pKit to Kotlin 2.4.0/AGP 9.2.1 upstream**, or keep 2.3.21 and rely on klib forward-compat? (Either works; aligning removes a variable.)
3. **Plaintext v1 acceptable?** (§10/§12.1 — recommendation: yes for v1 given the consent gate; encryption when upstream ships it.)
4. **Entry-point set**: Settings row + Library selection action (planned) — also a Details-screen "share this manga" action?
5. **Non-downloaded chapters**: v1 refuses them (only downloaded content is sendable). OK, or should metadata-only sharing (add-to-library without files) be in scope?
