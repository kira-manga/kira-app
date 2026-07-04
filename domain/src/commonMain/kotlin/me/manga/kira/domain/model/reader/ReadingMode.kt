package me.manga.kira.domain.model.reader

/**
 * User-selectable reader presentation mode.
 *
 * Canonical home for this enum is the rework `:domain` layer — the legacy `:shared` copy at
 * `me.manga.kira.presentation.features.reader.data.ReadingMode` will be retired when the
 * legacy reader VM is replaced (Phase 6.4.3+). Until then, the two enums coexist; the rework
 * `:data` mapper converts at the boundary.
 *
 * Wire-format compatibility: persisted value on disk is the enum `name` (legacy `DataStoreHelper`
 * stores `String` via `valueOf`). The rework keeps every entry name byte-identical to the legacy
 * enum so existing user-stored preferences survive migration — see `:platform` settings glue.
 *
 * Resource concerns (icon drawable + localized label) are intentionally absent from the enum
 * constructor — both are `:ui` / `:composeApp` concerns and were the reason the legacy enum
 * pulled in `R.drawable.*` / `R.string.*`. The rework `:ui` reading-mode dialog resolves icon
 * + label via a `when (mode) { ... }` lookup co-located with the dialog composables.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster134.staleKdocSweep.cascade,
 * Task #590, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixteenth sibling of the cluster57-133
 * sweep — second file of the wave-24 second-cluster `:domain/model/
 * reader/` 3-leaf-model batch alongside Page plus PageDownloadProgress):
 *  (a) "Canonical-home-for-this-enum-is-the-rework-:domain-layer +
 *  legacy-:shared-copy-at-me.manga.kira.presentation.features.reader.
 *  data.ReadingMode-will-be-retired-when-the-legacy-reader-VM-is-
 *  replaced-(Phase-6.4.3+) + Until-then-the-two-enums-coexist + rework-
 *  :data-mapper-converts-at-the-boundary + Wire-format-compatibility-
 *  persisted-value-on-disk-is-the-enum-name-(legacy-DataStoreHelper-
 *  stores-String-via-valueOf) + rework-keeps-every-entry-name-byte-
 *  identical-to-the-legacy-enum-so-existing-user-stored-preferences-
 *  survive-migration" — LIVE-NOT-STALE + FULFILLED-PREDICTION-
 *  (coexistence) + FORECAST-NOT-YET-FULFILLED-(legacy-enum-retirement).
 *  Verified via Glob: the legacy ReadingMode at shared/.../presentation/
 *  features/reader/data/ReadingMode.kt still exists today (the legacy
 *  reader VM has not yet been retired so the predicted coexistence
 *  remains). The rework :data ReadingModeRepositoryImpl (verified
 *  L8-L36) imports me.manga.kira.domain.model.reader.ReadingMode and
 *  writes the enum `name` to multiplatform-settings ObservableSettings
 *  — the legacy DataStoreHelper.setReadingMode mirrors this byte-for-
 *  byte with `ReadingMode.<X>.name`. The 6 entry names (DEFAULT plus
 *  RIGHT_TO_LEFT plus LEFT_TO_RIGHT plus VERTICAL plus WEBTOON plus
 *  CONTINUOUS_VERTICAL) are byte-identical between legacy and rework
 *  enums; existing user preferences survive the strangler-fig
 *  transition without resetting.
 *  (b) "Resource-concerns-icon-drawable-plus-localized-label-are-
 *  intentionally-absent-from-the-enum-constructor + both-are-:ui-or-
 *  :composeApp-concerns-and-were-the-reason-the-legacy-enum-pulled-in-
 *  R-drawable-plus-R-string + rework-:ui-reading-mode-dialog-resolves-
 *  icon-plus-label-via-a-when(mode)-lookup-co-located-with-the-dialog-
 *  composables" — LIVE-NOT-STALE + FULFILLED-PREDICTION. The :domain
 *  enum carries 6 names ONLY (no constructor parameters, no resource
 *  references). The icon plus label resolution lives in :composeApp
 *  `presentation/features/reader/ui/components/reading_mode_dialog/
 *  ReadingModeResources.kt` (legacy plus shared dialog) and the rework
 *  :ui SettingsScreen + ReadingModeDialog surfaces consume the enum
 *  via per-value when-branches. The :domain layer remains resource-
 *  free per contract §1.
 *  (c) "isPaged-extension-True-when-ReadingMode-is-paginated-one-page-
 *  swipe-per-page-rather-than-a-scrolling-column + Ported-1-1-from-
 *  legacy-:shared-reader-data-isPaged.kt + Folded-into-this-file-
 *  rather-than-standing-alone-because-the-helper-is-tiny-and-tightly-
 *  coupled-to-the-enum + splitting-it-across-files-would-inflate-the-
 *  file-count-without-aiding-navigation + LEFT_TO_RIGHT + RIGHT_TO_LEFT
 *  + VERTICAL + DEFAULT-returns-true" — LIVE-NOT-STALE + FORECAST-NOT-
 *  YET-FULFILLED-(rework-consumer). The extension exists at L36-L40
 *  and ports the legacy semantic 1:1 (paginated = pager-style; WEBTOON
 *  + CONTINUOUS_VERTICAL = scrolling column). Verified via grep: the
 *  rework's :ui ReaderScreen does NOT currently consume `.isPaged` —
 *  instead it branches on the enum value directly via per-value `when`
 *  blocks (the per-mode layout implementations from Phase 7.x.reader.
 *  modelayout.* slices). Today only the legacy :shared ReaderScreen
 *  plus legacy isPaged.kt consume the helper. The rework helper
 *  remains LIVE in the :domain layer and ready for a future consumer;
 *  the predicted "tiny-and-tightly-coupled-to-the-enum" rationale
 *  stands — folding into the enum's file rather than orphaning a
 *  standalone file was sound by file-count economics. When the legacy
 *  reader retires, the legacy isPaged.kt retires with it; the rework
 *  helper survives the strangler-fig cleanup.
 *  Three classifications STAND on their own merits. Original Phase
 *  6.4.1-era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
enum class ReadingMode {
    DEFAULT,
    RIGHT_TO_LEFT,
    LEFT_TO_RIGHT,
    VERTICAL,
    WEBTOON,
    CONTINUOUS_VERTICAL,
}

/**
 * True when [ReadingMode] is paginated (one page swipe per "page" rather than a scrolling column).
 *
 * Ported 1:1 from legacy `:shared/.../reader/data/isPaged.kt`. Folded into this file rather than
 * standing alone because the helper is tiny and tightly coupled to the enum — splitting it across
 * files would inflate the file count without aiding navigation.
 */
val ReadingMode.isPaged: Boolean
    get() = this == ReadingMode.LEFT_TO_RIGHT ||
        this == ReadingMode.RIGHT_TO_LEFT ||
        this == ReadingMode.VERTICAL ||
        this == ReadingMode.DEFAULT
