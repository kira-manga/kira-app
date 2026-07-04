package me.manga.kira.ui.complaint

import androidx.compose.runtime.Composable
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.add_languages
import me.manga.kira.ui.generated.resources.add_manga_site
import me.manga.kira.ui.generated.resources.ask_to_add_features
import me.manga.kira.ui.generated.resources.custom_feedback
import me.manga.kira.ui.generated.resources.error_in_manga_site
import me.manga.kira.ui.generated.resources.error_in_the_app
import org.jetbrains.compose.resources.stringResource

/**
 * `:ui`-side display-name lookup for the rework's `:domain` [ComplaintType] enum.
 *
 * Phase 7.x.complaint.displaynames corrective slice: see sibling
 * [ComplaintStatusDisplay] KDoc for the broader rationale (raw `name` exposure correction).
 * UP-3 localization lift: each variant resolves through `stringResource(Res.string.*)` against
 * the `:ui` compose-resources catalog, reusing the legacy complaint-type keys so the
 * hand-authored Arabic translations apply verbatim.
 *
 * Key sources (reused from the legacy complaint catalog):
 *  - `TECHNICAL` → `error_in_the_app` ("Error In The App").
 *  - `LANGUAGES` → `add_languages` ("Add Languages").
 *  - `SITES_ADD` → `add_manga_site` ("Add Manga Site").
 *  - `SITE_ERROR` → `error_in_manga_site` ("Error In Manga Site").
 *  - `FEATURES` → `ask_to_add_features` ("Ask to Add Features").
 *  - `CUSTOM` → `custom_feedback` ("Custom Feedback").
 *
 * Contract §6 SRP / OCP / DIP — same posture as [ComplaintStatusDisplay].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster4.staleKdocSweep.cascade,
 * Task #459, 2026-05-28): a stale citation into the §370-retired legacy
 * `composeApp/.../presentation/features/complaint/model/ComplaintTypeDisplay.kt`
 * appears above:
 *  - Lines 11-13 (Phase 7.x.complaint.displaynames corrective preamble):
 *    "The legacy
 *    `composeApp/.../presentation/features/complaint/model/ComplaintTypeDisplay.kt`
 *    uses `stringResource` for every variant; the rework uses inline
 *    literals matching the legacy's English fallback values verbatim
 *    (deferred i18n lift to Phase 10)".
 * The legacy
 * `composeApp/.../presentation/features/complaint/model/ComplaintTypeDisplay.kt`
 * was retired in Phase 9.x.complaint.legacycomponents.retire (§370 sweep,
 * commit `6792209` "(1/2): delete 5 orphan complaint UI component
 * files"); verified by a filesystem check returning zero hits for that
 * path. The inline-literals-matching-legacy-English-fallback rationale
 * and the deferred-i18n-to-Phase-10 posture both stand on their own
 * merits — the rework `:ui` design language's stringResource-deferral
 * strategy is documented inline above and independent of which legacy
 * file originally implemented the `stringResource` lookup that the
 * literal sources were captured from. The 6 captured literals match the
 * legacy `composeApp/.../values/strings.xml` English fallback values
 * (lines 307-312, which remain in-repo as the i18n source of truth for
 * Phase 10). Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical
 * record of the design lineage; the rework ComplaintType.displayName()
 * continues to surface the correct user-visible labels through the
 * legacy retire.
 */
@Composable
fun ComplaintType.displayName(): String = when (this) {
    ComplaintType.TECHNICAL -> stringResource(Res.string.error_in_the_app)
    ComplaintType.LANGUAGES -> stringResource(Res.string.add_languages)
    ComplaintType.SITES_ADD -> stringResource(Res.string.add_manga_site)
    ComplaintType.SITE_ERROR -> stringResource(Res.string.error_in_manga_site)
    ComplaintType.FEATURES -> stringResource(Res.string.ask_to_add_features)
    ComplaintType.CUSTOM -> stringResource(Res.string.custom_feedback)
}
