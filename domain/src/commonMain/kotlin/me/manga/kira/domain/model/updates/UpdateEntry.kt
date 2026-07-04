package me.manga.kira.domain.model.updates

import kotlinx.datetime.LocalDate

/**
 * A single chapter-update entry — one row on the Updates screen.
 *
 * Phase 7.x.updates rework. The `:data` layer's
 * [me.manga.kira.data.repository.UpdatesRepositoryImpl] maps the legacy `:shared`
 * `ChapterNotification` Room entity (`shared/.../data/local/entity/ChapterNotification.kt`) into
 * this pure domain model. The `:presentation` VM projects a `List<UpdateEntry>` into its MVI
 * state; the `:ui` composable regroups by [notificationDate] and renders each entry as a row
 * with cover + title + chapter + relative date + read-state indicator + per-row "Mark read" /
 * "Delete" buttons.
 *
 * Why the 14-field payload (not just the screen-visible subset): the Updates screen's per-row
 * callbacks navigate to (a) `Screen.MangaDetails(mangaUrl, api)` for the cover tap, and (b)
 * `Screen.ChapterImagesFragment(...)` for the row body tap — and the chapter-reader route needs
 * the FULL identity tuple (api, language, mangaId, chapterId, mangaTitle, mangaUrl,
 * mangaImageUrl, chapterUrl, chapterNumber, localImagePaths, isDownloaded). The legacy
 * `UpdatesScreen.kt` reads all those fields off the same entity. Carrying them on the domain
 * model lets the rework route adapter build the legacy `Screen.ChapterImagesFragment` argument
 * shape verbatim, without an extra DAO round-trip — same rationale as
 * [me.manga.kira.domain.model.history.HistoryEntry]'s 14-field payload (§82.5).
 *
 * Why a `kotlinx.datetime.LocalDate` (not `LocalDateTime` like [HistoryEntry]): the legacy
 * `ChapterNotification` entity only stores the date, not the time-of-day — chapter updates are
 * reported at day granularity by the `LibraryRefreshWorker`. The rework preserves the same wire
 * shape so an update added to the `notifications` table by the legacy worker appears identically
 * on both the legacy and rework Updates screens.
 *
 * Contract §6 SRP: one rule — "a single chapter-update entry as a value". No methods, no
 * derivation, no Room annotations (those live on the legacy entity in `:shared`). The mapper
 * in `:data/.../mapper/UpdateMappers.kt` translates between this domain model and the
 * persistence shape; that mapping rule is its own SRP island.
 *
 * Contract §17: no `Any`, no `!!`. All fields are statically-typed; no nullable fields here —
 * the legacy entity initialises every field at insert time (defaults supplied by the
 * worker / `ChapterNotificationHelper`).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster138.staleKdocSweep.cascade,
 * Task #594, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-second sibling of the cluster57-137
 * sweep — third and closing file of the wave-24 sixth-cluster closing
 * 3-leaf-model joint batch alongside Source plus ReadingStatistics;
 * CLOSES :domain/model/updates/ subpackage at 1/1, CLOSES cluster138,
 * and CLOSES :domain/model/ tier at 26/26 FULLY SWEPT — closes
 * wave-24):
 *  (a) "Phase-7.x.updates-rework + :data-layer-UpdatesRepositoryImpl-
 *  maps-the-legacy-:shared-ChapterNotification-Room-entity-into-this-
 *  pure-domain-model + :presentation-VM-projects-a-List-UpdateEntry-
 *  into-its-MVI-state + :ui-composable-regroups-by-notificationDate-
 *  and-renders-each-entry-as-a-row-with-cover-plus-title-plus-chapter-
 *  plus-relative-date-plus-read-state-indicator-plus-per-row-Mark-
 *  read-and-Delete-buttons + Why-the-14-field-payload-not-just-the-
 *  screen-visible-subset + the-Updates-screen-per-row-callbacks-
 *  navigate-to-Screen.MangaDetails-mangaUrl-api-for-the-cover-tap-
 *  and-Screen.ChapterImagesFragment-for-the-row-body-tap + the-chapter-
 *  reader-route-needs-the-FULL-identity-tuple-api-language-mangaId-
 *  chapterId-mangaTitle-mangaUrl-mangaImageUrl-chapterUrl-chapterNumber-
 *  localImagePaths-isDownloaded + legacy-UpdatesScreen-reads-all-those-
 *  fields-off-the-same-entity + Carrying-them-on-the-domain-model-
 *  lets-the-rework-route-adapter-build-the-legacy-Screen.ChapterImages-
 *  Fragment-argument-shape-verbatim-without-an-extra-DAO-round-trip +
 *  same-rationale-as-HistoryEntry-14-field-payload-(§82.5)" — LIVE-
 *  NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive grep:
 *  UpdateEntry is consumed by 10+ files — ObserveUpdatesUseCase plus
 *  UpdatesRepositoryImpl plus UpdateMappers plus UpdatesState plus
 *  UpdatesViewModel plus UpdatesScreen plus UpdatesReworkScreenRoute
 *  plus DownloadUpdateChapterUseCase plus MarkUpdateAsReadUseCase plus
 *  DeleteUpdateUseCase. The rework data class carries exactly 14
 *  fields matching the predicted full identity tuple. The Updates-
 *  ReworkScreenRoute (post-cluster289 swap) reads all 14 fields off
 *  this domain model when building the Screen.MangaDetails(mangaUrl,
 *  api) navigation argument and the Screen.ChapterImagesFragment full-
 *  identity argument tuple — same posture as HistoryReworkScreenRoute
 *  per the §82.5 cross-reference.
 *  (b) "Why-a-kotlinx.datetime.LocalDate-not-LocalDateTime-like-
 *  HistoryEntry + the-legacy-ChapterNotification-entity-only-stores-
 *  the-date-not-the-time-of-day + chapter-updates-are-reported-at-day-
 *  granularity-by-the-LibraryRefreshWorker + The-rework-preserves-the-
 *  same-wire-shape-so-an-update-added-to-the-notifications-table-by-
 *  the-legacy-worker-appears-identically-on-both-the-legacy-and-
 *  rework-Updates-screens + Contract-§6-SRP-one-rule-a-single-chapter-
 *  update-entry-as-a-value + No-methods-no-derivation-no-Room-
 *  annotations-those-live-on-the-legacy-entity-in-:shared + The-mapper-
 *  in-:data-mapper-UpdateMappers-translates-between-this-domain-model-
 *  and-the-persistence-shape + Contract-§17-no-Any-no-!!-All-fields-
 *  are-statically-typed-no-nullable-fields-here + the-legacy-entity-
 *  initialises-every-field-at-insert-time" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION. Verified: the notificationDate field is
 *  declared as kotlinx.datetime.LocalDate (NOT LocalDateTime). The
 *  HistoryEntry/UpdateEntry split (LocalDateTime vs LocalDate) holds
 *  per the predicted granularity rationale — HistoryEntry tracks
 *  wall-clock read time per row; UpdateEntry tracks day-only when a
 *  refresh worker added the row. UpdateMappers.kt in :data is the
 *  single SRP-island translation point. Zero Room annotations on the
 *  :domain model file.
 *  Two classifications STAND on their own merits. CLOSES :domain/
 *  model/updates/ subpackage at 1/1, CLOSES cluster138, and CLOSES
 *  :domain/model/ tier at 26/26 FULLY SWEPT — CLOSES wave-24. Original
 *  Phase 7.x.updates-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
data class UpdateEntry(
    /** Primary key from the Room `notifications` table (autogenerated). */
    val id: Long,
    /** Source API identifier (e.g. "mangakakalot"). */
    val api: String,
    /** Source language code (e.g. "en"). */
    val language: String,
    /** Manga's library row id (`MangaD.id`). */
    val mangaId: Long,
    /** Manga title at time of update (may have drifted vs. the live source listing). */
    val mangaTitle: String,
    /** Cover thumbnail URL — feeds the row's `AsyncImage`. */
    val mangaImageUrl: String,
    /** Canonical manga URL on the source. */
    val mangaUrl: String,
    /** Chapter's library row id (`ChapterD.id`); the reader nav arg consumes this directly. */
    val chapterId: Long,
    /** Chapter number / display label as the source reported it (e.g. "Chapter 12", "12.5"). */
    val chapterNumber: String,
    /** Canonical chapter URL on the source. */
    val chapterUrl: String,
    /** Day the update was recorded by the refresh worker; the row groups + sorts by this. */
    val notificationDate: LocalDate,
    /** Whether the user has marked this update as read (legacy uses opacity; rework uses weight). */
    val isRead: Boolean,
    /** Whether the chapter's images are fully downloaded locally. */
    val isDownloaded: Boolean,
    /** Local image paths for downloaded chapters; empty for streamed reads. */
    val localImagePaths: List<String>,
)
