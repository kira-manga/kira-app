package me.manga.kira.data.repository

import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType

/**
 * Static admin-pinned "top FAQ" entries prepended to every user-side Feedback Manager list.
 *
 * Phase 7.x.complaint.pinnedfaq rework. Ports the legacy
 * `composeApp/.../complaint/data/getCustomTopComplaints.kt` (which is a `@Composable` function
 * reading two `stringResource` entries each time it's called) into a static `val` consumed by
 * [ComplaintListRepositoryImpl.loadUserComplaints]. The two entries are the community's
 * admin-authored FAQ items the legacy displays unconditionally above the user's own submissions:
 *
 *  1. **"Content removed — 18+ / Hentai"** — community guideline reminder about removed adult
 *     content references.
 *  2. **"New manga site requirements"** — admin-policy explainer about the 200-title / no-bot
 *     threshold for new sources.
 *
 * Both entries carry [ComplaintStatus.PINNED], which causes the [ComplaintActionDialog]'s
 * status-gated affordances (Edit / Delete) to be hidden — PINNED records are admin-pinned and
 * users must not mutate them. Reply remains available (reply is a fresh user-feedback thread
 * back to admin; PINNED parents accept replies as a feedback channel).
 *
 * **Why `:data`, not `:ui` or `:presentation`** — the legacy treats these as part of the
 * complaint list (they participate in the same search + filter pipeline as DB-backed records).
 * Putting them in `:data` lets them flow through the repository → use case → VM → state.all
 * pipeline naturally, so the `:presentation` filter / search logic doesn't need to know they
 * exist. Same MVI posture as DB-loaded items.
 *
 * **Why hardcoded English literals, not `stringResource`** — Phase 10 i18n lift is deferred for
 * the entire rework `:ui`/`:data` surface. The legacy `getCustomTopComplaints()` uses
 * `stringResource(Res.string.*)` (rework `composeApp` ported them on the legacy side), but the
 * rework's `:data` layer has no Compose Resources access and i18n is deferred globally. Phase
 * 10 will re-point both legacy and rework copies to the shared resource keys in one pass.
 * Inline-literal text here verbatim mirrors the legacy English fallback values.
 *
 * **`id = "admin"`** — legacy uses `stringResource(Res.string.admin)` which resolves to the
 * literal string "admin" (verified in `composeApp/.../composeResources/values/strings.xml` line
 * 381). The Firestore doc-id field is repurposed as the author identifier for pinned items;
 * synthetic but consistent with legacy. Search-filter pattern `complaint.id.contains(query,
 * true)` on the legacy means typing "admin" surfaces these items by id-match — the rework's
 * search pattern matches subject/body/id (id-match included), so "admin" remains a search-hit
 * anchor like legacy.
 *
 * **`userId = "0"`** — legacy magic-string for the admin author. Synthetic per item; not a real
 * device/Firestore user id.
 *
 * **`createdAt = null`** — legacy uses `Clock.System.now()` which recomputes on every
 * recomposition (timestamp drifts forever forward). For the rework these items have no real
 * submission timestamp — they're static admin-pinned entries — so `null` is the honest value.
 * The [ComplaintSummary.createdAt] field is `Instant?` for exactly this case (pre-write /
 * synthetic records). Sort logic in the VM treats `null` as oldest, so pinned entries sort to
 * the bottom of any date-descending list — but the user-side surface today renders in
 * insertion order (no date sort), so the effect is to keep them at the top of `state.all`
 * since the prepend in [ComplaintListRepositoryImpl.loadUserComplaints] puts them first.
 *
 * **`appVersion = null`** — pinned entries don't carry an app-version origin (the user-side
 * surface doesn't display appVersion today, and the admin surface filters to user-submitted
 * complaints separately; pinned items are visible on user-side only).
 *
 * **`type = ComplaintType.CUSTOM`** — same as legacy. CUSTOM is the catch-all bucket for
 * admin-authored content that doesn't map to one of the user-facing categories
 * (TECHNICAL / LANGUAGES / SITES_ADD / SITE_ERROR / FEATURES).
 *
 * **SRP (contract §6)**: one rule — "the canonical static list of admin-pinned FAQ entries".
 * No mutation, no derivation, no networking. Pure value.
 *
 * **OCP (contract §6)**: additional pinned entries slot in as new list elements without
 * modifying existing entries. If a future admin slice wants to load these from a remote
 * config (Firestore `pinned_complaints` collection or RemoteDocStore JSON), the consumer
 * call site in [ComplaintListRepositoryImpl] swaps `PINNED_COMPLAINTS` for a fetched list —
 * the data shape stays `List<ComplaintSummary>`.
 *
 * **DIP (contract §6)**: depends only on `:domain` value types ([ComplaintSummary] /
 * [ComplaintStatus] / [ComplaintType]). No `:shared`, no `:presentation`, no platform
 * dependency.
 *
 * **`internal` visibility** — consumer is co-located in this package
 * ([ComplaintListRepositoryImpl]); module-external consumers should NOT see these — pinned
 * entries are a presentation detail of the user-side feedback list, not a domain concept.
 *
 * **Behaviour parity vs legacy**: when the repository call succeeds, the rework's `state.all`
 * carries `PINNED_COMPLAINTS + db.map { it.toSummary() }`, mirroring legacy
 * `pinnedTop + (success.data ?: emptyList())`. The screen's filter / search operates over
 * `state.all`, so pinned entries participate in search and status-filter exactly like real
 * records (legacy parity). When the repository call fails, the entire `Result.failure`
 * surfaces an error state and pinned entries are NOT shown — also legacy parity (legacy
 * `is State.Error` returns early before rendering the list).
 */
internal val PINNED_COMPLAINTS: List<ComplaintSummary> = listOf(
    ComplaintSummary(
        id = "admin",
        userId = "0",
        type = ComplaintType.CUSTOM,
        subject = "Content removed - 18+ / Hentai",
        body = "References to adult / 18+ content aren't allowed here, so we've removed them " +
            "to keep our community safe. Thanks for understanding.",
        createdAt = null,
        status = ComplaintStatus.PINNED,
        appVersion = null,
        // Legacy `getCustomTopComplaints.kt` carried a `metadata["reason"]` on each pinned FAQ
        // entry which drives the user-side `ClosureReasonCard` (GAP-CMP-02). Mirror the legacy
        // English-fallback reason verbatim. This first entry's reason carries no `pinned :`
        // prefix, so `ClosureReasonType.fromString(...)` resolves it to OTHER (red errorContainer
        // card) — matching native, whose first entry reason is also unprefixed.
        reason = "Removed +18 hentai reference to comply with community guidelines",
    ),
    ComplaintSummary(
        id = "admin",
        userId = "0",
        type = ComplaintType.CUSTOM,
        subject = "Pinned: New manga site requirements",
        body = "Any new manga site must offer at least 200 titles, have no bot verification " +
            "steps, and be worth the setup effort. Adding a site takes significant time and " +
            "work.",
        createdAt = null,
        status = ComplaintStatus.PINNED,
        appVersion = null,
        // The `pinned :` prefix makes `ClosureReasonType.fromString(...)` resolve to PINNED
        // (white/black colour scheme + pin icon), matching native — only this second entry
        // carries the prefix.
        reason = "pinned : New site requires ≥200 mangas & no bot checks to justify the manual " +
            "setup effort",
    ),
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster153.staleKdocSweep.cascade,
 * Task #609, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-seventh sibling of the cluster57-152
 * sweep — CLOSING file of the wave-26 :data/repository complaint trio
 * 3-leaf batch alongside AdminComplaintListRepositoryImpl plus Complaint
 * ActionRepositoryImpl; CLOSES :data/repository complaint trio 3/3):
 *  (a) "Static-admin-pinned-top-FAQ-entries-prepended-to-every-user-side-
 *  Feedback-Manager-list + Phase-7.x.complaint.pinnedfaq-rework + Ports-the
 *  -legacy-composeApp-complaint-data-getCustomTopComplaints.kt-which-is-a-
 *  Composable-function-reading-two-stringResource-entries-each-time-it-s-
 *  called-into-a-static-val-consumed-by-ComplaintListRepositoryImpl.load
 *  UserComplaints + Two-entries-Content-removed-18-plus-Hentai-and-New-
 *  manga-site-requirements + Both-entries-carry-ComplaintStatus.PINNED-
 *  which-causes-the-ComplaintActionDialog-s-status-gated-affordances-Edit-
 *  Delete-to-be-hidden + Reply-remains-available + Why-:data-not-:ui-or-:
 *  presentation-the-legacy-treats-these-as-part-of-the-complaint-list-they
 *  -participate-in-the-same-search-plus-filter-pipeline-as-DB-backed-records
 *  + Putting-them-in-:data-lets-them-flow-through-the-repository-use-case-
 *  VM-state.all-pipeline-naturally + Why-hardcoded-English-literals-not-
 *  stringResource-Phase-10-i18n-lift-is-deferred-for-the-entire-rework-:ui
 *  -:data-surface + id-admin-legacy-uses-stringResource-Res.string.admin-
 *  which-resolves-to-the-literal-string-admin + userId-0-legacy-magic-string
 *  -for-the-admin-author-Synthetic-per-item-not-a-real-device-Firestore-
 *  user-id + createdAt-null-legacy-uses-Clock.System.now-which-recomputes-
 *  on-every-recomposition-For-the-rework-these-items-have-no-real-submission
 *  -timestamp-null-is-the-honest-value + appVersion-null-pinned-entries-do
 *  -not-carry-an-app-version-origin + type-ComplaintType.CUSTOM-same-as-
 *  legacy + SRP-contract-section-6-one-rule-the-canonical-static-list-of-
 *  admin-pinned-FAQ-entries-No-mutation-no-derivation-no-networking-Pure-
 *  value + OCP-contract-section-6-additional-pinned-entries-slot-in-as-new
 *  -list-elements-without-modifying-existing-entries + DIP-contract-section
 *  -6-depends-only-on-:domain-value-types + internal-visibility-consumer-
 *  is-co-located-in-this-package-ComplaintListRepositoryImpl-module-external
 *  -consumers-should-NOT-see-these + Behaviour-parity-vs-legacy-when-the-
 *  repository-call-succeeds-the-rework-s-state.all-carries-PINNED_COMPLAINTS
 *  -plus-db.map-it.toSummary-mirroring-legacy-pinnedTop-plus-success.data-
 *  emptyList + The-screen-s-filter-search-operates-over-state.all-so-pinned
 *  -entries-participate-in-search-and-status-filter-exactly-like-real-
 *  records + When-the-repository-call-fails-the-entire-Result.failure-
 *  surfaces-an-error-state-and-pinned-entries-are-NOT-shown-also-legacy-
 *  parity" — LIVE-NOT-STALE plus FULFILLED-PORT. Verified: PINNED_COMPLAINTS
 *  shipped as a top-level internal val of List<ComplaintSummary> with two
 *  entries. Both entries honor the documented field shape: id = "admin",
 *  userId = "0", type = CUSTOM, status = PINNED, createdAt = null,
 *  appVersion = null. The two subject/body pairs match the documented
 *  English-fallback prose for the legacy stringResource keys. The "ported
 *  from legacy getCustomTopComplaints.kt @Composable function into static
 *  val" stance is honored — no Compose-Resources dependency in :data, the
 *  Phase 10 i18n lift remains deferred. Consumed by ComplaintListRepository
 *  Impl.loadUserComplaints (cluster23 sibling X) via the PINNED_COMPLAINTS
 *  + db.map { it.toSummary() } prepend posture — mirrors the legacy pinned
 *  Top + (success.data ?: emptyList()) ordering. CLOSING FILE of the
 *  :data/repository complaint trio 3-leaf batch (3 of 3: AdminComplaint
 *  ListRepositoryImpl + ComplaintActionRepositoryImpl + PinnedComplaints).
 *  One classification. Original Phase 7.x.complaint.pinnedfaq (Task #269)
 *  static-val prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */

