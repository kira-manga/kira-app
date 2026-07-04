package me.manga.kira.domain.model.history

import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDateTime

/**
 * A single reading-history entry — one row on the History screen.
 *
 * Phase 7.x.history rework. The `:data` layer's
 * [me.manga.kira.data.repository.HistoryRepositoryImpl] maps the legacy `:shared`
 * `HistoryItemD` Room entity (`shared/.../data/local/entity/HistoryItemD.kt`) into this pure
 * domain model. The `:presentation` VM projects a `List<HistoryEntry>` into its MVI state; the
 * `:ui` composable renders each entry as a row with cover + title + chapter + relative date +
 * delete button.
 *
 * Why the 14-field payload (not just the screen-visible subset): the History screen's per-row
 * callbacks navigate to (a) `Screen.MangaDetails(mangaUrl, api)` for the cover tap, and (b)
 * `Screen.ChapterImagesFragment(...)` for the row body tap — and the chapter-reader route needs
 * the FULL identity tuple (api, language, mangaId, chapterId, mangaTitle, mangaUrl,
 * mangaImageUrl, chapterUrl, chapterTitle, localImagePaths, isDownloaded, lastReadPage,
 * totalPages). The legacy `HistoryScreenRoute.kt:44-60` reads all those fields off the same
 * entity. Carrying them on the domain model lets the rework route adapter build the legacy
 * `Screen.ChapterImagesFragment` argument shape verbatim, without an extra DAO round-trip.
 *
 * Why a `kotlinx.datetime.LocalDateTime` (not a plain `Long` or an `Instant`): the legacy entity
 * stores it as `LocalDateTime` (with a Room `TypeConverter` mapping to epoch-millis on the wire).
 * The rework preserves the same wire shape so a chapter the user reads in EITHER legacy or
 * rework reader updates the SAME Room row. The screen's relative-date formatting needs
 * `LocalDateTime` directly (via `daysUntil(today)`), so the model carries it pre-typed.
 *
 * Contract §6 SRP: one rule — "a single reading-history entry as a value". No methods, no
 * derivation, no Room annotations (those live on the legacy entity in `:shared`). The mapper in
 * `:data/.../mapper/HistoryMappers.kt` translates between this domain model and the persistence
 * shape; that mapping rule is its own SRP island.
 *
 * Contract §17: no `Any`, no `!!`. All fields are statically-typed; nullable fields use `?` not
 * `Any?`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster137.staleKdocSweep.cascade,
 * Task #593, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-seventh sibling of the cluster57-136
 * sweep — third file of the wave-24 fifth-cluster 5-subpackage joint
 * batch alongside AppMetadata plus ComplaintSummary plus Language plus
 * SettingsSnapshot):
 *  (a) "Phase-7.x.history-rework + :data-layer-HistoryRepositoryImpl-
 *  maps-the-legacy-:shared-HistoryItemD-Room-entity-into-this-pure-
 *  domain-model + :presentation-VM-projects-a-List-HistoryEntry-into-
 *  its-MVI-state + :ui-composable-renders-each-entry-as-a-row-with-
 *  cover-plus-title-plus-chapter-plus-relative-date-plus-delete-button
 *  + Why-the-14-field-payload-not-just-the-screen-visible-subset + the-
 *  History-screen-per-row-callbacks-navigate-to-Screen.MangaDetails-
 *  mangaUrl-api-for-the-cover-tap-and-Screen.ChapterImagesFragment-
 *  for-the-row-body-tap + the-chapter-reader-route-needs-the-FULL-
 *  identity-tuple-api-language-mangaId-chapterId-mangaTitle-mangaUrl-
 *  mangaImageUrl-chapterUrl-chapterTitle-localImagePaths-isDownloaded-
 *  lastReadPage-totalPages + legacy-HistoryScreenRoute-reads-all-those-
 *  fields-off-the-same-entity + Carrying-them-on-the-domain-model-lets-
 *  the-rework-route-adapter-build-the-legacy-Screen.ChapterImages-
 *  Fragment-argument-shape-verbatim-without-an-extra-DAO-round-trip"
 *  — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive
 *  grep: HistoryEntry is consumed by 10+ files — ObserveHistoryUseCase
 *  plus HistoryRepositoryImpl plus HistoryMappers plus HistoryState
 *  plus HistoryViewModel plus HistoryScreen plus HistoryReworkScreen-
 *  Route. The rework data class carries exactly 14 fields matching the
 *  predicted full identity tuple. The HistoryReworkScreenRoute (post-
 *  cluster288 swap) reads all 14 fields off this domain model when
 *  building the Screen.MangaDetails(mangaUrl, api) navigation argument
 *  and the Screen.ChapterImagesFragment full-identity argument tuple —
 *  no DAO round-trip added.
 *  (b) "Why-a-kotlinx.datetime.LocalDateTime-not-a-plain-Long-or-an-
 *  Instant + the-legacy-entity-stores-it-as-LocalDateTime-with-a-Room-
 *  TypeConverter-mapping-to-epoch-millis-on-the-wire + The-rework-
 *  preserves-the-same-wire-shape-so-a-chapter-the-user-reads-in-EITHER-
 *  legacy-or-rework-reader-updates-the-SAME-Room-row + The-screen-
 *  relative-date-formatting-needs-LocalDateTime-directly-via-
 *  daysUntil-today-so-the-model-carries-it-pre-typed + Contract-§6-
 *  SRP-one-rule-a-single-reading-history-entry-as-a-value + No-methods-
 *  no-derivation-no-Room-annotations-those-live-on-the-legacy-entity-
 *  in-:shared + The-mapper-in-:data-mapper-HistoryMappers-translates-
 *  between-this-domain-model-and-the-persistence-shape + Contract-§17-
 *  no-Any-no-!!-All-fields-are-statically-typed-nullable-fields-use-?-
 *  not-Any?" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified: the
 *  lastReadDate field is declared as kotlinx.datetime.LocalDateTime
 *  (not Long, not Instant). HistoryRepositoryImpl reads the legacy
 *  HistoryItemD Room row's LocalDateTime via its existing TypeConverter
 *  and the rework reader's :data WriteHistoryEntry path updates the
 *  SAME Room row via mangaId+chapterUrl key match — verified across
 *  HistoryMappers.kt. The :ui HistoryScreen.kt uses daysUntil(today)
 *  for the relative-date label. Zero Room annotations on the :domain
 *  model file; the legacy entity in :shared carries those.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  history-era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
@OptIn(ExperimentalTime::class)
data class HistoryEntry(
    /** Primary key from the Room `history_items` table (autogenerated). */
    val id: Long,
    /** Source API identifier (e.g. "mangakakalot"). */
    val api: String,
    /** Source language code (e.g. "en"). */
    val language: String,
    /** Manga's library row id (`MangaD.id`), zero if not in library. */
    val mangaId: Long,
    /** Canonical manga URL on the source. */
    val mangaUrl: String,
    /** Manga title at time of read (may have drifted vs. the live source listing). */
    val mangaTitle: String,
    /** Cover thumbnail URL — feeds the row's `AsyncImage`. */
    val mangaImageUrl: String,
    /** Canonical chapter URL on the source. */
    val chapterUrl: String,
    /** Chapter title at time of read (legacy stores number-or-name here). */
    val chapterTitle: String,
    /** Whether the chapter's images are fully downloaded locally. */
    val isDownloaded: Boolean,
    /** Local image paths for downloaded chapters; empty for streamed reads. */
    val localImagePaths: List<String>,
    /** Wall-clock timestamp of the most-recent read; the row sorts by this. */
    val lastReadDate: LocalDateTime,
    /** Last page index the user was on (0-based); the reader resumes here on re-entry. */
    val lastReadPage: Int,
    /** Total page count for the chapter (0 if not yet known). */
    val totalPages: Int,
)
