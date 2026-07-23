package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.presentation.features.repo_settings.domain.SourceState

// Phase 9.x.updatesourcesrepository.daoprune (Task #388): dropped 5 coupled-dead members
// after the `UpdateSourcesRepository` retire (Task #387). 3-pass reacher-chain audit
// (anchored `sourcesDao.X(` across the source tree) showed each dropped member had ZERO
// reachers — URS was their sole legacy reacher.
// Removed (URS-coupled):
//   - `updateBaseUrlAndVersionByName(name, baseUrl, version): Int`
//   - `updateImageBaseUrlAndVersionByName(apiName, newImageBaseUrl, newImageVersion): Int`
//   - `updateSiteStateByName(name, siteState): Int`
//   - `deleteSourceByName(name)` @Transaction
//   - `deleteByName(name): Int` (transitively-dead inside `deleteSourceByName`)
//
// Phase 9.x.sourcesdao.componentprune (Task #389): dropped 5 independently-orphan members
// surfaced during Task #388's audit but kept out-of-scope (pre-dated URS retire, not
// URS-coupled). 3-pass reacher-chain audit confirmed each had ZERO source-tree reachers
// at any point in the live codebase — they are independently dead, not URS-coupled.
// Removed (independent orphans):
//   - `update(source: SourcesEntity)` — bare `@Update`. Note: no LIVE sibling on this
//     DAO under any name (unlike MangaDao's `updateManga` or HistoryDao's `updateHistory`).
//     Mutations to existing rows are reached via `setEnabledByName` (LIVE) and the now-
//     dropped URS @Query helpers; no caller ever invoked the bare row-replace path.
//     `import androidx.room.Update` dropped (was the only `@Update` annotation).
//   - `delete(source: SourcesEntity)` — bare `@Delete`. No reacher anywhere. Per-source
//     deletion was reached through the URS-coupled `deleteSourceByName @Transaction`,
//     itself now dropped. `import androidx.room.Delete` dropped.
//   - `enableByName(name)` @Transaction — wrapper around LIVE `setEnabledByName(name, true)`.
//     No external reacher; `SourcesRepository` calls `setEnabledByName(...)` directly.
//     The underlying @Query is LIVE; only the @Transaction wrapper was dead.
//   - `disableByName(name)` @Transaction — wrapper around LIVE `setEnabledByName(name, false)`.
//     Same posture: no external reacher; underlying @Query is LIVE.
//     `import androidx.room.Transaction` dropped (both wrappers were the only @Transaction
//     annotations).
//   - `getSourcesByEnabled(enabled): Flow<List<SourcesEntity>>` — no reacher; the LIVE
//     repository observer uses `getAllSources()` (different return shape, no filter).
//
// Phase 9.x.sourcesrepository.componentprune (Task #390): dropped 1 transitively-dead member
// coupled to the same-slice drop of `SourcesRepository.disabledSourcesFlow` (its sole reacher).
// 3-pass anchored grep (`sourcesDao.getSourceNamesByState(` / `.getSourceNamesByState(`) confirmed
// zero remaining reachers after the repository-side drop.
// Removed (coupled-dead):
//   - `getSourceNamesByState(state: SourceState): Flow<List<String>>` — only reacher was
//     `SourcesRepository.disabledSourcesFlow`, itself dropped this slice for being a zero-reacher
//     orphan.
//
// LIVE members preserved (all reached from SourcesRepository):
//   - `getAllSources()` — repository observers (×5 call sites).
//   - `insert(source): Long` — repository seed/upsert path.
//   - `setEnabledByName(name, enabled): Int` — repository toggle path.
//   - `getBaseUrlFor(name): String?` — repository per-source URL lookup.
//   - `getSiteStateByName(name)` / `getSiteStateByNameSync(name)` — repository state probes.
@Dao
interface SourcesDao {

    @Query("SELECT * FROM sources ORDER BY priority")
    fun getAllSources(): Flow<List<SourcesEntity>>

    @Query("SELECT * FROM sources ORDER BY priority")
    suspend fun getAllSourcesOnce(): List<SourcesEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: SourcesEntity): Long

    @Query("UPDATE sources SET isEnabled = :enabled WHERE name = :name")
    suspend fun setEnabledByName(name: String, enabled: Boolean): Int

    @Query("SELECT baseUrl FROM sources WHERE name = :name LIMIT 1")
    suspend fun getBaseUrlFor(name: String): String?

    @Query("SELECT siteState FROM sources WHERE name = :name LIMIT 1")
    fun getSiteStateByName(name: String): Flow<SourceState?>

    @Query("SELECT siteState FROM sources WHERE name = :name LIMIT 1")
    suspend fun getSiteStateByNameSync(name: String): SourceState?

    // P0-SRCSEED (startup remote source-list seeding): restored the four write members the
    // legacy `UpdateSourcesRepository.updateSourcesFromApi` upsert used (Tasks #388 dropped them
    // as URS-coupled-dead after the URS retire — Task #387). Consumed today by the config-driven
    // catalog sync (`SourceCatalogSyncRepositoryImpl` — the interim KMP endpoint refresh that
    // also wrote through these was deleted in SourceRegistry retirement Phase 6). Signatures +
    // SQL are byte-for-byte the native SourcesDao members.
    @Query(
        """
        UPDATE sources
        SET baseUrl = :baseUrl, baseVersion = :version
        WHERE name = :name
        """,
    )
    suspend fun updateBaseUrlAndVersionByName(name: String, baseUrl: String, version: Int): Int

    @Query("UPDATE sources SET imageBaseUrl = :newImageBaseUrl, imageUrlVersion = :newImageVersion WHERE name = :apiName")
    suspend fun updateImageBaseUrlAndVersionByName(apiName: String, newImageBaseUrl: String, newImageVersion: Int): Int

    @Query("UPDATE sources SET siteState = :siteState WHERE name = :name")
    suspend fun updateSiteStateByName(name: String, siteState: SourceState): Int

    @Query("DELETE FROM sources WHERE name = :name")
    suspend fun deleteSourceByName(name: String): Int

    @Query("UPDATE sources SET isEnabled = 0 WHERE name NOT IN (:activeApis)")
    suspend fun disableOutsideCatalog(activeApis: List<String>): Int

    @Query("DELETE FROM sources WHERE name NOT IN (:activeApis)")
    suspend fun deleteOutsideCatalog(activeApis: List<String>): Int

    @Query(
        """
        UPDATE sources
        SET priority = :priority, language = :language, siteState = :siteState
        WHERE name = :api
        """,
    )
    suspend fun updateCatalogMetadata(
        api: String,
        priority: Int,
        language: String,
        siteState: SourceState,
    ): Int
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster185.staleKdocSweep.cascade,
 * Task #673, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-fifth sibling of the cluster57-184
 * sweep continuum — opening leaf 1/5 of the wave-55 commonMain :data/local
 * closing-tier 5-leaf batch; SourcesDao interface 1/5).
 *
 *  (a) Inline cumulative-prune comment "Phase-9-x-updatesourcesrepository-
 *  daoprune Task-388-dropped-5-coupled-dead-members + updateBaseUrlAndVersionByName
 *  + updateImageBaseUrlAndVersionByName + updateSiteStateByName +
 *  deleteSourceByName + deleteByName" — LIVE-NOT-STALE for the post-§388
 *  SourcesDao surface AND FULFILLED-RETIRE for the Phase
 *  9.x.updatesourcesrepository.daoprune Task #388 5-orphan drop (verified:
 *  none of the 5 dropped URS-coupled members re-appears in the @Dao
 *  interface body; the `UpdateSourcesRepository` class retire (Task #387)
 *  cited as the upstream blocker is documented retired in Task #387; the
 *  `deleteByName` transitively-dead orphan inside the now-dropped
 *  `deleteSourceByName` @Transaction body was correctly retired as
 *  transitively-dead — no live caller).
 *
 *  (b) Inline successor-prune comment "Phase-9-x-sourcesdao-componentprune
 *  Task-389-dropped-5-independently-orphan-members + update + delete +
 *  enableByName + disableByName + getSourcesByEnabled" — LIVE-NOT-STALE
 *  for the post-§389 SourcesDao surface AND FULFILLED-RETIRE for the
 *  Phase 9.x.sourcesdao.componentprune Task #389 5-independent-orphan
 *  drop (verified: none of the 5 dropped pre-dated-URS-orphan members
 *  re-appears in the @Dao interface body; `import androidx.room.Update`
 *  + `import androidx.room.Delete` + `import androidx.room.Transaction`
 *  were the only annotations dropped along with the 5 members, and none
 *  re-appears in the import block — the only remaining annotations are
 *  `@Dao` + `@Insert` + `@OnConflictStrategy` + `@Query`; the
 *  `enableByName`/`disableByName` @Transaction wrappers were correctly
 *  identified as dead — the LIVE `setEnabledByName(name, enabled)` Query
 *  is reached directly by `SourcesRepository` for the toggle path,
 *  bypassing the now-dropped wrappers).
 *
 *  (c) Inline successor-prune comment "Phase-9-x-sourcesrepository-
 *  componentprune Task-390-dropped-1-transitively-dead-member +
 *  getSourceNamesByState" — LIVE-NOT-STALE for the post-§390 SourcesDao
 *  surface AND FULFILLED-RETIRE for the Phase 9.x.sourcesrepository
 *  .componentprune Task #390 1-coupled-dead drop (verified:
 *  `getSourceNamesByState` does not appear in the @Dao interface body;
 *  the upstream `SourcesRepository.disabledSourcesFlow` retire cited as
 *  the coupling blocker is documented retired in Task #390; no
 *  re-introduction). The `Phase 9.x.sourcesrepository.staleKdocSweep`
 *  follow-up (Task #391) finalized the documentation close-out on the
 *  repository side.
 *
 * Verified: 6-method SourcesDao interface (getAllSources + insert +
 * setEnabledByName + getBaseUrlFor + getSiteStateByName +
 * getSiteStateByNameSync). All 6 reached by `SourcesRepository`
 * facade methods (×5 call sites for getAllSources observer; insert
 * for seed/upsert; setEnabledByName for the toggle path; getBaseUrlFor
 * for per-source URL lookup; getSiteStateByName + Sync variants for
 * the state probe). Imports verified: 7 imports (Dao + Insert +
 * OnConflictStrategy + Query + Flow + SourcesEntity + SourceState) —
 * no orphan import re-emerged. Sibling: cluster184 NotificationDao
 * (cluster184 closing leaf); StatisticsDeo (cluster185 succeeding
 * sibling). OPENING LEAF 1/5 of the cluster185 commonMain :data/local
 * closing-tier 5-leaf batch. Three compound classifications (each
 * LIVE-NOT-STALE + FULFILLED-RETIRE for Phase 9.x.updatesourcesrepository
 * .daoprune Task #388, Phase 9.x.sourcesdao.componentprune Task #389,
 * Phase 9.x.sourcesrepository.componentprune Task #390 respectively).
 * Original Phase-9 cumulative-prune prose preserved verbatim per the
 * audit-trail-preservation convention.
 *
 * CORRECTION (2026-06-12): section (a)'s "none of the 5 dropped URS-coupled members re-appears" and
 * the "6-method SourcesDao interface" summary are STALE — the P0-SRCSEED startup-seeding work
 * restored updateBaseUrlAndVersionByName, updateImageBaseUrlAndVersionByName, updateSiteStateByName
 * and deleteSourceByName (lines 87-103), all live-reached from SourceCatalogSyncRepositoryImpl
 * (originally from the endpoint refresh, retired in SourceRegistry retirement Phase 6),
 * so the live surface is 10 members. Retained as lineage per the audit-trail-preservation convention.
 */
