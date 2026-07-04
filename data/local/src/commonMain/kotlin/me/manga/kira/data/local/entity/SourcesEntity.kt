package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import me.manga.kira.presentation.features.repo_settings.domain.SourceState

@Entity(tableName = "sources")
data class SourcesEntity(
    @PrimaryKey
    val name: String,
    val isEnabled: Boolean = true,
    val priority: Int,
    val language: String,
    val siteState: SourceState = SourceState.WORKING,
    val baseUrl: String = "",
    val baseVersion: Int = 0,
    val imageBaseUrl: String,
    val imageUrlVersion: Int,
)

/*
 * §253 audit-trail postscript — cluster280 §253 sweep (2026-05-29)
 * ------------------------------------------------------------------
 * Classification: LIVE / LEGACY.
 *
 * LIVE evidence: registered as a database entity at MangaDatabase.kt:42
 * ("SourcesEntity::class," inside the @Database entities array, lines 36-43).
 * The rework consumes it through the :data strangler-fig mapper:
 * SourcesMappers.kt:43 ("internal fun SourcesEntity.toDomain(): Source = ...")
 * projects the row into the rework :domain Source model (renaming the "name"
 * column to "api" for semantic clarity, per SourcesMappers.kt:38). That mapper
 * feeds SourcesRepositoryImpl.kt:14, which the SourcesViewModel (:presentation)
 * observes. The legacy side is also LIVE: repo_settings/domain/SourcesRepository.kt:114
 * exposes "allSources: Flow<List<SourcesEntity>> by lazy { sourcesDao.getAllSources() }"
 * and :276 constructs SourcesEntity rows in its first-boot saveSources() seed.
 * The owning DAO is bound per-platform at PlatformModule.android.kt:90
 * ("single<SourcesDao> { get<MangaDatabase>().sourcesDao() }"). ARCHITECTURE.md:38490
 * corroborates: "SourcesEntity | 9 | :shared/data/local/entity | LIVE".
 *
 * LEGACY status: pre-rework :shared/commonMain Room entity. NOT a Phase-5.x
 * platform facade — no expect/actual fan. The rework :domain Source model
 * deliberately NARROWS this 9-field row (ARCHITECTURE.md:13448) and never writes
 * a full SourcesEntity back (enableDisAbleSource forwards name + flag only), so
 * no reverse Source.toEntity() exists. cluster186 + ChapterNotification.kt:96
 * flagged this entity as a "zero comment lines" prose-less skip; cluster280
 * closes that gap.
 *
 * Delta-axes:
 *  1. Platform API: androidx.room (KMP) — @Entity(tableName="sources"),
 *     @PrimaryKey on the "name" column (note: NOT autoGenerate — the source API
 *     name IS the natural key; Migrations.kt:221 records the PK change from
 *     auto-generated id to name). Pure commonMain.
 *  2. Threading/dispatcher: none owned by the entity (a plain data class). DAO
 *     reads return a cold Flow (getAllSources) re-emitted on Room invalidation;
 *     the legacy SourcesRepository wraps it in a lazy delegate.
 *  3. Error handling: no constraints beyond the unique PK; the siteState column
 *     persists a SourceState enum (@Serializable, 4 constants: WORKING,
 *     UNDER_MAINTENANCE, STOPPED, ADULT_18_PLUS — SourceState.kt, swept in
 *     cluster208) via its own converter. Defaulted fields (isEnabled=true,
 *     siteState=WORKING, baseUrl="", baseVersion=0) tolerate partial seed rows.
 *  4. DI binding mechanism: entity is bound implicitly by membership in the
 *     @Database entities array (MangaDatabase.kt:42), not a Koin single; its DAO
 *     (SourcesDao) is the Koin-provided single per platform.
 *  5. Field-liveness note: three fields that LOOK dead are confirmed LIVE per
 *     ARCHITECTURE.md:38531-38532 — siteState is read 8× by legacy HomeScreen.kt
 *     routing, and baseVersion / imageUrlVersion drive per-source refetch
 *     decisions in BaseManga.kt. The rework Source model drops siteState, but
 *     the entity column stays because legacy Home routing still consumes it;
 *     database-migration-report.md:33 records "SourcesEntity | no changes |
 *     Yes | migrated".
 *
 * Nested-comment hazard check: zero legitimate KDoc/comment openers exist in the
 * original file body (bare entity declaration, no doc comments anywhere). This
 * appended block is balanced — exactly one opener and one closer, with no
 * interior comment delimiters in the prose.
 */
