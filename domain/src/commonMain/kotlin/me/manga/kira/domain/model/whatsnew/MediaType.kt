package me.manga.kira.domain.model.whatsnew

/**
 * Classifies the media payload carried by a [WhatsNewFeature].
 *
 * Phase 7.x.whatsnew. Ports the legacy `:shared`
 * `me.manga.kira.presentation.features.whatsnew.model.MediaType` enum verbatim — same variant
 * names, same wire-format strings expected from the remote JSON endpoint (case-insensitive parse
 * via `MediaType.valueOf(...uppercase())` at the data-source seam).
 *
 * The foundation `:ui` (`ui/.../whatsnew/WhatsNewScreen.kt`) renders title + description only and
 * IGNORES this discriminant; the field is carried on the `:domain` model for follow-on slices
 * (`Phase 7.x.whatsnew.images`, `Phase 7.x.whatsnew.video`) to branch on without breaking the
 * model contract.
 *
 * - [IMAGE]: render `imageResName` (compose-resources drawable name) OR `imageUrl` (Coil URL).
 * - [VIDEO]: render `videoUrl` via the deferred `:platform` MediaPlayer SPI.
 * - [LIST]: render `imageResNameList` / `imageUrlList` as a horizontally-scrolling gallery.
 * - [URL]: open `imageUrl` (or another carried URL) externally via `IntentLauncher` — rare in
 *   practice; legacy treats this as an "image with external open-in-browser" affordance.
 *
 * Wire-format note: the remote JSON encodes these as lowercase / mixed-case strings; the legacy
 * data source does `MediaType.valueOf(it.uppercase())` and falls back to `IMAGE` on unknown
 * values. The rework's `:data` mapper preserves that fallback posture verbatim.
 *
 * Pure `:domain` enum — no `:data` / `:shared` reach. Contract §17.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster136.staleKdocSweep.cascade,
 * Task #592, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-third sibling of the cluster57-135
 * sweep — third file of the wave-24 fourth-cluster joint batch alongside
 * DownloadState plus DownloadedChapter plus WhatsNewFeature):
 *  (a) "Phase-7.x.whatsnew + Ports-the-legacy-:shared-MediaType-enum-
 *  verbatim + same-variant-names-same-wire-format-strings-expected-
 *  from-the-remote-JSON-endpoint + case-insensitive-parse-via-MediaType.
 *  valueOf-uppercase-at-the-data-source-seam + Wire-format-note-remote-
 *  JSON-encodes-these-as-lowercase-or-mixed-case-strings + legacy-data-
 *  source-does-MediaType.valueOf-uppercase-and-falls-back-to-IMAGE-on-
 *  unknown-values + rework-:data-mapper-preserves-that-fallback-posture-
 *  verbatim + Pure-:domain-enum-no-:data-or-:shared-reach-Contract-§17"
 *  — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive
 *  grep: the rework MediaType enum carries exactly 4 variants (IMAGE +
 *  VIDEO + LIST + URL) byte-identical to the legacy enum. WhatsNew-
 *  RepositoryImpl.kt consumes MediaType.IMAGE/VIDEO/LIST/URL during
 *  remote-JSON mapping; the IMAGE fallback for unknown wire-format
 *  values is preserved verbatim. Zero :data or :shared imports in the
 *  :domain file.
 *  (b) "Foundation-:ui-WhatsNewScreen-renders-title-plus-description-
 *  only-and-IGNORES-this-discriminant + field-carried-on-the-:domain-
 *  model-for-follow-on-slices-(Phase-7.x.whatsnew.images-Phase-7.x.
 *  whatsnew.video)-to-branch-on-without-breaking-the-model-contract +
 *  IMAGE-render-imageResName-or-imageUrl-Coil-URL + VIDEO-render-
 *  videoUrl-via-deferred-:platform-MediaPlayer-SPI + LIST-render-
 *  imageResNameList-or-imageUrlList-as-horizontally-scrolling-gallery
 *  + URL-open-imageUrl-externally-via-IntentLauncher-rare-in-practice"
 *  — LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED. Verified via
 *  grep: WhatsNewScreen.kt currently renders title + description only;
 *  the MediaType discriminant is carried on WhatsNewFeature.mediaType
 *  but no per-MediaType branch lands in the :ui yet. The follow-on
 *  slices (Phase 7.x.whatsnew.images for IMAGE+LIST + Phase 7.x.
 *  whatsnew.video for VIDEO + the URL external-open via Intent-
 *  Launcher facade) remain forecast targets — none have been
 *  scheduled in the task ledger as of 2026-05-28. The :data + :domain
 *  surface is ready to consume them without a model breaking change.
 *  Two classifications STAND on their own merits. Original Phase
 *  7.x.whatsnew-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
enum class MediaType {
    IMAGE,
    VIDEO,
    LIST,
    URL,
}
