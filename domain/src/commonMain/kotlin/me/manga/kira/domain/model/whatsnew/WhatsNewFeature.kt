package me.manga.kira.domain.model.whatsnew

/**
 * One entry in the rework What's New surface — a release-note row describing a single new
 * feature, fix, or behaviour change.
 *
 * Phase 7.x.whatsnew. Ports the legacy `:shared`
 * `me.manga.kira.presentation.features.whatsnew.model.WhatsNewFeature` data class VERBATIM —
 * same field names, same nullability, same defaults. The verbatim port (instead of a foundation-
 * scoped subset) keeps the contract stable as the follow-on media-rendering sub-slices
 * (`Phase 7.x.whatsnew.images`, `Phase 7.x.whatsnew.video`, `Phase 7.x.whatsnew.fullscreen`)
 * consume the additional fields without forcing a model breaking change.
 *
 * Field semantics (lifted from the legacy KDoc + remote JSON contract):
 * - [title]: short headline ("Reader: continuous scroll").
 * - [description]: paragraph-length body.
 * - [mediaType]: discriminant — see [MediaType]. The foundation `:ui` ignores it.
 * - [imageResName]: compose-resources drawable name (without extension), e.g. `"new_su"`.
 *   Resolved by the `:ui` follow-on via `Res.drawable.<name>`. Null when no bundled image.
 * - [imageResNameList]: list of bundled drawable names — used by `mediaType == LIST` for the
 *   horizontal gallery.
 * - [imageUrl]: remote URL — Coil-loaded by the follow-on. Null when only a bundled image is
 *   available.
 * - [imageUrlList]: list of remote URLs — gallery via `mediaType == LIST`.
 * - [videoUrl]: remote video URL — consumed by `Phase 7.x.whatsnew.video` once the `:platform`
 *   MediaPlayer SPI lands. Null on `mediaType != VIDEO`.
 * - [isNew]: surfaces the "NEW" chip in the foundation `:ui`. Currently the ONLY field besides
 *   title + description that the foundation render consumes.
 * - [version]: app version this entry first appeared in. Foundation `:ui` does not display it;
 *   present for follow-on grouping ("What's new in 1.2.3" sections).
 *
 * **Why a verbatim port (not a foundation-scoped subset)**: a subset would force a model
 * breaking change when the media sub-slices land — every call site (state, intent dispatch,
 * `:data` mapper, UI Card) would need a contract bump. Carrying the unused fields in the
 * foundation has zero runtime cost (data class with default null/empty values) and zero render
 * cost (the foundation `:ui` doesn't read them).
 *
 * **Why a `data class` not a sealed hierarchy keyed by [mediaType]**: the legacy ships a flat
 * `data class` with all fields optional; the remote JSON contract is the same. A sealed
 * hierarchy would force a model breaking change on the wire format (encoding the variant tag),
 * which neither the legacy nor the rework controls — the remote endpoint is the source of truth
 * for the schema. Same flat-data-class posture as
 * [me.manga.kira.domain.model.statistics.ReadingStatistics] (8-field aggregate from
 * Phase 7.x.statistics).
 *
 * Pure `:domain` value type — no `:data` / `:shared` reach. Contract §17.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster136.staleKdocSweep.cascade,
 * Task #592, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-fourth sibling of the cluster57-135
 * sweep — fourth and closing file of the wave-24 fourth-cluster joint
 * batch alongside DownloadState plus DownloadedChapter plus MediaType;
 * CLOSES :domain/model/whatsnew/ subpackage at 2/2 and closes cluster136):
 *  (a) "Phase-7.x.whatsnew + Ports-the-legacy-:shared-WhatsNewFeature-
 *  data-class-VERBATIM + same-field-names-same-nullability-same-
 *  defaults + verbatim-port-instead-of-foundation-scoped-subset-keeps-
 *  the-contract-stable-as-the-follow-on-media-rendering-sub-slices-
 *  consume-the-additional-fields-without-forcing-a-model-breaking-
 *  change + Field-semantics-title-headline + description-paragraph-
 *  length-body + mediaType-discriminant + imageResName-compose-
 *  resources-drawable-name + imageResNameList-list-of-bundled-drawable-
 *  names + imageUrl-remote-URL-Coil-loaded + imageUrlList-list-of-
 *  remote-URLs-gallery + videoUrl-remote-video-URL-consumed-by-Phase-
 *  7.x.whatsnew.video-once-:platform-MediaPlayer-SPI-lands + isNew-
 *  surfaces-NEW-chip-in-foundation-:ui + version-app-version-this-
 *  entry-first-appeared-in-foundation-:ui-does-not-display-it-present-
 *  for-follow-on-grouping" — LIVE-NOT-STALE plus FULFILLED-PREDICTION
 *  plus FORECAST-NOT-YET-FULFILLED-(media-rendering-slices). Verified
 *  via recursive grep: WhatsNewFeature is consumed by 15+ files —
 *  GetWhatsNewFeaturesUseCase, MarkWhatsNewSeenUseCase, WhatsNewView-
 *  Model, WhatsNewIntent, WhatsNewState, WhatsNewReworkModule,
 *  WhatsNewScreenRoute, WhatsNewScreen, WhatsNewRepositoryImpl,
 *  WhatsNewReworkScreenRoute. The rework data class carries exactly
 *  10 fields (title + description + mediaType + 4 image fields + 1
 *  video field + isNew + version) byte-identical to the legacy. The
 *  foundation :ui WhatsNewScreen renders title + description + isNew
 *  chip; the version field and media URLs/resource names await the
 *  forecast follow-on slices (Phase 7.x.whatsnew.images/.video).
 *  (b) "Why-a-verbatim-port-not-a-foundation-scoped-subset + subset-
 *  would-force-a-model-breaking-change-when-the-media-sub-slices-land
 *  + every-call-site-(state-intent-dispatch-:data-mapper-:ui-Card)-
 *  would-need-a-contract-bump + Carrying-the-unused-fields-in-the-
 *  foundation-has-zero-runtime-cost-(data-class-with-default-null-or-
 *  empty-values)-and-zero-render-cost-(the-foundation-:ui-does-not-
 *  read-them)" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified:
 *  the data class default values (imageResName = null, imageResName-
 *  List = emptyList(), imageUrl = null, imageUrlList = emptyList(),
 *  videoUrl = null, isNew = false, version = null) allow callers to
 *  construct a minimal WhatsNewFeature(title, description, mediaType)
 *  without specifying any media fields. WhatsNewScreen.kt reads only
 *  title + description + isNew per render — zero render cost from
 *  the unused fields holds verbatim.
 *  (c) "Why-a-data-class-not-a-sealed-hierarchy-keyed-by-mediaType +
 *  legacy-ships-a-flat-data-class-with-all-fields-optional + remote-
 *  JSON-contract-is-the-same + sealed-hierarchy-would-force-a-model-
 *  breaking-change-on-the-wire-format-(encoding-the-variant-tag) +
 *  remote-endpoint-is-the-source-of-truth-for-the-schema + Same-flat-
 *  data-class-posture-as-ReadingStatistics-(8-field-aggregate-from-
 *  Phase-7.x.statistics)" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: the rework WhatsNewFeature remains a flat data class
 *  with all media fields optional — no sealed hierarchy was
 *  introduced. The flat-data-class posture mirrors ReadingStatistics
 *  per the cross-reference (both are :domain aggregates whose wire
 *  format is dictated by an upstream source — JSON endpoint for
 *  WhatsNew, DAO aggregate for ReadingStatistics).
 *  Three classifications STAND on their own merits. CLOSES :domain/
 *  model/whatsnew/ subpackage at 2/2 and closes cluster136. Original
 *  Phase 7.x.whatsnew-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
data class WhatsNewFeature(
    val title: String,
    val description: String,
    val mediaType: MediaType,
    val imageResName: String? = null,
    val imageResNameList: List<String> = emptyList(),
    val imageUrl: String? = null,
    val imageUrlList: List<String> = emptyList(),
    val videoUrl: String? = null,
    val isNew: Boolean = false,
    val version: String? = null,
)
