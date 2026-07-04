package me.manga.kira.ui.complaint

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.format

/**
 * Absolute-format complaint-row timestamp formatter.
 *
 * Phase 7.x.complaint.date rework. Ports the legacy
 * `shared/.../presentation/features/complaint/utils/formatTimestamp.kt` (retired in Phase
 * 9.x.complaint.legacymodels.retire — see ARCHITECTURE §206; the legacy file itself
 * replaced an Android-only `SimpleDateFormat + java.util.Date` formatter during the Phase 4
 * batch-4.5 KMP migration). Produces the same `"MMM dd, yyyy HH:mm"` pattern used by the
 * legacy `ComplaintCard.kt:150` row footer:
 *
 *  - `"May 22, 2026 14:30"`
 *  - `"Dec 31, 2025 09:05"`
 *
 * **Parity with legacy** — same formatter shape, same TimeZone (current system default), same
 * locale (English month abbreviations). The legacy keeps month names hardcoded English because
 * of the same Phase-10-deferred-i18n posture this rework carries (§107.5 / §109.4 / §110.1 /
 * §111.10 / §112.6 / §113.5). Phase 10's i18n lift will localize both legacy and rework
 * consumers in one pass.
 *
 * **`Instant?` input rather than `Long`** — the legacy takes `Long` (epoch-ms) and treats
 * `0L` as the sentinel for "no timestamp" → returns the empty string. The rework's domain type
 * `ComplaintSummary.createdAt` is already `Instant?` (carrying the null directly), so this
 * helper accepts the null at the boundary and the caller renders nothing for null. No sentinel,
 * no magic value — DIP-clean: the helper doesn't know about `0L` or other domain sentinels.
 *
 * **`internal` visibility** — consumer is the same-package `ComplaintScreen.ComplaintRow`.
 * If a future admin / dialog surface needs the same formatter, the visibility lifts to
 * package-level then. For now, locality wins (SRP: one rule per file, one consumer per visibility).
 *
 * **Why not `:domain`** — `:domain` is platform-agnostic value types only. Formatting is
 * presentation concern (locale, timezone, character set). Belongs in `:ui` per the established
 * layer-boundary policy (§64 / §76).
 *
 * **Why not `:presentation`** — the formatter doesn't drive state; it's pure projection from
 * an `Instant` to a `String` at render time. Putting it in `:presentation` would mean either
 * (a) eagerly formatting in the VM (re-running on every state emission for every row, wasteful)
 * or (b) exposing the formatter as a function to `:ui` (which is then a dependency inversion
 * with no benefit). Same reasoning as `ComplaintStatus.displayName()` / `ComplaintType.displayName()`
 * which live in `:ui` per §107 / §110.
 *
 * **Concurrent safety / Compose recomposition** — the [LocalDateTime.Format] DSL produces an
 * immutable `DateTimeFormat<LocalDateTime>` cached at file-private `val formatter`. The
 * `Instant.toLocalDateTime` call resolves on each invocation against the current system TZ
 * (which is correct — if the user changes timezone, subsequent recompositions reflect it).
 *
 * **SRP (contract §6)** — one rule: "format an Instant timestamp as an absolute display
 * string for complaint rows."
 *
 * **OCP (contract §6)** — closed under the existing absolute format; if a relative-format
 * variant ("3 days ago") is ever needed, slot in a separate `formatComplaintTimestampRelative`
 * function in this file without modifying this one.
 *
 * **DIP (contract §6)** — depends only on `kotlin.time.Instant` (or its `kotlinx.datetime`
 * alias, both in commonMain) and `kotlinx.datetime` DSL. No `:domain` / `:data` /
 * `:presentation` / `:shared` reach.
 *
 * **Audit-trail postscript** (Phase 9.x.complaint.staleKdocSweep.cascade,
 * Task #452, 2026-05-28): the line-anchored citation at line 21 to "legacy
 * `ComplaintCard.kt:150` row footer" refers to the legacy
 * `composeApp/.../presentation/features/complaint/ui/components/
 * ComplaintCard.kt` file, which was retired in
 * Phase 9.x.complaint.legacyui.retire (§355); verified by a filesystem check
 * returning zero hits for that path. (The peer reference to
 * `shared/.../presentation/features/complaint/utils/formatTimestamp.kt` at
 * line 17 was already past-tense'd in §206 — that surface-level retire is
 * inline-documented above and does not need a second postscript here.) The
 * "MMM dd, yyyy HH:mm" formatter shape stands on its own merits — it's a
 * deliberate parity choice with the legacy's English-fallback shape that's
 * independent of which legacy file originally rendered it; the
 * Phase-10-deferred-i18n lift remains the canonical opportunity to localize
 * the month abbreviations. Original §253-era prose preserved verbatim per
 * the audit-trail-preservation convention — the line-anchored citation is
 * historical record of the design lineage; the formatter continues to format
 * correctly through the legacy retire.
 */
@OptIn(ExperimentalTime::class)
private val formatter = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day(Padding.ZERO)
    chars(", ")
    year()
    char(' ')
    hour(Padding.ZERO)
    char(':')
    minute(Padding.ZERO)
}

@OptIn(ExperimentalTime::class)
internal fun formatComplaintTimestamp(instant: Instant): String {
    val ldt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return formatter.format(ldt)
}
