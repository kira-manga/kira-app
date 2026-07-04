# RESOLVED — GAP-CMP-01 (Pinned FAQ complaints) — FALSE POSITIVE / already-done

**Disposition:** GAP-CMP-01's *core* contract — "the user Complaint list ALWAYS prepends 2
localized PINNED FAQ complaints, shown Reply-only with Edit/Delete hidden" — is **already fully
implemented and reachable** in the KMP rework. **No code was changed.** The audit's "KMP (current):
No pinned/FAQ prepend" claim is incorrect.

The only genuinely-open sub-clause folded into CMP-01's acceptance criteria ("each rendered with a
ClosureReasonCard" + "strings match native verbatim Arabic+en") is **not** the pinned-prepend
behavior of CMP-01 — it is tracked separately as **GAP-CMP-02 (P1, ClosureReasonCard)** and the
globally-deferred Phase-10 i18n lift. See "Residual / out of scope" below.

---

## Evidence — the full pipeline is wired

### 1. Data prepend — PRESENT (the part the audit thought was missing)
`data/.../repository/ComplaintListRepositoryImpl.kt:130-133`:
```kotlin
override suspend fun loadUserComplaints(): Result<List<ComplaintSummary>> = runCatching {
    val userId = userIdProvider.getUserId()
    PINNED_COMPLAINTS + legacy(userId).map { it.toSummary() }
}
```
`data/.../repository/PinnedComplaints.kt:92-116` — `internal val PINNED_COMPLAINTS` is a 2-element
`List<ComplaintSummary>`:
- (1) "Content removed - 18+ / Hentai" — `type = CUSTOM`, `status = PINNED`, `userId = "0"`, `id = "admin"`.
- (2) "Pinned: New manga site requirements" (≥200 titles, no bot verification) — `type = CUSTOM`, `status = PINNED`.

These mirror the native `getCustomTopComplaints.kt:41-74` two entries (English-fallback prose;
the legacy used `stringResource` — Phase-10 i18n is deferred globally per the file KDoc).

### 2. Pipeline VM ← use case ← repository — WIRED
- `presentation/.../complaint/ComplaintViewModel.kt:116,254` injects `ObserveUserComplaintsUseCase`
  and calls `observeUserComplaints()` in `loadList()`, populating `state.all`.
- `domain/.../usecase/complaint/ObserveUserComplaintsUseCase.kt:108-113` is a pure pass-through to
  `repository.loadUserComplaints()` — i.e. it DOES route through `ComplaintListRepositoryImpl`,
  so `state.all = PINNED_COMPLAINTS + db…`. The two pinned entries are first in the list.
  (The audit's note "VM populates state.all purely from ObserveUserComplaintsUseCase" is true but
  misread — that very use case is the pinned-prepend path.)

### 3. Render with PINNED chip — PRESENT
`ui/.../complaint/ComplaintScreen.kt:423-495` — `ComplaintRow` renders, for every item in
`state.filtered` (which includes the pinned entries), the subject + `ComplaintStatusChip(status =
complaint.status)` (line 459) + body + type. For pinned entries `status == PINNED`, so the PINNED
status chip is shown. `createdAt == null` for pinned entries correctly omits the timestamp
(ComplaintScreen.kt:486 already documents this).

### 4. Reply-only gating (Edit/Delete hidden) — PRESENT
`ui/.../complaint/ComplaintActionDialog.kt:294-322` — the MENU (`ActionSelectionContent`) always
renders the Reply button; the Edit + Delete buttons are wrapped in
`if (complaint.status != ComplaintStatus.PINNED) { … }`. So a PINNED row opens a Reply-only menu —
exactly the native behavior.

---

## Residual / out of scope (NOT part of CMP-01's pinned-prepend, tracked elsewhere)

- **ClosureReasonCard (GAP-CMP-02, P1):** the pinned cards do not render a closure-reason card,
  because `ComplaintSummary` (domain/.../model/complaint/ComplaintSummary.kt:124-133) has **no
  `metadata`/`reason` field** — the rework deliberately carved metadata down to `appVersion` only.
  Native pinned entries carry a `"reason"` metadata string. Adding the ClosureReasonCard therefore
  requires (a) a `reason` field/carve-out on `ComplaintSummary`, (b) a ported `ClosureReasonType`
  + shared `:ui` `ClosureReasonCard`, (c) populating `reason` on the two `PINNED_COMPLAINTS`
  entries. This is precisely the scope of GAP-CMP-02 and should be done there (CMP-02 is also a
  prerequisite cited by CMP-02's own Notes). It was intentionally NOT bundled into CMP-01.
- **Verbatim Arabic + en strings:** `PINNED_COMPLAINTS` uses inline English literals by design
  (Phase-10 i18n lift deferred for the whole `:ui`/`:data` rework surface; see
  PinnedComplaints.kt KDoc lines 32-37). Not a CMP-01 functional gap.

---

## Conclusion
GAP-CMP-01 (the always-prepend 2 PINNED FAQ entries + Reply-only gating) is **DONE** — implemented
across `:data` (PinnedComplaints + ComplaintListRepositoryImpl), `:domain`
(ObserveUserComplaintsUseCase pass-through), `:presentation` (ComplaintViewModel.loadList), and
`:ui` (ComplaintRow PINNED chip + ComplaintActionDialog Reply-only gate). Landed under Task #269
(Phase 7.x.complaint.pinnedfaq). Mark GAP-CMP-01 RESOLVED (false positive). Route the
ClosureReasonCard + reason-metadata work to **GAP-CMP-02**.

No files were modified for this verification. No forbidden paths (sources_repositry/, the old
native app, or the 3 app/ WIP files) were touched.
