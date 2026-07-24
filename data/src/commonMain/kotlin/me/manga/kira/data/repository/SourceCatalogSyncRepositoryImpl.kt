package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.domain.repository.SourceCatalogSyncRepository
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.model.SourceConfig
import kotlin.coroutines.cancellation.CancellationException

/**
 * Config-driven source-catalog sync (Sources Migration — Phase 2). Makes the active config document
 * the source of truth for the `sources` table.
 *
 * **SourceRegistry retirement Phase 3** (`SOURCE_REGISTRY_RETIREMENT_PLAN.md` §4): this sync now
 * owns every behavior the retired remote endpoint used to provide, for EVERY config entry —
 * including metadata-only `engine="legacy"` stanzas (Phase 4):
 *  - **seed** (generic entries only): a missing config-backed source is inserted with
 *    `isEnabled = config.enabled && lifecycle == "active"` (the model default is false → classic
 *    user/onboarding opt-in). Legacy stanzas are never seeded — they manage rows the legacy
 *    first-boot seed created.
 *  - **host-move assertion** (R2/R4): `config.baseUrl`/`imageBase` are the trusted values — a
 *    differing row is migrated (stored URLs first — scoped to URLs on the OLD row host, so
 *    off-host stored URLs are never blanket-rewritten — then the row, so existing library/history
 *    rows resolve against the new host) — **except user mirrors**: the repo-settings screen persists a
 *    user-edited mirror URL into this same row (`SourcesRepository.findRepoByHost` KDoc), so for a
 *    source that DECLARES its host history (non-empty `previousHosts`) a row host outside
 *    `{config host} ∪ previousHosts` is user-owned and survives the sync untouched
 *    (regression-pinned in `SourceCatalogSyncRepositoryTest`). Mirror protection is
 *    authoring-opt-in by design: without declared history, "stale config host" and "user mirror"
 *    are indistinguishable, and the classic assert-any-difference posture keeps plain host moves
 *    working — the safe failure mode (a forgotten declaration clobbers a mirror, the status quo;
 *    the strict alternative would strand every user on a moved host). **Authoring obligation
 *    (append-only)**: once a source declares `previousHosts`, every FUTURE host move MUST append
 *    the outgoing host to that list in the same edit — a forgotten append makes old-host rows
 *    look like user mirrors (outside the declared set) and strands them: no assert, no sweep.
 *  - **alias sweep** (R3): stored URLs still sitting on a declared `previousHosts`/
 *    `previousImageHosts` host are selectively rewritten to the current base — idempotent, runs
 *    even when the row itself is already current (repairs partial past migrations), and also runs
 *    for apis with no catalog row (a legacy api's saved entries outlive its row).
 *  - **siteState projection** (R5): `config.siteState` is written to the row (drives the Home-tab
 *    maintenance/stopped states).
 *  - **lifecycle** (R6, replaces the endpoint's `delate` flag): `"disabled"` force-disables the
 *    row every sync (kept for saved-entry reads); `"removed"` deletes the row (saved-library
 *    tables untouched — endpoint-shouldDelete parity) and never re-seeds.
 *
 * Preserves the user's enable/disable choice (never writes `isEnabled` for an existing row outside
 * the lifecycle kill switches). Each source is isolated so one bad entry can't abort the rest;
 * [replaceBaseUrl] no-ops on a blank/unchanged base, so a steady-state sync costs zero writes.
 * Non-fatal — never blocks launch.
 */
class SourceCatalogSyncRepositoryImpl(
    private val updateManager: SourceUpdateManager,
    private val sourcesDao: SourcesDao,
    private val migrator: SourceUrlMigrator,
    private val dispatchers: DispatcherProvider,
) : SourceCatalogSyncRepository {
    override suspend fun syncFromConfig(): AppResult<Unit> =
        try {
            withContext(dispatchers.io) {
                val document = updateManager.activeDocument()
                val existing = sourcesDao.getAllSources().first().associateBy { it.name }

                document.sources.forEach { cfg ->
                    try {
                        syncOne(cfg, existing[cfg.api])
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        // Per-source isolation: one bad config entry can't abort the rest of the sync.
                    }
                }

                forceDisableNonConfigRows(
                    genericEntries = document.sources.filter { it.engine == "generic" },
                    rows = existing.values,
                )
            }
            AppResult.Success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppResult.Failure(
                AppError.Unexpected(message = t.message ?: "source config sync failed", cause = t),
            )
        }

    private suspend fun syncOne(
        cfg: SourceConfig,
        row: SourcesEntity?,
    ) {
        // R6 "removed" — endpoint-shouldDelete parity: drop the catalog row (saved-library tables
        // are untouched, they live in their own tables), never (re-)seed, skip all other management
        // including the alias sweep (a dead source's stored URLs are not rewritten).
        if (cfg.lifecycle == LIFECYCLE_REMOVED) {
            if (row != null) sourcesDao.deleteSourceByName(cfg.api)
            return
        }
        if (row == null) seedIfGeneric(cfg) else assertConfigTruth(cfg, row)
        // R3/R4 alias sweep — selective (only URLs on a declared previous host move) and idempotent.
        // Runs regardless of row state: it repairs stored rows even for apis whose catalog row is
        // absent, and costs nothing when the host lists are empty.
        sweepPreviousHosts(cfg)
    }

    private suspend fun seedIfGeneric(cfg: SourceConfig) {
        // Legacy metadata-only stanzas manage rows created by the legacy first-boot seed; they are
        // never seeded from config (a legacy source with no row stays invisible).
        if (cfg.engine != "generic") return
        sourcesDao.insert(
            SourcesEntity(
                name = cfg.api,
                // Retirement §2 `enabled` semantics: first-seed value only. The model default is
                // false (user/onboarding enables by language — the classic posture); a non-active
                // lifecycle can never seed an enabled row. User toggles own the field afterwards.
                isEnabled = cfg.enabled && cfg.lifecycle == LIFECYCLE_ACTIVE,
                priority = cfg.priority,
                language = cfg.language,
                siteState = cfg.siteState.toSourceStateOrNull() ?: SourceState.WORKING,
                baseUrl = cfg.baseUrl,
                baseVersion = 0,
                imageBaseUrl = cfg.imageBase,
                imageUrlVersion = 0,
            ),
        )
    }

    private suspend fun assertConfigTruth(
        cfg: SourceConfig,
        row: SourcesEntity,
    ) {
        // config.baseUrl is the trusted value: a difference means the host moved → migrate stored
        // URLs, then update the stored base (baseVersion bumped so the legacy refresh could never
        // down-version it) — UNLESS the row host is a user-edited mirror (see class KDoc): a host
        // outside {config host} ∪ previousHosts is user-owned and survives.
        //
        // The migration is scoped to URLs on the OLD row host (2026-07 audit): stored URLs living on
        // a DIFFERENT host than the row's base (e.g. Mangabuddy page rows on api.mangak.io while the
        // base is mangak.io, or leftovers from a user-mirror session) must not be blanket-rewritten
        // to the new host — that corrupts them silently and permanently. Only when the old row base
        // itself is unparseable (no host to scope by) does the classic indiscriminate rewrite run.
        // Multi-hop histories are the `previousHosts` alias sweep's job, not this assert's.
        if (cfg.baseUrl.isNotBlank() &&
            row.baseUrl != cfg.baseUrl &&
            !isUserMirror(row.baseUrl, cfg.baseUrl, cfg.previousHosts)
        ) {
            migrator.migratePageUrls(cfg.api, cfg.baseUrl, fromHosts = urlHost(row.baseUrl)?.let { setOf(it) })
            sourcesDao.updateBaseUrlAndVersionByName(cfg.api, cfg.baseUrl, row.baseVersion + 1)
        }
        if (cfg.imageBase.isNotBlank() &&
            row.imageBaseUrl != cfg.imageBase &&
            !isUserMirror(row.imageBaseUrl, cfg.imageBase, cfg.previousImageHosts)
        ) {
            migrator.migrateImageUrls(cfg.api, cfg.imageBase, fromHosts = urlHost(row.imageBaseUrl)?.let { setOf(it) })
            sourcesDao.updateImageBaseUrlAndVersionByName(cfg.api, cfg.imageBase, row.imageUrlVersion + 1)
        }
        // R5: project the config siteState into the row (Home-tab maintenance/stopped states).
        val configState = cfg.siteState.toSourceStateOrNull()
        if (configState != null && row.siteState != configState) {
            sourcesDao.updateSiteStateByName(cfg.api, configState)
        }
        // R6 "disabled": force-disabled every sync; the row is kept for saved-entry reads. A user
        // re-toggle lasts the session; the authority re-asserts on the next launch.
        if (cfg.lifecycle == LIFECYCLE_DISABLED && row.isEnabled) {
            sourcesDao.setEnabledByName(cfg.api, false)
        }
    }

    /**
     * True when [rowUrl]'s host is one config does NOT know for this source — i.e. a user-edited
     * mirror (repo-settings) that must survive the sync. **Activated by authoring**: only a source
     * with a non-empty [previousHosts] history can tell "row on a stale config host" (assert over)
     * from "row on a user mirror" (preserve) — with no declared history every differing host is
     * treated as stale and asserted over, the classic posture (see the class KDoc for why that is
     * the safe default). A blank/unparseable row URL is never a mirror (config repairs it); a row
     * on the config host itself (scheme/path drift) or on a declared previous host is config-owned.
     */
    private fun isUserMirror(
        rowUrl: String,
        configUrl: String,
        previousHosts: List<String>,
    ): Boolean {
        if (previousHosts.isEmpty()) return false
        val rowHost = urlHost(rowUrl)
        val configOwned =
            rowHost == null ||
                rowHost == urlHost(configUrl) ||
                previousHosts.any { it.lowercase() == rowHost }
        return !configOwned
    }

    private suspend fun sweepPreviousHosts(cfg: SourceConfig) {
        if (cfg.previousHosts.isNotEmpty() && cfg.baseUrl.isNotBlank()) {
            val declaredHosts = cfg.previousHosts.mapTo(mutableSetOf()) { it.lowercase() }
            migrator.migratePageUrls(cfg.api, cfg.baseUrl, declaredHosts)
        }
        if (cfg.previousImageHosts.isNotEmpty() && cfg.imageBase.isNotBlank()) {
            val declaredHosts = cfg.previousImageHosts.mapTo(mutableSetOf()) { it.lowercase() }
            migrator.migrateImageUrls(cfg.api, cfg.imageBase, declaredHosts)
        }
    }

    // Sources Migration Phase 5/6: the config document is the active catalog — config-backed
    // (engine=="generic") sources are the ONLY active/user-facing sources. Force-disable any
    // enabled NON-config (legacy) row so the whole active flow (Home tabs / search-all / active
    // source — all gated on isEnabled) surfaces only config-backed sources. The legacy row +
    // code stay in place (archived), just disabled; this also turns off a pre-migration install
    // where a legacy source was enabled. An empty active catalog is authoritative and therefore
    // disables every row. Parse/network failures never reach this layer: SourceUpdateManager
    // retains a complete verified cache or bundled tier instead.
    private suspend fun forceDisableNonConfigRows(
        genericEntries: List<SourceConfig>,
        rows: Collection<SourcesEntity>,
    ) {
        val configApis = genericEntries.mapTo(mutableSetOf()) { it.api }
        rows.forEach { row ->
            if (row.name !in configApis && row.isEnabled) {
                try {
                    sourcesDao.setEnabledByName(row.name, false)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Per-row isolation: one failed disable can't abort the rest.
                }
            }
        }
    }

    private companion object {
        const val LIFECYCLE_ACTIVE = "active"
        const val LIFECYCLE_DISABLED = "disabled"
        const val LIFECYCLE_REMOVED = "removed"
    }
}

/**
 * The config `siteState` string mapped to the persisted [SourceState] enum, or null for an unknown
 * value (the validator rejects those upstream; the fail-safe here is "don't touch the row").
 */
private fun String.toSourceStateOrNull(): SourceState? =
    try {
        SourceState.valueOf(this)
    } catch (_: IllegalArgumentException) {
        null
    }
