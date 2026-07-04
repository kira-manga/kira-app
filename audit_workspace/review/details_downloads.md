# Phase-3 Review — Details/Reader + Downloads/Sources (parent-verified)

_The delegated reviewer for this pair hit repeated socket errors; parent verified the key gaps
directly by grepping the committed code (anchors below)._

## Details & Reader
| GAP | status | evidence |
|---|---|---|
| LIB-01/02/03 chapter actions (decision B, on Details) | CLOSED | `DetailsIntent.kt` OnToggleChapterRead/OnDownloadChapter/OnCancelChapterDownload; `DetailsScreen.kt` ChapterSelectionBar + selectedChapterUrls (9 hits) |
| GAP-RDR-01 broaden 403 set {403,429,503,520-524} | CLOSED | `ReaderViewModel.kt:629 CHALLENGE_STATUSES`, used at :540 |
| GAP-RDR-02 mode chip dialog (Apply/Revert) | CLOSED | `ReaderScreen.kt:768/797 ReadingModeDialog` |
| GAP-RDR-05 next-chapter overlay | CLOSED | `ReaderScreen.kt:588/1528 NextChapterOverlay` |
| GAP-DET-10/11/13/14 genre expand, expandable desc, localized download-all+dates | CLOSED | committed `6ee1bc3` (P1 wave 2), gated green |
| GAP-DET-02/06/12, RDR-07/09/10 P2 nits | CLOSED | committed `416e59a` (P2 wave) |
| GAP-RDR-16 legacy-args reader title | CLOSED | route adapter chapter.name="" (`6f68d44`) |
| AdMob / telephoto / MStep meme / Firebase analytics | DEFERRED(platform) | documented DEVIATIONs |
**Verdict: PASS** — all functional P0/P1 closed; deferrals are platform-only.

## Downloads & Sources
| GAP | status | evidence |
|---|---|---|
| GAP-SRC-01 char-gate (P0) | CLOSED | `SendComplaintUseCase.MIN_BODY_LENGTH=5` (`9788464`) |
| GAP-DL-01/02 back + row-action icons | CLOSED | `DownloadsScreen.kt` IconButton/Icons.Filled.Cancel (11 hits) |
| GAP-SRC-02/03 localized snackbars + retry | CLOSED | `SourcesScreen.kt` RequestSubmitted/RequestFailed + stringResource |
| GAP-SRC-04 onboarding animated bg + headline | CLOSED | `AnimatedSourcesBackground` present |
| GAP-SRC-08 Checkbox per-source | CLOSED | `SourcesScreen.kt` Checkbox (9-hit grep cohort) |
| GAP-DL-03 card styling, SRC-06/10/11 polish | CLOSED | committed P2 wave |
| run-all/clear-all, source-tabs, Paging3 | DEFERRED | parity-confirmed absent in both apps / platform |
| per-source colored icon | SUB-GAP | needs `Source.icon` field (model+mapper) — logged |
**Verdict: PASS** — all functional P0/P1 closed; one logged sub-gap (source icon).
